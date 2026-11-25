package com.kingzcheung.xime.settings

import org.junit.Assert.*
import org.junit.Test

class BackupRunLogVisibilityTest {
    @Test
    fun `entering with an existing run never opens historical records`() {
        val page = BackupRunLogVisibility(observedRunId = 8)
        assertFalse(page.expanded)
        assertFalse(page.observeRun(8, pageResumed = true).expanded)
    }

    @Test
    fun `a new run begins visible while the page is active`() {
        val page = BackupRunLogVisibility(observedRunId = 8)
        assertTrue(page.observeRun(9, pageResumed = true).expanded)
    }

    @Test
    fun `manual collapse is respected as current run keeps updating`() {
        val page = BackupRunLogVisibility(8).observeRun(9, pageResumed = true).hide()
        assertFalse(page.observeRun(9, pageResumed = true).expanded)
        assertTrue(page.toggle().expanded)
    }

    @Test
    fun `new background runs are consumed without reopening on resume`() {
        val left = BackupRunLogVisibility(8).show().hide()
        val background = left.observeRun(9, pageResumed = false)
        assertFalse(background.expanded)
        assertFalse(background.observeRun(9, pageResumed = true).expanded)
    }

    @Test
    fun `returning while backup is still running starts collapsed`() {
        val firstPage = BackupRunLogVisibility(8).observeRun(9, pageResumed = true)
        assertTrue(firstPage.expanded)
        val returnedPage = BackupRunLogVisibility(observedRunId = 9)
        assertFalse(returnedPage.observeRun(9, pageResumed = true).expanded)
        assertTrue(returnedPage.show().expanded)
    }

    @Test
    fun `deferred old UI emissions cannot reopen a run consumed on resume`() {
        val resumed = BackupRunLogVisibility(8).hide().observeRun(10, pageResumed = false)
        val stale = resumed.observeRun(9, pageResumed = true)
        assertEquals(10L, stale.observedRunId)
        assertFalse(stale.expanded)
        assertFalse(stale.observeRun(10, pageResumed = true).expanded)
    }

    @Test
    fun `explicit backup request remains visible during navigation transition`() {
        val page = BackupRunLogVisibility(0).show()
        assertTrue(page.observeRun(1, pageResumed = false).expanded)
        assertFalse(BackupRunLogVisibility(0).observeRun(0, pageResumed = true).expanded)
    }
}
