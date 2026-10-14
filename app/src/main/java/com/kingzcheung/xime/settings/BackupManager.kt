package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 云备份引擎：备份包的生成与恢复在宿主，传输协议由 [BackupPlugin] 的 Lua 实现承载。
 *
 * 分工（与剪贴板同步的 ClipboardSyncBridge 同构）：
 * - 备份 = [RimeExportManager.buildArchive] 打包 → plugin.pushBackup 上传
 * - 恢复 = plugin.pullBackup 下载 zip → [restoreArchive] 校验路由落盘
 * - 插件永不接触宿主文件系统：Lua 侧只拿到/交回 zip 字节流
 *
 * 备份包格式（v1，兼容旧格式：`_xime_backup/` 前缀外的条目一律视为 rime 目录相对路径）：
 * - rime 目录文件（含 librime userdb 与 t9_digit.userdb，即自造词）在包根
 * - `_xime_backup/settings.json`                    设置项（kime_settings，类型保留）
 * - `_xime_backup/plugin_configs/<pluginId>.json`   插件配置（密文原样，安全换机不解密）
 * - `_xime_backup/plugins.xml`                      插件注册表（含启用状态）
 * - `_xime_backup/plugins/<pluginId>/…`             插件包（仅完整备份，包体可能数 MB）
 *
 * 恢复后：rime 文件重启输入会话生效；插件/设置在重启应用后生效（插件随 Application 重建加载）。
 * 注意：userdb 为 leveldb，运行中打快照存在一致性风险（文件级覆盖合并），
 * 建议在非输入状态执行备份/恢复。
 */
object BackupManager {

    private const val TAG = "BackupManager"

    /** 备份包内元数据条目前缀（保留字，rime 目录不应有同名顶层文件）。 */
    const val META_PREFIX = "_xime_backup/"

    /** 设置项 SharedPreferences 文件名（SettingsPreferences.PREFS_NAME，此处避免依赖私有常量）。 */
    private const val PREFS_NAME_SETTINGS = "kime_settings"

    private const val PREFS_PREFIX_PLUGIN_CFG = "plugin_cfg_"

    /** 云备份到远端。返回 BackupResult（失败含原因）。 */
    suspend fun backupNow(
        context: Context,
        plugin: BackupPlugin,
        mode: ExportMode
    ): com.kingzcheung.xime.plugin.core.api.BackupResult {
        val archive = RimeExportManager.buildArchive(context, mode)
            .getOrElse { return com.kingzcheung.xime.plugin.core.api.BackupResult(ok = false, message = it.message) }
        val (fileName, bytes) = archive
        Log.i(TAG, "Backup: name=$fileName size=${bytes.size} mode=$mode")
        return plugin.pushBackup(fileName, bytes)
    }

    /** 列出远端备份条目（透传插件；失败返回 null）。 */
    suspend fun listRemote(plugin: BackupPlugin): List<RemoteBackupEntry>? = plugin.listBackups()

    /** 删除远端备份条目。 */
    suspend fun deleteRemote(plugin: BackupPlugin, id: String): Boolean = plugin.deleteBackup(id)

    /**
     * 从远端恢复：下载备份包并按条目前缀路由落盘。
     *
     * @return 成功 true；失败返回错误消息
     */
    suspend fun restore(context: Context, plugin: BackupPlugin, id: String): Result<Unit> {
        val bytes = plugin.pullBackup(id)
            ?: return Result.failure(Exception("下载备份包失败"))
        return restoreArchive(context, bytes)
    }

    /** 校验 zip 条目路径并解压覆盖（rime 文件 + META 元数据路由），Android 入口。 */
    fun restoreArchive(context: Context, bytes: ByteArray): Result<Unit> {
        val filesDir = context.filesDir
        return restoreArchive(filesDir, { prefsName, json ->
            restorePrefsJson(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), json)
        }, bytes, visibleConfigWriter = { text ->
            UserConfigDir.writeCustomConfig(context, text)
        }, candidateOrderWriter = { text ->
            restoreCandidateOrder(context, text)
        }).onSuccess {
            Log.i(TAG, "Restore done -> ${filesDir.path}")
        }.onFailure { e ->
            Log.e(TAG, "restoreArchive failed", e)
        }
    }

    /**
     * 恢复候选排序词频表：优先写可见目录（与 xime.yaml 同级，用户可直接查看），
     * 失败时回退私有目录，保证恢复的排序不丢。与
     * [com.kingzcheung.xime.service.CandidateOrderStore.save] 的落盘策略一致。
     */
    private fun restoreCandidateOrder(context: Context, text: String): Boolean {
        val fileName = com.kingzcheung.xime.service.CandidateOrderStore.FILE_NAME
        if (UserConfigDir.writeText(context, fileName, text)) return true
        return runCatching {
            File(context.filesDir, fileName).writeText(text)
        }.isSuccess
    }

    /**
     * 恢复核心（纯 JVM，便于单测）：META 条目按前缀路由，其余条目进 filesDir/rime。
     *
     * @param prefsRestorer 宿主注入的 prefs 恢复器（Android 用 SharedPreferences；测试可注入假实现）
     * @param candidateOrderWriter 候选排序词频表（candidate_order.txt）的可见目录写入通道；
     *   为 null 时（纯 JVM 测试）写入 `filesDir/candidate_order.txt`。与
     *   [visibleConfigWriter] 同理：不这样分流的话，恢复出来的词频表会被可见目录里的
     *   旧文件在下次读取时遮蔽。
     */
    fun restoreArchive(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        bytes: ByteArray,
        /**
         * 用户可见配置目录可用时的写入通道（Android 侧注入 `UserConfigDir.writeCustomConfig`）。
         * 为 null 时（纯 JVM 测试，或可见目录不可用）退回写入 `filesDir/rime`。
         * 不这样分流的话，恢复出来的 `xime.custom.yaml` 会被可见目录里的旧配置静默覆盖。
         */
        visibleConfigWriter: ((String) -> Boolean)? = null,
        /** 候选排序词频表（candidate_order.txt）的写入通道，见类注释。 */
        candidateOrderWriter: ((String) -> Boolean)? = null
    ): Result<Unit> {
        try {
            val rimeDir = File(filesDir, "rime")
            if (!rimeDir.exists()) rimeDir.mkdirs()
            val pluginsDir = File(filesDir, "plugins")
            var entryCount = 0
            ZipInputStream(bytes.inputStream()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name.startsWith(META_PREFIX)) {
                        val rel = name.removePrefix(META_PREFIX)
                        val data = zis.readBytes()
                        when {
                            rel == "settings.json" -> prefsRestorer(PREFS_NAME_SETTINGS, data)
                            rel.startsWith("plugin_configs/") && rel.endsWith(".json") -> {
                                val pluginId = rel.removePrefix("plugin_configs/").removeSuffix(".json")
                                if (pluginId.isNotBlank() && !pluginId.contains('/') && !pluginId.contains('\\')) {
                                    prefsRestorer(PREFS_PREFIX_PLUGIN_CFG + pluginId, data)
                                }
                            }
                            rel == "plugins.xml" -> writeChecked(File(filesDir, "plugins.xml"), filesDir, data)
                            rel.startsWith("plugins/") -> writeChecked(
                                File(pluginsDir, rel.removePrefix("plugins/")), pluginsDir, data
                            )
                            else -> { /* 未知元数据条目跳过（向前兼容） */ }
                        }
                        entryCount++
                        zis.closeEntry()
                        continue
                    }
                    val target = File(rimeDir, name)
                    // 显式拒绝绝对路径（File(parent, "/abs") 在 Unix 下会被拼接为 parent/abs，
                    // canonical 检查拦不住；此处按契约直接拒绝）
                    if (isRootedEntryName(name)) {
                        return Result.failure(Exception("备份包包含非法路径: $name"))
                    }
                    val canonicalRoot = rimeDir.canonicalPath + File.separator
                    if (target.canonicalPath != canonicalRoot.removeSuffix(File.separator) &&
                        !target.canonicalPath.startsWith(canonicalRoot)
                    ) {
                        return Result.failure(Exception("备份包包含非法路径: $name"))
                    }
                    // 用户配置改走「真源」通道（可见目录 xime.yaml），否则恢复出来的配置会被
                    // 可见目录里的旧配置静默覆盖；写入失败才退回 rime 目录，避免恢复彻底失效。
                    // 旧备份里的条目名是 xime.custom.yaml / xime.txt，同样路由过来，等于顺带完成迁移。
                    val relName = name.replace('\\', '/').removePrefix("./")
                    val isUserConfig = relName == UserConfigDir.UNIFIED_CONFIG_FILE ||
                        relName == UserConfigDir.LEGACY_UNIFIED_CONFIG_FILE ||
                        relName == UserConfigDir.CUSTOM_CONFIG_FILE
                    // 候选排序词频表（备份包为顶层 candidate_order.txt）：走「真源」通道
                    //（可见目录，与 xime.yaml 同级），否则恢复出的排序会被可见目录旧表遮蔽。
                    // writer 为 null（纯 JVM 测试）或写入失败时，回退私有目录
                    // `filesDir/candidate_order.txt`（与 CandidateOrderStore 的私有回退路径一致）。
                    val isCandidateOrder = relName == com.kingzcheung.xime.service.CandidateOrderStore.FILE_NAME
                    if (isCandidateOrder) {
                        val text = String(zis.readBytes(), Charsets.UTF_8)
                        val routed = candidateOrderWriter?.invoke(text) ?: false
                        if (!routed) {
                            File(filesDir, com.kingzcheung.xime.service.CandidateOrderStore.FILE_NAME).writeText(text)
                        }
                        entryCount++
                        zis.closeEntry()
                        continue
                    }
                    if (isUserConfig && visibleConfigWriter != null) {
                        val text = String(zis.readBytes(), Charsets.UTF_8)
                        if (!visibleConfigWriter(text)) {
                            target.parentFile?.mkdirs()
                            target.writeText(text)
                        }
                        entryCount++
                        zis.closeEntry()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                    entryCount++
                    zis.closeEntry()
                }
            }
            if (entryCount == 0) return Result.failure(Exception("备份包为空"))
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /** 目标必须落在 baseDir 内（防元数据条目穿越），否则抛异常中止恢复。 */
    private fun writeChecked(target: File, baseDir: File, data: ByteArray) {
        val canonicalBase = baseDir.canonicalPath + File.separator
        if (isRootedEntryName(target.name) ||
            !(target.canonicalPath + File.separator).startsWith(canonicalBase)
        ) {
            throw SecurityException("备份包包含非法路径: ${target.name}")
        }
        target.parentFile?.mkdirs()
        target.writeBytes(data)
    }

    /**
     * 条目名是否指向根路径（绝对路径或盘符路径）。
     *
     * 不能用 `File(name).isAbsolute` 作为唯一判据：该属性依赖平台，Windows 上
     * `File("/etc/evil.txt").isAbsolute` 为 false（Windows 绝对路径需有盘符），
     * 于是 POSIX 风格的绝对路径会被当作相对路径拼进目标目录，绕过恢复校验。
     * 故先按平台无关的字符判据识别 `/`、`\` 开头与盘符形式，再回退到 isAbsolute
     * 以覆盖 UNC 等其余平台相关形态。
     */
    private fun isRootedEntryName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name[0] == '/' || name[0] == '\\') return true
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter() &&
            (name.length == 2 || name[2] == '\\' || name[2] == '/')
        ) {
            return true
        }
        return File(name).isAbsolute
    }

    // ---- prefs 备份/恢复（JSON，类型保留：b/i/l/f/s/ss） ----

    /** 序列化 prefs 全部键值（插件配置值为密文字符串，原样存取，不在备份链路解密）。 */
    fun prefsToJson(prefs: SharedPreferences): String {
        val root = org.json.JSONObject()
        for ((key, value) in prefs.all) {
            val entry = org.json.JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "b").put("v", value)
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is String -> entry.put("t", "s").put("v", value)
                is Set<*> -> entry.put("t", "ss").put("v", org.json.JSONArray(value))
                else -> continue
            }
            root.put(key, entry)
        }
        return root.toString()
    }

    /** 按 [prefsToJson] 的类型标注恢复 prefs（合并写，不删除备份中不存在的新增键）。 */
    fun restorePrefsJson(prefs: SharedPreferences, json: ByteArray) {
        val root = org.json.JSONObject(json.toString(Charsets.UTF_8))
        val editor = prefs.edit()
        for (key in root.keys()) {
            val entry = root.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "b" -> editor.putBoolean(key, entry.getBoolean("v"))
                "i" -> editor.putInt(key, entry.getInt("v"))
                "l" -> editor.putLong(key, entry.getLong("v"))
                "f" -> editor.putFloat(key, entry.getDouble("v").toFloat())
                "s" -> editor.putString(key, entry.getString("v"))
                "ss" -> {
                    val arr = entry.getJSONArray("v")
                    editor.putStringSet(key, (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) })
                }
            }
        }
        editor.apply()
    }

    /** 收集备份包元数据条目（settings/插件配置/plugins.xml；包根前缀外为 rime 文件）。 */
    internal fun collectMetaEntries(context: Context, mode: ExportMode): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        val settingsPrefs = context.getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE)
        entries += (META_PREFIX + "settings.json") to prefsToJson(settingsPrefs).toByteArray(Charsets.UTF_8)

        val sharedPrefsDir = File(context.filesDir.parentFile, "shared_prefs")
        sharedPrefsDir.listFiles()
            ?.filter { it.name.startsWith(PREFS_PREFIX_PLUGIN_CFG) && it.name.endsWith(".xml") }
            ?.forEach { file ->
                val pluginId = file.name
                    .removePrefix(PREFS_PREFIX_PLUGIN_CFG).removeSuffix(".xml")
                if (pluginId.isNotBlank()) {
                    val prefs = context.getSharedPreferences(PREFS_PREFIX_PLUGIN_CFG + pluginId, Context.MODE_PRIVATE)
                    entries += (META_PREFIX + "plugin_configs/$pluginId.json") to
                        prefsToJson(prefs).toByteArray(Charsets.UTF_8)
                }
            }

        val pluginsXml = File(context.filesDir, "plugins.xml")
        if (pluginsXml.exists()) {
            entries += (META_PREFIX + "plugins.xml") to pluginsXml.readBytes()
        }
        return entries
    }
}
