package com.kingzcheung.xime.settings

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 备份包范围（**只有一个范围：完整备份**）。
 *
 * 这个应用里"备份"就一件事——把整机状态打成一个时间点包：
 * - rime 目录里的**设置与用户补丁**（`*.custom.yaml`、`xime.custom.yaml`、`user.yaml`、
 *   `installation.yaml`、`custom_phrase.txt`、`symbols.yaml`）
 * - **方案本体与资源**：`*.schema.yaml` / `*.dict.yaml` / `opencc/` / `lua/` / `fonts/` / `themes/`
 * - **自造词快照**：`sync/<installation_id>/` 下的 `*.userdb.txt`（恢复时按时间戳合并）
 * - 插件包与插件/方案清单、设置项与插件配置（见 [BackupManager.collectMetaEntries]）
 * - 普通剪贴板文本/图片与快捷发送逻辑快照（不含临时验证码，恢复按内容合并）
 *
 * 一律排除"派生/可由引擎重建/另有合并通道"的东西（见 [includeRimeEntry]）：
 * `build/`（librime 编译产物）、`*.bin/.gram/.db*`（二进制词典与模型）、
 * `*.userdb/`（leveldb 用户词典库）、相同内容的 `schemes/` 镜像（唯一归档内容会保留）、`default.yaml`（派生基座，恢复后重新镜像 schema_list）、
 * 以及其他设备的 `sync/<其它 id>/`。
 *
 * 自造词另有专门的**词条同步**通道（增量、多设备按时间戳合并）；本包里的快照是"顺带一份"，
 * 恢复时同样走合并，不会覆盖本机更新的词条。
 */
data class ExportResult(
    val uri: Uri?,
    val fileName: String,
    val savedToDownloads: Boolean
)

object RimeExportManager {

    private const val TAG = "RimeExportManager"

    /** 本地 Downloads 里保留的历史备份包份数（含刚导出的这份）。 */
    private const val LOCAL_BACKUP_KEEP = 5

    fun shareSingleFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = inferMimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(null, uri)
        }
        val chooser = Intent.createChooser(intent, "分享文件")
        context.startActivity(chooser)
    }

    suspend fun exportArchive(context: Context): Result<ExportResult> {
        val runId = BackupRunLog.begin(BackupRunTarget.LOCAL)
        val coroutineContext = currentCoroutineContext()
        val checkActive = { coroutineContext.ensureActive() }
        try {
            // 不再触发 librime sync 导出 *.userdb.txt 快照：
            // 备份只打包 rime 目录里的原始文件，恢复时逐文件写回，
            // 不需要「先导出文本快照、恢复时再合并」这一层。
            checkActive()
            val (fileName, tempZip) = buildArchiveToFile(context, runId, checkActive).getOrElse {
                if (it is CancellationException) throw it
                BackupRunLog.finish(runId, BackupRunOutcome.PACKING_FAILED)
                return Result.failure(it)
            }
            BackupRunLog.stage(runId, BackupRunStage.SAVING_LOCAL)
            // 直接采用实际写入的那条记录：不能按文件名回查（同日重名会拿到旧包）
            val savedUri = try {
                checkActive()
                saveToDownloads(context, tempZip, fileName, checkActive).also {
                    // 保留策略：同一天多次备份会因 MediaStore 自动改名（(1)、(2)…）无限堆积，
                    // 每份都是几十 MB 的完整词库包。放在导出完成后清理，不影响返回的 uri。
                    runCatching { pruneLocalDownloads(context, keep = LOCAL_BACKUP_KEEP) }
                }
            } finally {
                runCatching { tempZip.delete() }
            }
            if (savedUri == null) {
                BackupRunLog.finish(runId, BackupRunOutcome.SAVING_FAILED)
                return Result.failure(Exception("完整备份本地保存失败"))
            }
            val resultUri = savedUri
            checkActive()
            SettingsPreferences.setLastBackupAt(context, System.currentTimeMillis())
            BackupRunLog.finish(runId, BackupRunOutcome.SUCCESS)
            return Result.success(ExportResult(resultUri, fileName, true))
        } catch (e: CancellationException) {
            BackupRunLog.finish(runId, BackupRunOutcome.CANCELLED)
            throw e
        } catch (e: Exception) {
            BackupRunLog.finish(runId, BackupRunOutcome.FAILED)
            android.util.Log.e(TAG, "完整备份本地导出失败")
            return Result.failure(e)
        }
    }

    /**
     * 流式生成备份包到 cacheDir（**不驻留内存**），供本地导出与云备份（BackupManager）共用。
     *
     * 包内容（v1 格式，`_xime_backup/` 前缀外均为 rime 目录相对路径；范围见 [includeRimeEntry]）：
     * 设置/补丁 + 方案本体与资源 + 自造词快照 + 插件包 + 清单（元数据见 [BackupManager.collectMetaEntries]）
     *
     * 逐条目边读边压（ZipOutputStream → FileOutputStream），内存占用与包体无关；
     * 完整方案含方案词典与插件包时可达数十 MB，旧的整体 ByteArray 实现会因此 OOM。
     *
     * @return Pair(文件名, 落盘文件)；**调用方负责删除**该文件
     */
    fun buildArchiveToFile(
        context: Context,
        runId: Long = 0L,
        checkActive: () -> Unit = {},
    ): Result<Pair<String, File>> {
        BackupRunLog.stage(runId, BackupRunStage.PACKING)
        checkActive()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val fileName = "Xime完整备份-$dateStr.zip"
        val cacheDir = File(context.cacheDir, "backup-export").apply { mkdirs() }
        // 本地导出与云备份可并发，临时包不能共用当天显示名称。
        val archive = File.createTempFile("backup-", ".zip", cacheDir)
        return try {
            val rimeDir = File(context.filesDir, "rime")
            if (!rimeDir.exists()) {
                runCatching { archive.delete() }
                return Result.failure(Exception("Rime 目录不存在"))
            }
            val installationId = SettingsPreferences.getRimeInstallationId(context)

            FileOutputStream(archive).use { fileOut ->
                BackupArchiveOutputStream(BufferedOutputStream(fileOut), checkActive) { category ->
                    BackupRunLog.entryPacked(runId, category)
                }.use { zos ->
                    // 可见目录 /storage/emulated/0/rime/xime/ 里的统一配置才是生效的那份（读取优先级最高），
                    // 单独取出来写入，并让 rime/ 下的旧副本跳过，避免包里出现两份互相矛盾的配置。
                    val visibleCustom = UserConfigDir.readText(context, UserConfigDir.UNIFIED_CONFIG_FILE)
                    val legacyNames = setOf(
                        UserConfigDir.UNIFIED_CONFIG_FILE,
                        UserConfigDir.LEGACY_UNIFIED_CONFIG_FILE,
                        UserConfigDir.CUSTOM_CONFIG_FILE
                    )
                    rimeDir.walkTopDown().forEach { file ->
                        if (file.isDirectory) return@forEach
                        val relativePath = file.relativeTo(rimeDir).path.replace('\\', '/')
                        if (relativePath in legacyNames && visibleCustom != null) {
                            return@forEach
                        }
                        if (!includeRimeEntry(relativePath, installationId)) return@forEach
                        zos.putNextEntry(ZipEntry(relativePath))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    archivedSchemeEntries(rimeDir, installationId).forEach { (archivePath, file) ->
                        checkActive()
                        zos.putNextEntry(ZipEntry(archivePath))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    if (visibleCustom != null) {
                        zos.putNextEntry(ZipEntry(UserConfigDir.UNIFIED_CONFIG_FILE))
                        zos.write(visibleCustom.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                    // 备份范围按 xime.yaml `backup` 段配置：与 xime.yaml 同级的用户数据表
                    //（候选排序、自动造词词库、上屏词频）。默认全开，换机/恢复后不丢。
                    val backupConfig = KeysConfigHelper.loadBackupConfig(context)
                    if (backupConfig.candidateOrder) {
                        packUserDataFile(
                            context, com.kingzcheung.xime.service.CandidateOrderStore.FILE_NAME, zos
                        )
                    }
                    if (backupConfig.autoWord) {
                        packUserDataFile(
                            context, com.kingzcheung.xime.service.AutoWordStore.TEMP_FILE_NAME, zos
                        )
                        packUserDataFile(
                            context, com.kingzcheung.xime.service.AutoWordStore.FIXED_FILE_NAME, zos
                        )
                    }
                    if (backupConfig.wordFreq) {
                        packUserDataFile(
                            context, com.kingzcheung.xime.service.WordFreqStore.FILE_NAME, zos
                        )
                    }
                    if (backupConfig.blacklist) {
                        packUserDataFile(
                            context, com.kingzcheung.xime.service.WordBlacklistStore.FILE_NAME, zos
                        )
                    }
                    packClipboardSection(SettingsPreferences.isClipboardBackupEnabled(context), runId) {
                        com.kingzcheung.xime.clipboard.ClipboardBackup.writeArchive(context, zos, BackupManager.META_PREFIX)
                    }
                    BackupManager.collectMetaEntries(context).forEach { (entryName, bytes) ->
                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                    val pluginsDir = File(context.filesDir, "plugins")
                    pluginsDir.walkTopDown().forEach { file ->
                        if (file.isDirectory) return@forEach
                        val entryName = BackupManager.META_PREFIX + "plugins/" +
                            file.relativeTo(pluginsDir).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }

            if (archive.length() == 0L) {
                runCatching { archive.delete() }
                return Result.failure(Exception("没有可导出的文件"))
            }
            checkActive()
            BackupRunLog.archiveReady(runId, archive.length())
            Result.success(fileName to archive)
        } catch (e: CancellationException) {
            runCatching { archive.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { archive.delete() }
            android.util.Log.e(TAG, "完整备份打包失败")
            Result.failure(e)
        }
    }

    /** Shared production gate: disabled sections never invoke the Room/image writer. */
    internal fun packClipboardSection(
        enabled: Boolean,
        runId: Long,
        writer: () -> com.kingzcheung.xime.clipboard.ClipboardBackupCounts,
    ): com.kingzcheung.xime.clipboard.ClipboardBackupCounts? {
        val counts = ClipboardCloudPolicy.withBackupEnabled(enabled, writer)
        if (counts == null) BackupRunLog.skippedClipboard(runId)
        else BackupRunLog.clipboardSnapshot(runId, counts.texts, counts.images, counts.quickSend)
        return counts
    }

    /**
     * 保存任意 zip 文件到 Downloads（词库同步快照包由 SyncManager 打包后经此落盘），
     * 返回是否成功；**不删除**源文件（调用方自行清理）。
     */
    fun saveSyncArchive(context: Context, fileName: String, zipFile: File): Boolean {
        return try {
            saveToDownloads(context, zipFile, fileName) != null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "saveSyncArchive failed", e)
            false
        }
    }

    /**
     * 保存到 Downloads，返回**实际发布出去的那条记录**的 Uri（null = 失败）。
     *
     * 不能返回 Boolean 再按文件名回查：同一天多次备份会重名，回查拿到的是先插入的
     * 旧包（用户点"打开"看到的是上一次的备份）；发布成功但查询失败还会误报失败。
     */
    private fun saveToDownloads(
        context: Context, zipFile: File, fileName: String, checkActive: () -> Unit = {},
    ): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri == null) return null
                var published = false
                try {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("备份输出流不可用")
                    output.use { stream ->
                        zipFile.inputStream().use { copyArchive(it, stream, checkActive) }
                    }
                    checkActive()
                    val completed = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    if (context.contentResolver.update(uri, completed, null, null) <= 0) {
                        throw java.io.IOException("备份文件发布失败")
                    }
                    published = true
                    uri
                } finally {
                    if (!published) runCatching { context.contentResolver.delete(uri, null, null) }
                }
            } else {
                @Suppress("DEPRECATION")
                val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!destDir.exists()) destDir.mkdirs()
                val dest = File(destDir, fileName)
                BackupLocalFileCommit.save(zipFile, dest, checkActive)
                Uri.fromFile(dest)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "完整备份文件保存失败")
            null
        }
    }

    /**
     * 清理本地 Downloads 里的历史备份包，只保留最近 [keep] 份。
     *
     * 为什么需要：云备份有 [BackupManager.pruneRemoteBackups] 按 keepCount 清理，
     * 但**本地导出从来没有保留策略**——同一天连续备份几次，MediaStore 会自动改名
     * （`Xime完整备份-2026-11-22 (1).zip`…）而不是覆盖，于是无限堆积，
     * 每份都是几十 MB 的完整词库包，用户磁盘被悄悄吃掉。
     *
     * 只删本应用命名的包（前缀 `Xime完整备份-`），不碰 Downloads 里的其它文件。
     */
    private fun pruneLocalDownloads(context: Context, keep: Int) {
        if (keep <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.DATE_ADDED,
            )
            val candidates = mutableListOf<Pair<Long, Long>>() // (id, dateAdded)
            // 必须同时限定 OWNER_PACKAGE_NAME：只按文件名过滤会把用户从其它设备
            // 拷进 Downloads 的同名备份（不是本应用创建的）一起删掉——那是用户数据。
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND " +
                    "${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?",
                arrayOf("Xime完整备份-%", context.packageName),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                while (c.moveToNext()) candidates += c.getLong(idIdx) to c.getLong(dateIdx)
            }
            candidates.drop(keep).forEach { (id, _) ->
                runCatching {
                    context.contentResolver.delete(
                        android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                        null, null,
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val files = destDir.listFiles { f -> f.isFile && f.name.startsWith("Xime完整备份-") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            // API<29 无法按 owner 过滤（File API 没有这个概念），只能靠文件名前缀。
            // 这段只在旧系统生效，且只删前缀完全匹配的本应用产物；风险低于 MediaStore 路径。
            files.drop(keep).forEach { runCatching { it.delete() } }
        }
    }

    private fun copyArchive(input: java.io.InputStream, output: java.io.OutputStream, checkActive: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val size = input.read(buffer)
            if (size < 0) break
            output.write(buffer, 0, size)
        }
        checkActive()
    }

    /**
     * 把一张用户数据表写入备份包（包根 `<fileName>`，与 xime.yaml 同级语义）。
     *
     * 适用对象：候选排序词频表 `candidate_order.txt`、自动造词临时/固定词库
     * `auto_word_temp.txt` / `auto_word.txt`、上屏词频表 `word_freq.txt`——
     * 它们都走同一套「可见目录为主、私有目录为备」的双写策略。
     *
     * 读取顺序与各 Store 的 load 一致：优先可见目录（用户可直接查看/备份的那份），
     * 缺失时回退私有目录（老版本遗留/未授权时的回退副本）。两处都没有则跳过，不报错。
     */
    private fun packUserDataFile(context: Context, fileName: String, zos: ZipOutputStream) {
        val text = UserConfigDir.readText(context, fileName)
            ?: runCatching {
                File(context.filesDir, fileName).takeIf { it.isFile }?.readText()
            }.getOrNull()
        if (text == null || text.isBlank()) return
        zos.putNextEntry(ZipEntry(fileName))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    /**
     * 返回 schemes/ 中需要额外备份的已归档文件。
     * 已启用方案通常在顶层有硬链接/副本；内容相同就不重复写包。未启用方案被移出顶层，
     * 必须保留其 schemes/<folder>/ 路径，否则恢复后无法再启用该方案。
     */
    internal fun archivedSchemeEntries(
        rimeDir: File,
        installationId: String,
    ): Sequence<Pair<String, File>> {
        val schemesDir = File(rimeDir, "schemes")
        if (!schemesDir.isDirectory) return emptySequence()
        return schemesDir.listFiles().orEmpty().asSequence().filter { it.isDirectory }.flatMap { folder ->
            folder.walkTopDown().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .mapNotNull { file ->
                    val relative = file.relativeTo(folder).invariantSeparatorsPath
                    if (!includeRimeEntry(relative, installationId)) return@mapNotNull null
                    val rootCopy = File(rimeDir, relative)
                    if (rootCopy.isFile && sameFileContent(rootCopy, file)) return@mapNotNull null
                    "schemes/${folder.name}/$relative" to file
                }
        }
    }

    private fun sameFileContent(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        if (runCatching { java.nio.file.Files.isSameFile(first.toPath(), second.toPath()) }.getOrDefault(false)) {
            return true
        }
        fun hash(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest()
        }
        return runCatching { hash(first).contentEquals(hash(second)) }.getOrDefault(false)
    }

    /**
     * 单条 rime 目录条目是否进包（纯函数，单测锚定）。
     *
     * 排除项（均属"派生/可由引擎重建"或"另有合并通道"）：
     * - `build/` 目录：librime 编译产物（本机实测占 rime 目录一半），跨版本不兼容
     * - `*.bin` / `*.gram` / `*.db*`：二进制词典与模型
     * - `*.userdb/` 目录：leveldb 用户词典库——文件级覆盖一致性差，且会吃掉其他设备新词；
     *   自造词改走 `sync/` 文本快照 + 引擎按时间戳合并（见 [SyncManager]）
     * - `default.yaml`：由 `default.custom.yaml` + APK 内置基座派生，恢复后再镜像
     *   schema_list（[SchemaManager.applyEnabledSchemasToDefaultYaml]），直接回灌会带回旧基座
     * - `sync/` 下**非本机 installation_id** 的目录：那是其它设备的历史快照
     *
     * @param installationId 本机 rime installation id（`sync/<id>/` 才认）
     */
    internal fun includeRimeEntry(
        relativePath: String,
        installationId: String
    ): Boolean {
        val path = relativePath.replace('\\', '/')
        // 派生与二进制
        if (path.startsWith("build/")) return false
        if (path == "default.yaml") return false
        if (path.endsWith(".bin") || path.endsWith(".gram")) return false
        if (path.endsWith(".db") || path.endsWith(".db-wal") || path.endsWith(".db-shm")) return false
        // leveldb 用户词典**目录**（pinyin_simp.userdb/…）：按路径段精确判断，
        // 否则会把自造词快照 pinyin_simp.userdb.txt 一起误伤
        if (path.split('/').any { it.endsWith(".userdb") }) return false
        // sync 目录：只收**本机**的自造词快照 `*.userdb.txt`。
        //
        // 注意：librime 的 sync_user_data 会把用户目录下所有顶层 .yaml/.txt 镜像一份到
        // sync/<user_id>/（lever/deployment_tasks.cc 的 BackupConfigFiles），词典/配置
        // 镜像动辄 20MB+——那是本包两档各自已覆盖的内容（配置在轻量档、方案在完整档），
        // 绝不能跟着快照一起塞进来（曾导致"设置与自造词"档实测 8.4MB）。
        // 其它 installation_id 的目录是别设备的历史状态，也不收。
        if (path == "sync" || path.startsWith("sync/")) {
            if (installationId.isEmpty()) return false
            if (path != "sync/$installationId" && !path.startsWith("sync/$installationId/")) return false
            return path.endsWith(".userdb.txt")
        }
        // 顶层枚举不直接收 schemes/：通常它是已启用方案的镜像，和顶层逐字相同。
        // buildArchiveToFile 会另外补入 schemes/ 中没有等价顶层副本的归档文件，避免
        // 未启用方案因只保存在该目录而从备份丢失；相同内容只写一份以控制包体。
        if (path == "schemes" || path.startsWith("schemes/")) return false
        // 其余为设置/用户补丁与方案本体/资源（*.custom.yaml、xime.custom.yaml、user.yaml、
        // installation.yaml、custom_phrase.txt、symbols.yaml、方案 schema/dict、opencc/、lua/、fonts/、themes/）
        return true
    }

    private fun inferMimeType(file: File): String {
        return when {
            file.name.endsWith(".yaml") -> "text/vnd.yaml"
            file.name.endsWith(".txt") -> "text/plain"
            file.name.endsWith(".bin") -> "application/octet-stream"
            file.name.endsWith(".gram") -> "application/octet-stream"
            file.name.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
