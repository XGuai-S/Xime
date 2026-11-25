package com.kingzcheung.xime.settings

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 本地目标先完整写到同目录临时文件；不支持原子换入就失败，绝不先截断旧备份。 */
internal object BackupLocalFileCommit {
    fun save(
        source: File, destination: File, checkActive: () -> Unit = {},
        commit: (File, File) -> Unit = { temporary, target ->
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        },
    ) {
        val parent = destination.absoluteFile.parentFile ?: throw IOException("备份目录不可用")
        if (!parent.isDirectory) throw IOException("备份目录不可用")
        val temporary = File.createTempFile(".xime-backup-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                }
                output.fd.sync()
            }
            checkActive()
            commit(temporary, destination)
        } finally {
            runCatching { temporary.delete() }
        }
    }
}
