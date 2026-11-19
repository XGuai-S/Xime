package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 词频「按前缀聚合」的护栏。
 *
 * 背景：小鹤音形里同一个词能用不同码长上屏（`好看` 既可打 `hck` 也可打 `hckj`）。
 * 如果查询只认精确编码，权重就会被码长割裂：按 `hckj` 上屏记的分，打 `hck` 时一点用不上。
 * 这里把「聚合到哪些词、同一词取最大而不是累加」两件事固定下来。
 */
class CandidateOrderPrefixAggregationTest {

    private val table = mapOf(
        "hck" to mapOf("号" to 20, "豪阔" to 2),
        "hckj" to mapOf("好看" to 29),
        "hckh" to mapOf("好康" to 1),
        "hc" to mapOf("好" to 99),
        "ab" to mapOf("啊吧" to 7),
    )

    @Test
    fun `exact code merges longer same-prefix codes`() {
        val f = CandidateOrderStore.collectFrequency(table, "hck")
        assertEquals(20, f["号"])
        assertEquals(2, f["豪阔"])
        // 3 码查询必须拿到 4 码记录的权重，否则「按 hckj 上屏的词」在 3 码下排不上
        assertEquals(29, f["好看"])
        assertEquals(1, f["好康"])
    }

    @Test
    fun `shorter parent code is not pulled in`() {
        val f = CandidateOrderStore.collectFrequency(table, "hck")
        // "hc" 是 hck 的前缀而不是反过来，不能被并进来
        assertNull(f["好"])
        // 无关编码也不能混入
        assertNull(f["啊吧"])
    }

    @Test
    fun `code without exact entry still aggregates children`() {
        val f = CandidateOrderStore.collectFrequency(table, "abcd")
        assertFalse(f.isNotEmpty())

        val onlyChildren = mapOf("xyzw" to mapOf("词" to 3))
        val f2 = CandidateOrderStore.collectFrequency(onlyChildren, "xyz")
        assertEquals(3, f2["词"])
    }

    @Test
    fun `same word under two codes takes max not sum`() {
        val t = mapOf(
            "hck" to mapOf("好看" to 10),
            "hckj" to mapOf("好看" to 29),
        )
        val f = CandidateOrderStore.collectFrequency(t, "hck")
        // 取最大（29），不是累加（39）：同一个词用两种码长各打一次不代表打了两次
        assertEquals(29, f["好看"])
    }

    @Test
    fun `empty table yields empty map`() {
        assertFalse(CandidateOrderStore.collectFrequency(emptyMap(), "hck").isNotEmpty())
    }
}
