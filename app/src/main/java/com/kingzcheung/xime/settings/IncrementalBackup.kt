package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.BackupResult
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 文件级增量备份：远端按**单个文件**存储，每次只上传内容变化的那几个。
 *
 * ## 为什么不再用整包
 *
 * 旧实现每次备份都生成完整 zip 再整包上传：即使只改了三个自造词，也要重传几十 MB；
 * 而且包内自造词来自 librime 导出的 `*.userdb.txt` 文本快照，恢复时要再合并一次。
 *
 * 现在：
 * - **上传**：本地仍用 [RimeExportManager.buildArchiveToFile] 打包（复用已验证的枚举/排除规则，
 *   避免两套逻辑漂移），但随后把包**拆成条目逐个比对哈希**，只上传变化的文件；
 * - **恢复**：按远端清单逐个下载，本地重新拼成同样的 zip，再交给
 *   [BackupManager.restoreArchive] —— 恢复语义与整包完全一致，零格式风险。
 *
 * 换句话说：**远端是文件级、无整包；本地打包/恢复逻辑不变**。
 *
 * ## 远端布局
 *
 * ```
 * <插件配置的备份目录>/
 *   rime/user.yaml                        ← 原始 rime 文件（含 *.userdb.txt 若存在）
 *   _xime_backup/settings.json
 *   _xime_backup/clipboard/manifest.json
 *   _xime_backup/clipboard/images/<sha>.<ext>
 *   ...
 *   .xime-manifest.json                   ← {version, files:{path:sha256}}
 * ```
 *
 * 清单最后上传：中途失败时远端清单仍是旧的，下次备份会把缺的文件补上，不会出现
 * 「清单说有、文件没有」的残缺状态。
 */
object IncrementalBackup {

    private const val TAG = "IncrementalBackup"

    /** 远端清单文件名（放在备份根目录，与内容文件同级）。 */
    const val MANIFEST_NAME = ".xime-manifest.json"

    private const val MANIFEST_VERSION = 1

    /** diff 结果：需要上传的路径 → 本地哈希。 */
    data class UploadPlan(
        val toUpload: List<Pair<String, String>>,
        val unchanged: Int,
        /** 本地没有、远端有的路径（默认不删：可能是别的设备的文件）。 */
        val remoteOnly: List<String>,
    )

    data class BackupOutcome(
        val uploaded: Int,
        val skipped: Int,
        val bytes: Long,
    )

    // ------------------------------------------------------------------
    // 纯函数（单测锚定）
    // ------------------------------------------------------------------

    /**
     * 计算上传计划。
     *
     * 判据：本地哈希 != 远端哈希 就要传。远端哈希缺失（空串）时保守重传——
     * 宁可多传一次，也不能因为插件没返回哈希就漏掉更新。
     */
    fun planUploads(
        local: Map<String, String>,
        remote: Map<String, String>,
    ): UploadPlan {
        val toUpload = local.entries
            .filter { (path, hash) -> remote[path] != hash }
            .map { it.key to it.value }
            .sortedBy { it.first }
        val unchanged = local.size - toUpload.size
        val remoteOnly = remote.keys.filter { it !in local }.sorted()
        return UploadPlan(toUpload, unchanged, remoteOnly)
    }

    /**
     * 渲染清单：`<sha256>\t<path>` 每行一条，首行是版本头。
     *
     * 用纯文本而不是 JSON：`org.json` 在 JVM 单测里是桩实现（返回 null/0），
     * 用了就没法给清单的序列化/解析写单测。项目里 [RimeUserFileSync] 的基线清单
     * 出于同一理由也用的纯文本。
     */
    fun renderManifest(files: Map<String, String>, version: Int = MANIFEST_VERSION): String = buildString {
        append("# xime incremental backup manifest v").append(version).append('\n')
        files.toSortedMap().forEach { (path, hash) ->
            // 路径里不会有换行/制表符（都是文件系统路径），无需转义
            append(hash).append('\t').append(path).append('\n')
        }
    }

    /** 解析清单；版本不认识或格式不符时返回 null（调用方回退整包，不误判成「远端全空」）。 */
    fun parseManifest(text: String): Map<String, String>? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val header = lines.firstOrNull() ?: return null
        if (!header.startsWith("# xime incremental backup manifest v")) return null
        val version = header.substringAfterLast('v').trim().toIntOrNull() ?: return null
        if (version != MANIFEST_VERSION) return null
        val out = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val hash = line.substring(0, tab).trim()
            val path = line.substring(tab + 1).trim()
            if (path.isNotEmpty() && hash.isNotEmpty()) out[path] = hash
        }
        return out
    }

    // ------------------------------------------------------------------
    // 备份
    // ------------------------------------------------------------------

    /**
     * 执行增量备份。
     *
     * @return 成功时带上传统计；插件不支持文件级或远端查询失败时返回 null，
     *   调用方（[BackupManager]）据此回退整包路径。
     */
    suspend fun backup(
        context: Context,
        plugin: BackupPlugin,
        runId: Long,
        checkActive: () -> Unit,
    ): Result<BackupOutcome?> = withContext(Dispatchers.IO) {
        if (!plugin.supportsFileLevel()) return@withContext Result.success(null)

        var archive: File? = null
        var staging: File? = null
        try {
            // 复用既有打包逻辑：枚举规则、排除项、剪贴板/插件/设置各段都与整包一致
            val packed = RimeExportManager.buildArchiveToFile(context, runId, checkActive).getOrElse {
                if (it is CancellationException) throw it
                return@withContext Result.failure(it)
            }
            val (_, zipFile) = packed
            archive = zipFile

            // 远端现状：查询失败就回退整包（不把「查询失败」当成「远端为空」而重传所有文件）
            val remoteEntries = plugin.listRemoteFiles()
                ?: return@withContext Result.success(null)
            checkActive()

            val remoteHashes = remoteEntries.associate { it.path to it.sha256 }
            // 远端清单也可能单独存了一份（插件不返回 sha256 时用它兜底）
            val manifestHashes = fetchRemoteManifest(context, plugin)
                .let { if (it.isNullOrEmpty()) remoteHashes else it }

            staging = File(context.cacheDir, "incremental-backup/staging").apply {
                deleteRecursively()
                mkdirs()
            }

            val localHashes = LinkedHashMap<String, String>()
            val sourceMap = LinkedHashMap<String, File>()

            // 拆包：**流式**逐个条目算哈希，只有需要上传的才落成临时文件。
            // 不把条目读进内存：插件包单个可达数十 MB，整包条目累加会直接把堆撑爆。
            ZipFile(archive).use { zip ->
                for (entry in zip.entries().asSequence().filter { !it.isDirectory }) {
                    checkActive()
                    val hash = zip.getInputStream(entry).use { sha256Of(it) }
                    localHashes[entry.name] = hash
                    if (manifestHashes[entry.name] == hash) continue   // 未变，不落盘
                    val out = File(staging, safeStagingPath(entry.name))
                    out.parentFile?.mkdirs()
                    // 第二遍读同一 entry（ZipFile 支持随机访问，重新取流即可）
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().buffered().use { input.copyTo(it) }
                    }
                    sourceMap[entry.name] = out
                }
            }
            checkActive()

            var uploaded = 0
            var bytesUploaded = 0L
            for ((path, hash) in sourceMap.keys.sorted().map { it to localHashes.getValue(it) }) {
                checkActive()
                val file = sourceMap.getValue(path)
                val r = plugin.pushFile(path, file, hash)
                if (!r.ok) {
                    FileLogger.w(TAG, "增量上传失败: $path ${r.message.orEmpty()}")
                    return@withContext Result.failure(Exception("上传失败: $path"))
                }
                uploaded++
                bytesUploaded += file.length()
                file.delete()   // 传完即删，staging 峰值 = 单个条目
            }

            // 清单最后传：中途失败时远端清单仍是旧的，下次会补齐缺失文件
            pushManifest(context, plugin, localHashes, staging)

            Result.success(
                BackupOutcome(
                    uploaded = uploaded,
                    skipped = localHashes.size - uploaded,
                    bytes = bytesUploaded,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "增量备份失败", e)
            Result.failure(e)
        } finally {
            runCatching { archive?.delete() }
            runCatching { staging?.deleteRecursively() }
        }
    }

    private suspend fun pushManifest(
        context: Context,
        plugin: BackupPlugin,
        hashes: Map<String, String>,
        staging: File,
    ) {
        val text = renderManifest(files = hashes)
        val file = File(staging, "manifest.tmp").apply { writeText(text) }
        val hash = sha256Hex(text.toByteArray(Charsets.UTF_8))
        val r = plugin.pushFile(MANIFEST_NAME, file, hash)
        if (!r.ok) FileLogger.w(TAG, "清单上传失败（下次会重传）: ${r.message.orEmpty()}")
        file.delete()
    }

    /** 读远端清单（插件把清单也当普通文件存）。取不到返回 null。 */
    private suspend fun fetchRemoteManifest(context: Context, plugin: BackupPlugin): Map<String, String>? {
        val download = plugin.pullFile(MANIFEST_NAME) ?: return null
        return try {
            val text = download.file.readText()
            parseManifest(text)
        } catch (e: Exception) {
            null
        } finally {
            plugin.releaseBackup(download.blobId)
        }
    }

    // ------------------------------------------------------------------
    // 恢复
    // ------------------------------------------------------------------

    /**
     * 从文件级远端恢复。
     *
     * 做法：按远端清单逐个下载 → 在本地重新拼成与整包**同结构**的 zip →
     * 交给 [BackupManager.restoreArchive]。这样恢复语义（路径校验、事务、快照合并）
     * 与整包完全一致，不需要另写一套。
     *
     * @return null 表示不支持/远端没有清单（调用方回退到整包恢复列表）
     */
    suspend fun restore(
        context: Context,
        plugin: BackupPlugin,
        checkActive: () -> Unit,
    ): Result<Unit>? = withContext(Dispatchers.IO) {
        if (!plugin.supportsFileLevel()) return@withContext null
        val manifest = fetchRemoteManifest(context, plugin) ?: return@withContext null
        if (manifest.isEmpty()) return@withContext Result.failure(Exception("远端没有可恢复的文件"))

        val work = File(context.cacheDir, "incremental-restore").apply {
            deleteRecursively(); mkdirs()
        }
        val zipFile = File(work, "restored.zip")
        try {
            var downloaded = 0
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                for (path in manifest.keys.sorted()) {
                    checkActive()
                    val dl = plugin.pullFile(path) ?: return@withContext Result.failure(
                        Exception("下载失败: $path")
                    )
                    try {
                        // 按清单哈希强校验：远端文件损坏 / 被篡改时拒绝恢复，
                        // 否则会把坏数据写进用户的词库与配置（整包恢复本来就有校验，
                        // 文件级恢复不能反而更弱）。清单本身无哈希时保守放行。
                        val expected = manifest[path]
                        if (!expected.isNullOrBlank()) {
                            val actual = sha256Of(dl.file.inputStream())
                            if (!actual.equals(expected.trim(), ignoreCase = true)) {
                                return@withContext Result.failure(
                                    Exception("文件校验失败（sha256 不匹配）: $path")
                                )
                            }
                        }
                        zos.putNextEntry(ZipEntry(path))
                        dl.file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        downloaded++
                    } finally {
                        plugin.releaseBackup(dl.blobId)
                    }
                }
            }
            FileLogger.i(TAG, "文件级恢复：已下载 $downloaded 个文件，重建整包 ${zipFile.length()} 字节")
            checkActive()
            BackupManager.restoreArchive(context, zipFile).map { }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "文件级恢复失败", e)
            Result.failure(e)
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 把 zip 条目名安全地映射到 staging 下的相对路径（防 ../ 穿越）。 */
    internal fun safeStagingPath(entryName: String): String {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        return if (parts.isEmpty()) "_" else parts.joinToString("/")
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) out.append("%02x".format(b))
        return out.toString()
    }

    /** 流式计算 SHA-256，内存占用固定 64KB（与文件大小无关）。 */
    internal fun sha256Of(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        val out = StringBuilder(64)
        for (b in digest.digest()) out.append("%02x".format(b))
        return out.toString()
    }
}
