package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kingzcheung.xime.clipboard.ClipboardBackup
import com.kingzcheung.xime.clipboard.ClipboardBackupCodec
import com.kingzcheung.xime.clipboard.ClipboardBackupRecord
import com.kingzcheung.xime.clipboard.ClipboardImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * - `_xime_backup/clipboard/manifest.json`           普通历史/快捷发送（排除 OTP，Room 合并）
 * - `_xime_backup/clipboard/images/<sha256>.<ext>`    清单声明的图片资源
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
     * **优先文件级增量**：插件支持 `backup.pushFile` 时，本地打包后按内容哈希只上传
     * 变化的文件（见 [IncrementalBackup]）；插件不支持或远端查询失败时回退整包上传。
     *
     * 不再触发 librime `sync` 导出 `*.userdb.txt` 快照：那份快照是整包时代的产物，
     * 文件级备份直接传 rime 目录里的原始文件，恢复时逐文件写回，不需要先导出再合并。
     */
    suspend fun backupNow(
        context: Context,
        plugin: BackupPlugin
    ): com.kingzcheung.xime.plugin.core.api.BackupResult = backupMutex.withLock {
        val runId = BackupRunLog.begin(BackupRunTarget.CLOUD)
        var archive: File? = null
        try {
            val coroutineContext = currentCoroutineContext()
            val checkActive = { coroutineContext.ensureActive() }

            // ── 第一选择：文件级增量 ──────────────────────────────────
            val incremental = IncrementalBackup.backup(context, plugin, runId, checkActive)
            incremental.getOrNull()?.let { outcome ->
                checkActive()
                SettingsPreferences.setLastBackupAt(context, System.currentTimeMillis())
                BackupRunLog.finish(runId, BackupRunOutcome.SUCCESS)
                val msg = if (outcome.uploaded == 0) {
                    "没有变化，无需上传（共 ${outcome.skipped} 个文件已是最新）"
                } else {
                    "已增量上传 ${outcome.uploaded} 个文件（${outcome.skipped} 个未变），共 " +
                        "${outcome.bytes / 1024} KB"
                }
                FileLogger.i(TAG, "增量备份完成：$msg")
                return@withLock com.kingzcheung.xime.plugin.core.api.BackupResult(ok = true, message = msg)
            }
            incremental.exceptionOrNull()?.let {
                if (it is CancellationException) throw it
                // 增量中途失败：不立刻放弃，回退整包再试一次（整包是更保守的路径）
                FileLogger.w(TAG, "增量备份失败，回退整包: ${it.message}")
            }

            // ── 回退：整包上传（老插件 / 增量失败） ────────────────────
            BackupRunLog.stage(runId, BackupRunStage.PACKING)
            val packed = RimeExportManager.buildArchiveToFile(context, runId, checkActive).getOrElse {
                if (it is CancellationException) throw it
                BackupRunLog.finish(runId, BackupRunOutcome.PACKING_FAILED)
                FileLogger.w(TAG, "完整备份打包失败")
                return@withLock com.kingzcheung.xime.plugin.core.api.BackupResult(ok = false, message = "完整备份打包失败")
            }
            val (fileName, packedFile) = packed
            archive = packedFile
            checkActive()
            BackupRunLog.stage(runId, BackupRunStage.UPLOADING)
            val result = plugin.pushBackup(fileName, packedFile)
            checkActive()
            if (!result.ok) {
                BackupRunLog.finish(runId, BackupRunOutcome.UPLOAD_FAILED)
                return@withLock result.copy(message = "完整备份上传未完成")
            }
            SettingsPreferences.setLastBackupAt(context, System.currentTimeMillis())
            try {
                if (!pruneRemoteBackups(context, plugin, runId, fileName, result.id)) BackupRunLog.remoteCleanupFailed(runId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BackupRunLog.remoteCleanupFailed(runId)
            }
            checkActive()
            BackupRunLog.finish(runId, BackupRunOutcome.SUCCESS)
            result
        } catch (e: CancellationException) {
            BackupRunLog.finish(runId, BackupRunOutcome.CANCELLED)
            throw e
        } catch (_: Exception) {
            BackupRunLog.finish(runId, BackupRunOutcome.FAILED)
            FileLogger.w(TAG, "完整云备份流程未完成")
            com.kingzcheung.xime.plugin.core.api.BackupResult(ok = false, message = "完整云备份流程未完成")
        } finally {
            runCatching { archive?.delete() }
        }
    }

    /**
     * 清理远端备份目录，只保留最近 [BackupConfig.keepCount] 份（含刚上传的）。
     *
     * 仅整理已识别的完整备份，按有效时间倒序保留；未知文件、无时间戳及本次上传均不删除。
     * 本次上传尚未出现在列表时延后清理，避免过期列表导致误删。
     *
     * keepCount <= 0 表示不清理（保留全部历史）。
     */
    private suspend fun pruneRemoteBackups(
        context: Context,
        plugin: BackupPlugin,
        runId: Long,
        uploadedName: String,
        uploadedId: String?,
    ): Boolean = cleanupRemoteBackups(
        plugin, KeysConfigHelper.loadBackupConfig(context).keepCount, uploadedName, uploadedId, runId,
    )

    internal suspend fun cleanupRemoteBackups(
        plugin: BackupPlugin,
        keepCount: Int,
        uploadedName: String,
        uploadedId: String?,
        runId: Long = 0L,
    ): Boolean {
        if (keepCount <= 0) return true
        BackupRunLog.stage(runId, BackupRunStage.CLEANING_REMOTE)
        val remote = plugin.listBackups() ?: return false
        // 同一 ID 的冲突元数据不能证明归属，整组保留。
        val unambiguous = remote.groupBy { it.id }.values.filter { group -> group.distinct().size == 1 }.map { it.first() }
        val managed = unambiguous.filter { it.id.isNotBlank() && isManagedFullBackupName(it.name) }
        val uploaded = managed.singleOrNull {
            it.name == uploadedName && (uploadedId.isNullOrBlank() || it.id == uploadedId)
        } ?: return false
        val toDelete = managed.asSequence()
            .filter { it.id != uploaded.id && it.createdAt > 0 }
            .sortedWith(compareByDescending<RemoteBackupEntry> { it.createdAt }.thenBy { it.id })
            .drop(keepCount - 1)
            .toList()
        var deleted = 0
        for (entry in toDelete) {
            val ok = try {
                plugin.deleteBackup(entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (ok) deleted++
        }
        Log.i(TAG, "Backup: 远端历史整理，保留 $keepCount 份，已删除 $deleted 份")
        return deleted == toDelete.size
    }

    private fun isManagedFullBackupName(name: String): Boolean {
        val date = Regex("^(?:Xime完整备份|Xime配置)-([0-9]{4}-[0-9]{2}-[0-9]{2})\\.zip$")
            .matchEntire(name)?.groupValues?.get(1) ?: return false
        return runCatching { java.time.LocalDate.parse(date) }.isSuccess
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
        // 还没到周期就直接返回：这是最便宜的判据（一次 SharedPreferences 读），
        // 而周期通常是 12/24 小时——绝大多数轮询到这里就该结束，
        // 不必再走「取插件列表 + 逐个查配置」那套。
        val last = SettingsPreferences.getLastBackupAt(context)
        if (System.currentTimeMillis() - last < intervalMs) return false
        val enabled = ExtensionManager.getEnabledBackupPlugins(context)
        val plugin = enabled.firstOrNull { it.first == SettingsPreferences.getBackupPluginId(context) }
            ?: enabled.firstOrNull()
            ?: return false
        // 配置未就绪（url/账号等必填项缺失）不自动备份，避免每天往错误地址传空包
        if (!plugin.second.isConfigured()) {
            Log.i(TAG, "AutoBackup: 备份插件未配置完整，跳过自动备份")
            return false
        }
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
        //
        // 用户在备份列表里选了具体 id，就恢复那个**整包**——这是列表的语义。
        // 之前无条件优先"文件级镜像恢复"会无视所选 id：选了 3 天前的旧包，
        // 实际恢复的却是当前镜像，时间点回滚完全失效。
        // 只有按 id 拿不到整包（镜像模式可能只留清单没有历史包）时才回退增量镜像恢复。
        val download = plugin.pullBackup(id)
        if (download != null) {
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

        // 回退：文件级镜像恢复（远端只有持续同步的镜像、没有该 id 的整包时）
        FileLogger.w(TAG, "按 id 下载整包失败（id=$id），回退文件级镜像恢复")
        val coroutineContext = currentCoroutineContext()
        val checkActive = { coroutineContext.ensureActive() }
        val incremental = IncrementalBackup.restore(context, plugin, checkActive)
            ?: return@withLock Result.failure(Exception("下载备份包失败"))
        if (incremental.isSuccess) {
            FileLogger.i(TAG, "文件级备份恢复完成")
        } else {
            FileLogger.w(TAG, "文件级备份恢复失败: ${incremental.exceptionOrNull()?.message}")
        }
        return@withLock incremental
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
                copyLocalImportBounded(input, temp)
            } ?: return@withLock Result.failure(Exception("无法读取所选文件"))
            if (!isBackupPackage(temp)) {
                return@withLock Result.failure(
                    IllegalArgumentException("这不是完整方案包（缺少 ${META_PREFIX} 元数据）；词条快照请用「导入快照」")
                )
            }
            restoreFromArchive(context, temp)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "importLocal failed", e)
            Result.failure(e)
        } finally {
            temp.delete()
        }
        }

    /**
     * 有界流式拷贝本地导入包到缓存临时文件。
     *
     * 一次 `copyTo` 没有上限：误选/恶意的超大文件会把 cacheDir 写满，
     * 且中间没有取消检查。这里按 [MAX_ARCHIVE_FILE_BYTES] 硬性截断，
     * 超限即失败（调用方负责删临时文件），每个分片检查一次协程取消。
     */
    private suspend fun copyLocalImportBounded(input: java.io.InputStream, target: File) {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        target.outputStream().use { output ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_ARCHIVE_FILE_BYTES) {
                    throw java.io.IOException(
                        "备份包超过 ${MAX_ARCHIVE_FILE_BYTES / 1024 / 1024}MB 上限，已中止导入"
                    )
                }
                output.write(buffer, 0, read)
            }
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
        // **必须**同时给两个 writer：xime.yaml 与候选排序/词频表这类「真源」在可见目录
        // （/sdcard/rime/xime/…）。只写私有目录的话，读取侧（UserConfigDir.readText）
        // 会先读到可见目录里的**旧文件**，恢复出来的配置被静默遮蔽 ——
        // 用户看到的就是「提示恢复成功，重启后设置一点没变」。
        // 云端恢复与本机整包导入都走这里（另一组 restoreArchive(context, …) 重载早已传了
        // 两个 writer，只有这条主路径漏了）。
        val outcome = restoreWithMirror(context) { restoreArchiveOutcome(
            context.filesDir,
            { prefsName, json ->
                restorePrefsJson(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), json)
            },
            archive,
            visibleConfigWriter = { text -> UserConfigDir.writeCustomConfig(context, text) },
            userDataWriter = { fileName, text -> restoreUserDataFile(context, fileName, text) },
            clipboardRestorer = { records, staging -> ClipboardBackup.restore(context, records, staging) },
        ) }.onSuccess {
            Log.i(TAG, "Restore done -> ${context.filesDir.path}")
        }.onFailure { e ->
            Log.e(TAG, "restoreArchive failed", e)
        }
        return outcome.map { afterRestore(context, it); it }.completion()
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
        // 恢复是「绕过 save 直接写盘」：AutoWordStore.dataVersion 不会自增，运行中的
        // 输入法服务据此判定「词库没变」→ 不重载 → 用户恢复完立刻打字看到的仍是旧候选，
        // 只能靠之后某次 save 的三方合并慢慢自愈。这里显式推一格版本号，让下个会话重载。
        runCatching { com.kingzcheung.xime.service.AutoWordStore.invalidateForExternalChange() }
            .onFailure { Log.w(TAG, "Restore: 词库版本失效标记失败", it) }
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Restore: 引擎未就绪，自造词快照待合并（pending）", e)
                    SettingsPreferences.setPendingDictMerge(context, true)
                }
        }
    }

    /** Runs file publication and its mirror before ordinary sync can observe a mixed state. */
    private fun restoreWithMirror(
        context: Context,
        restore: () -> Result<RestoreOutcome>,
    ): Result<RestoreOutcome> = RimeUserFileSync.withRestoreSyncLock {
        restore().map { outcome ->
            val failures = outcome.failures.toMutableSet()
            val paths = outcome.restoredRimePaths.toMutableSet()
            if ("default.custom.yaml" in paths) {
                try {
                    if (SchemaManager.applyEnabledSchemasToDefaultYaml(context)) paths += "default.yaml"
                    else failures += RestoreFailure.SCHEMAS
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failures += RestoreFailure.SCHEMAS
                }
            }
            // 必须先按恢复清单镜像顶层源文件：旧备份的未启用方案可能仍平铺在顶层，
            // 后面的 ensureSchemeLayout 会把它们移进 schemes/，再镜像就会报源缺失。
            try {
                RimeUserFileSync.forceSyncPrivateToVisible(context, paths)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                failures += RestoreFailure.MIRROR
            }
            // 布局整理在镜像之后：两边都拿到恢复文件，再各自归档未启用方案。
            try {
                SchemaManager.ensureSchemeLayout(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // 布局整理失败不影响恢复结果本身（文件已经写好了），只记日志
            }
            outcome.copy(failures = failures)
        }
    }

    /** 校验 zip 条目路径并解压覆盖（rime 文件 + META 元数据路由），Android 入口。 */
    fun restoreArchive(context: Context, archive: File): Result<Unit> {
        val filesDir = context.filesDir
        return restoreWithMirror(context) { restoreArchiveOutcome(filesDir, { prefsName, json ->
            restorePrefsJson(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), json)
        }, archive, visibleConfigWriter = { text ->
            UserConfigDir.writeCustomConfig(context, text)
        }, userDataWriter = { fileName, text ->
            restoreUserDataFile(context, fileName, text)
        }, clipboardRestorer = { records, staging ->
            ClipboardBackup.restore(context, records, staging)
        }) }.completion().onSuccess {
            Log.i(TAG, "Restore done -> ${filesDir.path}")
        }.onFailure { e ->
            Log.e(TAG, "restoreArchive failed", e)
        }
    }

    /** 字节流入口（内存解压；本地导入等小包场景），内部复用文件流核心。 */
    fun restoreArchive(context: Context, bytes: ByteArray): Result<Unit> {
        val filesDir = context.filesDir
        return restoreWithMirror(context) { restoreArchiveOutcome(filesDir, { prefsName, json ->
            restorePrefsJson(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), json)
        }, bytes.inputStream(), visibleConfigWriter = { text ->
            UserConfigDir.writeCustomConfig(context, text)
        }, userDataWriter = { fileName, text ->
            restoreUserDataFile(context, fileName, text)
        }, clipboardRestorer = { records, staging ->
            ClipboardBackup.restore(context, records, staging)
        }) }.completion()
    }

    /**
     * 恢复结果概要（收尾步骤据此判断要不要合并词库快照）。
     *
     * @param entryCount        落盘条目总数（0 → 视为空包失败）
     * @param dictSnapshotCount 落盘的自造词快照文件数（`sync/<id>/` 下的 `*.userdb.txt`）
     */
    internal enum class RestoreFailure(val label: String) {
        PREFERENCES("设置"), USER_DATA("用户词表"), CONFIG("配置"), CLIPBOARD("剪贴板"),
        MIRROR("可见目录同步"), SCHEMAS("启用方案"),
    }

    internal class PartialRestoreException(val failures: Set<RestoreFailure>) : java.io.IOException(
        "文件恢复已完成，部分内容未恢复：" + failures.sortedBy { it.ordinal }.joinToString("、") { it.label },
    )

    internal data class RestoreOutcome(
        val entryCount: Int,
        val dictSnapshotCount: Int,
        val restoredRimePaths: Set<String> = emptySet(),
        val failures: Set<RestoreFailure> = emptySet(),
    )

    private fun Result<RestoreOutcome>.completion(): Result<Unit> = fold(
        onSuccess = { if (it.failures.isEmpty()) Result.success(Unit) else Result.failure(PartialRestoreException(it.failures)) },
        onFailure = { Result.failure(it) },
    )

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
        // 注意：AtomicFileWriter.write 失败时返回 false 而**不抛异常**，
        // 这里必须直接采纳布尔值；用 runCatching{}.isSuccess 会把失败误判成成功。
        return com.kingzcheung.xime.util.AtomicFileWriter.write(File(context.filesDir, fileName), text)
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
        userDataWriter: ((fileName: String, text: String) -> Boolean)? = null,
        clipboardRestorer: ((List<ClipboardBackupRecord>, File) -> Unit)? = null
    ): Result<Unit> = restoreArchiveOutcome(
        filesDir, prefsRestorer, archive.inputStream(), visibleConfigWriter, userDataWriter, clipboardRestorer
    ).completion()

    /** 字节入口：语义与文件入口一致（校验规则共用一份实现）。 */
    fun restoreArchive(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        bytes: ByteArray,
        visibleConfigWriter: ((String) -> Boolean)? = null,
        userDataWriter: ((fileName: String, text: String) -> Boolean)? = null,
        clipboardRestorer: ((List<ClipboardBackupRecord>, File) -> Unit)? = null
    ): Result<Unit> = restoreArchiveOutcome(
        filesDir, prefsRestorer, bytes.inputStream(), visibleConfigWriter, userDataWriter, clipboardRestorer
    ).completion()

    private fun restoreArchiveOutcome(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        archive: File,
        visibleConfigWriter: ((String) -> Boolean)? = null,
        userDataWriter: ((fileName: String, text: String) -> Boolean)? = null,
        clipboardRestorer: ((List<ClipboardBackupRecord>, File) -> Unit)? = null
    ): Result<RestoreOutcome> = restoreArchiveOutcome(
        filesDir, prefsRestorer, archive.inputStream(), visibleConfigWriter, userDataWriter, clipboardRestorer
    )

    /** 字节入口（internal 供单测断言条目计数）：语义与文件入口一致。 */
    internal fun restoreArchiveOutcome(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        bytes: ByteArray,
        visibleConfigWriter: ((String) -> Boolean)? = null,
        userDataWriter: ((fileName: String, text: String) -> Boolean)? = null,
        clipboardRestorer: ((List<ClipboardBackupRecord>, File) -> Unit)? = null,
        transactionIo: RestoreTransactionIo = RestoreTransactionIo(),
    ): Result<RestoreOutcome> = restoreArchiveOutcome(
        filesDir, prefsRestorer, bytes.inputStream(), visibleConfigWriter, userDataWriter, clipboardRestorer, transactionIo
    )

    private fun restoreArchiveOutcome(
        filesDir: File,
        prefsRestorer: (prefsName: String, json: ByteArray) -> Unit,
        rawInput: java.io.InputStream,
        visibleConfigWriter: ((String) -> Boolean)? = null,
        userDataWriter: ((fileName: String, text: String) -> Boolean)? = null,
        clipboardRestorer: ((List<ClipboardBackupRecord>, File) -> Unit)? = null,
        transactionIo: RestoreTransactionIo = RestoreTransactionIo(),
    ): Result<RestoreOutcome> {
        // 两阶段恢复：阶段一全部解到 staging（可随时整目录丢弃），阶段二逐文件换入 + 事务日志。
        // 换入按**文件**进行而不是整目录替换：备份包刻意排除 build/、*.userdb/、default.yaml、
        // 其它设备的 sync/<id>/，整目录换入会把这些没进包的东西一并删掉。
        val staging = File(filesDir, STAGING_DIR_NAME)
        val rollback = File(filesDir, ROLLBACK_DIR_NAME)
        val journal = File(filesDir, JOURNAL_FILE_NAME)
        val plan = mutableListOf<String>()
        val transaction = RestoreFileTransaction(filesDir, staging, rollback, journal, transactionIo)
        var ownsStaging = false
        try {
            if (!transaction.prepare()) return Result.failure(Exception("上次恢复事务未完成，已保留原文件与恢复日志"))
            ownsStaging = true
            if (!staging.mkdirs() && !staging.isDirectory) return Result.failure(Exception("恢复暂存目录不可用"))
            val stagingRime = File(staging, "rime")
            var entryCount = 0
            /** 真正落盘/入计划的条目数（只含文件条目）；目录条目算预算但不算内容。 */
            var fileEntryCount = 0
            var dictSnapshotCount = 0
            var totalBytes = 0L
            var formatVersion = 0
            val clipboardEntries = mutableSetOf<String>()
            val clipboardImages = mutableSetOf<String>()
            var clipboardImageBytes = 0L
            // prefs 与「可见目录」写入是不可回滚的副作用：先攒在内存，等文件全部换入后再提交，
            // 避免出现「配置写进去了、文件没换上」的割裂态。
            val pendingPrefs = mutableListOf<Pair<String, ByteArray>>()
            val pendingUserConfig = mutableListOf<Pair<String, String>>()
            val pendingUserData = mutableListOf<Pair<String, String>>()
            // META 条目是**先读进内存再提交**的：单条上限 32MB、总量闸 1GB 都管不到
            // 「N 条各 30MB」这种包，会把常驻的输入法进程堆直接打爆（表现为恢复时闪退）。
            // 这里给内存累积单独设预算，超了明确失败。
            var pendingMemBytes = 0L
            // 总量守卫：备份包是**外部输入**（用户从任意来源选的 zip / 远端下载的包），
            // 恶意或损坏的包可以是 zip 炸弹（单个高压缩比条目、或数万小条目）。
            // 无上限时 readBytes() 直接打爆常驻的输入法进程，或者把 /data 写满。
            ZipInputStream(rawInput).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    // 目录条目也要计入条目数预算：否则海量空目录条目可绕过条目上限
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) {
                        return Result.failure(
                            Exception("备份包条目过多（> $MAX_ENTRY_COUNT），已中止")
                        )
                    }
                    if (entry.isDirectory) {
                        // 目录条目正常是空正文；带正文的目录条目会绕过解压预算，直接拒绝
                        if (zis.read() >= 0) {
                            return Result.failure(Exception("备份包包含带正文的目录条目: $name"))
                        }
                        zis.closeEntry()
                        continue
                    }
                    fileEntryCount++
                    if (name.startsWith(META_PREFIX)) {
                        val rel = name.removePrefix(META_PREFIX)
                        if (rel.startsWith(ClipboardBackupCodec.PREFIX)) {
                            require(clipboardEntries.add(rel)) { "剪贴板备份包含重复条目" }
                            val limit = when {
                                rel == ClipboardBackupCodec.MANIFEST -> ClipboardBackupCodec.MAX_MANIFEST_BYTES.toLong()
                                ClipboardBackupCodec.isImageEntry(rel) -> ClipboardBackupCodec.MAX_IMAGE_BYTES
                                else -> throw IllegalArgumentException("非法剪贴板备份路径")
                            }
                            val data = zis.readBounded(limit)
                            totalBytes += data.size
                            require(totalBytes <= MAX_TOTAL_BYTES) { "备份包解压后超过大小上限" }
                            if (rel == ClipboardBackupCodec.MANIFEST) {
                                pendingMemBytes += data.size
                                require(pendingMemBytes <= MAX_PENDING_MEMORY_BYTES) { "备份包元数据超过内存预算" }
                            } else {
                                clipboardImages += rel
                                clipboardImageBytes += data.size
                                require(clipboardImages.size <= ClipboardBackupCodec.MAX_IMAGES &&
                                    clipboardImageBytes <= ClipboardBackupCodec.MAX_IMAGE_TOTAL_BYTES) { "剪贴板图片超过总量上限" }
                            }
                            stageBytes(staging, rel, data)
                            zis.closeEntry()
                            continue
                        }
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
                                val pluginPath = rel.removePrefix("plugins/").replace('\\', '/')
                                require(!isRootedEntryName(pluginPath) &&
                                    pluginPath.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
                                    "备份包包含非法插件路径"
                                }
                                val relPath = "plugins/$pluginPath"
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
                    // 条目名必须与 plan 用同一个规范化结果：plan 记的是 relName，
                    // 若这里用原始 name，含反斜杠或 "./" 前缀的条目会写成另一个文件名，
                    // 旧换入实现曾跳过缺失源文件，导致静默「恢复成功」但少文件。
                    val normalizedName = name.replace('\\', '/').removePrefix("./")
                    val target = File(stagingRime, normalizedName)
                    val canonicalRoot = stagingRime.canonicalPath + File.separator
                    if (target.canonicalPath != canonicalRoot.removeSuffix(File.separator) &&
                        !target.canonicalPath.startsWith(canonicalRoot)
                    ) {
                        return Result.failure(Exception("备份包包含非法路径: $name"))
                    }
                    // 用户配置改走「真源」通道（可见目录 xime.yaml），否则恢复出来的配置会被
                    // 可见目录里的旧配置静默覆盖；写入失败才退回 rime 目录，避免恢复彻底失效。
                    // 旧备份里的条目名是 xime.custom.yaml / xime.txt，同样路由过来，等于顺带完成迁移。
                    val relName = normalizedName
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
                        pendingMemBytes += data.size
                        if (pendingMemBytes > MAX_PENDING_MEMORY_BYTES) {
                            return Result.failure(
                                Exception("备份包元数据超过 ${MAX_PENDING_MEMORY_BYTES / 1024 / 1024}MB 内存预算，已中止")
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
                        pendingMemBytes += data.size
                        if (pendingMemBytes > MAX_PENDING_MEMORY_BYTES) {
                            return Result.failure(
                                Exception("备份包元数据超过 ${MAX_PENDING_MEMORY_BYTES / 1024 / 1024}MB 内存预算，已中止")
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
            if (fileEntryCount == 0) return Result.failure(Exception("备份包为空"))
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
            val clipboardRecords = if (clipboardEntries.isEmpty()) null else {
                require(ClipboardBackupCodec.MANIFEST in clipboardEntries) { "剪贴板资源缺少清单" }
                ClipboardBackupCodec.decode(File(staging, ClipboardBackupCodec.MANIFEST).readBytes()).also {
                    ClipboardBackupCodec.validateResources(it, staging, clipboardImages)
                    require(it.isEmpty() || clipboardRestorer != null) { "此恢复入口未提供剪贴板合并器" }
                }
            }
            if (!clipboardRecords.isNullOrEmpty()) {
                // 只在预检期间持锁，退出后才换入文件；restorer 会再次取同一锁并复检。
                runBlocking(Dispatchers.IO) {
                    ClipboardImageStore.mutationMutex.withLock {
                        ClipboardBackupCodec.validateExistingImages(filesDir, clipboardRecords)
                    }
                }
            }
            // —— 阶段二：换入（带事务日志，可回滚） ——
            if (!transaction.commit(plan)) {
                return Result.failure(
                    Exception("备份恢复文件事务未完成；已保留尚需处理的恢复材料，请重试或检查恢复日志")
                )
            }
            // 文件已提交：继续各项副作用，并向外报告部分完成，不回滚已恢复的文件。
            val failures = linkedSetOf<RestoreFailure>()
            pendingPrefs.forEach { (prefsName, data) ->
                try {
                    prefsRestorer(prefsName, data)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += RestoreFailure.PREFERENCES
                    FileLogger.e(TAG, "恢复设置未完成", e)
                }
            }
            fun restoreWithFallback(primary: () -> Boolean, fallback: () -> Unit): Boolean {
                try {
                    if (primary()) return true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) { }
                return try {
                    fallback()
                    true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) { false }
            }
            pendingUserData.forEach { (fileName, text) ->
                if (!restoreWithFallback({ userDataWriter?.invoke(fileName, text) ?: false }, {
                    // 写失败要抛出：restoreWithFallback 靠异常判定 fallback 是否成功
                    if (!com.kingzcheung.xime.util.AtomicFileWriter.write(File(filesDir, fileName), text)) {
                        throw java.io.IOException("写入失败: $fileName")
                    }
                })) failures += RestoreFailure.USER_DATA
            }
            pendingUserConfig.forEach { (relName, text) ->
                if (!restoreWithFallback({ visibleConfigWriter?.invoke(text) ?: false }, {
                    val fallback = File(filesDir, "rime/$relName")
                    check(fallback.parentFile!!.isDirectory || fallback.parentFile!!.mkdirs())
                    if (!com.kingzcheung.xime.util.AtomicFileWriter.write(fallback, text)) {
                        throw java.io.IOException("写入失败: $relName")
                    }
                })) failures += RestoreFailure.CONFIG
            }
            if (!clipboardRecords.isNullOrEmpty()) {
                try {
                    clipboardRestorer?.invoke(clipboardRecords, staging)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failures += RestoreFailure.CLIPBOARD
                }
            }
            return Result.success(RestoreOutcome(fileEntryCount, dictSnapshotCount,
                plan.filter { it.startsWith("rime/") }.mapTo(linkedSetOf()) { it.removePrefix("rime/") }, failures))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            runCatching { rawInput.close() }
            // 旧事务恢复失败时不接管其暂存目录；日志/原件仍待恢复时也不清理任何材料。
            if (ownsStaging && transaction.canDiscardStaging()) staging.deleteRecursively()
        }
    }

    /** 把字节写到 staging 下的相对路径（父目录自动创建）。 */
    private fun stageBytes(staging: File, relPath: String, data: ByteArray) {
        val target = File(staging, relPath)
        writeChecked(target, staging, data)
    }


    /**
     * 按事务日志恢复：未提交的 M/W 撤销换入，末尾 C 标记只清理已提交原件。
     *
     * 仅重放完整且安全的日志；半行或非法记录保留材料并拒绝继续。
     * @return 事务恢复或清理完成 true；材料仍待处理 false
     */
    private fun rollbackByJournal(filesDir: File, rollback: File, journal: File): Boolean =
        RestoreFileTransaction(filesDir, File(filesDir, STAGING_DIR_NAME), rollback, journal).rollback()

    /**
     * 启动自检：上一次恢复中断时，按提交标记撤销未提交换入或重试已提交清理。
     *
     * 必须由 Application 在 IO 线程调用（恢复中途被杀是常态：输入法常驻进程被 LMK 回收）。
     * 没有日志时仍检查孤儿回滚原件，不能把日志缺失视为可直接清理。
     */
    fun recoverInterruptedRestore(context: Context) {
        val transaction = RestoreFileTransaction(context.filesDir)
        if (transaction.prepare()) {
            FileLogger.w(TAG, "恢复文件事务检查完成")
        } else {
            FileLogger.e(TAG, "恢复文件事务尚未完成，已保留暂存、原文件和恢复日志")
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

    /**
     * 备份包**压缩体**大小上限（本地 SAF 导入与远端恢复共用同一个包）。
     *
     * 取值理由：与解压侧 [MAX_TOTAL_BYTES]（1GB）同量级——正常完整备份只有几十 MB，
     * 1GB 压缩包已远超任何真实备份，而继续放大只会让「先落盘再解包」白占缓存。
     */
    internal const val MAX_ARCHIVE_FILE_BYTES = 1_024L * 1024 * 1024

    /** 备份包条目数上限（正常完整备份约数百个文件，留足余量）。 */
    private const val MAX_ENTRY_COUNT = 20_000

    /** 单个 META 条目（settings.json / plugin_configs / manifests）的大小上限。 */
    private const val MAX_META_ENTRY_BYTES = 32L * 1024 * 1024

    /** 恢复时「先读进内存再提交」的 META 条目总量预算（见 pendingMemBytes）。 */
    private const val MAX_PENDING_MEMORY_BYTES = 16L * 1024 * 1024

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
        check(editor.commit()) { "恢复设置写入未完成" }
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
