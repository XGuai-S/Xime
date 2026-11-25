package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val CONNECT_TIMEOUT = 30L
    private const val READ_TIMEOUT = 300L
    private const val MAX_RETRIES = 3

    private class UnsafeModelArchiveException : IOException("模型归档包含不安全的路径或文件类型")

    private class Sha256MismatchException : IOException("模型归档 sha256 校验失败")

    /** 流式计算文件 sha256（小写 hex），大归档也不会整份读入堆。 */
    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(FileInputStream(file), digest).use { input ->
            val buf = ByteArray(64 * 1024)
            while (input.read(buf) != -1) { /* digest 在 read 时已更新 */ }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * 校验归档 sha256。
     * - [expected] 为空（索引未提供）时返回 true——渐进增强，不破坏旧索引里没哈希的模型；
     * - 否则忽略大小写与首尾空白比对，不匹配即返回 false。
     */
    internal fun verifySha256(file: File, expected: String?): Boolean {
        val want = expected?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return sha256Hex(file).equals(want, ignoreCase = true)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun downloadModel(
        context: Context,
        modelInfo: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit,
        version: ModelVersion? = null
    ) = withContext(Dispatchers.IO) {
        FileLogger.i(TAG, "Starting download: ${modelInfo.id}")

        val target = version ?: modelInfo.resolvedVersion()
        if (target == null) {
            onProgress(ModelDownloadState.Error("模型没有可用版本"))
            return@withContext
        }

        val storageDir = try {
            ModelPaths.validateFiles(target.files)
            if (target.archiveUrl == null) require(target.files.isNotEmpty()) { "模型文件清单为空" }
            getStorageDir(context, modelInfo).also { directory ->
                for (file in target.files) {
                    val destination = ModelPaths.child(directory, file.name)
                    require(!destination.exists() || destination.isFile) { "模型目标不是普通文件" }
                }
            }
        } catch (_: Exception) {
            onProgress(ModelDownloadState.Error("模型清单或存储路径不安全"))
            return@withContext
        }
        check(storageDir.isDirectory || storageDir.mkdirs()) { "无法创建模型目录" }
        val work = java.nio.file.Files.createTempDirectory(storageDir.toPath(), ".model-download-").toFile()
        val staged = File(work, "staged").apply { mkdirs() }
        try {
            if (target.archiveUrl != null) {
                downloadAndExtractArchive(context, target.archiveUrl, target.sha256, staged, onProgress)
            } else {
                downloadFiles(target.files, staged, onProgress)
            }
            currentCoroutineContext().ensureActive()
            require(getStorageDir(context, modelInfo) == storageDir) { "模型存储目录已变化" }
            commitStagedFiles(staged, storageDir, File(work, "previous"))
            FileLogger.i(TAG, "Download complete: ${modelInfo.id}")
            onProgress(ModelDownloadState.Complete)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            FileLogger.e(TAG, "Download failed: ${modelInfo.id}: ${e.message}", e)
            onProgress(ModelDownloadState.Error("下载失败: ${e.message}"))
        } finally {
            // Only this invocation's verified temporary tree is eligible for cleanup.
            if (!File(work, "previous").exists()) removeWorkTree(work, storageDir)
        }
    }

    private fun removeWorkTree(work: File, parent: File) {
        require(work.canonicalFile.parentFile == parent.canonicalFile && work.name.startsWith(".model-download-"))
        work.deleteRecursively()
    }

    /** Preserve unrelated files and roll back only paths replaced by this download. */
    private fun commitStagedFiles(staged: File, destination: File, previous: File) {
        val installed = mutableListOf<File>()
        val backedUp = mutableListOf<Pair<File, File>>()
        try {
            // Preflight the entire plan before replacing any installed file.
            val plan = staged.walkTopDown().mapNotNull { source ->
                require(!java.nio.file.Files.isSymbolicLink(source.toPath())) { "模型暂存路径不允许符号链接" }
                if (!source.isFile) return@mapNotNull null
                // 统一用 '/' 作分隔符：Windows 上 relativeTo().path 返回反斜杠，
                // 而 ModelPaths.isSafeRelativePath 明确拒绝 '\'（防穿越），
                // 不归一化会导致 Windows 上所有模型安装都报「模型文件路径不安全」。
                val relative = source.relativeTo(staged).invariantSeparatorsPath
                val target = ModelPaths.child(destination, relative)
                require(!target.exists() || target.isFile) { "模型目标不是普通文件" }
                ModelPaths.child(previous, relative)
                source to target
            }.toList()
            for ((source, target) in plan) {
                val relative = source.relativeTo(staged).invariantSeparatorsPath
                require(ModelPaths.child(destination, relative) == target) { "模型目标已变化" }
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建模型目录" }
                if (target.exists()) {
                    val backup = File(previous, relative)
                    check(backup.parentFile!!.isDirectory || backup.parentFile!!.mkdirs())
                    java.nio.file.Files.move(target.toPath(), backup.toPath())
                    backedUp += target to backup
                }
                replaceCompletedFile(source, target)
                installed += target
            }
        } catch (failure: Exception) {
            var restored = true
            for (target in installed.asReversed()) {
                if (target.exists() && !target.delete()) restored = false
            }
            for ((target, backup) in backedUp.asReversed()) {
                try { replaceCompletedFile(backup, target) } catch (rollback: Exception) {
                    restored = false
                    failure.addSuppressed(rollback)
                }
            }
            if (restored) previous.deleteRecursively()
            throw failure
        }
        previous.deleteRecursively()
    }

    private fun getStorageDir(context: Context, modelInfo: ModelInfo): File {
        // 统一规则：所有模型一律存 filesDir/models/<id>/
        return ModelStorage.getModelDir(context, modelInfo.id)
    }

    private suspend fun downloadFiles(
        files: List<ModelFile>,
        targetDir: File,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val totalFiles = files.size

        files.forEachIndexed { index, file ->
            FileLogger.i(TAG, "Downloading ${file.name} (${index + 1}/$totalFiles)")
            downloadSingleFile(file.downloadUrl, ModelPaths.child(targetDir, file.name)) { fileProgress ->
                val overall = (index.toFloat() + fileProgress) / totalFiles
                onProgress(ModelDownloadState.Downloading(overall, 0, -1))
            }
        }
    }

    private suspend fun downloadSingleFile(
        url: String,
        targetFile: File,
        onProgress: (Float) -> Unit = {}
    ) {
        val request = Request.Builder().url(url).build()
        downloadCallToFile(request, targetFile) { downloadedBytes, totalBytes ->
            if (totalBytes > 0) onProgress(downloadedBytes.toFloat() / totalBytes.toFloat())
        }
        FileLogger.i(TAG, "Downloaded ${targetFile.name}: ${targetFile.length()} bytes")
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun downloadCallToFile(
        request: Request,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val call = client.newCall(request)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            // Call.cancel unblocks execute/read; avoid losing a returned response at a
            // runInterruptible cancellation boundary before response.use can own it.
            val response = call.execute()
            downloadResponseToFile(response, targetFile, onProgress)
        } finally {
            cancellation?.dispose()
        }
    }

    /** Consumes and closes a response; the existing destination is untouched until completion. */
    internal suspend fun downloadResponseToFile(
        response: okhttp3.Response,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        var partial: File? = null
        try {
            response.use { received ->
                if (!received.isSuccessful) throw IOException("HTTP ${received.code}")
                val coroutineContext = currentCoroutineContext()
                coroutineContext.ensureActive()
                val parent = targetFile.parentFile ?: throw IOException("模型文件没有父目录")
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建模型目录")
                val prefix = targetFile.name.take(48).padEnd(3, '_') + "."
                val temp = File.createTempFile(prefix, ".download", parent)
                partial = temp
                val body = received.body
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                body.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val bytesRead = runInterruptible { input.read(buffer) }
                            if (bytesRead < 0) break
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            onProgress(downloadedBytes, totalBytes)
                        }
                    }
                }
                if (totalBytes > 0 && downloadedBytes != totalBytes) {
                    throw IOException("Download incomplete: $downloadedBytes/$totalBytes bytes")
                }
                if (downloadedBytes == 0L) throw IOException("Downloaded file is empty")
                coroutineContext.ensureActive()
            }
            replaceCompletedFile(checkNotNull(partial), targetFile)
        } finally {
            partial?.let { temp ->
                if (temp.exists() && !temp.delete()) {
                    FileLogger.w(TAG, "Failed to remove partial model file: ${temp.name}")
                }
            }
        }
    }

    private fun replaceCompletedFile(partial: File, targetFile: File) {
        try {
            java.nio.file.Files.move(
                partial.toPath(),
                targetFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                partial.toPath(),
                targetFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private suspend fun downloadArchive(
        url: String,
        tmpFile: File,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        downloadCallToFile(request, tmpFile) { downloadedBytes, totalBytes ->
            if (totalBytes > 0) {
                val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                onProgress(ModelDownloadState.Downloading(progress, downloadedBytes, totalBytes))
            }
        }
        FileLogger.i(TAG, "Archive downloaded: ${tmpFile.length()} bytes")
    }

    private suspend fun downloadAndExtractArchive(
        context: Context,
        archiveUrl: String,
        expectedSha256: String,
        targetDir: File,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val tmpFile = File.createTempFile("mdl-", ".tar.bz2", context.cacheDir)
        try {
            for (attempt in 1..MAX_RETRIES) {
                try {
                    if (attempt > 1) {
                        tmpFile.delete()
                        onProgress(ModelDownloadState.Downloading(0f, 0, -1))
                    }

                    downloadArchive(archiveUrl, tmpFile, onProgress)
                    // 归档 sha256 强校验：索引提供了哈希时必须匹配，防止下载损坏或被篡改的
                    // 模型包进入解压流程。不匹配会走下方重试，重试耗尽后向用户报错。
                    if (!verifySha256(tmpFile, expectedSha256)) {
                        FileLogger.e(TAG, "Archive sha256 mismatch: $archiveUrl")
                        throw Sha256MismatchException()
                    }
                    val attemptDir = File(targetDir.parentFile, "attempt-$attempt")
                    check(attemptDir.mkdir()) { "无法创建模型解压暂存目录" }
                    extractTarBz2(tmpFile, attemptDir)
                    check(targetDir.delete()) { "无法切换模型暂存目录" }
                    check(attemptDir.renameTo(targetDir)) { "无法切换模型解压结果" }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsafeModelArchiveException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    FileLogger.e(TAG, "Attempt $attempt/$MAX_RETRIES failed: ${e.message}")
                    tmpFile.delete()
                    if (attempt < MAX_RETRIES) {
                        val delayMs = 1000L * (1L shl (attempt - 1))
                        delay(delayMs)
                    } else {
                        throw e
                    }
                }
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    private suspend fun extractTarBz2(archiveFile: File, targetDir: File) {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis, 65536).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextEntry
                        while (entry != null) {
                            coroutineContext.ensureActive()
                            val rawName = entry.name
                            val segments = rawName.split('/')
                            val regularFile = entry.linkFlag == TarConstants.LF_NORMAL ||
                                entry.linkFlag == TarConstants.LF_OLDNORM
                            val directory = entry.linkFlag == TarConstants.LF_DIR
                            if (rawName.startsWith('/') || rawName.contains('\\') || rawName.contains(':') ||
                                rawName.contains('\u0000') || segments.any { it == ".." || it == "." } ||
                                (!regularFile && !directory) || entry.isSparse
                            ) {
                                throw UnsafeModelArchiveException()
                            }
                            val parts = rawName.split("/", limit = 2)
                            val entryName = if (parts.size > 1) parts[1] else rawName

                            if (entryName.isNotEmpty() && !entry.isDirectory) {
                                val outputFile = File(targetDir, entryName).canonicalFile
                                val root = targetDir.canonicalFile
                                if (!outputFile.toPath().startsWith(root.toPath()) || outputFile == root) {
                                    throw UnsafeModelArchiveException()
                                }
                                val parent = outputFile.parentFile ?: throw UnsafeModelArchiveException()
                                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建模型解压目录")
                                FileOutputStream(outputFile).use { out ->
                                    val buffer = ByteArray(8192)
                                    var len: Int
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        len = tarIn.read(buffer)
                                        if (len < 0) break
                                        out.write(buffer, 0, len)
                                    }
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }
    }

    suspend fun getDownloadSize(modelInfo: ModelInfo, version: ModelVersion? = null): Long {
        val target = version ?: modelInfo.resolvedVersion()
        val url = target?.archiveUrl ?: target?.files?.firstOrNull()?.downloadUrl ?: return -1
        return try {
            val request = Request.Builder().head().url(url).build()
            runInterruptible {
                client.newCall(request).execute().use { response ->
                    response.body.contentLength()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            -1
        }
    }
}
