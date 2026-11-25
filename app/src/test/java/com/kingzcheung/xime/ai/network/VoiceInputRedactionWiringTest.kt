package com.kingzcheung.xime.ai.network

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音润色输入框文本脱敏的接线契约。
 *
 * 缺陷：`processVoiceAudio` 把 selectedText / surroundingBeforeCursor / editorDraft
 * 原样拼进 prompt 送云端 LLM，从未经过 [LocalPiiRedactor]。用户正在编辑的密码、
 * 证件号、银行卡、API key 会明文出境。
 *
 * 修法：入口用 redactVoiceInput 脱敏，用脱敏值构建 prompt；出口用 restore 还原可逆占位符。
 * JVM 单测无法跑通网络调用，故用源码契约钉住四点接线；脱敏工具本身的行为
 * 由 VoiceRedactionContractTest 覆盖。
 */
class VoiceInputRedactionWiringTest {

    private val engineSource: String by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/ai/network/AiPipelineEngine.kt").readText()
    }

    /** 截取 processVoiceAudio 方法体（到下一个同级 fun 为止）。 */
    private fun processVoiceAudioBody(): String {
        val start = engineSource.indexOf("suspend fun processVoiceAudio(")
        assertTrue("找不到 processVoiceAudio", start >= 0)
        val rest = engineSource.substring(start)
        val next = rest.indexOf("\n    private fun ", 1)
            .let { if (it < 0) rest.length else it }
        return rest.substring(0, next)
    }

    @Test
    fun `入口必须调用脱敏预处理`() {
        val body = processVoiceAudioBody()
        assertTrue(
            "processVoiceAudio 必须对输入框文本调用 redactVoiceInput",
            "redactVoiceInput(selectedText, surroundingBeforeCursor, editorDraft)" in body,
        )
    }

    @Test
    fun `prompt 使用脱敏后的选中文本而非原始参数`() {
        val body = processVoiceAudioBody()
        assertFalse(
            "prompt 不得再直接引用原始 selectedText",
            "selectedText = selectedText," in body,
        )
        assertTrue(
            "prompt 必须使用 redaction.selectedText",
            "selectedText = redaction.selectedText," in body,
        )
    }

    @Test
    fun `prompt 使用脱敏后的编辑框全文`() {
        val body = processVoiceAudioBody()
        assertFalse("prompt 不得再直接引用原始 editorDraft", "editorDraft = editorDraft" in body)
        assertTrue("prompt 必须使用 redaction.editorDraft", "editorDraft = redaction.editorDraft" in body)
    }

    @Test
    fun `结果出口必须还原可逆占位符`() {
        val body = processVoiceAudioBody()
        assertTrue(
            "结果 finalOutput 必须经 redaction.restore 还原可逆占位符",
            "finalOutput = redaction.restore(" in body,
        )
    }

    @Test
    fun `高危凭据占位符不得被还原`() {
        // restore 只调 restoreSafePlaceholders（该方法文档与实现保证高危类别不还原）
        val privacy = RepoFiles.file("src/main/java/com/kingzcheung/xime/ai/privacy/LocalPiiRedactor.kt").readText()
        val m = Regex("""fun restoreSafePlaceholders\(.*?\n\s*\}""", RegexOption.DOT_MATCHES_ALL)
            .find(privacy) ?: error("找不到 restoreSafePlaceholders")
        assertTrue(
            "restoreSafePlaceholders 必须只还原 restorable 类别",
            "category.restorable" in m.value,
        )
    }
}
