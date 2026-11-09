package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 黑名单词库：解析/加入/过滤（纯逻辑，无 IO）。 */
class WordBlacklistStoreTest {

    @Test
    fun `解析跳过注释与空行`() {
        val table = WordBlacklistStore.parse("# 表头\n\n想得\tab\n想的\tcd\n")
        assertEquals(mapOf("ab" to mapOf("想得" to 1), "cd" to mapOf("想的" to 1)), table)
    }

    @Test
    fun `解析兼容旧格式_一行一词无编码`() {
        // 旧数据没有编码列 → 归入空编码（按词全局屏蔽）
        val table = WordBlacklistStore.parse("# 表头\n想得\n想的\n")
        assertEquals(mapOf("" to mapOf("想得" to 1, "想的" to 1)), table)
    }

    @Test
    fun `加入与去重`() {
        val first = WordBlacklistStore.withBlocked(emptyMap(), "想的", "ab")
        assertEquals(mapOf("ab" to mapOf("想的" to 1)), first)
        // 同编码同词已在黑名单 → 返回同一实例（调用方据此跳过写盘）
        assertTrue(first === WordBlacklistStore.withBlocked(first, "想的", "ab"))
        // 同词不同编码 → 新增条目
        val second = WordBlacklistStore.withBlocked(first, "想的", "cd")
        assertEquals(mapOf("ab" to mapOf("想的" to 1), "cd" to mapOf("想的" to 1)), second)
    }

    @Test
    fun `序列化回读一致`() {
        val table = linkedMapOf(
            "ab" to linkedMapOf("想的" to 1, "想得" to 1),
            "cd" to linkedMapOf("都是" to 1),
        )
        assertEquals(table, WordBlacklistStore.parse(WordBlacklistStore.serialize(table)))
    }

    @Test
    fun `按编码屏蔽_不影响其它编码下的同词`() {
        val table = mapOf("ab" to mapOf("想的" to 1))
        // 当前编码 ab → 屏蔽「想的」
        assertEquals(setOf("想的"), WordBlacklistStore.blockedFor(table, "ab"))
        // 其它编码 cd → 不屏蔽
        assertTrue(WordBlacklistStore.blockedFor(table, "cd").isEmpty())
    }

    @Test
    fun `空编码条目全局屏蔽`() {
        val table = mapOf("" to mapOf("想得" to 1))
        assertEquals(setOf("想得"), WordBlacklistStore.blockedFor(table, "ab"))
        assertEquals(setOf("想得"), WordBlacklistStore.blockedFor(table, ""))
    }

    @Test
    fun `过滤黑名单词并同步注释与动作`() {
        val table = mapOf("ab" to mapOf("想的" to 1))
        val actions = listOf(
            CandidateAction.engine(0),
            CandidateAction.plugin("想得"),
            CandidateAction.engine(2),
        )
        val filtered = WordBlacklistStore.filter(
            candidates = listOf("想的", "想得", "都是"),
            comments = listOf("", "注", ""),
            actions = actions,
            table = table,
            code = "ab",
        )
        assertEquals(listOf("想得", "都是"), filtered?.candidates)
        assertEquals(listOf("注", ""), filtered?.comments)
        // 动作必须跟着候选中留下来的那条走，不能错位到别的候选上
        assertEquals(actions[1], filtered?.actions?.get(0))
        assertEquals(actions[2], filtered?.actions?.get(1))
    }

    @Test
    fun `编码不匹配时不过滤`() {
        val table = mapOf("ab" to mapOf("想的" to 1))
        assertNull(
            WordBlacklistStore.filter(
                candidates = listOf("想的"), comments = listOf(""),
                actions = listOf(CandidateAction.engine(0)),
                table = table, code = "cd",
            )
        )
    }

    @Test
    fun `无需过滤时返回空表示不干预`() {
        assertNull(
            WordBlacklistStore.filter(
                candidates = listOf("想得"), comments = listOf(""),
                actions = listOf(CandidateAction.engine(0)), table = emptyMap(), code = "ab",
            )
        )
        assertNull(
            WordBlacklistStore.filter(
                candidates = listOf("想得"), comments = listOf(""),
                actions = listOf(CandidateAction.engine(0)),
                table = mapOf("ab" to mapOf("别的词" to 1)), code = "ab",
            )
        )
    }
}
