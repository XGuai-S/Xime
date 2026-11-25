package com.kingzcheung.xime.handwriting

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * :inference 绑定失败的用户提示节流。
 *
 * 手写板「写了没候选」以前只有日志，用户无从判断；这里补一次可读提示，
 * 但连续落笔会反复失败，必须节流，否则提示本身变成骚扰。
 */
class HandwritingBindFailureToastTest {

    @Before
    fun setUp() {
        // 节流状态是 HandwritingEngine 的实例字段，而它是单例：
        // 只靠 @After 清理的话，前一个测试若异常退出就会把状态漏给下一个。
        HandwritingEngine.resetToastThrottleForTest()
    }

    @After
    fun tearDown() {
        HandwritingEngine.resetToastThrottleForTest()
    }

    @Test
    fun `first failure shows a toast`() {
        val shown = mutableListOf<String>()
        HandwritingEngine.toastDispatcher = { shown.add(it) }

        HandwritingEngine.notifyBindFailure(nowMs = 1_000L)

        assertEquals(1, shown.size)
        assertTrue(shown[0].contains("手写"))
    }

    @Test
    fun `failures within 30s are throttled`() {
        val shown = mutableListOf<String>()
        HandwritingEngine.toastDispatcher = { shown.add(it) }

        HandwritingEngine.notifyBindFailure(nowMs = 1_000L)
        HandwritingEngine.notifyBindFailure(nowMs = 20_000L)
        HandwritingEngine.notifyBindFailure(nowMs = 30_999L)

        assertEquals(1, shown.size)
    }

    @Test
    fun `failure after 30s shows again`() {
        val shown = mutableListOf<String>()
        HandwritingEngine.toastDispatcher = { shown.add(it) }

        HandwritingEngine.notifyBindFailure(nowMs = 1_000L)
        HandwritingEngine.notifyBindFailure(nowMs = 31_000L)

        assertEquals(2, shown.size)
    }
}
