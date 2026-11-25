package com.kingzcheung.xime.settings

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Copies only restored paths; unrelated visible files and baselines remain untouched. */
internal object RestoreMirror {
    data class Outcome(val copied: Int, val baseline: Map<String, String>, val failedPaths: Set<String> = emptySet())

    fun copy(
        sourceRoot: File,
        visibleRoot: File,
        restoredPaths: Set<String>,
        baseline: Map<String, String>,
        publish: (File, File) -> Unit = ::publishFile,
    ): Outcome {
        val next = baseline.toMutableMap()
        var copied = 0
        val failed = linkedSetOf<String>()
        for (path in restoredPaths) {
            if (!RimeUserFileSync.isSyncable(path) || path.split('/').any {
                    it.startsWith(".") || it in RimeUserFileSync.EXCLUDED_VISIBLE_DIRS
                }) continue
            runCatching {
                val source = child(sourceRoot, path)
                val target = child(visibleRoot, path)
                require(source.isFile) { "恢复源文件不可读" }
                require(!target.exists() || target.isFile) { "镜像目标不是普通文件" }
                publish(source, target)
                val sourceHash = hash(source)
                require(hash(target) == sourceHash) { "镜像复制内容不一致" }
                next[path] = sourceHash
                copied++
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                failed += path
            }
        }
        return Outcome(copied, next, failed)
    }

    private fun child(root: File, path: String): File {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\'))
        val parts = path.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl) })
        require(!Files.isSymbolicLink(root.toPath())) { "恢复镜像根目录不允许符号链接" }
        val anchor = root.canonicalFile
        var file = anchor
        for (part in parts) {
            file = File(file, part)
            require(!Files.isSymbolicLink(file.toPath()))
        }
        require(file.canonicalFile.toPath().startsWith(anchor.toPath()))
        return file
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun publishFile(source: File, target: File) {
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val temporary = File.createTempFile("restore-mirror-", ".tmp", parent)
        try {
            source.inputStream().use { input ->
                java.io.FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
