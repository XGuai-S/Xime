package com.kingzcheung.xime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:asr` 空闲回收判据的护栏。
 *
 * 这段逻辑错了两种方向都难看：
 *  - 判太松（该回收不回收）→ 232MB 进程继续常驻，就是本次要修的 bug；
 *  - 判太紧（会话中误判空闲）→ 录音中途解绑，识别直接断掉，用户只会觉得「语音坏了」。
 * 因此「刚用过不收 / 到点要收 / 从未用过直接收」三条都要盯住。
 */
class AsrConnectionWatchdogTest {

    private val timeout = 3 * 60 * 1000L

    @Test
    fun `never used is reclaimable`() {
        assertTrue(AsrConnectionWatchdog.shouldUnbind(nowMs = 1_000L, lastActivityMs = 0L, timeoutMs = timeout))
    }

    @Test
    fun `recent activity is not reclaimed`() {
        assertFalse(
            AsrConnectionWatchdog.shouldUnbind(
                nowMs = 10_000L,
                lastActivityMs = 9_000L,
                timeoutMs = timeout
            )
        )
    }

    @Test
    fun `exactly at timeout is reclaimed`() {
        assertTrue(
            AsrConnectionWatchdog.shouldUnbind(
                nowMs = 10_000L + timeout,
                lastActivityMs = 10_000L,
                timeoutMs = timeout
            )
        )
    }

    @Test
    fun `one millisecond before timeout is kept`() {
        assertFalse(
            AsrConnectionWatchdog.shouldUnbind(
                nowMs = 10_000L + timeout - 1,
                lastActivityMs = 10_000L,
                timeoutMs = timeout
            )
        )
    }

    @Test
    fun `default timeout matches client constant`() {
        // 防止改了常量却忘了默认参数（默认值在编译期固化，运行时才发现对不上）
        assertTrue(AsrInferenceClient.IDLE_UNBIND_MS in 60_000L..600_000L)
    }
}
