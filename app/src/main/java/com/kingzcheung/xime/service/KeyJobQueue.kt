package com.kingzcheung.xime.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach

/** 容量只计算等待中的 LAZY 任务；执行中的任务由消费者单独持有。 */
internal fun createKeyJobQueue(capacity: Int = 64): Channel<Job> =
    Channel(capacity = capacity, onUndeliveredElement = { it.cancel() })

internal fun tryEnqueueKeyJob(queue: SendChannel<Job>, job: Job): Boolean {
    val accepted = queue.trySend(job).isSuccess
    if (!accepted) job.cancel()
    return accepted
}

/**
 * 队列满丢键的可观测兜底（P2-1）。
 *
 * 有界队列积压时只能丢新键，但静默丢弃 = 丢字且用户无从判断是引擎卡慢还是键盘坏了。
 * 这里累计丢弃数，达阈值给用户一次提示，提示后清零并进入冷却，避免持续卡顿时刷屏。
 *
 * 不改成「合并同键」或「同步执行」：拼音/双拼里连续同键是不同的编码（nn ≠ n），合并会
 * 静默改掉输入内容；同步执行跑在 UI 线程（onKeyPress 入口），既可能阻塞主线程，又会插到
 * 排队按键之前，打乱 keyJobs 的 FIFO 编码顺序。
 */
internal class KeyJobDropTracker(
    private val noticeThreshold: Int = DEFAULT_NOTICE_THRESHOLD,
    private val noticeCooldownMs: Long = DEFAULT_NOTICE_COOLDOWN_MS,
    private val clock: () -> Long = android.os.SystemClock::uptimeMillis,
) {
    /** 本次服务生命周期内累计丢弃数，仅用于日志留痕。 */
    @Volatile
    var totalDropped: Long = 0L
        private set

    private var pendingNoticeCount = 0
    private var lastNoticeAtMs: Long? = null

    /** 记录一次丢弃；返回需要提示的丢弃次数（null = 本次不提示）。 */
    @Synchronized
    fun onDropped(): Int? {
        totalDropped++
        pendingNoticeCount++
        if (pendingNoticeCount < noticeThreshold) return null
        val now = clock()
        val last = lastNoticeAtMs
        if (last != null && now - last < noticeCooldownMs) return null
        val count = pendingNoticeCount
        pendingNoticeCount = 0
        lastNoticeAtMs = now
        return count
    }

    private companion object {
        const val DEFAULT_NOTICE_THRESHOLD = 8
        const val DEFAULT_NOTICE_COOLDOWN_MS = 5_000L
    }
}

/** join 挂起消费者而不阻塞线程，避免把有界队列搬入 executor 的无界队列。 */
internal suspend fun consumeKeyJobs(queue: ReceiveChannel<Job>, onFailure: (Throwable) -> Unit) {
    queue.consumeEach { job ->
        try {
            job.start()
            job.join()
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        } catch (failure: Throwable) {
            job.cancel()
            onFailure(failure)
        }
    }
}
