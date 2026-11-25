package com.kingzcheung.xime.plugin

import com.kingzcheung.xime.RepoFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupServiceActivationTest {
    private class Fixture {
        val enabled = linkedMapOf("old" to true, "new" to false, "spare" to false)
        val running = mutableSetOf("old")
        val events = mutableListOf<String>()
        var selected = "old"
        var failEnable = false
        var failDisable = false
        var failCommit = false
        var launchAction: suspend (String) -> String? = { "ready:$it" }

        suspend fun activate(id: String = "new"): Result<String> {
            val previousEnabled = enabled.toMap()
            val previousRunning = running.toSet()
            val previousSelected = selected
            return activateBackupService(id, enabled.keys.toList(), { key, value ->
                events += "$key:$value"
                enabled[key] = value // A persistence failure can follow a partial mutation.
                if (!value) running.remove(key)
                !(value && failEnable) && !(!value && key == "spare" && failDisable)
            }, { key ->
                events += "launch:$key"
                launchAction(key)?.also { running.add(key) }
            }, {
                events += "commit"
                selected = id
                if (failCommit) error("commit failed")
            }, {
                currentCoroutineContext().ensureActive()
                events += "restore"
                enabled.clear()
                enabled.putAll(previousEnabled)
                running.clear()
                running.addAll(previousRunning)
                selected = previousSelected
            })
        }

        fun assertRestored() {
            assertEquals(mapOf("old" to true, "new" to false, "spare" to false), enabled)
            assertEquals(setOf("old"), running)
            assertEquals("old", selected)
            assertEquals("restore", events.last())
        }
    }

    @Test
    fun `installed disabled service is enabled and validated before old service stops`() = runBlocking {
        val f = Fixture()
        assertEquals("ready:new", f.activate().getOrThrow())
        assertEquals(listOf("new:true", "launch:new", "old:false", "spare:false", "commit"), f.events)
        assertEquals(setOf("new"), f.running)
        assertEquals("new", f.selected)
        assertEquals(mapOf("old" to false, "new" to true, "spare" to false), f.enabled)
    }

    @Test
    fun `unknown plugin does not change or launch anything`() = runBlocking {
        val f = Fixture()
        assertEquals("备份插件未安装", f.activate("missing").exceptionOrNull()?.message)
        assertTrue(f.events.isEmpty())
        assertEquals(setOf("old"), f.running)
    }

    @Test
    fun `failed persistent enable restores partially changed flags`() = runBlocking {
        val f = Fixture().apply { failEnable = true }
        assertTrue(f.activate().isFailure)
        assertFalse(f.events.any { it.startsWith("launch:") })
        f.assertRestored()
    }

    @Test
    fun `missing or wrong service instance restores state before old service stops`() = runBlocking {
        val f = Fixture().apply { launchAction = { null } }
        assertTrue(f.activate().exceptionOrNull()?.message.orEmpty().contains("启动失败"))
        assertFalse(f.events.contains("old:false"))
        f.assertRestored()
    }

    @Test
    fun `partial deactivation failure restores the old running service and selection`() = runBlocking {
        val f = Fixture().apply { failDisable = true }
        assertEquals("无法停用其他备份插件", f.activate().exceptionOrNull()?.message)
        assertTrue(f.events.contains("old:false"))
        f.assertRestored()
    }

    @Test
    fun `failed selection commit restores flags running services and previous choice`() = runBlocking {
        val f = Fixture().apply { failCommit = true }
        assertEquals("commit failed", f.activate().exceptionOrNull()?.message)
        f.assertRestored()
    }

    @Test
    fun `same service can be activated again after its runtime disappears`() = runBlocking {
        val f = Fixture()
        f.activate().getOrThrow()
        f.running.clear()
        f.activate().getOrThrow()
        assertEquals(setOf("new"), f.running)
        assertEquals(2, f.events.count { it == "launch:new" })
    }

    @Test
    fun `cancellation restores state in a non cancelled context`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val f = Fixture().apply {
            launchAction = {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { f.activate() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        f.assertRestored()
    }

    @Test
    fun `rollback failure is surfaced rather than reporting activation success`() = runBlocking {
        val result = activateBackupService<String>("new", listOf("new"), { _, _ -> false },
            { "ready" }, {}, { error("restore failed") })
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("恢复原备份服务失败"))
        assertEquals("restore failed", result.exceptionOrNull()?.suppressed?.single()?.message)
    }

    @Test
    fun `disabled service guide opens picker and caller persists both enable stores`() {
        val source = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/ui/settings/BackupSettingsScreen.kt",
        ).readText()
        val guide = source.substringAfter("activePlugin == null -> ServiceGuideRow(")
            .substringBefore("configured == false ->")
        assertTrue(guide.contains("showServicePicker = true"))
        assertFalse(guide.contains("onNavigateToPlugins"))
        val select = source.substringAfter("onSelect = { pickedId ->")
            .substringBefore("onDismiss = { showServicePicker = false }")
        assertTrue(select.contains("busyOp == null"))
        assertTrue(select.contains("activePlugin?.first != pickedId"))
        assertTrue(select.contains("activateBackupService("))
        assertTrue(select.contains("PluginManager.setPluginEnabled(id, enabled)"))
        assertTrue(select.contains("SettingsPreferences.setPluginEnabled(context, id, enabled)"))
        assertTrue(select.contains("as? BackupPlugin"))
        assertTrue(select.contains("commit = { SettingsPreferences.setBackupPluginId(context, pickedId) }"))
        assertTrue(select.contains("restore = {"))
        val refresh = source.substringAfter("fun refreshRemoteList() {")
            .substringBefore("SettingsScreenScaffold(")
        assertTrue(refresh.contains("if (busyOp != null) return"))
        assertTrue(refresh.contains("if (activePlugin?.second !== plugin || busyOp != \"list\") return@withContext"))
    }
}
