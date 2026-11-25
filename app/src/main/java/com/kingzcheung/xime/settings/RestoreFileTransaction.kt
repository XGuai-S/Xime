package com.kingzcheung.xime.settings

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface RestoreJournalWriter : Closeable {
    fun write(bytes: ByteArray)
    fun flush()
    fun sync()
}

private fun fileJournalWriter(file: File, append: Boolean): RestoreJournalWriter {
    val stream = FileOutputStream(file, append)
    return object : RestoreJournalWriter {
        override fun write(bytes: ByteArray) = stream.write(bytes)
        override fun flush() = stream.flush()
        override fun sync() = stream.fd.sync()
        override fun close() = stream.close()
    }
}

/** 每次事务独立注入，测试不依赖权限或全局可变钩子。 */
internal class RestoreTransactionIo(
    val openJournal: (File) -> RestoreJournalWriter = { fileJournalWriter(it, false) },
    val readJournal: (File) -> ByteArray = { it.readBytes() },
    // 均在 filesDir 内，同卷 rename 失败就保留源；不把半截 copy 当作有效原件。
    val move: (File, File) -> Boolean = { source, destination -> source.renameTo(destination) },
    val openCommitJournal: (File) -> RestoreJournalWriter = { fileJournalWriter(it, false) },
    val delete: (File) -> Boolean = { it.delete() },
    val publishCommitJournal: (File, File) -> Unit = { temporary, authoritative ->
        Files.move(temporary.toPath(), authoritative.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
)

/** 仅负责恢复文件事务；设置、数据库等提交仍由调用方处理。 */
internal class RestoreFileTransaction(
    private val filesDir: File,
    private val staging: File = File(filesDir, ".restore-staging"),
    private val rollback: File = File(filesDir, ".restore-rollback"),
    private val journal: File = File(filesDir, ".restore-journal"),
    private val io: RestoreTransactionIo = RestoreTransactionIo(),
) {
    private data class Record(val operation: Char, val path: String)
    private data class JournalState(val records: List<Record>, val committed: Boolean)

    fun prepare(): Boolean = attempt {
        checkArtifactRoots()
        if (journal.exists()) {
            if (!rollback()) return@attempt false
        } else if (hasMaterial(rollback)) {
            return@attempt false
        }
        if (staging.exists() && !staging.deleteRecursively()) return@attempt false
        if (rollback.exists() && !rollback.deleteRecursively()) return@attempt false
        !journal.exists()
    }

    fun canDiscardStaging(): Boolean = attempt {
        checkArtifactRoots()
        !journal.exists() && !hasMaterial(rollback)
    }

    fun commit(plan: List<String>): Boolean = attempt {
        checkArtifactRoots()
        if (journal.exists() || hasMaterial(rollback)) return@attempt false
        val targets = HashSet<String>()
        plan.forEach { relative ->
            val source = checkedPath(staging, relative)
            val destination = checkedPath(filesDir, relative)
            checkedPath(rollback, relative)
            if (!source.isFile || !targets.add(destination.canonicalPath)) return@attempt false
            if (destination.exists() && !destination.isFile) return@attempt false
        }
        if (plan.isEmpty()) return@attempt true
        if (!rollback.isDirectory && !rollback.mkdirs()) return@attempt false
        var writer: RestoreJournalWriter? = null
        var failure: Exception? = null
        var journalFailure = false
        fun append(operation: Char, relative: String) {
            try {
                val active = writer ?: throw IOException("恢复事务日志不可用")
                active.write("$operation $relative\n".toByteArray(Charsets.UTF_8))
                active.flush()
                active.sync()
            } catch (e: Exception) {
                journalFailure = true
                throw e
            }
        }
        try {
            try {
                writer = io.openJournal(journal)
            } catch (e: Exception) {
                journalFailure = true
                throw e
            }
            for (relative in plan) {
                val source = checkedPath(staging, relative)
                val destination = checkedPath(filesDir, relative)
                if (destination.parentFile?.isDirectory != true && destination.parentFile?.mkdirs() != true) {
                    throw IOException("恢复目标目录不可用")
                }
                if (destination.exists()) {
                    val original = checkedPath(rollback, relative)
                    if (original.parentFile?.isDirectory != true && original.parentFile?.mkdirs() != true) {
                        throw IOException("恢复回滚目录不可用")
                    }
                    append('M', relative)
                    if (!io.move(destination, original)) throw IOException("恢复原文件暂存失败")
                }
                // W 先同步到日志，供进程中断后识别并撤销换入；不承诺设备掉电原子性。
                append('W', relative)
                if (!io.move(source, destination)) throw IOException("恢复文件换入失败")
            }
        } catch (e: Exception) {
            failure = e
        } finally {
            try {
                writer?.close()
            } catch (e: Exception) {
                journalFailure = true
                failure = e
            }
        }
        if (failure != null) {
            // 日志 IO 失败时不再执行移动，保留材料；合法完整日志可在下次重试恢复。
            if (!journalFailure) rollback()
            return@attempt false
        }
        // 候选日志全部落盘并关流后才原子发布 C；失败时权威日志仍是可回滚的 M/W。
        val originalBytes = io.readJournal(journal)
        val originalState = readRecords(originalBytes)
        if (originalState.committed || originalState.records.filter { it.operation == 'W' }.map { it.path } != plan) {
            return@attempt false
        }
        val committedBytes = originalBytes + "C\n".toByteArray(Charsets.UTF_8)
        val committedState = readRecords(committedBytes)
        val temporary = File.createTempFile("restore-commit-", ".tmp", staging)
        try {
            io.openCommitJournal(temporary).use { committed ->
                committed.write(committedBytes)
                committed.flush()
                committed.sync()
            }
            io.publishCommitJournal(temporary, journal)
        } finally {
            runCatching { temporary.delete() }
        }
        // C 已发布即完成文件提交；清理失败留待 prepare 重试，不能阻断后续设置/剪贴板恢复。
        runCatching { cleanupCommitted(committedState) }
        true
    }

    fun rollback(): Boolean = attempt {
        checkArtifactRoots()
        if (!journal.exists()) return@attempt !hasMaterial(rollback)
        if (!journal.isFile) return@attempt false
        val state = readRecords()
        if (state.committed) return@attempt cleanupCommitted(state)
        val records = state.records
        if (records.isEmpty() && hasMaterial(rollback)) return@attempt false
        val moved = records.filter { it.operation == 'M' }.mapTo(HashSet()) { it.path }
        if (moved.any { relative ->
                val original = checkedPath(rollback, relative)
                original.exists() && !original.isFile
            }) return@attempt false
        var complete = true
        for (record in records.asReversed()) {
            try {
                val destination = checkedPath(filesDir, record.path)
                val original = checkedPath(rollback, record.path)
                when (record.operation) {
                    'W' -> {
                        // 有 M 但 bak 已不在：可能 M 尚未执行，或此路径已在前次重试恢复。
                        // 此时绝不能按旧 W 再删当前原文件。
                        if (record.path !in moved || original.exists()) {
                            if (destination.exists() && (!destination.isFile || !destination.delete())) complete = false
                        } else if (!destination.isFile) {
                            complete = false
                        }
                    }
                    'M' -> {
                        if (original.exists()) {
                            if (!original.isFile) { complete = false; continue }
                            if (destination.exists() && (!destination.isFile || !destination.delete())) {
                                complete = false
                                continue
                            }
                            if (destination.parentFile?.isDirectory != true && destination.parentFile?.mkdirs() != true) {
                                complete = false
                                continue
                            }
                            if (!io.move(original, destination)) complete = false
                        } else if (!destination.isFile) {
                            complete = false
                        }
                    }
                }
            } catch (_: Exception) {
                complete = false
            }
        }
        if (!complete || hasMaterial(rollback)) return@attempt false
        if (rollback.exists() && !rollback.deleteRecursively()) return@attempt false
        journal.delete()
    }

    private fun readRecords(bytes: ByteArray = io.readJournal(journal)): JournalState {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        // 半行（包括半个 C）不能证明操作已持久化，不猜测或删除唯一回滚材料。
        if (text.isNotEmpty() && !text.endsWith('\n')) throw IOException("恢复事务日志不完整")
        val moved = HashSet<String>()
        val written = HashSet<String>()
        val records = mutableListOf<Record>()
        var committed = false
        val lines = text.split('\n').dropLast(1)
        lines.forEachIndexed { index, raw ->
            val line = raw.removeSuffix("\r")
            if (line == "C") {
                if (committed || index != lines.lastIndex || !written.containsAll(moved)) {
                    throw IOException("恢复事务提交标记无效")
                }
                committed = true
            } else {
                if (committed || line.length < 3 || line[1] != ' ' || line[0] !in "MW") {
                    throw IOException("恢复事务日志格式无效")
                }
                val relative = line.substring(2)
                checkedPath(filesDir, relative)
                checkedPath(rollback, relative)
                if (line[0] == 'M') {
                    if (!moved.add(relative) || relative in written) throw IOException("恢复事务日志顺序无效")
                } else if (!written.add(relative)) {
                    throw IOException("恢复事务日志重复")
                }
                records += Record(line[0], relative)
            }
        }
        return JournalState(records, committed)
    }

    /** C 之后只清理日志声明的原件，失败留 C 重试，绝不再回退已提交的新目标。 */
    private fun cleanupCommitted(state: JournalState): Boolean {
        checkArtifactRoots()
        if (!state.committed) return false
        val originals = state.records.filter { it.operation == 'M' }.mapTo(HashSet()) { it.path }
        if (state.records.filter { it.operation == 'W' }.any { !checkedPath(filesDir, it.path).isFile }) return false
        val oldFiles = mutableListOf<File>()
        val directories = mutableListOf<File>()
        fun inspect(file: File) {
            if (Files.isSymbolicLink(file.toPath())) throw IOException("已提交恢复的原件路径不能是符号链接")
            if (!file.exists()) return
            if (file != rollback) {
                val relative = file.relativeTo(rollback).path.replace('\\', '/')
                checkedPath(rollback, relative)
                if (file.isDirectory) {
                    if (relative in originals || originals.none { it.startsWith("$relative/") }) {
                        throw IOException("已提交恢复包含未知回滚目录")
                    }
                } else if (!file.isFile || relative !in originals) {
                    throw IOException("已提交恢复包含未知回滚原件")
                }
            } else if (!file.isDirectory) {
                throw IOException("已提交恢复的回滚目录无效")
            }
            if (file.isDirectory) {
                val children = file.listFiles() ?: throw IOException("已提交恢复的回滚目录不可读")
                children.forEach { inspect(it) }
                directories += file
            } else {
                oldFiles += file
            }
        }
        // 先完整校验整个 bak，再开始清理，避免先删合法原件后才发现未知材料。
        inspect(rollback)
        for (file in oldFiles) if (!io.delete(file)) return false
        for (directory in directories) if (!io.delete(directory)) return false
        return io.delete(journal)
    }

    private fun checkedPath(root: File, relative: String): File {
        if (relative.isEmpty() || relative.startsWith('/') || relative.contains('\\') ||
            Regex("^[A-Za-z]:").containsMatchIn(relative) ||
            relative.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } ||
            relative.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            relative.substringBefore('/') in setOf(staging.name, rollback.name, journal.name)) {
            throw IOException("恢复事务路径无效")
        }
        val canonicalRoot = root.canonicalFile
        val file = File(root, relative)
        if (!file.canonicalPath.startsWith(canonicalRoot.path + File.separator)) throw IOException("恢复事务路径越界")
        var cursor: File? = file
        while (cursor != null && cursor.absoluteFile != root.absoluteFile) {
            if (Files.isSymbolicLink(cursor.toPath())) throw IOException("恢复事务路径不能是符号链接")
            cursor = cursor.parentFile
        }
        return file
    }

    private fun checkArtifactRoots() {
        val root = filesDir.canonicalFile
        for (artifact in listOf(staging, rollback, journal)) {
            if (artifact.canonicalFile != File(root, artifact.name) || Files.isSymbolicLink(artifact.toPath())) {
                throw IOException("恢复事务目录越界")
            }
        }
    }

    private fun hasMaterial(file: File): Boolean {
        if (Files.isSymbolicLink(file.toPath())) return true
        if (!file.exists()) return false
        if (!file.isDirectory) return true
        val children = file.listFiles() ?: throw IOException("恢复回滚目录不可读")
        return children.any { hasMaterial(it) }
    }

    private inline fun attempt(action: () -> Boolean): Boolean = try { action() } catch (_: Exception) { false }
}
