package com.kingzcheung.xime.settings

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 归档解压的暂存区：先把全部条目解到目标目录**同级**的暂存目录（保证同一文件系统），
 * 全部成功后再 [commit] 逐文件替换进目标目录。
 *
 * - 解压中途失败（超额、IO 错误、进程被杀）时目标目录一个字节都没动。
 * - [commit] 中途失败时，已替换的文件按原样回滚（被覆盖的旧文件先挪到备份区）。
 * - 进程在 [commit] 的极小窗口内被杀无法回滚；遗留的暂存/备份目录由 [create] 顺手清理（超过 24 小时）。
 */
internal class ArchiveStaging private constructor(
    private val targetDir: File,
    val stagingDir: File,
    private val backupDir: File,
) {
    private class Applied(val dest: File, val rel: String, val hadBackup: Boolean)

    /** 把暂存区里的全部文件替换进目标目录，返回文件数。失败则回滚并抛 [IOException]。 */
    fun commit(): Int {
        val files = stagingDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        val applied = mutableListOf<Applied>()
        try {
            for (src in files) {
                val rel = src.relativeTo(stagingDir).path
                val dest = File(targetDir, rel)
                dest.parentFile?.mkdirs()
                if (dest.exists()) {
                    val bak = File(backupDir, rel)
                    bak.parentFile?.mkdirs()
                    Files.move(dest.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    applied.add(Applied(dest, rel, hadBackup = true))
                } else {
                    applied.add(Applied(dest, rel, hadBackup = false))
                }
                Files.move(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            val failures = rollback(applied)
            val suffix = if (failures.isEmpty()) "" else "；回滚未完全成功：${failures.joinToString()}"
            throw IOException("导入替换失败，已回滚$suffix", e)
        }
        return files.size
    }

    private fun rollback(applied: List<Applied>): List<String> {
        val failures = mutableListOf<String>()
        for (item in applied.asReversed()) {
            try {
                if (item.dest.exists()) item.dest.delete()
                if (item.hadBackup) {
                    Files.move(
                        File(backupDir, item.rel).toPath(), item.dest.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (e: Exception) {
                failures.add(item.rel)
            }
        }
        return failures
    }

    /** 删除暂存区与备份区（成功、失败都要调用）。 */
    fun cleanup() {
        stagingDir.deleteRecursively()
        backupDir.deleteRecursively()
    }

    companion object {
        private const val PREFIX = ".xime_import_"
        private const val STALE_MS = 24L * 60 * 60 * 1000

        fun create(targetDir: File): ArchiveStaging {
            val target = targetDir.absoluteFile
            val parent = target.parentFile ?: throw IOException("目标目录没有父目录：$target")
            sweepStale(parent)
            val stamp = "${System.currentTimeMillis()}_${System.nanoTime()}"
            val staging = File(parent, "$PREFIX$stamp")
            val backup = File(parent, "$PREFIX$stamp.bak")
            if (!staging.mkdirs()) throw IOException("无法创建暂存目录：$staging")
            return ArchiveStaging(target, staging, backup)
        }

        private fun sweepStale(parent: File) {
            val now = System.currentTimeMillis()
            parent.listFiles()?.forEach { f ->
                if (f.name.startsWith(PREFIX) && now - f.lastModified() > STALE_MS) {
                    f.deleteRecursively()
                }
            }
        }
    }
}
