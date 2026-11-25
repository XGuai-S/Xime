package com.kingzcheung.xime.settings

import com.kingzcheung.xime.clipboard.ClipboardBackupCodec
import com.kingzcheung.xime.clipboard.ClipboardBackupRecord
import com.kingzcheung.xime.clipboard.ClipboardImageStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class BackupRunLogTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `run ids isolate concurrent and delayed events and preserve terminal state`() {
        val log = BackupRunLogStore { 100L }
        val old = log.begin(BackupRunTarget.LOCAL)
        log.entryPacked(old, BackupArchiveCategory.SETTINGS)
        val latest = log.begin(BackupRunTarget.CLOUD)
        assertTrue(latest > old)
        log.stage(old, BackupRunStage.UPLOADING)
        log.finish(old, BackupRunOutcome.SUCCESS)
        assertTrue(log.state.value.running)
        assertTrue(log.state.value.counts.isEmpty())
        log.entryPacked(latest, BackupArchiveCategory.PLUGINS)
        log.finish(latest, BackupRunOutcome.UPLOAD_FAILED)
        val finished = log.state.value
        assertFalse(finished.running)
        assertEquals(BackupLogLevel.ERROR, finished.entries.last().level)
        log.finish(latest, BackupRunOutcome.SUCCESS)
        log.entryPacked(latest, BackupArchiveCategory.SETTINGS)
        assertEquals(finished, log.state.value)
    }

    @Test fun `archive megabytes use two decimals and root locale while retaining raw bytes`() {
        val log = BackupRunLogStore { 1L }
        val id = log.begin(BackupRunTarget.LOCAL)
        assertEquals("0.00 MB", BackupRunLogStore.formatArchiveMegabytes(0))
        assertEquals("1.50 MB", BackupRunLogStore.formatArchiveMegabytes((1024L * 1024L * 3L) / 2L))
        assertEquals("10240.00 MB", BackupRunLogStore.formatArchiveMegabytes(10L * 1024L * 1024L * 1024L))
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("ar", "EG"))
            assertEquals("1.50 MB", BackupRunLogStore.formatArchiveMegabytes(3L * 1024L * 1024L / 2L))
        } finally {
            java.util.Locale.setDefault(original)
        }
        val bytes = 3L * 1024L * 1024L / 2L
        log.archiveReady(id, bytes)
        assertEquals(bytes, log.state.value.archiveBytes)
        assertTrue(log.state.value.entries.last().message.endsWith("1.50 MB"))
    }

    @Test fun `entries remain bounded and new run clears old entries counts and bytes`() {
        val log = BackupRunLogStore { 1L }
        val id = log.begin(BackupRunTarget.LOCAL)
        repeat(300) { log.stage(id, BackupRunStage.PACKING) }
        log.entryPacked(id, BackupArchiveCategory.RIME_CONFIGURATION)
        log.archiveReady(id, 1234)
        log.finish(id, BackupRunOutcome.SUCCESS)
        assertEquals(80, log.state.value.entries.size)
        assertTrue(log.state.value.entries.any { it.message.contains("0.00 MB") })
        assertEquals("完整备份成功", log.state.value.entries.last().message)
        assertEquals(1234L, log.state.value.archiveBytes)
        val next = log.begin(BackupRunTarget.CLOUD)
        assertEquals(next, log.state.value.runId)
        assertEquals(1, log.state.value.entries.size)
        assertTrue(log.state.value.counts.isEmpty())
        assertEquals(0L, log.state.value.archiveBytes)
        assertNull(log.state.value.outcome)
    }

    @Test fun `count updates are thread safe and do not append one line per file`() {
        val log = BackupRunLogStore { 1L }
        val id = log.begin(BackupRunTarget.CLOUD)
        val gate = CountDownLatch(1)
        val threads = List(4) {
            Thread { gate.await(); repeat(100) { log.entryPacked(id, BackupArchiveCategory.PLUGINS) } }.apply { start() }
        }
        gate.countDown()
        threads.forEach { it.join() }
        assertEquals(400, log.state.value.counts[BackupArchiveCategory.PLUGINS])
        assertEquals(2, log.state.value.entries.size)
        log.archiveReady(id, 50)
        assertTrue(log.state.value.entries.any { it.message == "已打包：插件包与注册信息 400 个文件" })
    }

    @Test fun `zip statistics reflect closed real entries and ignore directories and repeated closes`() {
        val log = BackupRunLogStore { 1L }
        val id = log.begin(BackupRunTarget.LOCAL)
        val output = ByteArrayOutputStream()
        val names = listOf(
            "one.custom.yaml", "two.custom.yaml", "shape.schema.yaml", "words.dict.yaml",
            "sync/device/dict.userdb.txt", "word_freq.txt", "_xime_backup/settings.json",
            "_xime_backup/plugin_configs/tool.json", "_xime_backup/plugins/tool/main.js",
            "_xime_backup/plugins/tool/manifest.json", "_xime_backup/manifest.json",
            "_xime_backup/clipboard/manifest.json", "_xime_backup/clipboard/images/hash.png",
        )
        BackupArchiveOutputStream(output) { log.entryPacked(id, it) }.use { zip ->
            zip.putNextEntry(ZipEntry("directory/")); zip.closeEntry()
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry(); zip.closeEntry()
            }
        }
        val state = log.state.value
        assertEquals(names.size, state.counts.values.sum())
        assertEquals(2, state.counts[BackupArchiveCategory.RIME_CONFIGURATION])
        assertEquals(2, state.counts[BackupArchiveCategory.SCHEME_AND_DICTIONARY])
        assertEquals(2, state.counts[BackupArchiveCategory.PLUGINS])
        assertEquals(2, state.counts[BackupArchiveCategory.SETTINGS])
        assertEquals(1, state.counts[BackupArchiveCategory.USER_DATA])
        assertEquals(1, state.counts[BackupArchiveCategory.USER_DICTIONARY])
        assertEquals(1, state.counts[BackupArchiveCategory.CLIPBOARD_RESOURCES])
        assertEquals(2, state.counts[BackupArchiveCategory.BACKUP_METADATA])
        assertNull(state.counts[BackupArchiveCategory.OTHER])
        log.archiveReady(id, output.size().toLong())
        assertEquals(output.size().toLong(), log.state.value.archiveBytes)
        var actual = 0
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; if (!entry.isDirectory) actual++ }
        }
        assertEquals(actual, state.counts.values.sum())
    }

    @Test fun `source read failure followed by use finish never counts incomplete entries`() {
        for (partial in listOf(false, true)) {
            val categories = mutableListOf<BackupArchiveCategory>()
            val output = ByteArrayOutputStream()
            val failure = IOException("source read failed")
            val result = runCatching {
                BackupArchiveOutputStream(output) { categories += it }.use { zip ->
                    zip.putNextEntry(ZipEntry("complete.custom.yaml"))
                    zip.write(byteArrayOf(1)); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("_xime_backup/plugins/tool/incomplete.js"))
                    val input = object : java.io.InputStream() {
                        var delivered = false
                        override fun read(): Int = throw failure
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            if (partial && !delivered) { bytes[offset] = 7; delivered = true; return 1 }
                            throw failure
                        }
                    }
                    input.use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(listOf(BackupArchiveCategory.RIME_CONFIGURATION), categories)
        }
    }

    @Test fun `cancelled write followed by use finish does not count zero or partial entries`() {
        for (partial in listOf(false, true)) {
            val categories = mutableListOf<BackupArchiveCategory>()
            var cancelled = false
            val result = runCatching {
                BackupArchiveOutputStream(ByteArrayOutputStream(), {
                    if (cancelled) throw CancellationException("cancelled")
                }) { categories += it }.use { zip ->
                    zip.putNextEntry(ZipEntry("incomplete.custom.yaml"))
                    if (partial) zip.write(byteArrayOf(1))
                    cancelled = true
                    zip.write(byteArrayOf(2))
                    zip.closeEntry()
                }
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertTrue(categories.isEmpty())
        }
    }

    @Test fun `only explicit closes count while put next entry and finish implicit closes do not`() {
        val categories = mutableListOf<BackupArchiveCategory>()
        BackupArchiveOutputStream(ByteArrayOutputStream()) { categories += it }.use { zip ->
            zip.putNextEntry(ZipEntry("implicit.custom.yaml")); zip.write(byteArrayOf(1))
            zip.putNextEntry(ZipEntry("explicit.schema.yaml")); zip.write(byteArrayOf(2)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("finish.custom.yaml")); zip.write(byteArrayOf(3))
            zip.finish()
        }
        assertEquals(listOf(BackupArchiveCategory.SCHEME_AND_DICTIONARY), categories)
    }

    @Test fun `archive stream close failure does not emit completed archive summaries`() {
        val log = BackupRunLogStore { 1L }
        val id = log.begin(BackupRunTarget.LOCAL)
        val output = object : ByteArrayOutputStream() {
            override fun close() { throw IOException("archive close failed") }
        }
        val result = runCatching {
            BackupArchiveOutputStream(output) { log.entryPacked(id, it) }.use { zip ->
                zip.putNextEntry(ZipEntry("complete.custom.yaml")); zip.write(byteArrayOf(1)); zip.closeEntry()
            }
            // 与生产入口一样，只在 ZIP 及底层流全部关流成功后生成最终汇总。
            log.archiveReady(id, output.size().toLong())
        }
        assertTrue(result.isFailure)
        log.finish(id, BackupRunOutcome.PACKING_FAILED)
        assertEquals(1, log.state.value.counts[BackupArchiveCategory.RIME_CONFIGURATION])
        assertFalse(log.state.value.entries.any { it.message.startsWith("已打包：") || it.message.startsWith("完整备份打包完成：") })
        assertEquals(BackupRunOutcome.PACKING_FAILED, log.state.value.outcome)
    }

    @Test fun `failed entry close is never counted`() {
        val categories = mutableListOf<BackupArchiveCategory>()
        val output = object : OutputStream() {
            var fail = false
            override fun write(value: Int) { if (fail) throw IOException("secret failure") }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { if (fail) throw IOException("secret failure") }
        }
        val zip = BackupArchiveOutputStream(output) { categories += it }
        zip.putNextEntry(ZipEntry("test.custom.yaml"))
        zip.write(byteArrayOf(1, 2, 3))
        assertTrue(categories.isEmpty())
        output.fail = true
        assertTrue(runCatching { zip.closeEntry() }.isFailure)
        assertTrue(categories.isEmpty())
        runCatching { zip.close() }
    }

    @Test fun `logger callback failure cannot fail a valid archive`() {
        val output = ByteArrayOutputStream()
        BackupArchiveOutputStream(output) { error("secret logger failure") }.use { zip ->
            zip.putNextEntry(ZipEntry("settings.custom.yaml")); zip.write(byteArrayOf(7)); zip.closeEntry()
        }
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertNotNull(zip.nextEntry)
            assertArrayEquals(byteArrayOf(7), zip.readBytes())
        }
        val brokenClock = BackupRunLogStore { error("secret clock failure") }
        assertEquals(0L, brokenClock.begin(BackupRunTarget.CLOUD))
        brokenClock.stage(0L, BackupRunStage.UPLOADING)
        brokenClock.finish(0L, BackupRunOutcome.SUCCESS)
        assertEquals(BackupRunState(), brokenClock.state.value)
    }

    @Test fun `log messages contain only fixed templates categories and numeric totals`() {
        val secrets = listOf("clipboard-body-secret", "quick-code-secret", "otp-secret-123456", "https://private.example/token", "/sdcard/private/secret-image.png")
        val log = BackupRunLogStore { 1L }
        val id = log.begin(BackupRunTarget.CLOUD)
        val output = ByteArrayOutputStream()
        BackupArchiveOutputStream(output) { log.entryPacked(id, it) }.use { zip ->
            secrets.forEach { secret ->
                zip.putNextEntry(ZipEntry("_xime_backup/plugin_configs/$secret.json"))
                zip.write(secret.toByteArray()); zip.closeEntry()
            }
        }
        log.clipboardSnapshot(id, 2, 3, 4)
        log.archiveReady(id, output.size().toLong())
        log.dictionarySnapshotUnavailable(id)
        log.remoteCleanupFailed(id)
        log.finish(id, BackupRunOutcome.UPLOAD_FAILED)
        val displayed = log.state.value.toString()
        secrets.forEach { secret -> assertFalse(secret, displayed.contains(secret)) }
        assertTrue(log.state.value.entries.any { it.message.contains("普通文本 2 条，图片 3 条，快捷发送 4 条") })
    }

    @Test fun `cancellation and every failure are terminal and cannot later become success`() {
        val outcomes = BackupRunOutcome.entries.filter { it != BackupRunOutcome.SUCCESS }
        outcomes.forEach { outcome ->
            val log = BackupRunLogStore { 1L }
            val id = log.begin(BackupRunTarget.CLOUD)
            log.finish(id, outcome)
            val terminal = log.state.value
            assertFalse(terminal.running)
            assertEquals(outcome, terminal.outcome)
            assertEquals(if (outcome == BackupRunOutcome.CANCELLED) BackupLogLevel.WARNING else BackupLogLevel.ERROR,
                terminal.entries.last().level)
            log.finish(id, BackupRunOutcome.SUCCESS)
            assertEquals(terminal, log.state.value)
        }
    }

    @Test fun `zip cancellation probe propagates before more payload is written`() {
        var cancelled = false
        val output = ByteArrayOutputStream()
        val zip = BackupArchiveOutputStream(output, { if (cancelled) throw CancellationException("private cancellation") }) { }
        zip.putNextEntry(ZipEntry("test.custom.yaml"))
        cancelled = true
        val result = runCatching { zip.write(byteArrayOf(1, 2, 3)) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        runCatching { zip.close() }
    }

    @Test fun `legacy local save atomically replaces only a complete new archive`() {
        val source = temp.newFile("source.zip").apply { writeText("complete new archive") }
        val folder = temp.newFolder()
        val target = File(folder, "backup.zip").apply { writeText("valid old archive") }
        BackupLocalFileCommit.save(source, target)
        assertEquals("complete new archive", target.readText())
        assertEquals("complete new archive", source.readText())
        assertEquals(listOf("backup.zip"), folder.list()!!.toList())
    }

    @Test fun `cancelled local copy cleans only its temporary file and preserves old archive`() {
        val source = temp.newFile("cancel-source.zip").apply { writeBytes(ByteArray(128 * 1024) { 7 }) }
        val folder = temp.newFolder()
        val target = File(folder, "backup.zip").apply { writeText("valid old archive") }
        var checks = 0
        val result = runCatching {
            BackupLocalFileCommit.save(source, target, checkActive = {
                checks++
                if (checks == 2) throw CancellationException("cancelled")
            })
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals("valid old archive", target.readText())
        assertEquals(listOf("backup.zip"), folder.list()!!.toList())
    }

    @Test fun `unsupported atomic commit or failed commit never falls back to truncating old archive`() {
        val source = temp.newFile("commit-source.zip").apply { writeText("new archive") }
        val failures = listOf(IOException("commit denied"), AtomicMoveNotSupportedException("source", "target", "unsupported"))
        failures.forEach { failure ->
            val folder = temp.newFolder()
            val target = File(folder, "backup.zip").apply { writeText("valid old archive") }
            val result = runCatching {
                BackupLocalFileCommit.save(source, target, commit = { temporary, destination ->
                    assertEquals("new archive", temporary.readText())
                    assertEquals(target, destination)
                    throw failure
                })
            }
            assertSame(failure, result.exceptionOrNull())
            assertEquals("valid old archive", target.readText())
            assertEquals(listOf("backup.zip"), folder.list()!!.toList())
        }
    }

    @Test fun `failed source read does not touch existing local backup`() {
        val source = File(temp.newFolder(), "missing.zip")
        val folder = temp.newFolder()
        val target = File(folder, "backup.zip").apply { writeText("valid old archive") }
        assertTrue(runCatching { BackupLocalFileCommit.save(source, target) }.isFailure)
        assertEquals("valid old archive", target.readText())
        assertEquals(listOf("backup.zip"), folder.list()!!.toList())
    }

    @Test fun `production clipboard section gate writes a real ZIP only when enabled`() {
        for (enabled in listOf(false, true)) {
            val files = temp.newFolder()
            val imageBytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
            val hash = ClipboardBackupCodec.sha256(imageBytes)
            val imagePath = ClipboardImageStore(ClipboardImageStore.rootDirOf(files))
                .save(imageBytes, hash, "png")!!
            val id = BackupRunLog.begin(BackupRunTarget.LOCAL)
            val output = ByteArrayOutputStream()
            var reads = 0
            BackupArchiveOutputStream(output) { BackupRunLog.entryPacked(id, it) }.use { zip ->
                zip.putNextEntry(ZipEntry("test.custom.yaml"))
                zip.write("patch: {}".toByteArray())
                zip.closeEntry()
                val counts = RimeExportManager.packClipboardSection(enabled, id) {
                    check(enabled) { "disabled backup accessed data" }
                    reads++
                    ClipboardBackupCodec.writeArchive(files, listOf(
                        ClipboardBackupRecord(text = "ordinary fixture"),
                        ClipboardBackupRecord(text = "quick fixture", code = "qf", isQuickSend = true),
                        ClipboardBackupRecord(type = "image", imagePath = imagePath, imageHash = hash,
                            mimeType = "image/png", sizeBytes = imageBytes.size.toLong()),
                        ClipboardBackupRecord(type = "otp", text = "000000"),
                    ), zip, BackupManager.META_PREFIX)
                }
                if (enabled) {
                    assertEquals(1, counts!!.texts)
                    assertEquals(1, counts.images)
                    assertEquals(1, counts.quickSend)
                } else assertNull(counts)
            }
            assertEquals(if (enabled) 1 else 0, reads)
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            val clipboardNames = entries.keys.filter { it.startsWith(BackupManager.META_PREFIX + ClipboardBackupCodec.PREFIX) }
            if (enabled) {
                assertEquals(2, clipboardNames.size)
                val records = ClipboardBackupCodec.decode(entries.getValue(BackupManager.META_PREFIX + ClipboardBackupCodec.MANIFEST))
                assertEquals(3, records.size)
                assertEquals("qf", records.single { it.isQuickSend }.code)
                assertTrue(records.none { it.type == "otp" })
            } else {
                assertTrue(clipboardNames.isEmpty())
                assertTrue(BackupRunLog.state.value.entries.any { it.message.contains("跳过剪贴板数据") })
            }
            BackupRunLog.finish(id, BackupRunOutcome.SUCCESS)
        }
    }

    @Test fun `invalid negative metrics do not alter the log`() {
        val log = BackupRunLogStore { 1L }
        val id = log.begin(BackupRunTarget.LOCAL)
        val previous = log.state.value
        log.clipboardSnapshot(id, -1, 2, 3)
        log.archiveReady(id, -1)
        assertEquals(previous, log.state.value)
    }
}
