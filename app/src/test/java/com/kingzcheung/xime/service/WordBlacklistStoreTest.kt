package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 黑名单词库：解析/加入/过滤（纯逻辑，无 IO）。 */
class WordBlacklistStoreTest {

    @Test
    fun `解析跳过注释与空行`() {
        val words = WordBlacklistStore.parse("# 表头\n\n想得\n想的\n")
        assertEquals(setOf("想得", "想的"), words)
    }

    @Test
    fun `加入与去重`() {
        val first = WordBlacklistStore.withBlocked(emptySet(), "想的")
        assertEquals(setOf("想的"), first)
        // 已在黑名单里 → 返回同一实例（调用方据此跳过写盘）
        assertTrue(first === WordBlacklistStore.withBlocked(first, "想的"))
    }

    @Test
    fun `序列化回读一致`() {
        val words = linkedSetOf("想的", "想得")
        assertEquals(words, WordBlacklistStore.parse(WordBlacklistStore.serialize(words)))
    }

    @Test
    fun `过滤黑名单词并同步注释与动作`() {
        val blocked = setOf("想的")
        val actions = listOf(
            CandidateAction.engine(0),
            CandidateAction.plugin("想得"),
            CandidateAction.engine(2),
        )
        val filtered = WordBlacklistStore.filter(
            candidates = listOf("想的", "想得", "都是"),
            comments = listOf("", "注", ""),
            actions = actions,
            blocked = blocked,
        )
        assertEquals(listOf("想得", "都是"), filtered?.candidates)
        assertEquals(listOf("注", ""), filtered?.comments)
        // 动作必须跟着候选中留下来的那条走，不能错位到别的候选上
        assertEquals(actions[1], filtered?.actions?.get(0))
        assertEquals(actions[2], filtered?.actions?.get(1))
    }

    @Test
    fun `无需过滤时返回空表示不干预`() {
        assertNull(
            WordBlacklistStore.filter(
                candidates = listOf("想得"), comments = listOf(""),
                actions = listOf(CandidateAction.engine(0)), blocked = emptySet(),
            )
        )
        assertNull(
            WordBlacklistStore.filter(
                candidates = listOf("想得"), comments = listOf(""),
                actions = listOf(CandidateAction.engine(0)), blocked = setOf("别的词"),
            )
        )
    }
}
