package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.*
import org.junit.Test

/** UI wiring checks complement the typed event, real ZIP counting and privacy tests. */
class BackupRunLogUiContractTest {
    private val screen by lazy {
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/ui/settings/BackupSettingsScreen.kt").readText()
    }
    private val panel by lazy {
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/ui/settings/BackupRunLogPanel.kt").readText()
    }

    @Test
    fun `complete backup observes live run and prints it between main actions and cloud files`() {
        assertTrue(screen.contains("BackupRunLog.state.collectAsState()"))
        assertTrue(screen.contains("runLog = backupRun"))
        val card = screen.substringAfter("private fun BackupCardActions(")
        val button = card.indexOf("text = \"立即备份\"")
        val log = card.indexOf("BackupRunLogPanel(runLog, onClose = onToggleRunLog)")
        val cloud = card.indexOf("if (expanded && remoteList")
        assertTrue(button >= 0 && log > button && cloud > log)
        assertTrue(card.contains("busyOp == \"backup\" || runLog.running"))
        assertTrue(card.contains("云端列表"))
        assertTrue(screen.contains("error = displayedBackupError"))
        assertTrue(screen.contains("backupRun.outcome == BackupRunOutcome.CANCELLED"))
    }

    @Test
    fun `leaving page hides records and only explicit view or new active run reopens them`() {
        assertTrue(screen.contains("BackupRunLogVisibility(observedRunId = backupRun.runId)"))
        assertTrue(screen.contains("LifecycleEventEffect(Lifecycle.Event.ON_PAUSE)"))
        assertTrue(screen.contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)"))
        assertTrue(screen.contains("observeRun(BackupRunLog.state.value.runId, pageResumed = false)"))
        assertTrue(screen.contains("runLogVisibility = runLogVisibility.hide()"))
        assertTrue(screen.contains("pageLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)"))
        assertTrue(screen.contains("if (runLogExpanded)"))
        assertTrue(screen.contains("查看运行记录"))
        assertTrue(panel.contains("TextButton(onClick = onClose)"))
        assertFalse(screen.contains("rememberSaveable { BackupRunLogVisibility"))
    }

    @Test
    fun `safe run entries have time level scroll and explicit empty state`() {
        assertTrue(panel.contains("state.entries.forEach"))
        assertTrue(panel.contains("entry.timestamp"))
        assertTrue(panel.contains("entry.message"))
        assertTrue(panel.contains("BackupLogLevel.ERROR"))
        assertTrue(panel.contains("state.outcome == BackupRunOutcome.SUCCESS"))
        assertTrue(panel.contains("state.outcome == BackupRunOutcome.CANCELLED"))
        assertTrue(panel.contains("heightIn(max = 220.dp)"))
        assertTrue(panel.contains("verticalScroll(scroll)"))
        assertTrue(panel.contains("尚未开始"))
        assertTrue(panel.contains("不显示剪贴板正文、快捷码或账号信息"))
        assertFalse(panel.contains("ClipboardManager"))
        assertFalse(panel.contains("configGet"))
        assertFalse(panel.contains("stackTraceToString"))
    }
}
