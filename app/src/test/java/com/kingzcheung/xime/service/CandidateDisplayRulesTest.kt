package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选栏显示规则的纯函数测试。
 *
 * 这些规则原先内联在 ImeSessionController 的两个入口里各写一遍，抽取后成为唯一实现；
 * 此处把行为钉住，避免以后改动两条路径时再次分叉。
 */
class CandidateDisplayRulesTest {

    private fun cand(text: String, comment: String = "") = RimeCandidate(text, comment)

    // ---------- 候选过滤 ----------

    @Test
    fun `ASCII 模式过滤掉含汉字的候选`() {
        val (texts, comments) = filterCandidatesForDisplay(
            isAsciiMode = true,
            candidates = arrayOf(cand("hello", "c1"), cand("你好", "c2"), cand("wor", "c3")),
        )
        assertEquals(listOf("hello", "wor"), texts)
        assertEquals(listOf("c1", "c3"), comments)
    }

    @Test
    fun `非 ASCII 模式保留全部候选`() {
        val (texts, comments) = filterCandidatesForDisplay(
            isAsciiMode = false,
            candidates = arrayOf(cand("hello", "c1"), cand("你好", "c2")),
        )
        assertEquals(listOf("hello", "你好"), texts)
        assertEquals(listOf("c1", "c2"), comments)
    }

    @Test
    fun `ASCII 模式只按汉字过滤 字母数字标点都保留`() {
        val (texts, _) = filterCandidatesForDisplay(
            isAsciiMode = true,
            candidates = arrayOf(cand("abc"), cand("123"), cand("a-b_c"), cand("中英mix")),
        )
        assertEquals(listOf("abc", "123", "a-b_c"), texts)
    }

    @Test
    fun `汉字区间边界 含区间内字符即被过滤`() {
        val (texts, _) = filterCandidatesForDisplay(
            isAsciiMode = true,
            candidates = arrayOf(
                cand("\u4DFF"), // 区间下界之前
                cand("\u4E00"), // 区间下界（一）
                cand("\u9FFF"), // 区间上界
                cand("\uA000"), // 区间上界之后
            ),
        )
        assertEquals(listOf("\u4DFF", "\uA000"), texts)
    }

    @Test
    fun `候选文本与注释始终平行`() {
        val (texts, comments) = filterCandidatesForDisplay(
            isAsciiMode = false,
            candidates = arrayOf(cand("a", "注释a"), cand("b"), cand("c", "注释c")),
        )
        assertEquals(listOf("a", "b", "c"), texts)
        assertEquals(listOf("注释a", "", "注释c"), comments)
    }

    @Test
    fun `空候选列表返回空结果`() {
        val (texts, comments) = filterCandidatesForDisplay(isAsciiMode = true, candidates = emptyArray())
        assertEquals(emptyList<String>(), texts)
        assertEquals(emptyList<String>(), comments)
    }

    // ---------- 显示文本 ----------

    @Test
    fun `preedit 非空时显示 preedit 并保留音节分隔符`() {
        assertEquals("ni'hao", resolveDisplayText(preeditText = "ni'hao", inputText = "nihao"))
    }

    @Test
    fun `preedit 为空时回退原始键入串`() {
        assertEquals("nihao", resolveDisplayText(preeditText = "", inputText = "nihao"))
    }

    @Test
    fun `两者都为空时显示空串`() {
        assertEquals("", resolveDisplayText(preeditText = "", inputText = ""))
    }

    // ---------- T9 raw preedit ----------

    @Test
    fun `T9 字母 preedit 直接采用`() {
        assertEquals("ni", resolveT9RawPreedit(preeditText = "ni", inputText = "64", lastPreedit = "上一帧"))
    }

    @Test
    fun `T9 preedit 含未转换数字时保留上一帧字母编码`() {
        assertEquals("上一帧", resolveT9RawPreedit(preeditText = "64", inputText = "64", lastPreedit = "上一帧"))
    }

    @Test
    fun `T9 preedit 为空但输入非空时保留上一帧字母编码`() {
        assertEquals("ji", resolveT9RawPreedit(preeditText = "", inputText = "54", lastPreedit = "ji"))
    }

    @Test
    fun `T9 输入为空时返回空串 不沿用上一帧`() {
        assertEquals("", resolveT9RawPreedit(preeditText = "", inputText = "", lastPreedit = "上一帧"))
    }

    @Test
    fun `T9 混合数字与字母的 preedit 视为未转换 保留上一帧`() {
        assertEquals("ji", resolveT9RawPreedit(preeditText = "ji5", inputText = "ji5", lastPreedit = "ji"))
    }

    // ---------- 候选排序应用条件 ----------

    @Test
    fun `纯引擎中文路径允许排序`() {
        assertTrue(shouldApplyCandidateOrder(isT9 = false, isAsciiMode = false, candidateActions = emptyList()))
    }

    @Test
    fun `T9 路径不排序`() {
        assertFalse(shouldApplyCandidateOrder(isT9 = true, isAsciiMode = false, candidateActions = emptyList()))
    }

    @Test
    fun `ASCII 模式不排序 引擎候选被过滤后索引不可信`() {
        assertFalse(shouldApplyCandidateOrder(isT9 = false, isAsciiMode = true, candidateActions = emptyList()))
    }

    @Test
    fun `带插件动作不排序 已有显示到引擎的映射不能被打乱`() {
        assertFalse(
            shouldApplyCandidateOrder(
                isT9 = false,
                isAsciiMode = false,
                candidateActions = listOf(CandidateAction.engine(0)),
            )
        )
    }
}
