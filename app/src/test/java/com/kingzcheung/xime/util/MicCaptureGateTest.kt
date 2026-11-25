package com.kingzcheung.xime.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 麦克风闸门的行为护栏。
 *
 * 为什么值得专门测：这把锁住在语音主链路上，写错两种方向都会出事——
 *  - 太松（失败时不拒绝）：回到「两路同时录、都录出静音」的老 bug；
 *  - 太紧（该放不放）：麦克风被永久占住，用户按麦克风永远提示「被占用」。
 * 因此成功、拒绝、同持有者重入、非法释放、租约接管五条都要盯住。
 */
class MicCaptureGateTest {

    @After
    fun tearDown() {
        MicCaptureGate.resetForTest()
    }

    @Test
    fun `first acquire succeeds and exposes holder`() {
        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 1_000L))
        assertEquals("键盘语音", MicCaptureGate.currentHolder())
    }

    @Test
    fun `second owner is rejected while first holds`() {
        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 1_000L))
        assertFalse(MicCaptureGate.tryAcquire("悬浮胶囊", nowMs = 2_000L))
        // 被拒绝不能改变持有者
        assertEquals("键盘语音", MicCaptureGate.currentHolder())
    }

    @Test
    fun `same owner re-acquire refreshes lease`() {
        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 1_000L))
        // 预启动 → 正式录音就是这个模式
        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 3 * 60 * 1000L))
        // 刷新后租约从新时间点算起：别的持有者仍抢不到
        assertFalse(MicCaptureGate.tryAcquire("悬浮胶囊", nowMs = 3 * 60 * 1000L + 1_000L))
    }

    @Test
    fun `release by non-holder does not free the gate`() {
        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 1_000L))
        MicCaptureGate.release("悬浮胶囊")
        assertEquals("键盘语音", MicCaptureGate.currentHolder())
    }

    @Test
    fun `release by holder frees the gate and is idempotent`() {
        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 1_000L))
        MicCaptureGate.release("键盘语音")
        assertNull(MicCaptureGate.currentHolder())
        MicCaptureGate.release("键盘语音")
        assertNull(MicCaptureGate.currentHolder())
        // 放开后另一条路径可以拿到
        assertTrue(MicCaptureGate.tryAcquire("悬浮胶囊", nowMs = 2_000L))
    }

    @Test
    fun `expired lease allows takeover`() {
        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 0L))
        val justBefore = MicCaptureGate.LEASE_TIMEOUT_MS
        assertFalse(MicCaptureGate.tryAcquire("悬浮胶囊", nowMs = justBefore))
        // 超过租约视为泄漏（异常路径忘了 release），允许强制接管，避免麦克风被永久锁死
        assertTrue(MicCaptureGate.tryAcquire("悬浮胶囊", nowMs = justBefore + 1))
        assertEquals("悬浮胶囊", MicCaptureGate.currentHolder())
    }

    @Test
    fun `process change logs a gate-broken error`() {
        val errors = mutableListOf<String>()
        var process = "com.kingzcheung.xime"
        MicCaptureGate.processNameProvider = { process }
        MicCaptureGate.errorReporter = { errors.add(it) }

        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 1_000L))
        assertTrue(errors.isEmpty())
        MicCaptureGate.release("键盘语音")

        // 模拟有人给悬浮胶囊组件加了 android:process：两条路径各自持锁，互斥静默失效
        process = "com.kingzcheung.xime:bubble"
        assertTrue(MicCaptureGate.tryAcquire("悬浮胶囊", nowMs = 2_000L))

        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("闸门已失效"))
        assertTrue(errors[0].contains("com.kingzcheung.xime:bubble"))
    }

    @Test
    fun `process mismatch is reported only once`() {
        val errors = mutableListOf<String>()
        var process = "com.kingzcheung.xime"
        MicCaptureGate.processNameProvider = { process }
        MicCaptureGate.errorReporter = { errors.add(it) }

        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 1_000L))
        process = "com.kingzcheung.xime:bubble"
        MicCaptureGate.release("键盘语音")
        assertTrue(MicCaptureGate.tryAcquire("悬浮胶囊", nowMs = 2_000L))
        MicCaptureGate.release("悬浮胶囊")
        assertTrue(MicCaptureGate.tryAcquire("悬浮胶囊", nowMs = 3_000L))

        // 每次起录音都刷同一条 ERROR 只会淹没日志
        assertEquals(1, errors.size)
    }

    @Test
    fun `same process never warns`() {
        val errors = mutableListOf<String>()
        MicCaptureGate.processNameProvider = { "com.kingzcheung.xime" }
        MicCaptureGate.errorReporter = { errors.add(it) }

        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 1_000L))
        MicCaptureGate.release("键盘语音")
        assertTrue(MicCaptureGate.tryAcquire("悬浮胶囊", nowMs = 2_000L))

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `unknown process name skips the assertion`() {
        val errors = mutableListOf<String>()
        MicCaptureGate.processNameProvider = { null }
        MicCaptureGate.errorReporter = { errors.add(it) }

        assertTrue(MicCaptureGate.tryAcquire("键盘语音", nowMs = 1_000L))
        MicCaptureGate.release("键盘语音")
        assertTrue(MicCaptureGate.tryAcquire("悬浮胶囊", nowMs = 2_000L))

        assertTrue(errors.isEmpty())
    }
}
