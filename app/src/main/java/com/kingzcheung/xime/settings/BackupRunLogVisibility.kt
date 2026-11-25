package com.kingzcheung.xime.settings

/** A page-local view state; archived run data remains owned by BackupRunLog. */
internal data class BackupRunLogVisibility(
    val observedRunId: Long,
    val expanded: Boolean = false,
) {
    fun observeRun(runId: Long, pageResumed: Boolean): BackupRunLogVisibility =
        if (runId <= observedRunId) this
        else copy(observedRunId = runId, expanded = expanded || (runId > 0 && pageResumed))

    fun show(): BackupRunLogVisibility = copy(expanded = true)
    fun hide(): BackupRunLogVisibility = copy(expanded = false)
    fun toggle(): BackupRunLogVisibility = copy(expanded = !expanded)
}
