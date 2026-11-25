package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCloudPolicyTest {
    @Test
    fun `complete backup gate defaults on and disabled gate is lazy`() {
        assertTrue(ClipboardCloudPolicy.DEFAULT_BACKUP_ENABLED)
        var called = false
        assertTrue(ClipboardCloudPolicy.withBackupEnabled(true) { called = true; "written" } == "written")
        assertTrue(called)
        called = false
        assertTrue(ClipboardCloudPolicy.withBackupEnabled(false) { called = true; "must-not-read" } == null)
        assertFalse(called)
        assertTrue(ClipboardCloudPolicy.backupSkipMessage(false)!!.contains("跳过剪贴板数据"))
        assertTrue(ClipboardCloudPolicy.backupSkipMessage(true) == null)
    }

    @Test
    fun `complete backup preference uses independent key and source gates writer before clipboard backup`() {
        val preferences = source("settings/SettingsPreferences.kt")
        val export = source("settings/RimeExportManager.kt")
        assertTrue(preferences.contains("backup_clipboard_enabled"))
        assertTrue(preferences.contains("ClipboardCloudPolicy.DEFAULT_BACKUP_ENABLED"))
        assertTrue(export.contains("packClipboardSection(SettingsPreferences.isClipboardBackupEnabled(context), runId)"))
        assertTrue(export.contains("ClipboardCloudPolicy.withBackupEnabled(enabled, writer)"))
        assertTrue(export.contains("ClipboardBackup.writeArchive"))
        assertTrue(export.indexOf("SettingsPreferences.isClipboardBackupEnabled(context)") < export.indexOf("ClipboardBackup.writeArchive"))
        assertTrue(export.contains("BackupRunLog.skippedClipboard(runId)"))
    }

    @Test
    fun `legacy realtime sync key remains independent from complete backup gate`() {
        assertFalse(ClipboardCloudPolicy.isLegacyRealtimeSyncEnabled(true))
        assertTrue(ClipboardCloudPolicy.shouldIncludeInBackup(true))
        assertFalse(ClipboardCloudPolicy.shouldIncludeInBackup(false))
    }


    @Test
    fun `legacy realtime sync is disabled regardless of stored value`() {
        assertFalse(ClipboardCloudPolicy.isLegacyRealtimeSyncEnabled(false))
        assertFalse(ClipboardCloudPolicy.isLegacyRealtimeSyncEnabled(true))
    }

    @Test
    fun `preference getter and service startup use disabled policy`() {
        val preferences = source("settings/SettingsPreferences.kt")
        val service = source("service/XimeInputMethodService.kt")

        assertTrue(preferences.contains("ClipboardCloudPolicy.isLegacyRealtimeSyncEnabled(savedEnabled)"))
        assertTrue(service.contains("get() = if (SettingsPreferences.isClipboardSyncEnabled(this)) clipboardSyncBridgeInternal else null"))

        val start = service.substringAfter("private fun startClipboardSyncIfEnabled() {")
            .substringBefore("private fun stopClipboardSync()")
        assertTrue(start.contains("if (!SettingsPreferences.isClipboardSyncEnabled(this))"))
        assertTrue(start.indexOf("if (!SettingsPreferences.isClipboardSyncEnabled(this))") < start.indexOf("ExtensionManager.getEnabledClipboardSyncPlugins(this)"))
        assertTrue(start.indexOf("if (!SettingsPreferences.isClipboardSyncEnabled(this))") < start.indexOf("ClipboardSyncBridge("))
        assertTrue(start.contains("clipboardSyncBridgeInternal?.start()"))

        val update = service.substringAfter("private fun updateClipboardSync() {")
            .substringBefore("/**\n     * 刷新已启用插件")
        assertTrue(update.contains("if (!SettingsPreferences.isClipboardSyncEnabled(this))"))
        assertTrue(update.contains("stopClipboardSync()"))
    }

    @Test
    fun `all service exposed pull entries pass through gated bridge getter`() {
        val service = source("service/XimeInputMethodService.kt")
        val callbacks = source("service/ImeKeyboardCallbacks.kt")

        assertTrue(service.contains("clipboardSyncBridge?.pullOnce()"))
        assertTrue(callbacks.contains("service.clipboardSyncBridge?.pullOnce()"))
        assertTrue(service.contains("get() = if (SettingsPreferences.isClipboardSyncEnabled(this)) clipboardSyncBridgeInternal else null"))
        assertFalse(service.contains("clipboardSyncBridgeInternal?.pullOnce()"))
    }

    @Test
    fun `old route redirects and plugin center cannot select legacy cloud service`() {
        val settings = source("ui/settings/SettingsScreen.kt")
        val plugins = source("ui/settings/PluginsSettingsScreen.kt")

        assertTrue(settings.contains("composable(SettingsRoutes.ClipboardSync)"))
        assertTrue(settings.contains("navController.navigate(SettingsRoutes.Backup)"))
        assertTrue(settings.contains("popUpTo(SettingsRoutes.ClipboardSync) { inclusive = true }"))
        assertFalse(settings.contains("ClipboardSyncSettingsContent("))
        assertTrue(plugins.contains("PluginCategory.CLIPBOARD_SYNC -> false"))
        assertTrue(plugins.contains("PluginCategory.CLIPBOARD_SYNC -> null"))
        assertTrue(plugins.contains("hasSettings && extension.category != PluginCategory.CLIPBOARD_SYNC"))
        assertFalse(plugins.contains("onNavigateToClipboardSync"))
    }

    private fun source(relativePath: String): String =
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/$relativePath")
            .readText()
            .replace("\r\n", "\n")
}
