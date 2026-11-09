package com.kingzcheung.xime.service.otp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选栏验证码展示规则（用户 2026-11）：
 * 只要发生键盘点击事件，此前收到的验证码就不再显示；新收到的验证码照常显示；
 * 过期（默认 2 分钟）的不显示。
 */
class OtpDisplayPolicyTest {

    private val freshWindow = 120_000L

    @Test
    fun `刚收到且没按过键盘时显示`() {
        assertTrue(
            isOtpDisplayable(
                capturedAt = 1_000L,
                nowMs = 2_000L,
                lastKeyPressAtMs = 0L,
                freshWindowMs = freshWindow,
            )
        )
    }

    @Test
    fun `收到后按过键盘就不显示`() {
        assertFalse(
            isOtpDisplayable(
                capturedAt = 1_000L,
                nowMs = 2_000L,
                lastKeyPressAtMs = 1_500L,
                freshWindowMs = freshWindow,
            )
        )
    }

    @Test
    fun `按过键盘之后新收到的验证码重新显示`() {
        assertTrue(
            isOtpDisplayable(
                capturedAt = 3_000L,
                nowMs = 3_100L,
                lastKeyPressAtMs = 1_500L,
                freshWindowMs = freshWindow,
            )
        )
    }

    @Test
    fun `同一毫秒按下的键盘点击算在验证码之后_不显示`() {
        // 保守处理：同一时刻无法判断先后，按下即视为「已点击过」
        assertFalse(
            isOtpDisplayable(
                capturedAt = 1_000L,
                nowMs = 1_000L,
                lastKeyPressAtMs = 1_000L,
                freshWindowMs = freshWindow,
            )
        )
    }

    @Test
    fun `超过有效期不显示`() {
        assertFalse(
            isOtpDisplayable(
                capturedAt = 1_000L,
                nowMs = 1_000L + freshWindow + 1,
                lastKeyPressAtMs = 0L,
                freshWindowMs = freshWindow,
            )
        )
    }

    @Test
    fun `恰好卡在有效期边界仍显示`() {
        assertTrue(
            isOtpDisplayable(
                capturedAt = 1_000L,
                nowMs = 1_000L + freshWindow,
                lastKeyPressAtMs = 0L,
                freshWindowMs = freshWindow,
            )
        )
    }
}
