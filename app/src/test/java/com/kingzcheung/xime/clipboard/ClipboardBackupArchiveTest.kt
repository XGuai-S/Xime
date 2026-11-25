package com.kingzcheung.xime.clipboard

import com.kingzcheung.xime.settings.BackupManager
import com.kingzcheung.xime.settings.UserConfigDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 真实 ZIP -> BackupManager staging -> codec -> 临时图片存储，不读取 Android/日用数据库。 */
class ClipboardBackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private val prefix = BackupManager.META_PREFIX
    private val manifest = prefix + ClipboardBackupCodec.MANIFEST
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()
    private fun text(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test fun `old package without clipboard retains existing records and skips merge`() {
        val files = temp.newFolder()
        var called = false
        val result = BackupManager.restoreArchive(files, { _, _ -> }, zip(mapOf("old.custom.yaml" to text("patch: {}"))),
            clipboardRestorer = { _, _ -> called = true })
        assertTrue(result.toString(), result.isSuccess)
        assertFalse(called)
        assertEquals("patch: {}", File(files, "rime/old.custom.yaml").readText())
    }

    @Test fun `full archive merges ordinary image and quick send with original local records intact`() {
        val source = temp.newFolder("source")
        val target = temp.newFolder("target")
        val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
        val hash = ClipboardBackupCodec.sha256(bytes)
        val image = ClipboardBackupRecord(type = "image", imageHash = hash, imagePath = "clipboard_images/$hash.png",
            mimeType = "image/png", sizeBytes = bytes.size.toLong(), width = 1, height = 1, timestamp = 55)
        ClipboardImageStore(ClipboardImageStore.rootDirOf(source)).save(bytes, hash, "png")
        val local = ClipboardBackupRecord(text = "同文", isQuickSend = true, code = "local", timestamp = 999, isPinned = true)
        val records = mutableListOf(local, ClipboardBackupRecord(text = "本地独有", timestamp = 1000))
        val incoming = listOf(local.copy(code = "remote", timestamp = 10), local.copy(isQuickSend = false, code = ""),
            ClipboardBackupRecord(text = "远端快发", isQuickSend = true, code = "fd", timestamp = 500), image)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { z ->
            z.putNextEntry(ZipEntry("example.custom.yaml")); z.write(text("patch: {}")); z.closeEntry()
            ClipboardBackupCodec.writeArchive(source, incoming, z, prefix)
        }
        repeat(2) {
            val result = BackupManager.restoreArchive(target, { _, _ -> }, output.toByteArray(), clipboardRestorer = { snapshot, staging ->
                ClipboardBackupCodec.restoreImages(target, snapshot, staging)
                records += ClipboardBackupCodec.missingRecords(records, snapshot)
            })
            assertTrue(result.toString(), result.isSuccess)
        }
        assertEquals(6, records.size)
        assertEquals(local, records.first())
        assertEquals(setOf("local", "remote"), records.filter { it.isQuickSend && it.text == "同文" }.map { it.code }.toSet())
        assertTrue(records.any { it.text == "本地独有" })
        assertTrue(records.any { !it.isQuickSend && it.text == "同文" })
        assertEquals("fd", records.first { it.text == "远端快发" }.code)
        assertArrayEquals(bytes, File(target, image.imagePath).readBytes())
        assertFalse(File(target, ".restore-staging").exists())
        assertFalse(File(target, "clipboard.db").exists())
    }

    @Test fun `corrupt image size hash and missing or undeclared assets fail before any commit`() {
        val bytes = byteArrayOf(1, 2, 3)
        val hash = ClipboardBackupCodec.sha256(bytes)
        val r = ClipboardBackupRecord(type = "image", imageHash = hash, imagePath = "clipboard_images/$hash.png",
            mimeType = "image/png", sizeBytes = bytes.size.toLong())
        val entry = prefix + ClipboardBackupCodec.imageEntry(r.imagePath)
        val good = ClipboardBackupCodec.encode(listOf(r))
        val cases = listOf(
            mapOf(manifest to good),
            mapOf(entry to bytes),
            mapOf(manifest to ClipboardBackupCodec.encode(emptyList()), entry to bytes),
            mapOf(manifest to good, entry to byteArrayOf(1)),
            mapOf(manifest to good, entry to byteArrayOf(3, 2, 1)),
        )
        cases.forEach { entries -> assertRejectedBeforeCommit(entries) }
    }

    @Test fun `traversal absolute and undeclared clipboard paths fail before commit`() {
        val names = listOf(
            "clipboard/images/../../escaped.txt", "clipboard/images//etc/passwd",
            "clipboard/images/C:\\tmp\\x.png", "clipboard/../../outside", "clipboard/random.json",
            "clipboard/images/" + "a".repeat(64) + ".svg",
        )
        names.forEach { name -> assertRejectedBeforeCommit(mapOf(
            manifest to ClipboardBackupCodec.encode(emptyList()), prefix + name to text("payload"),
        )) }
    }

    @Test fun `otp future clipboard version and malformed json cannot commit prefs or files`() {
        listOf(
            """{"version":1,"entries":[{"type":"otp","text":"123456"}]}""",
            """{"version":99,"entries":[]}""", "{",
        ).forEach { json -> assertRejectedBeforeCommit(mapOf(manifest to text(json))) }
    }

    @Test fun `valid package with corrupt local image fails before rime prefs or config commit`() {
        assertLocalImageProtected(corruptLocal = true)
    }

    @Test fun `plugin metadata cannot escape plugins namespace to overwrite declared local image`() {
        for (pluginPath in listOf("plugins/../clipboard_images/", "plugins/..\\clipboard_images/", "plugins//clipboard_images/")) {
            assertLocalImageProtected(corruptLocal = false, pluginPath = pluginPath)
        }
    }

    private fun assertLocalImageProtected(corruptLocal: Boolean, pluginPath: String? = null) {
        val files = temp.newFolder()
        val bytes = byteArrayOf(1, 2, 3)
        val hash = ClipboardBackupCodec.sha256(bytes)
        val r = ClipboardBackupRecord(type = "image", imageHash = hash, imagePath = "clipboard_images/$hash.png",
            mimeType = "image/png", sizeBytes = bytes.size.toLong())
        val original = if (corruptLocal) bytes.reversedArray() else bytes
        File(files, r.imagePath).apply { parentFile!!.mkdirs(); writeBytes(original) }
        File(files, "rime/example.custom.yaml").apply { parentFile!!.mkdirs(); writeText("old") }
        val entries = linkedMapOf(
            "example.custom.yaml" to text("new"),
            UserConfigDir.UNIFIED_CONFIG_FILE to text("new config"),
            prefix + "settings.json" to text("{}"),
            manifest to ClipboardBackupCodec.encode(listOf(r)),
            prefix + ClipboardBackupCodec.imageEntry(r.imagePath) to bytes,
        )
        if (pluginPath != null) entries[prefix + pluginPath + "$hash.png"] = text("malicious overwrite")
        var prefs = false
        var config = false
        var merged = false
        val result = BackupManager.restoreArchive(files, { _, _ -> prefs = true }, zip(entries),
            visibleConfigWriter = { config = true; true }, clipboardRestorer = { _, _ -> merged = true })
        assertTrue(result.toString(), result.isFailure)
        assertFalse(prefs)
        assertFalse(config)
        assertFalse(merged)
        assertEquals("old", File(files, "rime/example.custom.yaml").readText())
        assertArrayEquals(original, File(files, r.imagePath).readBytes())
        assertFalse(File(files, ".restore-staging").exists())
    }

    @Test fun `late invalid zip entry prevents clipboard callback and rime replacement`() {
        assertRejectedBeforeCommit(linkedMapOf(
            manifest to ClipboardBackupCodec.encode(listOf(ClipboardBackupRecord(text = "new"))),
            "../../escape" to text("bad"),
        ))
    }

    @Test fun `valid clipboard package cannot silently succeed without a merge adapter`() {
        val files = temp.newFolder()
        val result = BackupManager.restoreArchive(files, { _, _ -> }, zip(mapOf(
            manifest to ClipboardBackupCodec.encode(listOf(ClipboardBackupRecord(text = "new"))),
        )))
        assertTrue(result.isFailure)
    }

    @Test fun `merge adapter failure is reported rather than swallowed`() {
        val files = temp.newFolder()
        val result = BackupManager.restoreArchive(files, { _, _ -> }, zip(mapOf(
            manifest to ClipboardBackupCodec.encode(listOf(ClipboardBackupRecord(text = "new"))),
        )), clipboardRestorer = { _, _ -> error("Room merge failed") })
        assertTrue(result.isFailure)
        // 文件已提交、剪贴板合并失败：返回固定类别的部分恢复提示（不再透传原始异常文本），
        // 界面据此能区分「哪些内容没恢复」，见 BackupManager 的 RestoreFailure.CLIPBOARD。
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("必须标明部分内容未恢复：$message", message.startsWith("文件恢复已完成，部分内容未恢复"))
        assertTrue("必须指明剪贴板类别：$message", message.contains("剪贴板"))
    }

    private fun assertRejectedBeforeCommit(entries: Map<String, ByteArray>) {
        val files = temp.newFolder()
        File(files, "rime/example.custom.yaml").apply { parentFile!!.mkdirs(); writeText("old") }
        var merged = false
        var prefs = false
        val all = linkedMapOf("example.custom.yaml" to text("new"), prefix + "settings.json" to text("{}"))
        all.putAll(entries)
        val result = BackupManager.restoreArchive(files, { _, _ -> prefs = true }, zip(all),
            clipboardRestorer = { _, _ -> merged = true })
        assertTrue(result.toString(), result.isFailure)
        assertFalse(merged)
        assertFalse(prefs)
        assertEquals("old", File(files, "rime/example.custom.yaml").readText())
        assertFalse(File(files, ".restore-staging").exists())
        assertFalse(File(files, "clipboard_images").exists())
    }
}
