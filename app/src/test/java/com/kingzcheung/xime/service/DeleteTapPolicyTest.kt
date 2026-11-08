package com.kingzcheung.xime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退格「单击 / 连按」判定：
 * 单击（纯删除、不读屏）与连按（长按重复派发 → 开删除会话做撤回记账）的分界。
 */
class DeleteTapPolicyTest {

    @Test
    fun `首次按下不算连按`() {
        assertFalse(isContinuousDeletePress(previousDownAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun `窗口内再次按下算连按`() {
        // 长按重复派发间隔约 80ms
        assertTrue(isContinuousDeletePress(previousDownAtMs = 1_000L, nowMs = 1_080L))
    }

    @Test
    fun `恰好等于窗口边界算连按`() {
        assertTrue(
            isContinuousDeletePress(
                previousDownAtMs = 1_000L,
                nowMs = 1_000L + DELETE_CONTINUOUS_WINDOW_MS,
            )
        )
    }

    @Test
    fun `超出窗口算两次独立单击`() {
        assertFalse(
            isContinuousDeletePress(
                previousDownAtMs = 1_000L,
                nowMs = 1_000L + DELETE_CONTINUOUS_WINDOW_MS + 1,
            )
        )
    }

    @Test
    fun `时间回退不算连按`() {
        // 时钟源异常（uptime 回绕/测试注入）时宁可当单击，也不要误开撤回会话
        assertFalse(isContinuousDeletePress(previousDownAtMs = 2_000L, nowMs = 1_000L))
    }

    @Test
    fun `自定义窗口生效`() {
        assertTrue(isContinuousDeletePress(previousDownAtMs = 1_000L, nowMs = 1_500L, windowMs = 600L))
        assertFalse(isContinuousDeletePress(previousDownAtMs = 1_000L, nowMs = 1_500L, windowMs = 400L))
    }
}
