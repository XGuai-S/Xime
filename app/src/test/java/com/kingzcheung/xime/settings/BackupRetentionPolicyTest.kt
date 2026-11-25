package com.kingzcheung.xime.settings

import com.kingzcheung.xime.plugin.core.api.BackupDownload
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.BackupResult
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import com.kingzcheung.xime.plugin.core.model.PluginContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Executes the real cleanup driver with an in-memory provider; never accesses a server. */
class BackupRetentionPolicyTest {
    private val currentName = "Xime完整备份-2026-11-21.zip"
    private fun full(id: String, date: String, time: Long) =
        RemoteBackupEntry(id, "Xime完整备份-$date.zip", time)
    private fun current(time: Long = 100) = RemoteBackupEntry("current", currentName, time)
    private class Provider(val entries: List<RemoteBackupEntry>?) : BackupPlugin {
        val deleted = mutableListOf<String>()
        var lists = 0
        var deleteResult = true
        var cancellation: CancellationException? = null
        override fun onLoad(context: PluginContext) {}
        override fun onUnload() {}
        override suspend fun pushBackup(name: String, archive: File): BackupResult = error("not used")
        override suspend fun pullBackup(id: String): BackupDownload? = error("not used")
        override fun releaseBackup(blobId: String) {}
        override suspend fun testConnection(): String? = error("not used")
        override suspend fun listBackups(): List<RemoteBackupEntry>? { lists++; return entries }
        override suspend fun deleteBackup(id: String): Boolean {
            cancellation?.let { throw it }
            deleted += id
            return deleteResult
        }
    }
    private suspend fun cleanup(provider: Provider, keep: Int = 1, runId: Long = 0) =
        BackupManager.cleanupRemoteBackups(provider, keep, currentName, "current", runId)

    @Test fun `mixed folder never deletes word sync packages or unrelated files`() = runBlocking {
        val p = Provider(listOf(current(), RemoteBackupEntry("sync", "rime-sync-phone.zip", 90),
            RemoteBackupEntry("notes", "notes.zip", 80), full("old", "2026-11-01", 70)))
        assertTrue(cleanup(p))
        assertEquals(listOf("old"), p.deleted)
    }

    @Test fun `retention uses timestamps rather than trusting provider list order`() = runBlocking {
        val p = Provider(listOf(full("old", "2026-11-01", 10), current(), full("middle", "2026-11-15", 80)))
        assertTrue(cleanup(p, keep = 2))
        assertEquals(listOf("old"), p.deleted)
    }

    @Test fun `unknown modification time is retained instead of guessed old`() = runBlocking {
        val p = Provider(listOf(current(), full("unknown-time", "2026-10-01", 0), full("old", "2026-11-01", 70)))
        assertTrue(cleanup(p))
        assertEquals(listOf("old"), p.deleted)
    }

    @Test fun `when uploaded archive is not listed cleanup defers without deleting any history`() = runBlocking {
        val p = Provider(listOf(full("old-a", "2026-11-01", 80), full("old-b", "2026-10-01", 70)))
        assertFalse(cleanup(p))
        assertTrue(p.deleted.isEmpty())
    }

    @Test fun `new upload with unknown remote timestamp stays protected`() = runBlocking {
        val p = Provider(listOf(full("old", "2026-11-01", 100), current(time = 0)))
        assertTrue(cleanup(p))
        assertEquals(listOf("old"), p.deleted)
    }

    @Test fun `invalid names nested paths and empty ids are not owned cleanup targets`() = runBlocking {
        val p = Provider(listOf(current(), RemoteBackupEntry("invalid-date", "Xime完整备份-2026-02-30.zip", 90),
            RemoteBackupEntry("nested", "../Xime完整备份-2026-01-01.zip", 80),
            RemoteBackupEntry("", "Xime完整备份-2026-01-02.zip", 70)))
        assertTrue(cleanup(p))
        assertTrue(p.deleted.isEmpty())
    }

    @Test fun `known older app filename is managed but lookalike names are retained`() = runBlocking {
        val p = Provider(listOf(current(), RemoteBackupEntry("legacy", "Xime配置-2026-09-06.zip", 80),
            RemoteBackupEntry("lookalike", "xime完整备份-2026-09-06.zip", 70)))
        assertTrue(cleanup(p))
        assertEquals(listOf("legacy"), p.deleted)
    }

    @Test fun `disabled cleanup does not query provider or log a remote stage`() = runBlocking {
        val p = Provider(listOf(current(), full("old", "2026-11-01", 70)))
        for (keep in listOf(0, -1)) {
            val runId = BackupRunLog.begin(BackupRunTarget.CLOUD)
            val before = BackupRunLog.state.value
            assertTrue(cleanup(p, keep, runId))
            assertEquals(before, BackupRunLog.state.value)
            BackupRunLog.finish(runId, BackupRunOutcome.SUCCESS)
        }
        assertEquals(0, p.lists)
        assertTrue(p.deleted.isEmpty())
    }

    @Test fun `listing failure is reported without attempting any delete`() = runBlocking {
        val p = Provider(null)
        assertFalse(cleanup(p))
        assertTrue(p.deleted.isEmpty())
    }

    @Test fun `duplicate provider rows delete each managed id at most once`() = runBlocking {
        val old = full("old", "2026-11-01", 70)
        val p = Provider(listOf(current(), old, old))
        assertTrue(cleanup(p))
        assertEquals(listOf("old"), p.deleted)
    }

    @Test fun `conflicting ids and ambiguous upload names are retained`() = runBlocking {
        val p = Provider(listOf(current(), full("shared", "2026-11-01", 70),
            RemoteBackupEntry("shared", "rime-sync-phone.zip", 70)))
        assertTrue(cleanup(p))
        assertTrue(p.deleted.isEmpty())
        val ambiguous = Provider(listOf(current(), RemoteBackupEntry("other", currentName, 90),
            full("old", "2026-11-01", 70)))
        assertFalse(BackupManager.cleanupRemoteBackups(ambiguous, 1, currentName, null))
        assertTrue(ambiguous.deleted.isEmpty())
    }

    @Test fun `missing upload id can match one exact name but mismatched id defers`() = runBlocking {
        val entries = listOf(current(), full("old", "2026-11-01", 70))
        val byName = Provider(entries)
        assertTrue(BackupManager.cleanupRemoteBackups(byName, 1, currentName, null))
        assertEquals(listOf("old"), byName.deleted)
        val stale = Provider(entries)
        assertFalse(BackupManager.cleanupRemoteBackups(stale, 1, currentName, "missing"))
        assertTrue(stale.deleted.isEmpty())
    }

    @Test fun `equal timestamps have stable cleanup targets for any provider order`() = runBlocking {
        val entries = listOf(current(), full("a", "2026-11-01", 70), full("b", "2026-11-02", 70))
        for (order in listOf(entries, entries.reversed())) {
            val p = Provider(order)
            assertTrue(cleanup(p, keep = 2))
            assertEquals(listOf("b"), p.deleted)
        }
    }

    @Test fun `delete failure is reported and cancellation propagates`() = runBlocking {
        val entries = listOf(current(), full("old", "2026-11-01", 70))
        val failed = Provider(entries).apply { deleteResult = false }
        assertFalse(cleanup(failed))
        assertEquals(listOf("old"), failed.deleted)
        val cancelled = Provider(entries).apply { cancellation = CancellationException("fixture") }
        try {
            cleanup(cancelled)
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("fixture", e.message)
        }
    }
}
