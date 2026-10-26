package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 云备份引擎：备份包的生成与恢复在宿主，传输协议由 [BackupPlugin] 的 JS 实现承载。
 *
 * 分工（与剪贴板同步的 ClipboardSyncBridge 同构）：
 * - 备份 = [RimeExportManager.buildArchiveToFile] 流式打包到 cacheDir → 插件 pushBackup
 * - 恢复 = 插件 pullBackup（宿主流式下载到缓存文件）→ [restoreArchive] 校验路由落盘 → 释放句柄
 * - 插件永不接触宿主文件系统：JS 侧只拿到/交回**不透明 blob 句柄**，字节由宿主流式读写
 *   （大包不经过 JS 堆，避免整包 base64 进 JS 源码导致 OOM）
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
    /** 备份互斥锁：防止自动定时备份与用户手动备份并发（打包/上传互斥）。 */
    private val backupMutex = Mutex()

    /** 备份包内元数据条目前缀（保留字，rime 目录不应有同名顶层文件）。 */
    const val META_PREFIX = "_xime_backup/"

    /** 设置项 SharedPreferences 文件名（SettingsPreferences.PREFS_NAME，此处避免依赖私有常量）。 */
    private const val PREFS_NAME_SETTINGS = "kime_settings"

    private const val PREFS_PREFIX_PLUGIN_CFG = "plugin_cfg_"

    /** 方案清单条目（仅完整方案档）：文件所有权/校验记录，随方案本体一起恢复。 */
    private const val SCHEME_REGISTRY_ENTRY = "schemas/registry.json"
    private const val SCHEME_MANIFESTS_ENTRY = "schemas/manifests/"

    /** v3 插件注册表条目（仅完整方案档，与插件包同档）：安装记录 + 启用状态。 */
    private const val PLUGINS_REGISTRY_ENTRY = "plugins.json"

    /**
     * 备份包顶层的**用户数据表**白名单：这些文件与 xime.yaml 同级（可见配置目录），
     * 恢复时必须走「可见目录」通道（[restoreUserDataFile]），否则会被可见目录里的
     * 旧文件在下次读取时遮蔽——与 rime/ 下的普通条目不同。
     *
     * 清单与 xime.yaml `backup` 段一一对应（[com.kingzcheung.xime.settings.BackupConfig]）。
     */
    private val USER_DATA_FILE_NAMES = setOf(
        com.kingzcheung.xime.service.CandidateOrderStore.FILE_NAME,
        com.kingzcheung.xime.service.AutoWordStore.TEMP_FILE_NAME,
        com.kingzcheung.xime.service.AutoWordStore.FIXED_FILE_NAME,
        com.kingzcheung.xime.service.WordFreqStore.FILE_NAME,
        com.kingzcheung.xime.service.WordBlacklistStore.FILE_NAME,
    )

    /**
     * 云备份到远端。返回 BackupResult（失败含原因）。
     *
     * 打包前先触发一次引擎 sync：librime 把各 userdb 导出成
     * `rime/sync/<installation_id>/` 下的 `*.userdb.txt` 文本快照，随包上传（恢复侧再合并）。
     * 引擎未就绪时按"仅打包现有快照"降级，并在成功消息里说明，不阻断备份。
     */
    suspend fun backupNow(
        context: Context,
        plugin: BackupPlugin
    ): com.kingzcheung.xime.plugin.core.api.BackupResult = backupMutex.withLock {
        // 先刷新自造词快照（引擎未就绪则用现有快照并在成功消息里说明）
        val dictSnapshotNote = if (SyncManager.syncNow(context).isSuccess) {
            Log.i(TAG, "Backup: 自造词快照已刷新")
            null
        } else {
            Log.w(TAG, "Backup: 引擎未就绪，使用现有自造词快照（可能非最新）")
            "（自造词快照未刷新：需先唤起键盘一次）"
        }
        // 流式打包到 cacheDir：包体不驻留内存（含方案词典与插件包时可达数十 MB）
        val (fileName, archive) = RimeExportManager.buildArchiveToFile(context)
            .getOrElse { return@withLock com.kingzcheung.xime.plugin.core.api.BackupResult(ok = false, message = it.message) }
        return@withLock try {
            Log.i(TAG, "Backup: name=$fileName size=${archive.length()}")
            val result = plugin.pushBackup(fileName, archive)
            if (result.ok) {
                // 上传成功即一次完整备份，刷新卡片状态
                SettingsPreferences.setLastBackupAt(context, System.currentTimeMillis())
                // 按 xime.yaml backup.keep_count 清理远端旧备份，只保留最近 N 份。
                // 失败不影响本次备份结果（仅记日志）——清理是维护性动作，不让它
                // 反过来把「备份成功」变成失败。
                pruneRemoteBackups(context, plugin)
                if (dictSnapshotNote != null) result.copy(message = dictSnapshotNote) else result
            } else result
        } finally {
            archive.delete()
        }
    }

    /**
     * 清理远端备份目录，只保留最近 [BackupConfig.keepCount] 份（含刚上传的）。
     *
     * listBackups 已按创建时间**倒序**返回（WebDAV 插件按 createdAt 排序），
     * 直接取前 N 个保留、其余逐个 [deleteBackup]。远端不提供时间戳（createdAt=0）
     * 时也按列表顺序保留前 N——此时顺序即插件返回顺序，与展示一致。
     *
     * keepCount <= 0 表示不清理（保留全部历史）。
     */
    private suspend fun pruneRemoteBackups(context: Context, plugin: BackupPlugin) {
        val keepCount = KeysConfigHelper.loadBackupConfig(context).keepCount
        if (keepCount <= 0) return
        val remote = plugin.listBackups() ?: return
        if (remote.size <= keepCount) return
        val toDelete = remote.drop(keepCount) // 保留前 keepCount 个（最新），删后面更旧的
        for (entry in toDelete) {
            val ok = runCatching { plugin.deleteBackup(entry.id) }.getOrDefault(false)
            Log.i(
                TAG,
                "Backup: 清理旧备份 ${entry.name}（id=${entry.id}）= ${if (ok) "成功" else "失败"} 保留${keepCount}份"
            )
        }
        Log.i(TAG, "Backup: 远端清理完成，保留最近 $keepCount 份，删除 ${toDelete.size} 份")
    }

    /** 列出远端备份条目（透传插件；失败返回 null）。 */
    suspend fun listRemote(plugin: BackupPlugin): List<RemoteBackupEntry>? = plugin.listBackups()
    /** 删除远端备份条目。 */
    suspend fun deleteRemote(plugin: BackupPlugin, id: String): Boolean = plugin.deleteBackup(id)

    /**
     * 自动备份检查（定时任务入口）。距上次成功备份 >= [AutoBackup.INTERVAL_MS] 时，
     * 若已有已启用的备份插件且配置就绪，自动执行一次 [backupNow]。
     *
     * - 无启用插件 / 未配置时静默跳过（不报错、不污染错误状态）；
     * - 与手动备份通过 [backupMutex] 互斥，不并发；
     * - 失败仅写日志，不打断应用（定时任务重试由下一次调度负责）。
     *
     * @return true=本次实际执行了备份
     */
    suspend fun maybeAutoBackup(context: Context): Boolean {
        val intervalMs = KeysConfigHelper.loadBackupConfig(context).autoIntervalHours.let { hours ->
            if (hours <= 0) return false else hours * 60 * 60 * 1000L
        }
        val enabled = ExtensionManager.getEnabledBackupPlugins(context)
        val plugin = enabled.firstOrNull { it.first == SettingsPreferences.getBackupPluginId(context) }
            ?: enabled.firstOrNull()
            ?: return false
        // 配置未就绪（url/账号等必填项缺失）不自动备份，避免每天往错误地址传空包
        if (!plugin.second.isConfigured()) {
            Log.i(TAG, "AutoBackup: 备份插件未配置完整，跳过自动备份")
            return false
        }
        val last = SettingsPreferences.getLastBackupAt(context)
        if (System.currentTimeMillis() - last < intervalMs) return false
        Log.i(TAG, "AutoBackup: 距上次备份 ${(System.currentTimeMillis() - last) / 3600_000}h（周期 ${intervalMs / 3600_000}h），开始自动备份")
        val result = backupNow(context, plugin.second)
        if (!result.ok) {
            Log.w(TAG, "AutoBackup: 自动备份失败: ${result.message}")
        } else {
            Log.i(TAG, "AutoBackup: 自动备份完成")
        }
        return true
    }

    /**
     * 从远端恢复：宿主流式下载到缓存文件 → 按条目前缀路由落盘 → 释放句柄。
     *
     * 落盘后还有两步收尾（[afterRestore]）：包内含自造词快照时逐个直接合并进用户词库
     * （引擎未就绪则记 pending，等引擎起来再合并）；并把 default.custom.yaml 里的
     * 启用方案列表重新镜像进 default.yaml。
     *
     * @return 成功 true；失败返回错误消息
     */
    suspend fun restore(context: Context, plugin: BackupPlugin, id: String): Result<Unit> {
        val download = plugin.pullBackup(id)
            ?: return Result.failure(Exception("下载备份包失败"))
        return try {
            restoreFromArchive(context, download.file)
        } finally {
            // 恢复完释放落盘句柄（删除宿主缓存文件；幂等）
            plugin.releaseBackup(download.blobId)
        }
    }

    /**
     * 从本地文件导入完整方案包（覆盖式恢复；[isBackupPackage] 判定后的入口）。
     *
     * 大包不走内存：先流式拷进 cacheDir 临时文件再解包，用完删除。
     */
    suspend fun importLocal(context: Context, uri: android.net.Uri): Result<Unit> {
        val temp = File(File(context.cacheDir, "local-import").apply { mkdirs() }, "package.zip")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return Result.failure(Exception("无法读取所选文件"))
            if (!isBackupPackage(temp)) {
                return Result.failure(
                    IllegalArgumentException("这不是完整方案包（缺少 ${META_PREFIX} 元数据）；词条快照请用「导入快照」")
                )
            }
            restoreFromArchive(context, temp)
        } catch (e: Exception) {
            Log.e(TAG, "importLocal failed", e)
            Result.failure(e)
        } finally {
            temp.delete()
        }
    }

    /** 判定一个 zip 是否是本应用的备份包（看中央目录里有没有 `_xime_backup/` 条目，不解压）。 */
    fun isBackupPackage(archive: File): Boolean {
        if (!archive.isFile) return false
        return try {
            java.util.zip.ZipFile(archive).use { zip ->
                zip.entries().asSequence().any { it.name.startsWith(META_PREFIX) }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 备份包落盘 + 收尾（云端恢复与本地导入共用）。 */
    internal suspend fun restoreFromArchive(context: Context, archive: File): Result<Unit> {
        val outcome = restoreArchiveOutcome(
            context.filesDir,
            { prefsName, json ->
                restorePrefsJson(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), json)
            },
            archive
        ).onSuccess {
            Log.i(TAG, "Restore done -> ${context.filesDir.path}")
        }.onFailure { e ->
            Log.e(TAG, "restoreArchive failed", e)
        }
        return outcome.map { afterRestore(context, it) }
    }

    /**
     * 恢复后收尾：自造词快照合并 + 启用方案列表镜像。
     *
     * 词库不直接覆盖 leveldb，而是把落盘的 `*.userdb.txt` 快照交给 librime
     * `UserDictManager::Restore` 按时间戳合并（与词库同步同语义）：备份后本机新打的词
     * 不会被旧备份吃掉；目标库名取自快照元数据，本机还没有该词库时会创建
     * （换机/清数据场景，见 [SyncManager.mergeSnapshots]）。
     * 引擎未就绪（冷启动直接进设置页）时记 pending 标记，由 IME 服务引擎就绪后补做。
     */
    private suspend fun afterRestore(context: Context, outcome: RestoreOutcome) {
        if (outcome.dictSnapshotCount > 0) {
            // 词库不直接覆盖 leveldb：快照交给 librime `UserDictManager::Restore` 按时间戳合并
            // （与词库同步同语义）：备份后本机新打的词不会被旧备份吃掉。
            //
            // 这里**不能**只调 syncNow()：librime 的 `Synchronize` 只处理本机已有
            // `<词典名>.userdb` 的词典，换机/清数据时本机一个用户词库都没有，快照会被静默
            // 跳过（且 sync 仍返回成功）——曾是"显示恢复成功但词库是空的"的根因。
            val snapshots = SyncManager.snapshotFiles(context)
            SyncManager.mergeSnapshots(context, snapshots)
                .onSuccess { mergedCount ->
                    Log.i(TAG, "Restore: 自造词快照已合并（$mergedCount/${snapshots.size} 个文件）")
                    // 一个都没并进去（包内快照损坏等）时留 pending，交给引擎就绪后重试
                    SettingsPreferences.setPendingDictMerge(context, mergedCount == 0)
                }
                .onFailure { e ->
                    Log.w(TAG, "Restore: 引擎未就绪，自造词快照待合并（pending）", e)
                    SettingsPreferences.setPendingDictMerge(context, true)
                }
        }
        // default.custom.yaml 已随包恢复（启用方案列表）：重新镜像进 default.yaml。
        // default.yaml 本身不进包（派生基座），避免恢复时把旧版本基座一起盖回来。
        try {
            SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        } catch (e: Exception) {
            Log.e(TAG, "Restore: 重新镜像 schema_list 失败", e)
        }
        // 把刚恢复的私有 rime 内容推回可见镜像并刷新同步基线。
        //
        // 为什么必须做：恢复是覆盖式写入 `files/rime/`，但可见目录
        // `/storage/emulated/0/rime/` 里还留着**恢复前的旧副本**，且基线清单没跟上。
        // 用户随后点「重新部署」(oiz → reloadConfig) 会先调 RimeUserFileSync.sync()，
        // 三向比对发现「可见改了/私有也变了 + 基线过期」，按冲突规则保留可见旧版本
        // 并写回私有目录——于是刚恢复的内容被旧文件盖掉，表现为「恢复完再部署没用」。
        // 这里用**单向强制镜像**（私有→可见，覆盖+重置基线），恢复内容成为权威；
        // 之后 reloadConfig 再触发普通 sync 时两侧与基线一致，是空操作，不再冲突。
        try {
            RimeUserFileSync.forceSyncPrivateToVisible(context)
        } catch (e: Exception) {
            Log.e(TAG, "Restore: 可见目录同步失败", e)
        }
    }

    /** 校验 zip 条目路径并解压覆盖（rime 文件 + META 元数据路由），Android 入口。 */
    fun restoreArchive(context: Context, archive: File): Result<Unit> {
        val filesDir = context.filesDir
        return restoreArchive(filesDir, { prefsName, json ->
            restorePrefsJson(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), json)
        }, archive, visibleConfigWriter = { text ->
            UserConfigDir.writeCustomConfig(context, text)
        }, userDataWriter = { fileName, text ->
            restoreUserDataFile(context, fileName, text)
        }).onSuccess {
            Log.i(TAG, "Restore done -> ${filesDir.path}")
        }.onFailure { e ->
            Log.e(TAG, "restoreArchive failed", e)
        }
    }

    /** 字节流入口（内存解压；本地导入等小包场景），内部复用文件流核心。 */
    fun restoreArchive(context: Context, bytes: ByteArray): Result<Unit> {
        val filesDir = context.filesDir
        return restoreArchive(filesDir, { prefsName, json ->
            restorePrefsJson(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), json)
        }, bytes, visibleConfigWriter = { text ->
            UserConfigDir.writeCustomConfig(context, text)
        }, userDataWriter = { fileName, text ->
            restoreUserDataFile(context, fileName, text)
        })
    }

    /**
     * 恢复结果概要（收尾步骤据此判断要不要合并词库快照）。
     *
     * @param entryCount        落盘条目总数（0 → 视为空包失败）
     * @param dictSnapshotCount 落盘的自造词快照文件数（`sync/<id>/` 下的 `*.userdb.txt`）
     */
    internal data class RestoreOutcome(val entryCount: Int, val dictSnapshotCount: Int)

    /**
     * 恢复一张用户数据表：优先写可见目录（与 xime.yaml 同级，用户可直接查看），
     * 失败时回退私有目录，保证恢复的数据不丢。与各 Store 的落盘策略一致
     *（[com.kingzcheung.xime.service.CandidateOrderStore.save]、
     * [com.kingzcheung.xime.service.AutoWordStore]、
     * [com.kingzcheung.xime.service.WordFreqStore]）。
     *
     * @param fileName 目标文件名，取值见 [USER_DATA_FILE_NAMES]
     */
    private fun restoreUserDataFile(context: Context, fileName: String, text: String): Boolean {
        if (UserConfigDir.writeText(context, fileName, text)) return true
        return runCatching {
            File(context.filesDir, fileName).writeText(text)
        }.isSuccess
    }

    /**
     * 恢复核心（纯 JVM，便于单测）：META 条目按前缀路由，其余条目进 filesDir/rime。
     *
     * @param prefsRestorer 宿主注入的 prefs 恢复器（Android 用 SharedPreferences；测试可注入假实现）
     * @param userDataWriter 用户数据表（[USER_DATA_FILE_NAMES]：候选排序、自动造词词库、
     *   上屏词频）的可见目录写入通道，入参为 `(文件名, 内容)`；为 null 时（纯 JVM 测试）
     *   写入 `filesDir/<文件名>`。与 [visibleConfigWriter] 同理：不这样分流的话，
     *   恢复出来的数据会被可见目录里的旧文件在下次读取时遮蔽。
     */
    fun restoreArchive(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        archive: File,
        visibleConfigWriter: ((String) -> Boolean)? = null,
        userDataWriter: ((fileName: String, text: String) -> Boolean)? = null
    ): Result<Unit> = restoreArchiveOutcome(
        filesDir, prefsRestorer, archive.inputStream(), visibleConfigWriter, userDataWriter
    ).map { }

    /** 字节入口：语义与文件入口一致（校验规则共用一份实现）。 */
    fun restoreArchive(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        bytes: ByteArray,
        visibleConfigWriter: ((String) -> Boolean)? = null,
        userDataWriter: ((fileName: String, text: String) -> Boolean)? = null
    ): Result<Unit> = restoreArchiveOutcome(
        filesDir, prefsRestorer, bytes.inputStream(), visibleConfigWriter, userDataWriter
    ).map { }

    private fun restoreArchiveOutcome(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        archive: File,
        visibleConfigWriter: ((String) -> Boolean)? = null,
        userDataWriter: ((fileName: String, text: String) -> Boolean)? = null
    ): Result<RestoreOutcome> = restoreArchiveOutcome(
        filesDir, prefsRestorer, archive.inputStream(), visibleConfigWriter, userDataWriter
    )

    /** 字节入口（internal 供单测断言条目计数）：语义与文件入口一致。 */
    internal fun restoreArchiveOutcome(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        bytes: ByteArray,
        visibleConfigWriter: ((String) -> Boolean)? = null,
        userDataWriter: ((fileName: String, text: String) -> Boolean)? = null
    ): Result<RestoreOutcome> = restoreArchiveOutcome(
        filesDir, prefsRestorer, bytes.inputStream(), visibleConfigWriter, userDataWriter
    )

    private fun restoreArchiveOutcome(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        rawInput: java.io.InputStream,
        visibleConfigWriter: ((String) -> Boolean)? = null,
        userDataWriter: ((fileName: String, text: String) -> Boolean)? = null
    ): Result<RestoreOutcome> {
        try {
            val rimeDir = File(filesDir, "rime")
            if (!rimeDir.exists()) rimeDir.mkdirs()
            val pluginsDir = File(filesDir, "plugins")
            val manifestsDir = SchemaManifestManager.getManifestsDir(filesDir)
            var entryCount = 0
            var dictSnapshotCount = 0
            ZipInputStream(rawInput).use { zis ->
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
                                if (isSafeId(pluginId)) {
                                    prefsRestorer(PREFS_PREFIX_PLUGIN_CFG + pluginId, data)
                                }
                            }
                            rel == "plugins.xml" -> writeChecked(File(filesDir, "plugins.xml"), filesDir, data)
                            // v3 插件注册表（安装记录 + 启用状态）：与插件包同档（完整方案）
                            rel == "plugins.json" -> writeChecked(File(filesDir, "plugins.json"), filesDir, data)
                            // 方案清单（文件所有权/校验）：完整方案档随方案本体一起恢复
                            rel == SCHEME_REGISTRY_ENTRY -> writeChecked(
                                SchemaManifestManager.getRegistryFile(filesDir), filesDir, data
                            )
                            rel.startsWith(SCHEME_MANIFESTS_ENTRY) && rel.endsWith(".json") -> {
                                val schemeId = rel.removePrefix(SCHEME_MANIFESTS_ENTRY).removeSuffix(".json")
                                if (isSafeId(schemeId)) {
                                    writeChecked(File(manifestsDir, "$schemeId.json"), manifestsDir, data)
                                }
                            }
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
                    val isUserData = relName in USER_DATA_FILE_NAMES
                    if (isUserData) {
                        val text = String(zis.readBytes(), Charsets.UTF_8)
                        val routed = userDataWriter?.invoke(relName, text) ?: false
                        if (!routed) {
                            File(filesDir, relName).writeText(text)
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
                    if (name.startsWith("sync/") && name.endsWith(".userdb.txt")) dictSnapshotCount++
                    entryCount++
                    zis.closeEntry()
                }
            }
            if (entryCount == 0) return Result.failure(Exception("备份包为空"))
            return Result.success(RestoreOutcome(entryCount, dictSnapshotCount))
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /** 元数据 id 安全性：非空且不含路径分隔符（防 marketplace/manifests 目录穿越）。 */
    private fun isSafeId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "." && id != ".."

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

    /**
     * 收集备份包元数据条目（设置项、插件配置、插件注册表、方案清单）。
     *
     * 包根前缀外的条目一律视为 rime 目录相对路径（见 [RimeExportManager.includeRimeEntry]）；
     * 历史 `plugins.xml`（v2 Lua 注册表，v3 已不读）不再产出，但恢复侧仍兼容旧包。
     */
    internal fun collectMetaEntries(context: Context): List<Pair<String, ByteArray>> {
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

        // 插件注册表（安装记录 + 启用状态）：与 META plugins/ 下的插件包同档才有意义
        val pluginsRegistry = File(context.filesDir, PLUGINS_REGISTRY_ENTRY)
        if (pluginsRegistry.exists()) {
            entries += (META_PREFIX + PLUGINS_REGISTRY_ENTRY) to pluginsRegistry.readBytes()
        }

        // 方案清单（文件所有权 + 校验）：与方案本体同档，供方案管理与卸载使用
        val registry = SchemaManifestManager.getRegistryFile(context)
        if (registry.exists()) {
            entries += (META_PREFIX + SCHEME_REGISTRY_ENTRY) to registry.readBytes()
        }
        SchemaManifestManager.getManifestsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.forEach { manifest ->
                entries += (META_PREFIX + SCHEME_MANIFESTS_ENTRY + manifest.name) to manifest.readBytes()
            }
        return entries
    }
}
