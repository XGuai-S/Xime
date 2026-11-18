package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
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
     * 恢复事务暂存目录：解包只写这里，全部校验通过后才逐文件换入目标位置。
     *
     * 旧实现边解包边覆盖 `files/rime`、`files/plugins`、`.manifests` 与 prefs。进程在中间
     * 被杀（输入法常驻进程被回收很常见）、包体后半段损坏、或某条目路径校验失败时，
     * 落盘就是半新半旧的混合态——rime 里新旧方案文件互相引用，引擎加载失败，用户也没有回退手段。
     */
    private const val STAGING_DIR_NAME = ".restore-staging"

    /** 换入阶段的原文件备份目录（提交成功即删；回滚不完整时保留供人工取回）。 */
    private const val ROLLBACK_DIR_NAME = ".restore-rollback"

    /**
     * 恢复事务日志（追加写）：`M <rel>` = 原文件已移入备份目录，`W <rel>` = 新文件已换入。
     * 进程在换入中途死亡时，下次启动凭它把整个事务反向撤销（见 [recoverInterruptedRestore]）。
     */
    private const val JOURNAL_FILE_NAME = ".restore-journal"

    /** 方案清单目录 / 注册表文件名（SchemaManifestManager 的私有常量，这里需要按名字定位）。 */
    private const val MANIFESTS_DIR_NAME = ".manifests"
    private const val REGISTRY_FILE_NAME = ".registry.json"

    /** 备份包 manifest 条目名（v2 起随包写入：格式版本 + 生成者版本）。 */
    const val MANIFEST_ENTRY = "manifest.json"

    /**
     * 当前写入/支持的备份包格式版本。
     *
     * v1：无 manifest（历史包）；v2：带 manifest.json。恢复侧遇到**更高**版本直接拒绝，
     * 避免新版包被旧版应用静默丢掉它不认识的字段（用户看到「恢复成功」但配置残缺）。
     */
    const val FORMAT_VERSION = 2

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
            .getOrElse {
                FileLogger.w(TAG, "备份打包失败: ${it.message}", it)
                return@withLock com.kingzcheung.xime.plugin.core.api.BackupResult(ok = false, message = it.message)
            }
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
            FileLogger.w(TAG, "自动备份失败: ${result.message}")
        } else {
            Log.i(TAG, "AutoBackup: 自动备份完成")
            FileLogger.i(TAG, "自动备份完成")
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
    suspend fun restore(context: Context, plugin: BackupPlugin, id: String): Result<Unit> =
        backupMutex.withLock {
        // 恢复必须与备份互斥：否则 AutoBackup 的定时检查可能在「恢复写到一半」时
        // 把半新半旧的 rime 目录打包上传，覆盖掉云端唯一一份好备份。
        val download = plugin.pullBackup(id)
            ?: run {
                FileLogger.w(TAG, "从远端下载备份包失败（id=$id）")
                return@withLock Result.failure(Exception("下载备份包失败"))
            }
        return@withLock try {
            val outcome = restoreFromArchive(context, download.file)
            if (outcome.isSuccess) {
                FileLogger.i(TAG, "备份恢复完成：${outcome.getOrNull()}")
            } else {
                FileLogger.w(TAG, "备份恢复失败: ${outcome.exceptionOrNull()?.message}")
            }
            outcome
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
    suspend fun importLocal(context: Context, uri: android.net.Uri): Result<Unit> =
        backupMutex.withLock {
        // 与 restore 同理：导入也是覆盖式写入 files/rime、files/plugins 与 prefs，
        // 必须与自动备份互斥，且临时文件用唯一名（旧实现固定叫 package.zip，
        // 两次导入并发会互相踩）。
        val temp = File.createTempFile("local-import-", ".zip", context.cacheDir)
        return@withLock try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withLock Result.failure(Exception("无法读取所选文件"))
            if (!isBackupPackage(temp)) {
                return@withLock Result.failure(
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
        // 两阶段恢复：阶段一全部解到 staging（可随时整目录丢弃），阶段二逐文件换入 + 事务日志。
        // 换入按**文件**进行而不是整目录替换：备份包刻意排除 build/、*.userdb/、default.yaml、
        // 其它设备的 sync/<id>/，整目录换入会把这些没进包的东西一并删掉。
        val staging = File(filesDir, STAGING_DIR_NAME)
        val rollback = File(filesDir, ROLLBACK_DIR_NAME)
        val journal = File(filesDir, JOURNAL_FILE_NAME)
        val plan = mutableListOf<String>()
        try {
            discardTransaction(staging, rollback, journal)
            staging.mkdirs()
            val stagingRime = File(staging, "rime")
            var entryCount = 0
            var dictSnapshotCount = 0
            var totalBytes = 0L
            var formatVersion = 0
            // prefs 与「可见目录」写入是不可回滚的副作用：先攒在内存，等文件全部换入后再提交，
            // 避免出现「配置写进去了、文件没换上」的割裂态。
            val pendingPrefs = mutableListOf<Pair<String, ByteArray>>()
            val pendingUserConfig = mutableListOf<Pair<String, String>>()
            val pendingUserData = mutableListOf<Pair<String, String>>()
            // 总量守卫：备份包是**外部输入**（用户从任意来源选的 zip / 远端下载的包），
            // 恶意或损坏的包可以是 zip 炸弹（单个高压缩比条目、或数万小条目）。
            // 无上限时 readBytes() 直接打爆常驻的输入法进程，或者把 /data 写满。
            ZipInputStream(rawInput).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) {
                        return Result.failure(
                            Exception("备份包条目过多（> $MAX_ENTRY_COUNT），已中止")
                        )
                    }
                    if (name.startsWith(META_PREFIX)) {
                        val rel = name.removePrefix(META_PREFIX)
                        val data = zis.readBounded(MAX_META_ENTRY_BYTES)
                        totalBytes += data.size
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            return Result.failure(
                                Exception("备份包解压后超过 $MAX_TOTAL_BYTES 字节上限，已中止")
                            )
                        }
                        when {
                            rel == MANIFEST_ENTRY -> formatVersion = parseFormatVersion(data)
                            rel == "settings.json" -> pendingPrefs += PREFS_NAME_SETTINGS to data
                            rel.startsWith("plugin_configs/") && rel.endsWith(".json") -> {
                                val pluginId = rel.removePrefix("plugin_configs/").removeSuffix(".json")
                                if (isSafeId(pluginId)) {
                                    pendingPrefs += PREFS_PREFIX_PLUGIN_CFG + pluginId to data
                                }
                            }
                            rel == "plugins.xml" -> {
                                stageBytes(staging, "plugins.xml", data); plan += "plugins.xml"
                            }
                            // v3 插件注册表（安装记录 + 启用状态）：与插件包同档（完整方案）
                            // H2：恢复备份不等于信任备份。
                            // 包内 plugins.json 带着 enabled=true 与安装记录，PluginLifecycleManager
                            // 只在启动时按 registry.enabled 加载 → 恢复一个第三方给的「完整方案包」
                            // 就等于装了一堆会自启的 JS 插件；再叠加同包里的 plugin_net_auth_*，
                            // 攻击者域名还直接处于「用户已授权」状态，完全绕过插件中心与网络授权 UI。
                            // 这里强制把每个插件置为未启用，由用户恢复后自行确认开启。
                            rel == PLUGINS_REGISTRY_ENTRY -> {
                                stageBytes(
                                    staging, PLUGINS_REGISTRY_ENTRY,
                                    disableAllPluginsInRegistry(data)
                                )
                                plan += PLUGINS_REGISTRY_ENTRY
                            }
                            // 方案清单（文件所有权/校验）：完整方案档随方案本体一起恢复
                            rel == SCHEME_REGISTRY_ENTRY -> {
                                stageBytes(staging, REGISTRY_FILE_NAME, data)
                                plan += REGISTRY_FILE_NAME
                            }
                            rel.startsWith(SCHEME_MANIFESTS_ENTRY) && rel.endsWith(".json") -> {
                                val schemeId = rel.removePrefix(SCHEME_MANIFESTS_ENTRY).removeSuffix(".json")
                                if (isSafeId(schemeId)) {
                                    val relPath = "$MANIFESTS_DIR_NAME/$schemeId.json"
                                    stageBytes(staging, relPath, data)
                                    plan += relPath
                                }
                            }
                            rel.startsWith("plugins/") -> {
                                val relPath = "plugins/" + rel.removePrefix("plugins/")
                                stageBytes(staging, relPath, data)
                                plan += relPath
                            }
                            else -> { /* 未知元数据条目跳过（向前兼容） */ }
                        }
                        // 条目计数已在循环开头统一累加（含条目数上限守卫），此处不再重复
                        zis.closeEntry()
                        continue
                    }
                    // 显式拒绝绝对路径（File(parent, "/abs") 在 Unix 下会被拼接为 parent/abs，
                    // canonical 检查拦不住；此处按契约直接拒绝）
                    if (isRootedEntryName(name)) {
                        return Result.failure(Exception("备份包包含非法路径: $name"))
                    }
                    val target = File(stagingRime, name)
                    val canonicalRoot = stagingRime.canonicalPath + File.separator
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
                    // `filesDir/<文件名>`（与 CandidateOrderStore 的私有回退路径一致）。
                    val isUserData = relName in USER_DATA_FILE_NAMES
                    if (isUserData) {
                        val data = zis.readBounded(MAX_META_ENTRY_BYTES)
                        totalBytes += data.size
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            return Result.failure(
                                Exception("备份包解压后超过 $MAX_TOTAL_BYTES 字节上限，已中止")
                            )
                        }
                        pendingUserData += relName to String(data, Charsets.UTF_8)
                        zis.closeEntry()
                        continue
                    }
                    if (isUserConfig && visibleConfigWriter != null) {
                        val data = zis.readBounded(MAX_META_ENTRY_BYTES)
                        totalBytes += data.size
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            return Result.failure(
                                Exception("备份包解压后超过 $MAX_TOTAL_BYTES 字节上限，已中止")
                            )
                        }
                        pendingUserConfig += relName to String(data, Charsets.UTF_8)
                        zis.closeEntry()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    // 流式写入 staging（不整包进内存），但累计总量防写满磁盘
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            totalBytes += n
                            if (totalBytes > MAX_TOTAL_BYTES) {
                                return Result.failure(
                                    Exception("备份包解压后超过 $MAX_TOTAL_BYTES 字节上限，已中止")
                                )
                            }
                            out.write(buf, 0, n)
                        }
                    }
                    if (relName.startsWith("sync/") && relName.endsWith(".userdb.txt")) dictSnapshotCount++
                    plan += "rime/$relName"
                    zis.closeEntry()
                }
            }
            if (entryCount == 0) return Result.failure(Exception("备份包为空"))
            // 版本闸：拒绝更高版本的包（旧版应用会静默丢掉它不认识的字段）
            if (formatVersion > FORMAT_VERSION) {
                return Result.failure(
                    Exception(
                        "备份包格式版本 v$formatVersion 高于当前支持的 v$FORMAT_VERSION，" +
                            "请先把应用升级到最新版本再恢复"
                    )
                )
            }
            if (formatVersion == 0) {
                FileLogger.w(TAG, "恢复：包内无 $MANIFEST_ENTRY（v1 旧包），按兼容模式恢复")
            }
            // —— 阶段二：换入（带事务日志，可回滚） ——
            if (!commitPlan(filesDir, staging, rollback, journal, plan)) {
                return Result.failure(
                    Exception("备份恢复换入失败，已回滚到恢复前状态；详情见日志")
                )
            }
            // 文件已换入，提交不可回滚的副作用（失败只记日志，不回滚文件：
            // 设置项本就是合并写，少恢复几个键比让用户丢掉刚恢复的文件代价小得多）
            pendingPrefs.forEach { (prefsName, data) ->
                try {
                    prefsRestorer(prefsName, data)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "恢复设置失败：$prefsName", e)
                }
            }
            pendingUserData.forEach { (fileName, text) ->
                val routed = userDataWriter?.invoke(fileName, text) ?: false
                if (!routed) {
                    runCatching { File(filesDir, fileName).writeText(text) }
                        .onFailure { FileLogger.w(TAG, "恢复用户数据表失败：$fileName", it) }
                }
            }
            pendingUserConfig.forEach { (relName, text) ->
                val routed = visibleConfigWriter?.invoke(text) ?: false
                if (!routed) {
                    val fallback = File(filesDir, "rime/$relName")
                    runCatching {
                        fallback.parentFile?.mkdirs()
                        fallback.writeText(text)
                    }.onFailure { FileLogger.w(TAG, "恢复用户配置失败：$relName", it) }
                }
            }
            return Result.success(RestoreOutcome(entryCount, dictSnapshotCount))
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            staging.deleteRecursively()
            // 回滚目录只在「回滚本身失败」时保留：里面是恢复前的原始文件，
            // 删掉就等于把用户数据一起删了，宁可留一个可人工取回的目录。
            // journal 必须跟 rollback **同进退**：留着 rollback 却删了 journal，
            // 下次启动 recoverInterruptedRestore 只会看到「没有日志 = 正常结束」，
            // 于是这份回滚材料永不还原、也永不清理，用户得到的是半新半旧的 rime 目录。
            if (rollback.listFiles()?.isEmpty() != false) {
                rollback.deleteRecursively()
                journal.delete()
            } else {
                FileLogger.e(
                    TAG,
                    "回滚未完成：保留 rollback=${rollback.path} 与 journal=${journal.path} 待下次启动重试",
                )
            }
        }
    }

    /** 把字节写到 staging 下的相对路径（父目录自动创建）。 */
    private fun stageBytes(staging: File, relPath: String, data: ByteArray) {
        val target = File(staging, relPath)
        writeChecked(target, staging, data)
    }

    /**
     * 阶段二：把 staging 里 [plan] 列出的每个文件换入 [filesDir]，逐条写事务日志。
     *
     * 每个文件：原目标先移入 [rollback]（记 `M`）→ staging 文件移入目标（记 `W`）。
     * 任一步失败立即按日志反向撤销；进程若在此间被杀，下次启动由
     * [recoverInterruptedRestore] 用同一份日志完成撤销。备份包范围外的文件
     * （build/、*.userdb/、default.yaml 等不在 plan 里）完全不受影响。
     */
    private fun commitPlan(
        filesDir: File,
        staging: File,
        rollback: File,
        journal: File,
        plan: List<String>
    ): Boolean {
        rollback.deleteRecursively()
        rollback.mkdirs()
        journal.writeText("")
        try {
            for (rel in plan) {
                val src = File(staging, rel)
                if (!src.isFile) continue
                val dst = File(filesDir, rel)
                dst.parentFile?.mkdirs()
                if (dst.exists()) {
                    val bak = File(rollback, rel)
                    bak.parentFile?.mkdirs()
                    if (!movePath(dst, bak)) throw java.io.IOException("旧文件暂存失败: $rel")
                    journal.appendText("M $rel\n")
                }
                if (!movePath(src, dst)) throw java.io.IOException("换入失败: $rel")
                journal.appendText("W $rel\n")
            }
            journal.delete()
            rollback.deleteRecursively()
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "备份恢复换入失败，开始回滚", e)
            val ok = rollbackByJournal(filesDir, rollback, journal)
            if (!ok) {
                FileLogger.e(TAG, "回滚不完整：${rollback.path} 保留了恢复前的原始文件")
            }
            return false
        }
    }

    /**
     * 按事务日志反向撤销：`W` 删除已换入的新文件，`M` 把原文件从备份目录移回。
     *
     * 进程在换入中途被杀也能安全执行（日志是追加写的，末尾半行会被忽略）。
     * @return 全部撤销成功 true；有残留 false（此时保留 [rollback] 供人工取回）
     */
    private fun rollbackByJournal(filesDir: File, rollback: File, journal: File): Boolean {
        if (!journal.isFile) return true
        val lines = runCatching { journal.readLines() }.getOrDefault(emptyList())
        var complete = true
        for (line in lines.asReversed()) {
            if (line.length < 3) continue
            val op = line.substring(0, 1)
            val rel = line.substring(2)
            if (rel.isEmpty() || isRootedEntryName(rel) || rel.contains("..")) {
                complete = false
                continue
            }
            when (op) {
                "W" -> {
                    // 换入的新文件：删掉，回到「原文件还在备份目录」的状态
                    val dst = File(filesDir, rel)
                    if (dst.exists() && !dst.deleteRecursively()) complete = false
                }
                "M" -> {
                    val bak = File(rollback, rel)
                    if (bak.exists()) {
                        val dst = File(filesDir, rel)
                        if (dst.exists()) dst.deleteRecursively()
                        if (!movePath(bak, dst)) complete = false
                    }
                }
            }
        }
        if (complete) {
            journal.delete()
            rollback.deleteRecursively()
        }
        return complete
    }

    /**
     * 启动自检：上一次恢复是否在换入途中被中断，是则整体撤销。
     *
     * 必须由 Application 在 IO 线程调用（恢复中途被杀是常态：输入法常驻进程被 LMK 回收）。
     * 没有事务日志时是廉价的一次 `File.exists()`。
     */
    fun recoverInterruptedRestore(context: Context) {
        val filesDir = context.filesDir
        val journal = File(filesDir, JOURNAL_FILE_NAME)
        val staging = File(filesDir, STAGING_DIR_NAME)
        val rollback = File(filesDir, ROLLBACK_DIR_NAME)
        if (!journal.isFile) {
            // 没有日志 = 上次恢复正常结束（或根本没跑过），顺手清掉可能残留的 staging
            if (staging.exists()) staging.deleteRecursively()
            return
        }
        FileLogger.w(TAG, "检测到未完成的备份恢复事务，开始回滚")
        val ok = rollbackByJournal(filesDir, rollback, journal)
        staging.deleteRecursively()
        if (ok) {
            FileLogger.w(TAG, "未完成的备份恢复已回滚到恢复前状态")
        } else {
            FileLogger.e(TAG, "未完成的备份恢复回滚不彻底，原文件保留在 ${rollback.path}")
        }
    }

    /**
     * 开始新恢复前清理上一次事务残留。
     *
     * **不能无条件删除 rollback 与 journal**：若上一次恢复在「换入」阶段被杀、还没被
     * [recoverInterruptedRestore] 撤销就再次发起恢复，`.restore-rollback` 里装的是
     * 恢复前的原始文件、`.restore-journal` 是撤销所需的清单。直接删掉等于把用户数据
     * 连同回滚能力一起丢弃，之后既回不去也说不清配置为什么坏了。
     *
     * 正确顺序：先按 journal 撤销上一次未完成的事务（把原始文件还回去），再清 staging。
     */
    private fun discardTransaction(staging: File, rollback: File, journal: File) {
        if (journal.isFile) {
            FileLogger.w(TAG, "检测到上一次未完成的恢复事务，先按 journal 回滚，再开始新的恢复")
            // staging = File(filesDir, STAGING_DIR_NAME)，故 parentFile 即 filesDir
            val filesDir = staging.parentFile ?: staging
            if (!rollbackByJournal(filesDir, rollback, journal)) {
                // 回滚本身就失败了：此时 rollback 目录里是**恢复前唯一一份**原始文件，
                // journal 是唯一的撤销清单。都不能删 —— 删掉就既回不去也查不清，
                // 只能让用户手抄。保留下来交给下次启动的 recoverInterruptedRestore。
                FileLogger.e(
                    TAG,
                    "上一次事务回滚不完整，原文件与 journal 一并保留：rollback=${rollback.path} journal=${journal.path}",
                )
                staging.deleteRecursively()
                return
            }
        }
        staging.deleteRecursively()
        rollback.deleteRecursively()
        journal.delete()
    }

    /** 移动文件或目录：优先 rename（同卷原子），失败退回递归复制 + 删除。 */
    private fun movePath(src: File, dst: File): Boolean {
        if (!src.exists()) return true
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return true
        return try {
            if (!copyTreeTo(src, dst)) return false
            src.deleteRecursively()
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "移动失败：${src.path} -> ${dst.path}", e)
            false
        }
    }

    private fun copyTreeTo(src: File, dst: File): Boolean {
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return false
            val children = src.listFiles() ?: return true
            for (child in children) {
                if (!copyTreeTo(child, File(dst, child.name))) return false
            }
            return true
        }
        return try {
            dst.parentFile?.mkdirs()
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 manifest.json 提取 formatVersion；无 manifest（v1 旧包）或解析失败返回 0。
     *
     * 这里不用 org.json：恢复核心是纯 JVM 实现（见 BackupManagerTest），
     * 单测环境下 android 的 org.json 是 stub，直接抛 "not mocked"。
     */
    private fun parseFormatVersion(data: ByteArray): Int {
        val text = String(data, Charsets.UTF_8)
        val match = Regex("\"formatVersion\"\\s*:\\s*(\\d+)").find(text) ?: return 0
        return match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
    }

    /** 元数据 id 安全性：非空且不含路径分隔符（防 marketplace/manifests 目录穿越）。 */
    private fun isSafeId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && id != "." && id != ".."

    /** 备份包条目数上限（正常完整备份约数百个文件，留足余量）。 */
    private const val MAX_ENTRY_COUNT = 20_000

    /** 单个 META 条目（settings.json / plugin_configs / manifests）的大小上限。 */
    private const val MAX_META_ENTRY_BYTES = 32L * 1024 * 1024

    /** 解压后总字节上限（防 zip 炸弹写满磁盘）。 */
    private const val MAX_TOTAL_BYTES = 1_024L * 1024 * 1024

    /**
     * 有界读取一个 zip 条目：超过 [max] 直接抛异常中止恢复。
     *
     * 不能改用 `readBytes()`：它对高压缩比条目会一次性分配完整解压大小。
     */
    private fun java.util.zip.ZipInputStream.readBounded(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw java.io.IOException("备份包单条目超过 $max 字节上限")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
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

    /**
     * 恢复时必须丢弃的设置键（H2）。
     *
     * 这些键代表「用户对某个插件/域名的显式授权」或「安全开关」，
     * 让它们随备份包一起还原，等于让备份包自行授予权限：
     * - `plugin_net_auth_*` / `plugin_net_pending_*`：插件网络域名白名单
     * - `plugin_enabled_*`：插件启用状态（与 registry 双写，见生命周期加载逻辑）
     * - `plugin_dev_mode*`：插件开发模式（会放开 exported 安装入口）
     * 恢复后一律回到「未授权」，由用户在插件中心逐条重新确认。
     */
    private val RESTORE_PREFS_SENSITIVE_KEY = Regex(
        "^plugin_(net_auth|net_pending|enabled|dev_mode)"
    )

    /** 按 [prefsToJson] 的类型标注恢复 prefs（合并写，不删除备份中不存在的新增键）。 */
    fun restorePrefsJson(prefs: SharedPreferences, json: ByteArray) {
        val root = org.json.JSONObject(json.toString(Charsets.UTF_8))
        val editor = prefs.edit()
        var skipped = 0
        for (key in root.keys()) {
            // 敏感授权键不随包恢复：备份包不可信，不能让它自带权限。
            if (RESTORE_PREFS_SENSITIVE_KEY.containsMatchIn(key)) {
                skipped++
                continue
            }
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
        if (skipped > 0) {
            FileLogger.w(TAG, "恢复设置：已跳过 $skipped 个插件授权/启用类敏感键（需用户重新确认）")
        }
    }

    /**
     * 把插件注册表里所有插件的 `enabled` 置为 false（H2）。
     *
     * 解析失败时返回**空注册表**（= 该备份包里的插件一个都不装），
     * 宁可让用户重装插件，也不让一个损坏/伪造的 registry 变成自启入口。
     */
    private fun disableAllPluginsInRegistry(data: ByteArray): ByteArray {
        return try {
            val json = org.json.JSONObject(String(data, Charsets.UTF_8))
            val array = json.optJSONArray("plugins")
            var count = 0
            if (array != null) {
                for (i in 0 until array.length()) {
                    val p = array.optJSONObject(i) ?: continue
                    if (p.optBoolean("enabled", false)) count++
                    p.put("enabled", false)
                }
            }
            if (count > 0) {
                FileLogger.w(TAG, "恢复插件注册表：已将 $count 个插件置为未启用，需用户重新开启")
            }
            json.toString().toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            FileLogger.e(TAG, "恢复插件注册表解析失败，改为空注册表（不安装任何插件）", e)
            "{}".toByteArray(Charsets.UTF_8)
        }
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

        // 格式版本标识：恢复侧据此拒绝「更高版本写出的包」，而不是静默丢掉不认识的字段
        entries += (META_PREFIX + MANIFEST_ENTRY) to buildManifestJson().toByteArray(Charsets.UTF_8)
        return entries
    }

    /** 备份包 manifest 内容（格式版本 + 生成者版本 + 创建时间）。 */
    private fun buildManifestJson(): String = buildString {
        append("{\"formatVersion\":").append(FORMAT_VERSION)
        append(",\"appVersion\":\"").append(com.kingzcheung.xime.BuildConfig.VERSION_NAME).append('"')
        append(",\"appVersionCode\":").append(com.kingzcheung.xime.BuildConfig.VERSION_CODE)
        append(",\"createdAt\":").append(System.currentTimeMillis())
        append(",\"generator\":\"xime\"}")
    }
}
