package com.kingzcheung.xime.ai.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音润色链路脱敏所依赖的底层行为契约。
 *
 * `AiPipelineEngine.processVoiceAudio` 把输入框文本（选中文本/光标上下文/编辑框全文）
 * 送云端 LLM。修复要求这些文本出境前先经 [LocalPiiRedactor.redactText]，
 * 而 engine 无 Android Context，因此这里钉住：**无 Context 时高危凭据正则兜底仍然有效**，
 * 且可逆占位符可被 [RedactionSession.restoreSafePlaceholders] 还原。
 *
 * 这样 UIC/工程侧只需在 processVoiceAudio 入口调用 redactText、出口调用 restore，
 * 就能保证：密码/证件/卡号/密钥永不出境，手机号等可逆项出境后能还原成原文。
 */
class VoiceRedactionContractTest {

    @Test
    fun `无 Context 时 API key 被脱敏且不可恢复`() {
        val raw = "帮我把 sk-proj-abcdefghijklmnopqrstuvwxyz123456 这句话润色一下"
        val session = LocalPiiRedactor.redactText(raw, context = null)
        assertTrue("API key 必须被识别为高危", session.hasHighRiskCredentials)
        assertTrue("脱敏后不得含原 key", !session.redactedText.contains("sk-proj-"))
        assertTrue("高危占位符不得被恢复", session.restoreSafePlaceholders(session.redactedText).contains("密钥") ||
            !session.restoreSafePlaceholders(session.redactedText).contains("sk-proj-"))
    }

    @Test
    fun `无 Context 时身份证号被脱敏`() {
        val id = "110101199003071233" // 校验位正确（GB 11643）
        assertTrue("测试身份证夹具本身应合法", LocalPiiRedactor.isValidCnIdCard18(id))
        val session = LocalPiiRedactor.redactText("我的证件号是 $id 请改写", context = null)
        assertTrue("身份证必须被识别为高危", session.hasHighRiskCredentials)
        assertFalse("脱敏后不得含原始身份证", session.redactedText.contains(id))
    }

    @Test
    fun `无 Context 时银行卡号按 Luhn 校验脱敏`() {
        val card = "4111111111111111" // Luhn 通过的 Visa 测试号
        assertTrue("测试卡号夹具本身应合法", LocalPiiRedactor.isValidLuhnBankCard(card))
        val session = LocalPiiRedactor.redactText("卡号 $card 帮我优化措辞", context = null)
        assertTrue("银行卡必须被识别为高危", session.hasHighRiskCredentials)
        assertFalse("脱敏后不得含原始卡号", session.redactedText.contains(card))
    }

    @Test
    fun `上下文密码被脱敏`() {
        val session = LocalPiiRedactor.redactText("密码: hunter2secret 帮我润色", context = null)
        assertTrue("密码必须被识别为高危", session.hasHighRiskCredentials)
        assertFalse("脱敏后不得含明文密码", session.redactedText.contains("hunter2secret"))
    }

    @Test
    fun `普通文本不被误伤`() {
        val raw = "把这句话改得正式一点，明天开会讨论项目进度"
        val session = LocalPiiRedactor.redactText(raw, context = null)
        assertEquals("普通文本应原样通过", raw, session.redactedText)
        assertEquals("普通文本不应有脱敏计数", 0, session.redactionCount)
        assertFalse(session.hasHighRiskCredentials)
    }

    @Test
    fun `空文本安全返回`() {
        val session = LocalPiiRedactor.redactText("", context = null)
        assertEquals("", session.redactedText)
        assertEquals(0, session.redactionCount)
    }

    @Test
    fun `可逆手机号占位符可被还原且高危类别不可还原`() {
        // 无 Context 时手机号靠内置正则（不依赖 TextClassifier），属于 restorable
        val session = LocalPiiRedactor.redactText("手机号 13800138000，证件 110101199003071233", context = null)
        val redacted = session.redactedText
        // 手机号占位符被还原成原值
        assertTrue("可逆手机号应被还原", session.restoreSafePlaceholders(redacted).contains("13800138000"))
        // 身份证占位符永不还原
        assertFalse("高危身份证不得被还原", session.restoreSafePlaceholders(redacted).contains("110101199003071233"))
    }
}
