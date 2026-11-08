package com.kingzcheung.xime.service.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证码提取的噪声词规则（用户 2026-11 修复）：
 * 原来正文只要含「金额/支付/尾号…」就整条放弃，银行和电商的验证码短信基本全被漏掉。
 * 现在含噪声词时，只有数字**紧邻关键词**（30 字符内）才认。
 */
class OtpExtractorNoiseTest {

    @Test
    fun `含金额但验证码紧邻关键词时仍识别`() {
        val body = "【某银行】验证码 123456，本次消费金额 100 元，请勿泄露。"
        assertEquals("123456", OtpExtractor.extract(body)?.primary)
    }

    @Test
    fun `噪声数字远离关键词时不识别`() {
        // 关键词在前，数字（订单号）距离超过 30 字符
        val body = "您的验证码已发送，请查收。订单号 99887766，金额 100 元，如有疑问请联系客服处理。"
        assertNull(OtpExtractor.extract(body))
    }

    @Test
    fun `没有关键词的纯噪声短信不识别`() {
        assertNull(OtpExtractor.extract("订单 123456 金额 100"))
    }

    @Test
    fun `Google 格式仍优先`() {
        val body = "G-344204 是你的 Google 验证码"
        val candidate = OtpExtractor.extract(body)
        assertEquals("344204", candidate?.primary)
        assertEquals("G-344204", candidate?.secondary)
    }
}
