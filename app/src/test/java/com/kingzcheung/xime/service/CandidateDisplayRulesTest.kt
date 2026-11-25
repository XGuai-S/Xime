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

    // ---------- 顶屏词与候选栏首词对齐 ----------

    /**
     * 回归：反馈「自动顶屏没有顶候选栏首码，顶的是次选」。
     *
     * 满码顶屏由 librime 直接 commit 它自己的首选；候选栏展示的是重排后的顺序。
     * 两者不一致时必须改以用户看到的首词上屏。
     */
    @Test
    fun `顶屏提交的是候选栏第二条时改以首词上屏`() {
        assertEquals(
            "你",
            resolveAutoCommitTop(
                committed = "尼",
                displayedCandidates = listOf("你", "尼", "泥"),
                isAsciiMode = false,
                preKeyCodeLength = 4,
            ),
        )
    }

    @Test
    fun `顶屏提交的词就是候选栏首词时原样返回`() {
        assertEquals(
            "你",
            resolveAutoCommitTop(
                committed = "你",
                displayedCandidates = listOf("你", "尼"),
                isAsciiMode = false,
                preKeyCodeLength = 4,
            ),
        )
    }

    @Test
    fun `编码未满时不得用上一屏首词替换 那是更长编码新算出的词`() {
        // 前缀命中：打 3 码时候选里有 A，第 4 码引擎算出别的词并提交。
        // 若拿上一屏首词覆盖，会把用户没打完的词顶出去。
        assertEquals(
            "新算出的词",
            resolveAutoCommitTop(
                committed = "新算出的词",
                displayedCandidates = listOf("上一屏首词", "新算出的词"),
                isAsciiMode = false,
                preKeyCodeLength = 3,
            ),
        )
    }

    @Test
    fun `提交的词不在按键前那一屏里时不得改写`() {
        assertEquals(
            "别的词",
            resolveAutoCommitTop(
                committed = "别的词",
                displayedCandidates = listOf("你", "尼"),
                isAsciiMode = false,
                preKeyCodeLength = 4,
            ),
        )
    }

    @Test
    fun `英文模式不改写提交词`() {
        assertEquals(
            "b",
            resolveAutoCommitTop(
                committed = "b",
                displayedCandidates = listOf("a", "b"),
                isAsciiMode = true,
                preKeyCodeLength = 4,
            ),
        )
    }

    @Test
    fun `空提交与空候选都原样返回 不越界不改写`() {
        assertEquals("", resolveAutoCommitTop("", listOf("你"), isAsciiMode = false, preKeyCodeLength = 4))
        assertEquals("你", resolveAutoCommitTop("你", emptyList(), isAsciiMode = false, preKeyCodeLength = 4))
    }

    @Test
    fun `候选栏首词为空串时不改写`() {
        assertEquals(
            "尼",
            resolveAutoCommitTop(
                committed = "尼",
                displayedCandidates = listOf("", "尼"),
                isAsciiMode = false,
                preKeyCodeLength = 4,
            ),
        )
    }

    // ---------- 上屏实际消费的编码 ----------

    /**
     * 回归：反馈「排序后没有更新状态」。
     *
     * 满码后的这一键是顶字上屏，消费的是**上一键已成型**的那段编码；把本键拼进去
     * 会记成 5 码，4 码的词频永远涨不上去，排序也就不动。
     */
    @Test
    fun `满码后按键 消费的是上一键已成型的编码 不含本键`() {
        assertEquals("hckj", resolveConsumedCode(preKeyInput = "hckj", key = "a"))
    }

    @Test
    fun `编码未满时按键 消费的是编码加本键`() {
        assertEquals("hck", resolveConsumedCode(preKeyInput = "hc", key = "k"))
        assertEquals("hckj", resolveConsumedCode(preKeyInput = "hck", key = "j"))
    }

    @Test
    fun `编码为空时 消费的就是本键`() {
        assertEquals("h", resolveConsumedCode(preKeyInput = "", key = "h"))
    }

    @Test
    fun `编码为空且本键非单字符时返回 null 由调用方回退 UI 快照`() {
        assertEquals(null, resolveConsumedCode(preKeyInput = "", key = ""))
    }

    @Test
    fun `非单字符键不改写已有编码`() {
        assertEquals("hckj", resolveConsumedCode(preKeyInput = "hckj", key = "shift"))
        assertEquals("hck", resolveConsumedCode(preKeyInput = "hck", key = ""))
    }

    @Test
    fun `本键大写时按小写记录编码`() {
        assertEquals("hck", resolveConsumedCode(preKeyInput = "hc", key = "K"))
    }

    @Test
    fun `满码边界与 PUSH_OUT_MIN_CODE_LENGTH 一致`() {
        // 恰好达到满码长度：走「已满」分支，不含本键。
        val full = "hckj".take(PUSH_OUT_MIN_CODE_LENGTH)
        assertEquals(PUSH_OUT_MIN_CODE_LENGTH, full.length)
        assertEquals(full, resolveConsumedCode(preKeyInput = full, key = "x"))

        // 少一码：走拼接分支，本键算进本段编码。
        val oneShort = "hckj".take(PUSH_OUT_MIN_CODE_LENGTH - 1)
        assertEquals(oneShort.length, PUSH_OUT_MIN_CODE_LENGTH - 1)
        assertEquals(oneShort + "x", resolveConsumedCode(preKeyInput = oneShort, key = "x"))

        // 两者结果必须不同：同一条编码不能既算「上一段」又算「本段」，否则顶屏记账会错。
        assertFalse(
            resolveConsumedCode(preKeyInput = full, key = "x") ==
                resolveConsumedCode(preKeyInput = oneShort, key = "x"),
        )
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
