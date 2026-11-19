package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词频排序只认**编码完全一致**的记录。
 *
 * 曾经按前缀聚合（把 `hckj` 的权重算进 `hck` 的查询），结果是「编码不对应的词被顶到前面」：
 * 打 `hck` 时 `好看` 因为 `hckj` 的权重排到了首位 —— 用户 2026-11-19 明确否掉这个行为。
 * 这几条断言把口径钉住：短码不吃长码的权重。
 */
class CandidateOrderExactMatchTest {

    private val table = mapOf(
        "hck" to mapOf("号" to 20, "豪阔" to 2),
        "hckj" to mapOf("好看" to 40),
        "hc" to mapOf("好" to 99),
    )

    @Test
    fun `exact code returns its own entries`() {
        val f = CandidateOrderStore.collectFrequency(table, "hck")
        assertEquals(20, f["号"])
        assertEquals(2, f["豪阔"])
    }

    @Test
    fun `longer codes do not leak into shorter query`() {
        val f = CandidateOrderStore.collectFrequency(table, "hck")
        // 好看的权重在 hckj 名下，不该出现在 hck 的结果里
        assertEquals(null, f["好看"])
    }

    @Test
    fun `shorter codes do not leak either`() {
        val f = CandidateOrderStore.collectFrequency(table, "hck")
        assertEquals(null, f["好"])
    }

    @Test
    fun `unknown code yields empty map`() {
        assertTrue(CandidateOrderStore.collectFrequency(table, "zzz").isEmpty())
    }

    @Test
    fun `reorder is a no-op when exact code has no weights`() {
        // hckj 有权重但候选列表里没有好看 → 顺序不变，reorder 返回 null
        val r = CandidateOrderStore.reorder(
            code = "hck",
            candidates = listOf("好看", "号"),
            comments = listOf("", ""),
            table = mapOf("hckj" to mapOf("好看" to 40)),
        )
        assertEquals(null, r)
    }
}
