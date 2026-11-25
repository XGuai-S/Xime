package com.kingzcheung.xime.settings

import com.kingzcheung.xime.SymlinkSupport
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreMirrorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `only restored files are published without deleting visible only edits`() {
        val source = temp.newFolder()
        val visible = temp.newFolder()
        source.resolve("restored.custom.yaml").writeText("restored")
        source.resolve("unrelated.custom.yaml").writeText("private-old")
        visible.resolve("visible-only.custom.yaml").writeText("user-work")
        visible.resolve("unrelated.custom.yaml").writeText("user-new")
        val result = RestoreMirror.copy(source, visible, setOf("restored.custom.yaml"), mapOf("unrelated.custom.yaml" to "baseline"))
        assertEquals(1, result.copied)
        assertEquals("restored", visible.resolve("restored.custom.yaml").readText())
        assertEquals("user-work", visible.resolve("visible-only.custom.yaml").readText())
        assertEquals("user-new", visible.resolve("unrelated.custom.yaml").readText())
        assertEquals("baseline", result.baseline["unrelated.custom.yaml"])
    }

    @Test fun `failed publication preserves old baseline and other visible files`() {
        val source = temp.newFolder()
        val visible = temp.newFolder()
        source.resolve("file.yaml").writeText("new")
        visible.resolve("file.yaml").writeText("old")
        val result = RestoreMirror.copy(source, visible, setOf("file.yaml", "missing.yaml"), mapOf("file.yaml" to "old-hash")) { _, _ ->
            throw IOException("fixture failure")
        }
        assertEquals(0, result.copied)
        assertEquals(mapOf("file.yaml" to "old-hash"), result.baseline)
        assertEquals("old", visible.resolve("file.yaml").readText())
    }

    @Test fun `empty restore scope never scans or alters visible files`() {
        val source = temp.newFolder()
        val visible = temp.newFolder()
        visible.resolve("only.yaml").writeText("keep")
        val result = RestoreMirror.copy(source, visible, emptySet(), mapOf("only.yaml" to "keep-hash"))
        assertEquals(0, result.copied)
        assertEquals("keep", visible.resolve("only.yaml").readText())
        assertEquals("keep-hash", result.baseline["only.yaml"])
    }

    @Test fun `unsafe paths and links never publish or advance baseline`() {
        val source = temp.newFolder()
        val visible = temp.newFolder()
        val external = temp.newFile().apply { writeText("outside") }
        SymlinkSupport.create(source.resolve("link.yaml"), external)
        var calls = 0
        val result = RestoreMirror.copy(source, visible, setOf("../outside.yaml", "link.yaml"), emptyMap()) { _, _ -> calls++ }
        assertEquals(0, calls)
        assertTrue(result.baseline.isEmpty())
        assertEquals("outside", external.readText())
    }

    @Test fun `mirror root link cannot redirect publication`() {
        val source = temp.newFolder()
        source.resolve("file.yaml").writeText("new")
        val parent = temp.newFolder()
        val outside = temp.newFolder()
        outside.resolve("file.yaml").writeText("keep")
        val link = parent.resolve("rime")
        SymlinkSupport.create(link, outside)
        val result = RestoreMirror.copy(source, link, setOf("file.yaml"), mapOf("file.yaml" to "old-hash"))
        assertEquals(0, result.copied)
        assertEquals("keep", outside.resolve("file.yaml").readText())
        assertEquals("old-hash", result.baseline["file.yaml"])
    }

    @Test fun `hidden parent and xime configuration channels are excluded`() {
        val source = temp.newFolder()
        val visible = temp.newFolder()
        source.resolve(".hidden").mkdirs()
        source.resolve(".hidden/file.yaml").writeText("private")
        source.resolve("xime").mkdirs()
        source.resolve("xime/data.txt").writeText("private")
        var calls = 0
        val result = RestoreMirror.copy(source, visible, setOf(".hidden/file.yaml", "xime/data.txt"), emptyMap()) { _, _ -> calls++ }
        assertEquals(0, calls)
        assertTrue(result.baseline.isEmpty())
    }

    @Test fun `real restore outcome contains only committed rime paths`() {
        val archive = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                for ((name, body) in listOf("fresh.custom.yaml" to "new", "nested/data.txt" to "data")) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val result = BackupManager.restoreArchiveOutcome(temp.newFolder(), { _, _ -> }, archive).getOrThrow()
        assertEquals(setOf("fresh.custom.yaml", "nested/data.txt"), result.restoredRimePaths)
    }

    @Test fun `mixed publication reports failure while committing successful baseline only`() {
        val source = temp.newFolder()
        val visible = temp.newFolder()
        source.resolve("good.yaml").writeText("new-good")
        source.resolve("bad.yaml").writeText("new-bad")
        val result = RestoreMirror.copy(source, visible, setOf("good.yaml", "bad.yaml"), mapOf("bad.yaml" to "old-hash")) { from, to ->
            if (from.name == "bad.yaml") throw IOException("fixture")
            from.copyTo(to)
        }
        assertEquals(1, result.copied)
        assertEquals(setOf("bad.yaml"), result.failedPaths)
        assertEquals("old-hash", result.baseline["bad.yaml"])
        assertNotNull(result.baseline["good.yaml"])
        assertEquals("new-good", visible.resolve("good.yaml").readText())
    }

    @Test fun `publisher cancellation propagates instead of failed path classification`() {
        val source = temp.newFolder()
        source.resolve("file.yaml").writeText("new")
        try {
            RestoreMirror.copy(source, temp.newFolder(), setOf("file.yaml"), emptyMap()) { _, _ ->
                throw kotlinx.coroutines.CancellationException("cancel")
            }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun `host uses restored paths without mirror deletion or full scan`() {
        val file = com.kingzcheung.xime.RepoFiles.file("app/src/main/java/com/kingzcheung/xime/settings/RimeUserFileSync.kt").readText()
        val method = file.substringAfter("fun forceSyncPrivateToVisible(").substringBefore("internal val RUNTIME_EXCLUDED_DIRS")
        assertTrue(method.contains("RestoreMirror.copy("))
        assertFalse(method.contains("file.delete()"))
        assertFalse(method.contains("walkTopDown()"))
        val backup = com.kingzcheung.xime.RepoFiles.file("app/src/main/java/com/kingzcheung/xime/settings/BackupManager.kt").readText()
        assertTrue(backup.contains("RimeUserFileSync.withRestoreSyncLock"))
        assertTrue(backup.contains("val paths = outcome.restoredRimePaths.toMutableSet()"))
        assertTrue(backup.contains("if (\"default.custom.yaml\" in paths)"))
        assertTrue(backup.contains("if (SchemaManager.applyEnabledSchemasToDefaultYaml(context)) paths += \"default.yaml\""))
        assertTrue(backup.contains("plan.filter { it.startsWith(\"rime/\") }"))
    }
}
