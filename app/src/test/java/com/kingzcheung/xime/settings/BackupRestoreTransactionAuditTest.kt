package com.kingzcheung.xime.settings

import com.kingzcheung.xime.SymlinkSupport
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 仅在临时目录构造中断事务；不依赖权限失败、root 或真实应用文件。 */
class BackupRestoreTransactionAuditTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `failed old rollback blocks a new restore and preserves original recovery material`() {
        val files = temporary.newFolder("files")
        // 确定性 IO 失败：rime 是普通文件，无法把旧文件移回 rime/old.custom.yaml。
        val blockedParent = File(files, "rime").apply { writeText("existing parent blocker") }
        val rollback = File(files, ".restore-rollback")
        val oldOriginal = File(rollback, "rime/old.custom.yaml").apply {
            parentFile!!.mkdirs()
            writeText("original before interrupted restore")
        }
        val journal = File(files, ".restore-journal").apply { writeText("M rime/old.custom.yaml\n") }
        val originalBytes = oldOriginal.readBytes()
        val journalBytes = journal.readBytes()
        var preferencesCommitted = false

        val result = BackupManager.restoreArchive(files, { _, _ -> preferencesCommitted = true }, settingsOnlyArchive())

        assertTrue("旧事务无法回滚时，不得继续新的恢复", result.isFailure)
        assertFalse("旧事务失败时不得提交新设置", preferencesCommitted)
        assertEquals("existing parent blocker", blockedParent.readText())
        assertTrue("旧 rollback 中的原文件必须保留", oldOriginal.isFile)
        assertArrayEquals(originalBytes, oldOriginal.readBytes())
        assertTrue("旧 journal 必须保留供重试", journal.isFile)
        assertArrayEquals(journalBytes, journal.readBytes())
    }

    @Test fun `legal filename containing two dots is replayed before starting a new restore`() {
        val files = temporary.newFolder("legal-name")
        val destination = File(files, "rime/a..txt").apply {
            parentFile!!.mkdirs()
            writeText("interrupted replacement")
        }
        val original = File(files, ".restore-rollback/rime/a..txt").apply {
            parentFile!!.mkdirs()
            writeText("original with legal two-dot name")
        }
        val journal = File(files, ".restore-journal").apply { writeText("M rime/a..txt\n") }
        var preferencesCommitted = false

        val result = BackupManager.restoreArchive(files, { _, _ -> preferencesCommitted = true }, settingsOnlyArchive())

        assertTrue(result.toString(), result.isSuccess)
        assertTrue(preferencesCommitted)
        assertEquals("original with legal two-dot name", destination.readText())
        assertFalse(original.exists())
        assertFalse(journal.exists())
        assertFalse(File(files, ".restore-rollback").exists())
    }

    @Test fun `parent traversal in old journal rejects new restore and keeps rollback and journal untouched`() {
        val sandbox = temporary.newFolder("traversal")
        val outside = File(sandbox, "outside.txt").apply { writeText("outside must remain") }
        val files = File(sandbox, "files").apply { mkdirs() }
        val existing = File(files, "rime/existing.custom.yaml").apply {
            parentFile!!.mkdirs()
            writeText("existing target")
        }
        val oldOriginal = File(files, ".restore-rollback/rime/original.custom.yaml").apply {
            parentFile!!.mkdirs()
            writeText("original recovery bytes")
        }
        val journal = File(files, ".restore-journal").apply { writeText("W ../outside.txt\n") }
        val originalBytes = oldOriginal.readBytes()
        val journalBytes = journal.readBytes()
        var preferencesCommitted = false
        val archive = archiveOf(
            BackupManager.META_PREFIX + "settings.json" to "{}",
            "new.custom.yaml" to "new package must not be installed",
        )

        val result = BackupManager.restoreArchive(files, { _, _ -> preferencesCommitted = true }, archive)

        assertTrue("非法旧 journal 必须阻断新恢复", result.isFailure)
        assertFalse(preferencesCommitted)
        assertEquals("outside must remain", outside.readText())
        assertEquals("existing target", existing.readText())
        assertFalse(File(files, "rime/new.custom.yaml").exists())
        assertTrue("拒绝非法日志后不得丢失原文件", oldOriginal.isFile)
        assertArrayEquals(originalBytes, oldOriginal.readBytes())
        assertTrue("拒绝非法日志后不得丢失 journal", journal.isFile)
        assertArrayEquals(journalBytes, journal.readBytes())
    }

    @Test fun `existing valid move and write journal is still replayed before a new restore`() {
        val files = temporary.newFolder("valid-journal")
        val destination = File(files, "rime/valid.custom.yaml").apply {
            parentFile!!.mkdirs()
            writeText("interrupted replacement")
        }
        File(files, ".restore-rollback/rime/valid.custom.yaml").apply {
            parentFile!!.mkdirs()
            writeText("original valid target")
        }
        val journal = File(files, ".restore-journal").apply {
            writeText("M rime/valid.custom.yaml\nW rime/valid.custom.yaml\n")
        }
        var preferencesCommitted = false

        val result = BackupManager.restoreArchive(files, { _, _ -> preferencesCommitted = true }, settingsOnlyArchive())

        assertTrue(result.toString(), result.isSuccess)
        assertTrue(preferencesCommitted)
        assertEquals("original valid target", destination.readText())
        assertFalse(journal.exists())
        assertFalse(File(files, ".restore-rollback").exists())
    }

    @Test fun `retry after a partial rollback never deletes an original already restored on first attempt`() {
        val files = temporary.newFolder("retry")
        val rollback = File(files, ".restore-rollback")
        val blockedParent = File(files, "rime/blocked").apply {
            parentFile!!.mkdirs()
            writeText("temporary parent blocker")
        }
        val originalA = File(rollback, "rime/blocked/a.custom.yaml").apply {
            parentFile!!.mkdirs()
            writeText("original A")
        }
        File(rollback, "rime/b.custom.yaml").writeText("original B")
        val destinationB = File(files, "rime/b.custom.yaml").apply { writeText("interrupted B replacement") }
        val journal = File(files, ".restore-journal").apply {
            writeText("M rime/blocked/a.custom.yaml\nW rime/blocked/a.custom.yaml\n" +
                "M rime/b.custom.yaml\nW rime/b.custom.yaml\n")
        }
        val journalBytes = journal.readBytes()

        assertFalse("A 回滚受阻时必须报告未完成", replayRollback(files, rollback, journal))
        assertEquals("original B", destinationB.readText())
        assertTrue(originalA.isFile)
        assertTrue(journal.isFile)
        assertArrayEquals(journalBytes, journal.readBytes())

        // 只解除本测试创建的临时阻塞，模拟下次恢复时 IO 条件已改善。
        assertTrue(blockedParent.delete())
        assertTrue(blockedParent.mkdirs())
        assertTrue(replayRollback(files, rollback, journal))
        assertEquals("original A", File(files, "rime/blocked/a.custom.yaml").readText())
        assertTrue("重试不能删除首轮已经恢复的 B 原文件", destinationB.isFile)
        assertEquals("original B", destinationB.readText())
        assertFalse(journal.exists())
        assertFalse(rollback.exists())
    }

    @Test fun `journal open write flush and sync errors stop before the first move`() {
        for (phase in listOf("open", "write", "flush", "sync")) {
            val files = temporary.newFolder()
            val target = file(files, "rime/old.custom.yaml", "original")
            val moves = mutableListOf<String>()
            var preferencesCommitted = false
            val result = BackupManager.restoreArchiveOutcome(files, { _, _ -> preferencesCommitted = true },
                replacementArchive(), transactionIo = failingJournalIo(phase, false, moves))
            assertTrue(phase, result.isFailure)
            assertTrue(phase, moves.isEmpty())
            assertFalse(preferencesCommitted)
            assertEquals("original", target.readText())
            if (phase != "open") {
                assertTrue(File(files, ".restore-journal").isFile)
                assertTrue(File(files, ".restore-staging/rime/old.custom.yaml").isFile)
            }
        }
    }

    @Test fun `failed write ahead W never installs a new file after M has moved the original`() {
        for (phase in listOf("write", "flush", "sync")) {
            val files = temporary.newFolder()
            val target = file(files, "rime/old.custom.yaml", "original")
            val moves = mutableListOf<String>()
            var preferencesCommitted = false
            val result = BackupManager.restoreArchiveOutcome(files, { _, _ -> preferencesCommitted = true },
                replacementArchive(), transactionIo = failingJournalIo(phase, true, moves))
            assertTrue(phase, result.isFailure)
            assertEquals(phase, 1, moves.size)
            assertFalse(preferencesCommitted)
            assertFalse(target.exists())
            assertEquals("original", File(files, ".restore-rollback/rime/old.custom.yaml").readText())
            assertTrue(File(files, ".restore-journal").isFile)
            assertTrue(File(files, ".restore-staging/rime/old.custom.yaml").isFile)
        }
    }

    @Test fun `journal close error reports failure and keeps originals recoverable on retry`() {
        val files = temporary.newFolder()
        val target = file(files, "rime/old.custom.yaml", "original")
        val moves = mutableListOf<String>()
        val result = BackupManager.restoreArchiveOutcome(files, { _, _ -> }, replacementArchive(),
            transactionIo = failingJournalIo("close", false, moves))
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("已回滚到恢复前状态"))
        assertEquals(2, moves.size)
        assertEquals("replacement", target.readText())
        assertEquals("original", File(files, ".restore-rollback/rime/old.custom.yaml").readText())
        assertTrue(File(files, ".restore-journal").isFile)
        assertTrue(File(files, ".restore-staging").exists())
        assertTrue(RestoreFileTransaction(files).prepare())
        assertEquals("original", target.readText())
        assertFalse(File(files, ".restore-journal").exists())
        assertFalse(File(files, ".restore-staging").exists())
    }

    @Test fun `journal read error preserves old staging originals journal and current target`() {
        val files = temporary.newFolder()
        val target = file(files, "rime/old.custom.yaml", "interrupted")
        val original = file(files, ".restore-rollback/rime/old.custom.yaml", "original")
        val journal = file(files, ".restore-journal", "M rime/old.custom.yaml\nW rime/old.custom.yaml\n")
        val staging = file(files, ".restore-staging/old-staged.txt", "pending bytes")
        val before = journal.readBytes()
        var preferencesCommitted = false
        val io = RestoreTransactionIo(readJournal = { throw java.io.IOException("injected read failure") },
            move = { _, _ -> error("read failure must not move files") })
        val result = BackupManager.restoreArchiveOutcome(files, { _, _ -> preferencesCommitted = true },
            settingsOnlyArchive(), transactionIo = io)
        assertTrue(result.isFailure)
        assertFalse(preferencesCommitted)
        assertEquals("interrupted", target.readText())
        assertEquals("original", original.readText())
        assertEquals("pending bytes", staging.readText())
        assertArrayEquals(before, journal.readBytes())
    }

    @Test fun `both M and W are flushed and synced before their corresponding file move`() {
        val files = temporary.newFolder()
        val target = file(files, "rime/old.custom.yaml", "original")
        val defaults = RestoreTransactionIo()
        val events = mutableListOf<String>()
        var line = ""
        var durableLine = ""
        val io = RestoreTransactionIo(openJournal = { journal ->
            val delegate = defaults.openJournal(journal)
            object : RestoreJournalWriter {
                override fun write(bytes: ByteArray) { line = bytes.toString(Charsets.UTF_8); delegate.write(bytes); events += "write:${line.first()}" }
                override fun flush() { delegate.flush(); events += "flush:${line.first()}" }
                override fun sync() { delegate.sync(); durableLine = line; events += "sync:${line.first()}" }
                override fun close() = delegate.close()
            }
        }, move = { source, destination ->
            val operation = if (destination.path.contains(".restore-rollback")) 'M' else 'W'
            assertEquals("$operation rime/old.custom.yaml\n", durableLine)
            events += "move:$operation"
            defaults.move(source, destination)
        })
        val result = BackupManager.restoreArchiveOutcome(files, { _, _ -> }, replacementArchive(), transactionIo = io)
        assertTrue(result.toString(), result.isSuccess)
        assertEquals(listOf("write:M", "flush:M", "sync:M", "move:M", "write:W", "flush:W", "sync:W", "move:W"), events)
        assertEquals("replacement", target.readText())
    }

    @Test fun `partial journal write and existing half line preserve all recovery material`() {
        val files = temporary.newFolder()
        val target = file(files, "rime/old.custom.yaml", "original")
        val defaults = RestoreTransactionIo()
        val io = RestoreTransactionIo(openJournal = { journal ->
            val delegate = defaults.openJournal(journal)
            object : RestoreJournalWriter {
                override fun write(bytes: ByteArray) { delegate.write(bytes.copyOf(4)); throw java.io.IOException("partial write") }
                override fun flush() = delegate.flush()
                override fun sync() = delegate.sync()
                override fun close() = delegate.close()
            }
        }, move = { _, _ -> error("partial journal cannot authorize a move") })
        assertTrue(BackupManager.restoreArchiveOutcome(files, { _, _ -> }, replacementArchive(), transactionIo = io).isFailure)
        val journal = File(files, ".restore-journal")
        val before = journal.readBytes()
        assertFalse(RestoreFileTransaction(files).prepare())
        assertEquals("original", target.readText())
        assertArrayEquals(before, journal.readBytes())
        assertTrue(File(files, ".restore-staging/rime/old.custom.yaml").isFile)

        val interrupted = temporary.newFolder()
        val destination = file(interrupted, "rime/old.custom.yaml", "interrupted")
        val original = file(interrupted, ".restore-rollback/rime/old.custom.yaml", "saved original")
        val halfLine = file(interrupted, ".restore-journal", "M rime/old.custom.yaml\nW rime/old.custom.yaml")
        val staged = file(interrupted, ".restore-staging/keep.txt", "keep staging")
        val originalJournal = halfLine.readBytes()
        assertFalse(RestoreFileTransaction(interrupted).prepare())
        assertEquals("interrupted", destination.readText())
        assertEquals("saved original", original.readText())
        assertEquals("keep staging", staged.readText())
        assertArrayEquals(originalJournal, halfLine.readBytes())
    }

    @Test fun `unknown operations and invalid paths preserve even an empty rollback directory`() {
        val invalid = listOf("Q rime/unknown.txt\n", "W ../outside.txt\n", "W /absolute.txt\n",
            "W rime/bad\u0000name.txt\n", "W rime/bad\rname.txt\n", "W rime/bad\tname.txt\n", "W rime/name\nQ injected\n")
        for (line in invalid) {
            val files = temporary.newFolder()
            val target = file(files, "rime/protected.custom.yaml", "must remain")
            val rollback = File(files, ".restore-rollback").apply { mkdirs() }
            val journal = file(files, ".restore-journal", "W rime/protected.custom.yaml\n$line")
            val staged = file(files, ".restore-staging/keep.txt", "keep staging")
            val bytes = journal.readBytes()
            var committed = false
            val result = BackupManager.restoreArchive(files, { _, _ -> committed = true }, settingsOnlyArchive())
            assertTrue(line, result.isFailure)
            assertFalse(committed)
            assertEquals("must remain", target.readText())
            assertEquals("keep staging", staged.readText())
            assertTrue(rollback.isDirectory)
            assertArrayEquals(bytes, journal.readBytes())
        }
    }

    @Test fun `orphan originals with no journal or empty journal block restore and keep staging`() {
        for (emptyJournal in listOf(false, true)) {
            val files = temporary.newFolder()
            val original = file(files, ".restore-rollback/rime/original.custom.yaml", "only original")
            val staged = file(files, ".restore-staging/keep.txt", "keep staging")
            val journal = File(files, ".restore-journal")
            if (emptyJournal) journal.writeText("")
            var committed = false
            val result = BackupManager.restoreArchive(files, { _, _ -> committed = true }, settingsOnlyArchive())
            assertTrue(result.isFailure)
            assertFalse(committed)
            assertEquals("only original", original.readText())
            assertEquals("keep staging", staged.readText())
            assertEquals(emptyJournal, journal.exists())
            if (emptyJournal) assertEquals("", journal.readText())
        }
    }

    @Test fun `M without backup preserves existing original but missing both sides is not success`() {
        for (exists in listOf(false, true)) {
            val files = temporary.newFolder()
            val target = File(files, "rime/a..txt")
            if (exists) file(files, "rime/a..txt", "original never moved or already restored")
            val journal = file(files, ".restore-journal", "M rime/a..txt\r\nW rime/a..txt\r\n")
            val staged = file(files, ".restore-staging/keep.txt", "keep staging")
            assertEquals(exists, RestoreFileTransaction(files).prepare())
            if (exists) {
                assertEquals("original never moved or already restored", target.readText())
                assertFalse(journal.exists())
            } else {
                assertTrue(journal.isFile)
                assertEquals("keep staging", staged.readText())
            }
        }
        val files = temporary.newFolder()
        val newlyInstalled = file(files, "rime/new.custom.yaml", "new interrupted file")
        file(files, ".restore-journal", "W rime/new.custom.yaml\n")
        assertTrue(RestoreFileTransaction(files).prepare())
        assertFalse(newlyInstalled.exists())
    }

    @Test fun `control characters in incoming zip paths cannot become journal records`() {
        for (name in listOf("bad\nW injected.custom.yaml", "bad\rname.custom.yaml", "bad\tname.custom.yaml", "bad\u2028name.custom.yaml")) {
            val files = temporary.newFolder()
            val target = file(files, "rime/old.custom.yaml", "original")
            var committed = false
            val archive = archiveOf("old.custom.yaml" to "replacement", name to "malicious filename",
                BackupManager.META_PREFIX + "settings.json" to "{}")
            val result = BackupManager.restoreArchive(files, { _, _ -> committed = true }, archive)
            assertTrue(name, result.isFailure)
            assertFalse(committed)
            assertEquals("original", target.readText())
            assertFalse(File(files, ".restore-journal").exists())
        }
    }

    @Test fun `symbolic links in targets or transaction roots cannot escape the temporary root`() {
        for (artifactLink in listOf(false, true)) {
            val sandbox = temporary.newFolder()
            val files = File(sandbox, "files").apply { mkdirs() }
            val outside = File(sandbox, "outside").apply { mkdirs() }
            val protectedFile = file(outside, "protected.txt", "outside original")
            val staged = file(files, ".restore-staging/keep.txt", "keep staging")
            val path = if (artifactLink) {
                SymlinkSupport.create(File(files, ".restore-rollback"), outside)
                "rime/protected.txt"
            } else {
                File(files, "rime").mkdirs()
                SymlinkSupport.create(File(files, "rime/link"), outside)
                "rime/link/protected.txt"
            }
            val journal = file(files, ".restore-journal", "W $path\n")
            val bytes = journal.readBytes()
            assertFalse(RestoreFileTransaction(files).prepare())
            assertEquals("outside original", protectedFile.readText())
            assertEquals("keep staging", staged.readText())
            assertArrayEquals(bytes, journal.readBytes())
        }
    }

    @Test fun `failed rename has no copy delete fallback and keeps the original intact`() {
        val files = temporary.newFolder()
        val target = file(files, "rime/old.custom.yaml", "original")
        val source = file(files, ".restore-staging/rime/old.custom.yaml", "replacement")
        var moves = 0
        val io = RestoreTransactionIo(move = { _, _ -> moves++; false })
        assertFalse(RestoreFileTransaction(files, io = io).commit(listOf("rime/old.custom.yaml")))
        assertEquals(1, moves)
        assertEquals("original", target.readText())
        assertEquals("replacement", source.readText())
        assertFalse(File(files, ".restore-rollback/rime/old.custom.yaml").exists())
    }

    @Test fun `duplicate destinations fail before opening a journal or moving originals`() {
        val files = temporary.newFolder()
        val target = file(files, "rime/old.custom.yaml", "original")
        val source = file(files, ".restore-staging/rime/old.custom.yaml", "replacement")
        var opened = false
        var moved = false
        val io = RestoreTransactionIo(openJournal = { opened = true; throw java.io.IOException("unexpected open") },
            move = { _, _ -> moved = true; false })
        assertFalse(RestoreFileTransaction(files, io = io).commit(listOf("rime/old.custom.yaml", "rime/old.custom.yaml")))
        assertFalse(opened)
        assertFalse(moved)
        assertEquals("original", target.readText())
        assertEquals("replacement", source.readText())
        assertFalse(File(files, ".restore-journal").exists())
    }

    @Test fun `durable C survives interrupted cleanup and recovery keeps the committed new target`() {
        val files = temporary.newFolder()
        val target = file(files, "rime/a.custom.yaml", "old A")
        file(files, ".restore-staging/rime/a.custom.yaml", "new A")
        val defaults = RestoreTransactionIo()
        val events = mutableListOf<String>()
        var durable = false
        var closed = false
        val io = RestoreTransactionIo(openCommitJournal = { journal ->
            val delegate = defaults.openCommitJournal(journal)
            object : RestoreJournalWriter {
                override fun write(bytes: ByteArray) {
                    assertEquals("M rime/a.custom.yaml\nW rime/a.custom.yaml\nC\n", bytes.toString(Charsets.UTF_8))
                    delegate.write(bytes); events += "write C"
                }
                override fun flush() { delegate.flush(); events += "flush C" }
                override fun sync() { delegate.sync(); durable = true; events += "sync C" }
                override fun close() { delegate.close(); closed = true; events += "close C" }
            }
        }, delete = { candidate ->
            assertTrue(durable)
            assertTrue(closed)
            assertTrue(File(files, ".restore-journal").readText().endsWith("C\n"))
            assertTrue(candidate.path.contains(".restore-rollback"))
            false
        }, publishCommitJournal = { temporary, authoritative ->
            assertTrue(durable)
            assertTrue(closed)
            assertEquals(File(files, ".restore-staging"), temporary.parentFile)
            assertEquals("M rime/a.custom.yaml\nW rime/a.custom.yaml\n", authoritative.readText())
            assertTrue(temporary.readText().endsWith("C\n"))
            defaults.publishCommitJournal(temporary, authoritative)
            events += "publish C"
        })
        assertTrue("C 已提交成功，原件清理可重试", RestoreFileTransaction(files, io = io).commit(listOf("rime/a.custom.yaml")))
        assertEquals(listOf("write C", "flush C", "sync C", "close C", "publish C"), events)
        assertEquals("new A", target.readText())
        assertEquals("old A", File(files, ".restore-rollback/rime/a.custom.yaml").readText())
        assertEquals("M rime/a.custom.yaml\nW rime/a.custom.yaml\nC\n", File(files, ".restore-journal").readText())
        assertTrue(RestoreFileTransaction(files).prepare())
        assertEquals("new A", target.readText())
        assertFalse(File(files, ".restore-journal").exists())
        assertFalse(File(files, ".restore-rollback").exists())
    }

    @Test fun `cleanup retry after some originals are deleted never rolls back either committed target`() {
        val files = temporary.newFolder()
        val a = file(files, "rime/a.custom.yaml", "old A")
        val b = file(files, "rime/b.custom.yaml", "old B")
        file(files, ".restore-staging/rime/a.custom.yaml", "new A")
        file(files, ".restore-staging/rime/b.custom.yaml", "new B")
        val defaults = RestoreTransactionIo()
        var removed = 0
        val io = RestoreTransactionIo(delete = { candidate ->
            if (candidate.isFile && candidate.path.contains(".restore-rollback")) {
                if (removed == 0) {
                    val result = defaults.delete(candidate)
                    if (result) removed++
                    result
                } else false
            } else defaults.delete(candidate)
        })
        assertTrue("C 已提交成功，剩余原件清理可重试", RestoreFileTransaction(files, io = io).commit(listOf("rime/a.custom.yaml", "rime/b.custom.yaml")))
        assertEquals(1, removed)
        assertEquals(1, File(files, ".restore-rollback").walkTopDown().count { it.isFile })
        assertTrue(File(files, ".restore-journal").readText().endsWith("C\n"))
        assertTrue(RestoreFileTransaction(files).prepare())
        assertEquals("new A", a.readText())
        assertEquals("new B", b.readText())
        assertFalse(File(files, ".restore-rollback").exists())
        assertFalse(File(files, ".restore-journal").exists())
    }

    @Test fun `journal is deleted last and failed final deletion remains retryable after bak is empty`() {
        val files = temporary.newFolder()
        val target = file(files, "rime/a.custom.yaml", "old A")
        file(files, ".restore-staging/rime/a.custom.yaml", "new A")
        val defaults = RestoreTransactionIo()
        var attemptedJournalDelete = false
        val io = RestoreTransactionIo(delete = { candidate ->
            if (candidate.name == ".restore-journal") {
                attemptedJournalDelete = true
                assertFalse(File(files, ".restore-rollback").exists())
                false
            } else defaults.delete(candidate)
        })
        assertTrue("C 已提交成功，日志清理可重试", RestoreFileTransaction(files, io = io).commit(listOf("rime/a.custom.yaml")))
        assertTrue(attemptedJournalDelete)
        assertTrue(File(files, ".restore-journal").readText().endsWith("C\n"))
        assertEquals("new A", target.readText())
        assertTrue(RestoreFileTransaction(files).prepare())
        assertEquals("new A", target.readText())
        assertFalse(File(files, ".restore-journal").exists())
    }

    @Test fun `C cannot authorize deletion of unknown backup files directories or symbolic links`() {
        for (kind in listOf("file", "directory", "symlink")) {
            val files = temporary.newFolder()
            val target = file(files, "rime/a.custom.yaml", "new A")
            val declared = file(files, ".restore-rollback/rime/a.custom.yaml", "old A")
            val staged = file(files, ".restore-staging/keep.txt", "keep staging")
            val outside = temporary.newFolder()
            val outsideFile = file(outside, "keep.txt", "outside original")
            when (kind) {
                "file" -> file(files, ".restore-rollback/unknown.txt", "unknown original")
                "directory" -> File(files, ".restore-rollback/unknown").mkdirs()
                "symlink" -> SymlinkSupport.create(File(files, ".restore-rollback/rime/link"), outside)
            }
            val journal = file(files, ".restore-journal", "M rime/a.custom.yaml\nW rime/a.custom.yaml\nC\n")
            val before = journal.readBytes()
            var deletions = 0
            val defaults = RestoreTransactionIo()
            val io = RestoreTransactionIo(delete = { deletions++; defaults.delete(it) })
            assertFalse(kind, RestoreFileTransaction(files, io = io).prepare())
            assertEquals(0, deletions)
            assertEquals("new A", target.readText())
            assertEquals("old A", declared.readText())
            assertEquals("keep staging", staged.readText())
            assertEquals("outside original", outsideFile.readText())
            assertArrayEquals(before, journal.readBytes())
        }
    }

    @Test fun `partial duplicate or misplaced C remains conservative and never replays earlier W`() {
        val base = "M rime/a.custom.yaml\nW rime/a.custom.yaml\n"
        val invalid = listOf(base + "C", base + "C\nC\n", base + "C\nW rime/later.txt\n",
            base + "C path\n", "M rime/a.custom.yaml\nC\n")
        for (contents in invalid) {
            val files = temporary.newFolder()
            val target = file(files, "rime/a.custom.yaml", "new A")
            val original = file(files, ".restore-rollback/rime/a.custom.yaml", "old A")
            val journal = file(files, ".restore-journal", contents)
            val staged = file(files, ".restore-staging/keep.txt", "keep staging")
            val before = journal.readBytes()
            assertFalse(RestoreFileTransaction(files).prepare())
            assertEquals("new A", target.readText())
            assertEquals("old A", original.readText())
            assertEquals("keep staging", staged.readText())
            assertArrayEquals(before, journal.readBytes())
        }
    }

    @Test fun `C candidate writer failures keep authoritative MW and retry restores original`() {
        for (phase in listOf("open", "write", "flush", "sync", "close")) {
            val files = temporary.newFolder()
            val target = file(files, "rime/a.custom.yaml", "old A")
            file(files, ".restore-staging/rime/a.custom.yaml", "new A")
            val defaults = RestoreTransactionIo()
            var deletions = 0
            var moves = 0
            val io = RestoreTransactionIo(openCommitJournal = { journal ->
                if (phase == "open") throw java.io.IOException("injected C open failure")
                val delegate = defaults.openCommitJournal(journal)
                object : RestoreJournalWriter {
                    override fun write(bytes: ByteArray) {
                        if (phase == "write") { delegate.write(byteArrayOf('C'.code.toByte())); throw java.io.IOException("partial C") }
                        delegate.write(bytes)
                    }
                    override fun flush() { if (phase == "flush") throw java.io.IOException("C flush failure"); delegate.flush() }
                    override fun sync() { if (phase == "sync") throw java.io.IOException("C sync failure"); delegate.sync() }
                    override fun close() { delegate.close(); if (phase == "close") throw java.io.IOException("C close failure") }
                }
            }, move = { source, destination -> moves++; defaults.move(source, destination) },
                delete = { deletions++; defaults.delete(it) })
            assertFalse(phase, RestoreFileTransaction(files, io = io).commit(listOf("rime/a.custom.yaml")))
            assertEquals(2, moves)
            assertEquals(0, deletions)
            assertEquals("new A", target.readText())
            assertEquals("old A", File(files, ".restore-rollback/rime/a.custom.yaml").readText())
            assertTrue(File(files, ".restore-journal").isFile)
            assertEquals("M rime/a.custom.yaml\nW rime/a.custom.yaml\n", File(files, ".restore-journal").readText())
            assertTrue(File(files, ".restore-staging").listFiles().orEmpty().none { it.name.startsWith("restore-commit-") })
            assertTrue(phase, RestoreFileTransaction(files).prepare())
            assertEquals("old A", target.readText())
            assertFalse(File(files, ".restore-rollback").exists())
            assertFalse(File(files, ".restore-journal").exists())
        }
    }

    @Test fun `failed atomic publication keeps MW authoritative and retry rolls back new target`() {
        for (unsupported in listOf(false, true)) {
            val files = temporary.newFolder()
            val target = file(files, "rime/a.custom.yaml", "old A")
            file(files, ".restore-staging/rime/a.custom.yaml", "new A")
            var published = false
            var deletions = 0
            val defaults = RestoreTransactionIo()
            val io = RestoreTransactionIo(publishCommitJournal = { temporary, authoritative ->
                published = true
                assertEquals(File(files, ".restore-staging"), temporary.parentFile)
                assertEquals("M rime/a.custom.yaml\nW rime/a.custom.yaml\nC\n", temporary.readText())
                assertEquals("M rime/a.custom.yaml\nW rime/a.custom.yaml\n", authoritative.readText())
                if (unsupported) throw java.nio.file.AtomicMoveNotSupportedException("candidate", "journal", "injected unsupported")
                throw java.io.IOException("injected publication failure")
            }, delete = { deletions++; defaults.delete(it) })
            assertFalse(RestoreFileTransaction(files, io = io).commit(listOf("rime/a.custom.yaml")))
            assertTrue(published)
            assertEquals(0, deletions)
            assertEquals("new A", target.readText())
            assertEquals("old A", File(files, ".restore-rollback/rime/a.custom.yaml").readText())
            assertEquals("M rime/a.custom.yaml\nW rime/a.custom.yaml\n", File(files, ".restore-journal").readText())
            assertTrue(File(files, ".restore-staging").listFiles().orEmpty().none { it.name.startsWith("restore-commit-") })
            assertTrue(RestoreFileTransaction(files).prepare())
            assertEquals("old A", target.readText())
            assertFalse(File(files, ".restore-journal").exists())
        }
    }

    @Test fun `incomplete source journal refuses C candidate creation and publication`() {
        val files = temporary.newFolder()
        val target = file(files, "rime/a.custom.yaml", "old A")
        file(files, ".restore-staging/rime/a.custom.yaml", "new A")
        var candidateOpened = false
        var published = false
        val defaults = RestoreTransactionIo()
        val io = RestoreTransactionIo(openJournal = { journal ->
            val delegate = defaults.openJournal(journal)
            object : RestoreJournalWriter {
                override fun write(bytes: ByteArray) = delegate.write(bytes)
                override fun flush() = delegate.flush()
                override fun sync() = delegate.sync()
                override fun close() {
                    delegate.close()
                    java.io.FileOutputStream(journal, true).use { it.write("W incomplete".toByteArray()) }
                }
            }
        }, openCommitJournal = { candidate -> candidateOpened = true; defaults.openCommitJournal(candidate) },
            publishCommitJournal = { _, _ -> published = true })
        assertFalse(RestoreFileTransaction(files, io = io).commit(listOf("rime/a.custom.yaml")))
        assertFalse(candidateOpened)
        assertFalse(published)
        assertEquals("new A", target.readText())
        assertEquals("old A", File(files, ".restore-rollback/rime/a.custom.yaml").readText())
        val journal = File(files, ".restore-journal")
        val before = journal.readBytes()
        assertTrue(journal.readText().endsWith("W incomplete"))
        assertFalse(RestoreFileTransaction(files).prepare())
        assertArrayEquals(before, journal.readBytes())
        assertEquals("new A", target.readText())
        assertEquals("old A", File(files, ".restore-rollback/rime/a.custom.yaml").readText())
    }

    @Test fun `committed cleanup failures do not skip full archive side effects`() {
        for (targetKind in listOf("original", "journal")) {
            for (throws in listOf(false, true)) {
                val files = temporary.newFolder()
                val target = file(files, "rime/old.custom.yaml", "original")
                val effects = mutableListOf<String>()
                var cleanupAttempted = false
                val defaults = RestoreTransactionIo()
                val io = RestoreTransactionIo(delete = { candidate ->
                    val selected = if (targetKind == "journal") candidate.name == ".restore-journal"
                        else candidate.isFile && candidate.path.contains(".restore-rollback")
                    if (selected) {
                        cleanupAttempted = true
                        assertTrue(File(files, ".restore-journal").readText().endsWith("C\n"))
                        if (throws) throw java.io.IOException("injected committed cleanup failure")
                        false
                    } else defaults.delete(candidate)
                })
                val result = BackupManager.restoreArchiveOutcome(
                    files, { name, bytes ->
                        effects += "prefs"
                        assertEquals("kime_settings", name)
                        assertEquals("{}", bytes.toString(Charsets.UTF_8))
                    }, fullSideEffectArchive(),
                    visibleConfigWriter = { text ->
                        effects += "config"
                        assertEquals("restored config", text)
                        true
                    },
                    userDataWriter = { name, text ->
                        effects += "userdata"
                        assertEquals("candidate_order.txt", name)
                        assertEquals("restored order", text)
                        true
                    },
                    clipboardRestorer = { records, staging ->
                        effects += "clipboard"
                        assertEquals(1, records.size)
                        com.kingzcheung.xime.clipboard.ClipboardBackupCodec.restoreImages(files, records, staging)
                        assertArrayEquals(byteArrayOf(1, 2, 3), File(files, records.single().imagePath).readBytes())
                    }, transactionIo = io,
                )
                val scenario = "$targetKind throws=$throws"
                assertTrue(scenario, cleanupAttempted)
                assertTrue("$scenario: $result", result.isSuccess)
                assertEquals(scenario, listOf("prefs", "userdata", "config", "clipboard"), effects)
                assertEquals(1, result.getOrThrow().dictSnapshotCount)
                assertEquals("replacement", target.readText())
                assertTrue(File(files, ".restore-journal").readText().endsWith("C\n"))
                assertTrue(File(files, ".restore-staging/clipboard/manifest.json").isFile)
                assertTrue(RestoreFileTransaction(files).prepare())
                assertEquals("replacement", target.readText())
                assertEquals(listOf("prefs", "userdata", "config", "clipboard"), effects)
                assertFalse(File(files, ".restore-journal").exists())
                assertFalse(File(files, ".restore-rollback").exists())
                assertFalse(File(files, ".restore-staging").exists())
            }
        }
    }

    @Test fun `commit candidate and publication failures skip every full archive side effect`() {
        for (phase in listOf("candidate", "publish")) {
            val files = temporary.newFolder()
            val target = file(files, "rime/old.custom.yaml", "original")
            val effects = mutableListOf<String>()
            val defaults = RestoreTransactionIo()
            val io = RestoreTransactionIo(
                openCommitJournal = { candidate ->
                    if (phase == "candidate") throw java.io.IOException("injected candidate failure")
                    defaults.openCommitJournal(candidate)
                },
                publishCommitJournal = { _, _ -> throw java.io.IOException("injected publication failure") },
            )
            val result = BackupManager.restoreArchiveOutcome(
                files, { _, _ -> effects += "prefs" }, fullSideEffectArchive(),
                visibleConfigWriter = { effects += "config"; true },
                userDataWriter = { _, _ -> effects += "userdata"; true },
                clipboardRestorer = { _, _ -> effects += "clipboard" }, transactionIo = io,
            )
            assertTrue(phase, result.isFailure)
            assertTrue(phase, effects.isEmpty())
            assertEquals("replacement", target.readText())
            assertFalse(File(files, ".restore-journal").readText().endsWith("C\n"))
            assertTrue(File(files, ".restore-staging/clipboard/manifest.json").isFile)
            assertFalse(File(files, "clipboard_images").exists())
            assertTrue(RestoreFileTransaction(files).prepare())
            assertEquals("original", target.readText())
            assertFalse(File(files, "rime/sync/test/dict.userdb.txt").exists())
            assertTrue(effects.isEmpty())
        }
    }

    private fun fullSideEffectArchive(): ByteArray {
        val codec = com.kingzcheung.xime.clipboard.ClipboardBackupCodec
        val image = byteArrayOf(1, 2, 3)
        val hash = codec.sha256(image)
        val record = com.kingzcheung.xime.clipboard.ClipboardBackupRecord(
            type = "image", imagePath = "clipboard_images/$hash.png", imageHash = hash,
            mimeType = "image/png", sizeBytes = image.size.toLong(),
        )
        val entries = linkedMapOf(
            "old.custom.yaml" to "replacement".toByteArray(),
            "sync/test/dict.userdb.txt" to "snapshot".toByteArray(),
            UserConfigDir.UNIFIED_CONFIG_FILE to "restored config".toByteArray(),
            "candidate_order.txt" to "restored order".toByteArray(),
            BackupManager.META_PREFIX + "settings.json" to "{}".toByteArray(),
            BackupManager.META_PREFIX + codec.MANIFEST to codec.encode(listOf(record)),
            BackupManager.META_PREFIX + codec.imageEntry(record.imagePath) to image,
        )
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun replacementArchive(): ByteArray = archiveOf(
        "old.custom.yaml" to "replacement", BackupManager.META_PREFIX + "settings.json" to "{}",
    )

    private fun file(root: File, path: String, text: String): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun failingJournalIo(phase: String, onlyW: Boolean, moves: MutableList<String>): RestoreTransactionIo {
        val defaults = RestoreTransactionIo()
        return RestoreTransactionIo(openJournal = { journal ->
            if (phase == "open") throw java.io.IOException("injected journal open failure")
            val delegate = defaults.openJournal(journal)
            var line = ""
            fun fail(currentPhase: String) {
                if (phase == currentPhase && (!onlyW || line.startsWith("W "))) throw java.io.IOException("injected journal failure")
            }
            object : RestoreJournalWriter {
                override fun write(bytes: ByteArray) { line = bytes.toString(Charsets.UTF_8); fail("write"); delegate.write(bytes) }
                override fun flush() { fail("flush"); delegate.flush() }
                override fun sync() { fail("sync"); delegate.sync() }
                override fun close() { delegate.close(); fail("close") }
            }
        }, move = { source, destination ->
            moves += source.name
            defaults.move(source, destination)
        })
    }

    /** 测试阶段复用现有私有重放入口，避免 discard 的另一个缺陷掩盖重试问题。 */
    private fun replayRollback(files: File, rollback: File, journal: File): Boolean {
        val method = BackupManager::class.java.getDeclaredMethod(
            "rollbackByJournal", File::class.java, File::class.java, File::class.java,
        ).apply { isAccessible = true }
        return method.invoke(BackupManager, files, rollback, journal) as Boolean
    }

    private fun settingsOnlyArchive(): ByteArray = archiveOf(BackupManager.META_PREFIX + "settings.json" to "{}")

    private fun archiveOf(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
