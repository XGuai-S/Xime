package com.kingzcheung.xime.service.otp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证码解析的「零分配预筛」护栏。
 *
 * 这个预筛挡在关键词正则前面，每一条系统通知都要过一遍，所以两个方向都不能错：
 *  - 该放行的被判掉 → 验证码不再自动填充（用户感知为功能消失）；
 *  - 该拦的放过 → 只是回到原路径，多跑一次正则而已（可接受）。
 * 因此断言集中在「4~8 位独立数字必须放行、超长数字串与无数字必须拦」。
 */
class OtpDigitRunPrefilterTest {

    @Test
    fun `four digit code passes`() {
        assertTrue(OtpExtractor.hasOtpLikeDigitRun("验证码：1234"))
    }

    @Test
    fun `six digit code passes`() {
        assertTrue(OtpExtractor.hasOtpLikeDigitRun("【某银行】验证码 911751，5分钟内有效"))
    }

    @Test
    fun `eight digit code passes`() {
        assertTrue(OtpExtractor.hasOtpLikeDigitRun("code 12345678"))
    }

    @Test
    fun `three digits rejected`() {
        assertFalse(OtpExtractor.hasOtpLikeDigitRun("共 123 条消息"))
    }

    @Test
    fun `nine digit run rejected`() {
        // 9 位以上多半是订单号/流水号
        assertFalse(OtpExtractor.hasOtpLikeDigitRun("订单号 123456789"))
    }

    @Test
    fun `no digits rejected`() {
        assertFalse(OtpExtractor.hasOtpLikeDigitRun("今晚一起吃饭吗"))
    }

    @Test
    fun `digit run at end of string is detected`() {
        // 结尾没有分隔符时也要判定到——原实现容易在这里漏
        assertTrue(OtpExtractor.hasOtpLikeDigitRun("验证码 5566"))
    }

    @Test
    fun `digits separated by text are not a run`() {
        assertFalse(OtpExtractor.hasOtpLikeDigitRun("1 2 3 4"))
    }

    @Test
    fun `blank body has no run`() {
        assertFalse(OtpExtractor.hasOtpLikeDigitRun(""))
    }
}
