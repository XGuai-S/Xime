package com.kingzcheung.xime.plugin.core.runtime.installer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption

/**
 * 用 [from] 原子替换 [to]，跨平台安全。
 *
 * **不能用裸 `File.renameTo`**：POSIX 上 rename 允许覆盖已存在的目标，Windows 上不允许——
 * 注册表文件已存在时 `renameTo` 恒返回 false，`flushToDisk` 于是恒失败，
 * 插件卸载/安装一律报「注册表落盘失败」（在 Windows 上跑单测即可复现）。
 * `Files.move(REPLACE_EXISTING)` 在两个平台都按「替换」语义工作。
 */
internal fun atomicReplace(from: File, to: File): Boolean {
    return try {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    } catch (_: Exception) {
        // 极少数不支持 Files.move 的场景（如目标被占）：退回 renameTo，
        // 至少在同为「目标不存在」的常见路径上仍能成功。
        from.renameTo(to)
    }
}

/** 以同文件系统 rename 暂存目录；工作目录由调用方在 plugins 根目录之外新建。 */
internal class PluginDirectoryCommit(
    private val move: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
) {
    fun <T> replace(staged: File, target: File, backup: File, publish: () -> T): T {
        validatePaths(staged, target, backup)
        val hadTarget = exists(target)
        var backupMoved = false

        if (hadTarget) {
            val error = moveError(target, backup)
            backupMoved = !exists(target) && exists(backup)
            if (error != null) {
                val rollbackError = if (backupMoved) moveError(backup, target) else null
                if (rollbackError != null) throw recoveryFailure(error, rollbackError, staged, target, backup)
                throw IOException("无法备份旧插件目录: ${target.absolutePath}", error)
            }
            backupMoved = true
        }

        val switchError = moveError(staged, target)
        val newAtTarget = !exists(staged) && exists(target)
        if (switchError != null) {
            val rollbackError = rollback(newAtTarget, backupMoved, staged, target, backup)
            if (rollbackError != null) throw recoveryFailure(switchError, rollbackError, staged, target, backup)
            throw IOException("无法切换到新插件目录: ${target.absolutePath}", switchError)
        }

        if (!newAtTarget) {
            val error = IOException("目录切换未产生目标目录: ${target.absolutePath}")
            val rollbackError = rollback(false, backupMoved, staged, target, backup)
            if (rollbackError != null) throw recoveryFailure(error, rollbackError, staged, target, backup)
            throw error
        }

        val result = try {
            publish()
        } catch (failure: Exception) {
            val rollbackError = rollback(true, backupMoved, staged, target, backup)
            if (rollbackError != null) throw recoveryFailure(failure, rollbackError, staged, target, backup)
            throw failure
        }

        if (backupMoved) deleteInstallTree(backup)
        return result
    }

    private fun validatePaths(staged: File, target: File, backup: File) {
        val rawStagedPath = staged.toPath()
        require(Files.isDirectory(rawStagedPath, LinkOption.NOFOLLOW_LINKS)) {
            "staged 必须是实际目录且不能是符号链接"
        }
        val stagedPath = staged.canonicalFile.toPath().normalize()
        val backupPath = backup.canonicalFile.toPath().normalize()
        val workDir = stagedPath.parent ?: throw IllegalArgumentException("暂存目录必须有工作目录")
        require(!exists(backup)) { "backup 必须不存在: ${backup.absolutePath}" }
        require(backupPath.parent == workDir) { "staged 与 backup 必须在同一工作目录" }

        val targetPath = target.canonicalFile.toPath().normalize()
        require(targetPath != workDir && !targetPath.startsWith(workDir) && !workDir.startsWith(targetPath)) {
            "工作目录与 target 不得相同或互相包含"
        }
    }

    /** 处理 renameTo 返回 false 或抛异常的情况，并以路径状态确认是否实际移动。 */
    private fun moveError(from: File, to: File): Throwable? {
        val reported = try {
            if (move(from, to)) null else IOException("rename 返回 false")
        } catch (failure: Exception) {
            failure
        }
        return if (!exists(from) && exists(to)) null else reported ?: IOException("rename 未完成")
    }

    private fun rollback(
        newAtTarget: Boolean,
        backupMoved: Boolean,
        staged: File,
        target: File,
        backup: File
    ): Throwable? {
        if (newAtTarget) {
            val error = moveError(target, staged)
            if (error != null) return error
        }
        if (backupMoved) return moveError(backup, target)
        return null
    }

    private fun recoveryFailure(
        cause: Throwable,
        rollbackFailure: Throwable,
        staged: File,
        target: File,
        backup: File
    ): IOException = IOException(
        "插件目录回滚失败；请人工检查 staged=${staged.absolutePath}, " +
            "target=${target.absolutePath}, backup=${backup.absolutePath}", cause
    ).also { it.addSuppressed(rollbackFailure) }

    private fun exists(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}

/** 不跟随符号链接；只遍历并删除传入的目录树。 */
internal fun deleteInstallTree(file: File): Boolean {
    val root = file.toPath()
    return try {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return true
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(path: Path, error: IOException): FileVisitResult =
                throw error

            override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
        })
        true
    } catch (_: Exception) {
        false
    }
}

/** 同目录临时文件 + fsync + rename，失败时不会先删除原注册表。 */
internal fun writeRegistryAtomically(
    target: File,
    text: String,
    replace: (File, File) -> Boolean = { from, to -> atomicReplace(from, to) }
) {
    val parent = target.absoluteFile.parentFile
        ?: throw IOException("注册表目标缺少父目录: ${target.absolutePath}")
    val temp = try {
        val prefix = target.name.take(40).let { if (it.length < 3) it.padEnd(3, '_') else it } + "-"
        File.createTempFile(prefix, ".tmp", parent)
    } catch (failure: Exception) {
        throw IOException("无法创建注册表临时文件: ${parent.absolutePath}", failure)
    }
    try {
        FileOutputStream(temp).use { output ->
            output.write(text.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        val renamed = replace(temp, target)
        if (!renamed) throw IOException("注册表 rename 失败，旧文件应保持不变: ${target.absolutePath}")
    } catch (failure: Exception) {
        if (failure is IOException) throw failure
        throw IOException("注册表写入失败，旧文件应保持不变: ${target.absolutePath}", failure)
    } finally {
        try {
            temp.delete()
        } catch (_: Exception) {
            // 提交结果已由 rename 决定；清理异常不能反转已提交状态。
        }
    }
}
