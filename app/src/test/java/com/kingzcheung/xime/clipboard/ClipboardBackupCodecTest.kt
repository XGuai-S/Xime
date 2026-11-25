package com.kingzcheung.xime.clipboard

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipboardBackupCodecTest {
    @get:Rule val temp = TemporaryFolder()
    private val imageBytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
    private val imageHash = ClipboardBackupCodec.sha256(imageBytes)
    private fun image() = ClipboardBackupRecord(
        type = "image", imageHash = imageHash, imagePath = "clipboard_images/$imageHash.png",
        mimeType = "image/png", sizeBytes = imageBytes.size.toLong(), width = 1, height = 1,
        timestamp = 42, isPinned = true, consumed = true,
    )
    private fun rejects(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test fun `archive counts come from the written snapshot excluding otp and separating quick send`() {
        val files = temp.newFolder()
        ClipboardImageStore(ClipboardImageStore.rootDirOf(files)).save(imageBytes, imageHash, "png")
        val ordinary = ClipboardBackupRecord(text = "private-body")
        val quick = ordinary.copy(isQuickSend = true, code = "private-code")
        val records = listOf(ordinary, quick, quick.copy(code = "another-code"), image(),
            ordinary.copy(type = "otp"), quick.copy(type = "otp"))
        val output = ByteArrayOutputStream()
        val counts = ZipOutputStream(output).use { zip -> ClipboardBackupCodec.writeArchive(files, records, zip, "_xime_backup/") }
        assertEquals(ClipboardBackupCounts(1, 1, 2), counts)
        assertEquals(counts, ClipboardBackupCodec.snapshotCounts(records))
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertNotNull(zip.nextEntry)
            val decoded = ClipboardBackupCodec.decode(zip.readBytes())
            assertEquals(counts, ClipboardBackupCodec.snapshotCounts(decoded))
        }
        assertFalse(counts.toString().contains("private"))
        assertEquals(ClipboardBackupCounts(0, 0, 0), ClipboardBackupCodec.snapshotCounts(emptyList()))
    }

    @Test fun `failed clipboard resource write returns no successful snapshot counts`() {
        val files = temp.newFolder()
        var counts: ClipboardBackupCounts? = null
        val result = runCatching {
            ZipOutputStream(ByteArrayOutputStream()).use { zip ->
                counts = ClipboardBackupCodec.writeArchive(files, listOf(image()), zip, "_xime_backup/")
            }
        }
        assertTrue(result.isFailure)
        assertNull(counts)
    }

    @Test fun `text and quick send round trip all metadata independently`() {
        val history = ClipboardBackupRecord(text = "同文\n🙂", timestamp = 100, isPinned = true, consumed = true)
        val quick = history.copy(code = "dh", timestamp = 200, isQuickSend = true, consumed = false)
        val secondQuick = quick.copy(text = "第二条", code = "dz", timestamp = 300)
        val records = listOf(secondQuick, quick.copy(code = "same_text_other_code"), quick, history)
        assertEquals(records, ClipboardBackupCodec.decode(ClipboardBackupCodec.encode(records)))
    }

    @Test fun `export excludes otp including forged quick send otp`() {
        val text = ClipboardBackupRecord(text = "普通123456")
        val otp = text.copy(type = "otp", text = "987654")
        val decoded = ClipboardBackupCodec.decode(ClipboardBackupCodec.encode(listOf(text, otp, otp.copy(isQuickSend = true))))
        assertEquals(listOf(text), decoded)
        assertFalse(ClipboardBackupCodec.encode(listOf(text, otp)).toString(Charsets.UTF_8).contains("987654"))
    }

    @Test fun `import rejects otp and unknown record types`() {
        for (type in listOf("otp", "unknown")) {
            rejects { ClipboardBackupCodec.decode("""{"version":1,"entries":[{"type":"$type","text":"secret"}]}""".toByteArray()) }
        }
    }

    @Test fun `merge keeps local metadata and separates ordinary from quick send`() {
        val localQuick = ClipboardBackupRecord(text = "同文", code = "local", timestamp = 900, isQuickSend = true, isPinned = true)
        val incomingQuick = localQuick.copy(timestamp = 10, isPinned = false)
        val differentCode = incomingQuick.copy(code = "other")
        val ordinary = incomingQuick.copy(isQuickSend = false, code = "", consumed = true)
        val newQuick = localQuick.copy(text = "新快发", code = "new", timestamp = 20)
        assertEquals(listOf(differentCode, ordinary, newQuick), ClipboardBackupCodec.missingRecords(
            listOf(localQuick), listOf(incomingQuick, differentCode, ordinary, ordinary.copy(timestamp = 100), newQuick),
        ))
        assertEquals("local", localQuick.code)
        assertEquals(900L, localQuick.timestamp)
    }

    @Test fun `same timestamp quick send round trip preserves order and distinct codes`() {
        val records = listOf(
            ClipboardBackupRecord(text = "同文", code = "second", timestamp = 123, isQuickSend = true),
            ClipboardBackupRecord(text = "同文", code = "first", timestamp = 123, isQuickSend = true),
            ClipboardBackupRecord(text = "第三条", code = "third", timestamp = 122, isQuickSend = true),
        )
        val decoded = ClipboardBackupCodec.decode(ClipboardBackupCodec.encode(records))
        // 模拟 Room 自增 id 和 observeQuickSend 的 timestamp DESC, id DESC 查询。
        val rows = ClipboardBackupCodec.mergeInsertionOrder(decoded).mapIndexed { index, record -> index to record }
        val observed = rows.sortedWith(compareByDescending<Pair<Int, ClipboardBackupRecord>> { it.second.timestamp }
            .thenByDescending { it.first }).map { it.second }
        assertEquals(records, observed)
        assertEquals(2, observed.count { it.text == "同文" })
        assertTrue(observed.take(2).all { it.timestamp == 123L })
    }

    @Test fun `image merge keys ignore timestamps and pin state`() {
        val record = image()
        assertTrue(ClipboardBackupCodec.missingRecords(listOf(record), listOf(record.copy(timestamp = 0, isPinned = false))).isEmpty())
    }

    @Test fun `real zip and existing image store round trip use only relative declared resources`() {
        val source = temp.newFolder("source")
        val r = image()
        val store = ClipboardImageStore(ClipboardImageStore.rootDirOf(source))
        assertEquals(r.imagePath, store.save(imageBytes, imageHash, "png"))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { ClipboardBackupCodec.writeArchive(source, listOf(r), it, "_xime_backup/") }
        val staging = temp.newFolder("staging")
        val entries = mutableSetOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val rel = entry.name.removePrefix("_xime_backup/")
                val file = File(staging, rel)
                file.parentFile!!.mkdirs()
                file.writeBytes(zip.readBytes())
                if (rel != ClipboardBackupCodec.MANIFEST) entries += rel
            }
        }
        val decoded = ClipboardBackupCodec.decode(File(staging, ClipboardBackupCodec.MANIFEST).readBytes())
        ClipboardBackupCodec.validateResources(decoded, staging, entries)
        val target = temp.newFolder("target")
        ClipboardBackupCodec.restoreImages(target, decoded, staging)
        assertArrayEquals(imageBytes, File(target, r.imagePath).readBytes())
        ClipboardBackupCodec.restoreImages(target, decoded, staging)
        assertArrayEquals(imageBytes, File(target, r.imagePath).readBytes())
    }

    @Test fun `resource validation rejects missing undeclared wrong size and wrong hash`() {
        val staging = temp.newFolder()
        val r = image()
        val entry = ClipboardBackupCodec.imageEntry(r.imagePath)
        rejects { ClipboardBackupCodec.validateResources(listOf(r), staging, setOf(entry)) }
        val file = File(staging, entry).apply { parentFile!!.mkdirs(); writeBytes(imageBytes) }
        rejects { ClipboardBackupCodec.validateResources(listOf(r), staging, emptySet()) }
        rejects { ClipboardBackupCodec.validateResources(emptyList(), staging, setOf(entry)) }
        rejects { ClipboardBackupCodec.validateResources(listOf(r.copy(sizeBytes = 1)), staging, setOf(entry)) }
        file.writeBytes(imageBytes.reversedArray())
        rejects { ClipboardBackupCodec.validateResources(listOf(r), staging, setOf(entry)) }
    }

    @Test fun `local corrupt file is never overwritten on image restore or export`() {
        val files = temp.newFolder()
        val staging = temp.newFolder()
        val r = image()
        val corrupt = imageBytes.reversedArray()
        File(files, r.imagePath).apply { parentFile!!.mkdirs(); writeBytes(corrupt) }
        File(staging, ClipboardBackupCodec.imageEntry(r.imagePath)).apply { parentFile!!.mkdirs(); writeBytes(imageBytes) }
        rejects { ClipboardBackupCodec.restoreImages(files, listOf(r), staging) }
        rejects { ZipOutputStream(ByteArrayOutputStream()).use { ClipboardBackupCodec.writeArchive(files, listOf(r), it, "_xime_backup/") } }
        assertArrayEquals(corrupt, File(files, r.imagePath).readBytes())
    }

    @Test fun `restricted image names reject rooted traversal backslashes and other storage`() {
        val names = listOf(
            "/clipboard_images/$imageHash.png", "../clipboard_images/$imageHash.png",
            "clipboard_images/../$imageHash.png", "clipboard_images/sub/$imageHash.png",
            "clipboard_images\\$imageHash.png", "clipboard_images//$imageHash.png",
            "other/$imageHash.png", "clipboard_images/$imageHash.svg",
        )
        names.forEach { path ->
            assertFalse(path, ClipboardBackupCodec.isImagePath(path))
            rejects { ClipboardBackupCodec.encode(listOf(image().copy(imagePath = path))) }
        }
        assertFalse(ClipboardBackupCodec.isImageEntry("clipboard/images/../manifest.json"))
        rejects { ClipboardBackupCodec.encode(listOf(image().copy(imageHash = "0".repeat(64)))) }
    }

    @Test fun `manifest record count size version and image bounds are enforced`() {
        rejects { ClipboardBackupCodec.decode(ByteArray(ClipboardBackupCodec.MAX_MANIFEST_BYTES + 1)) }
        rejects { ClipboardBackupCodec.decode("""{"version":2,"entries":[]}""".toByteArray()) }
        rejects { ClipboardBackupCodec.encode(List(ClipboardBackupCodec.MAX_RECORDS + 1) { ClipboardBackupRecord(text = "x") }) }
        for (size in listOf(0L, -1L, ClipboardBackupCodec.MAX_IMAGE_BYTES + 1)) {
            rejects { ClipboardBackupCodec.encode(listOf(image().copy(sizeBytes = size))) }
        }
        rejects { ClipboardBackupCodec.encode(listOf(image().copy(width = 4097))) }
        rejects { ClipboardBackupCodec.encode(listOf(image().copy(isQuickSend = true))) }
        rejects { ClipboardBackupCodec.encode(listOf(image().copy(mimeType = "text/plain"))) }
        val images = (1..6).map { n ->
            val hash = n.toString().padStart(64, '0')
            image().copy(imageHash = hash, imagePath = "clipboard_images/$hash.png", sizeBytes = ClipboardBackupCodec.MAX_IMAGE_BYTES)
        }
        rejects { ClipboardBackupCodec.encode(images) }
        val boundary = image().copy(sizeBytes = ClipboardBackupCodec.MAX_IMAGE_BYTES)
        assertEquals(listOf(boundary), ClipboardBackupCodec.decode(ClipboardBackupCodec.encode(listOf(boundary))))
    }
}
