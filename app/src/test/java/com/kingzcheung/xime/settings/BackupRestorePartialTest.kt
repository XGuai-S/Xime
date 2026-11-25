package com.kingzcheung.xime.settings

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRestorePartialTest {
    @get:Rule val temp = TemporaryFolder()

    private fun archive(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun `prefs failure is reported while committed files remain and later categories run`() {
        val root = temp.newFolder()
        var configWritten = false
        val result = BackupManager.restoreArchive(root, { _, _ -> throw IOException("private-error") },
            archive("restored.yaml" to "new", "_xime_backup/settings.json" to "{}", "xime.custom.yaml" to "config"),
            visibleConfigWriter = { configWritten = true; true })
        assertTrue("partial restore must not report success", result.isFailure)
        val partial = result.exceptionOrNull() as BackupManager.PartialRestoreException
        assertEquals(setOf(BackupManager.RestoreFailure.PREFERENCES), partial.failures)
        assertEquals("文件恢复已完成，部分内容未恢复：设置", partial.message)
        assertEquals("new", root.resolve("rime/restored.yaml").readText())
        assertTrue(configWritten)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("private-error"))
    }

    @Test fun `failed user table writer and failed fallback are reported`() {
        val root = temp.newFolder()
        val name = com.kingzcheung.xime.service.WordFreqStore.FILE_NAME
        root.resolve(name).mkdirs()
        val result = BackupManager.restoreArchive(root, { _, _ -> }, archive(name to "text", "restored.yaml" to "new"),
            userDataWriter = { _, _ -> false })
        assertTrue(result.isFailure)
        assertEquals("new", root.resolve("rime/restored.yaml").readText())
    }

    @Test fun `failed visible config and failed private fallback are reported`() {
        val root = temp.newFolder()
        root.resolve("rime/xime.custom.yaml").mkdirs()
        val result = BackupManager.restoreArchive(root, { _, _ -> }, archive("xime.custom.yaml" to "config"),
            visibleConfigWriter = { false })
        assertTrue(result.isFailure)
    }

    @Test fun `throwing visible writer falls back and does not prevent successful restore`() {
        val root = temp.newFolder()
        val result = BackupManager.restoreArchive(root, { _, _ -> }, archive("xime.custom.yaml" to "config"),
            visibleConfigWriter = { throw IOException("private-detail") })
        assertTrue(result.isSuccess)
        assertEquals("config", root.resolve("rime/xime.custom.yaml").readText())
    }

    @Test fun `cancellation propagates without becoming partial failure`() {
        val root = temp.newFolder()
        try {
            BackupManager.restoreArchive(root, { _, _ -> throw kotlinx.coroutines.CancellationException("cancel") },
                archive("restored.yaml" to "new", "_xime_backup/settings.json" to "{}"))
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals("new", root.resolve("rime/restored.yaml").readText())
        }
    }

    @Test fun `multiple failures have stable categories while core outcome retains committed scope`() {
        val root = temp.newFolder()
        val name = com.kingzcheung.xime.service.WordFreqStore.FILE_NAME
        root.resolve(name).mkdirs()
        val bytes = archive("restored.yaml" to "new", name to "table", "_xime_backup/settings.json" to "{}")
        val outcome = BackupManager.restoreArchiveOutcome(root, { _, _ -> throw IOException("private") }, bytes,
            userDataWriter = { _, _ -> false }).getOrThrow()
        assertEquals(setOf(BackupManager.RestoreFailure.PREFERENCES, BackupManager.RestoreFailure.USER_DATA), outcome.failures)
        assertEquals(setOf("restored.yaml"), outcome.restoredRimePaths)
    }

    @Test fun `invalid archive before file commit does not claim partial completion`() {
        val result = BackupManager.restoreArchive(temp.newFolder(), { _, _ -> }, archive("../outside.yaml" to "bad"))
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is BackupManager.PartialRestoreException)
    }

    @Test fun `preferences require successful commit and still filter sensitive authorization`() {
        for (committed in listOf(false, true)) {
            val prefs = org.mockito.kotlin.mock<android.content.SharedPreferences>()
            val editor = org.mockito.kotlin.mock<android.content.SharedPreferences.Editor>()
            org.mockito.kotlin.whenever(prefs.edit()).thenReturn(editor)
            org.mockito.kotlin.whenever(editor.putString(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(editor)
            org.mockito.kotlin.whenever(editor.commit()).thenReturn(committed)
            val failure = runCatching { BackupManager.restorePrefsJson(prefs,
                """{"regular":{"t":"s","v":"value"},"plugin_net_auth_untrusted":{"t":"s","v":"never"}}""".toByteArray()) }.exceptionOrNull()
            assertEquals(!committed, failure != null)
            org.mockito.kotlin.verify(editor).putString("regular", "value")
            org.mockito.kotlin.verify(editor, org.mockito.kotlin.never()).putString("plugin_net_auth_untrusted", "never")
            org.mockito.kotlin.verify(editor).commit()
            org.mockito.kotlin.verify(editor, org.mockito.kotlin.never()).apply()
        }
    }

    @Test fun `android workflow completes mirror and dictionary step before partial conversion`() {
        val source = com.kingzcheung.xime.RepoFiles.file("app/src/main/java/com/kingzcheung/xime/settings/BackupManager.kt").readText()
        val main = source.substringAfter("internal suspend fun restoreFromArchive(").substringBefore("private suspend fun afterRestore(")
        assertTrue(main.contains("val outcome = restoreWithMirror(context)"))
        assertTrue(main.contains("afterRestore(context, it); it }.completion()"))
        val mirror = source.substringAfter("private fun restoreWithMirror(").substringBefore("fun restoreArchive(context:")
        assertTrue(mirror.contains("restore().map { outcome ->"))
        assertTrue(mirror.contains("val failures = outcome.failures.toMutableSet()"))
        assertTrue(mirror.contains("failures += RestoreFailure.MIRROR"))
        assertFalse(mirror.contains(".completion()"))
    }

    @Test fun `successful fallback preserves compatible complete result`() {
        val root = temp.newFolder()
        val result = BackupManager.restoreArchive(root, { _, _ -> }, archive("xime.custom.yaml" to "config"),
            visibleConfigWriter = { false })
        assertTrue(result.isSuccess)
        assertEquals("config", root.resolve("rime/xime.custom.yaml").readText())
    }
}
