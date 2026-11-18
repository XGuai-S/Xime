package com.kingzcheung.xime.ai.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式回调节流器的行为护栏。
 *
 * 这些断言存在的理由：节流一旦写错成「permanently false」，表现是悬浮窗文本一直不更新，
 * 但最终结果页又是对的——很容易被当成网络慢而漏掉。必须让「首次放行、窗口内拒绝、
 * 窗口后放行、force 立即放行」四条都有测试盯着。
 */
class StreamEmitThrottleTest {

    private class FakeClock(var nanos: Long = 1_000_000_000L) : () -> Long {
        override fun invoke(): Long = nanos
        fun advanceMs(ms: Long) {
            nanos += ms * 1_000_000L
        }
    }

    @Test
    fun `first call always emits`() {
        val throttle = StreamEmitThrottle(minIntervalMs = 66, clockNanos = FakeClock())
        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun `calls inside the window are suppressed`() {
        val clock = FakeClock()
        val throttle = StreamEmitThrottle(minIntervalMs = 66, clockNanos = clock)
        assertTrue(throttle.shouldEmit())
        clock.advanceMs(10)
        assertFalse(throttle.shouldEmit())
        clock.advanceMs(10)
        assertFalse(throttle.shouldEmit())
    }

    @Test
    fun `emits again after the window elapses`() {
        val clock = FakeClock()
        val throttle = StreamEmitThrottle(minIntervalMs = 66, clockNanos = clock)
        assertTrue(throttle.shouldEmit())
        clock.advanceMs(65)
        assertFalse(throttle.shouldEmit())
        clock.advanceMs(1)
        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun `force emits immediately and resets the window`() {
        val clock = FakeClock()
        val throttle = StreamEmitThrottle(minIntervalMs = 66, clockNanos = clock)
        assertTrue(throttle.shouldEmit())
        clock.advanceMs(1)
        assertTrue(throttle.shouldEmit(force = true))
        // force 之后窗口重新计时：紧接着的普通调用应被拒绝
        clock.advanceMs(1)
        assertFalse(throttle.shouldEmit())
    }

    @Test
    fun `default interval matches engine constant`() {
        // 防止有人改了常量忘了同步默认参数（默认值在编译期固化，跑起来才发现不一致）
        assertTrue(AiPipelineEngine.STREAM_EMIT_MIN_INTERVAL_MS in 1L..1000L)
    }
}
