package com.kingzcheung.xime.association

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 等待上一次 release 派发的后台卸载任务结束。
 *
 * 返回 true 表示没有待处理任务或任务已在 [timeoutMs] 内结束；返回 false 表示超时，
 * 此时调用 [onTimeout] 并让调用方继续加载（不无限阻塞初始化）。
 * 抽成独立函数是为了让完成/超时两个分支可在纯 JVM 单测里覆盖。
 */
internal suspend fun awaitPendingUnload(
    job: Job?,
    timeoutMs: Long,
    onTimeout: () -> Unit,
): Boolean {
    if (job == null) return true
    val finished = withTimeoutOrNull(timeoutMs) { job.join(); true } ?: false
    if (!finished) onTimeout()
    return finished
}
