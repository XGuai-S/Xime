package com.kingzcheung.xime.service.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘点击时的验证码摘除范围（用户 2026-11）：
 * 按过键之后，候选栏里一条验证码都不能留——所以摘除范围取「全部未过期条目」，
 * 而不是「按键前还该显示的条目」（后者会被按键过滤掉，漏掉上一键没摘干净的残留）。
 */
class OtpDismissalPolicyTest {

    private val freshWindow = 120_000L

    private fun candidate(primary: String, secondary: String? = null, capturedAt: Long) =
        OtpCandidate(primary = primary, secondary = secondary, capturedAt = capturedAt)

    @Test
    fun `按键后仍要摘掉那些已被按键过滤的旧码`() {
        // 场景：验证码 1000ms 捕获，用户 1500ms 按过键（displayList 已经不返回它），
        // 但候选栏因为某条路径仍挂着 → 摘除范围必须包含它。
        val pending = listOf(candidate("123456", null, capturedAt = 1_000L))
        val texts = otpTextsForDismissal(pending, nowMs = 2_000L, freshWindowMs = freshWindow)
        assertEquals(listOf("123456"), texts)
    }

    @Test
    fun `次选形式一并摘除`() {
        val pending = listOf(candidate("344204", "G-344204", capturedAt = 1_000L))
        val texts = otpTextsForDismissal(pending, nowMs = 2_000L, freshWindowMs = freshWindow)
        assertEquals(listOf("344204", "G-344204"), texts)
    }

    @Test
    fun `过期条目不参与摘除`() {
        val pending = listOf(
            candidate("111111", null, capturedAt = 1_000L),
            candidate("222222", null, capturedAt = 200_000L),
        )
        val texts = otpTextsForDismissal(pending, nowMs = 250_000L, freshWindowMs = freshWindow)
        assertEquals(listOf("222222"), texts)
    }

    @Test
    fun `重复文本去重`() {
        val pending = listOf(
            candidate("654321", "654321", capturedAt = 1_000L),
        )
        val texts = otpTextsForDismissal(pending, nowMs = 2_000L, freshWindowMs = freshWindow)
        assertEquals(listOf("654321"), texts)
    }

    @Test
    fun `没有验证码时不摘任何候选`() {
        assertTrue(
            otpTextsForDismissal(emptyList(), nowMs = 2_000L, freshWindowMs = freshWindow).isEmpty()
        )
    }

    @Test
    fun `边界：恰好到期仍算未过期`() {
        val pending = listOf(candidate("999999", null, capturedAt = 1_000L))
        val texts = otpTextsForDismissal(
            pending,
            nowMs = 1_000L + freshWindow,
            freshWindowMs = freshWindow,
        )
        assertEquals(listOf("999999"), texts)
    }
}
