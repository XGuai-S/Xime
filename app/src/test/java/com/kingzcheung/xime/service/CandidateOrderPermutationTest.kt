package com.kingzcheung.xime.service

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CandidateOrderPermutationTest {
    private class CountingMap(private val source: Map<String, Int>) : Map<String, Int> by source {
        var reads = 0
        override fun get(key: String): Int? { reads++; return source[key] }
    }
    private class CountingList(private val source: List<Int>) : AbstractList<Int>() {
        var reads = 0
        override val size: Int get() = source.size
        override fun get(index: Int): Int { reads++; return source[index] }
    }
    private class CountingSet(private val source: Set<Int>) : Set<Int> by source {
        var queries = 0
        var iterations = 0
        override fun contains(element: Int): Boolean { queries++; return source.contains(element) }
        override fun iterator(): Iterator<Int> { iterations++; return source.iterator() }
    }

    private fun legacyOrder(candidates: List<String>, frequency: Map<String, Int>): List<Int>? {
        val order = candidates.withIndex().sortedWith(
            compareByDescending<IndexedValue<String>> { frequency[it.value] ?: Int.MIN_VALUE }
                .thenBy { it.index },
        ).map { it.index }
        return order.takeUnless { it == candidates.indices.toList() }
    }

    @Test
    fun `empty singleton absent code and empty frequency leave candidates unchanged`() {
        val table = mapOf("code" to mapOf("词" to 2))
        assertNull(computeOrderPermutation(emptyList(), table, "code"))
        assertNull(computeOrderPermutation(listOf("词"), table, "code"))
        assertNull(computeOrderPermutation(listOf("词", "新"), table, ""))
        assertNull(computeOrderPermutation(listOf("词", "新"), table, "absent"))
        assertNull(computeOrderPermutation(listOf("词", "新"), mapOf("code" to emptyMap()), "code"))
    }

    @Test
    fun `already ordered candidates require exactly one weight read per position`() {
        val candidates = List(1024) { "词$it" } + listOf("未知一", "未知二")
        val frequency = CountingMap((0 until 1024).associate { "词$it" to (1024 - it) })
        assertNull(computeOrderPermutation(candidates, mapOf("code" to frequency), "code"))
        assertEquals(candidates.size, frequency.reads)
    }

    @Test
    fun `equal weights and unrecorded candidates preserve original order`() {
        val candidates = listOf("乙", "甲", "乙", "未知一", "未知二")
        assertNull(computeOrderPermutation(candidates, mapOf("code" to mapOf("乙" to 5, "甲" to 5)), "code"))
        assertNull(computeOrderPermutation(candidates, mapOf("code" to mapOf("其他" to 10)), "code"))
    }

    @Test
    fun `reordering caches weights instead of looking them up inside comparisons`() {
        val candidates = (0 until 2048).map { "词$it" }.shuffled(Random(921))
        val values = (0 until 2048).associate { "词$it" to it }
        val optimized = CountingMap(values)
        val legacy = CountingMap(values)
        val result = computeOrderPermutation(candidates, mapOf("code" to optimized), "code")
        assertEquals(legacyOrder(candidates, legacy), result)
        assertNotNull(result)
        assertTrue("at most two linear scans: ${optimized.reads}", optimized.reads <= candidates.size * 2)
        assertTrue("reference must do more lookups", legacy.reads > optimized.reads)
        println("permutation-2048 weight reads: previous=${legacy.reads}, optimized=${optimized.reads}")
    }

    @Test
    fun `extreme negative weights duplicate words and absent words match stable reference`() {
        val candidates = listOf("未知一", "低", "同", "最高", "同", "零", "未知二", "负")
        val frequency = mapOf("低" to Int.MIN_VALUE, "同" to 10, "最高" to Int.MAX_VALUE, "零" to 0, "负" to -1)
        assertEquals(legacyOrder(candidates, frequency), computeOrderPermutation(candidates, mapOf("code" to frequency), "code"))
    }

    @Test
    fun `randomized permutations remain identical to previous behavior without modifying input`() {
        val random = Random(117)
        repeat(500) {
            val candidates = List(random.nextInt(0, 81)) { "词${random.nextInt(0, 24)}" }
            val before = candidates.toList()
            val frequency = (0 until 20).associate { index ->
                "词$index" to when (random.nextInt(5)) {
                    0 -> Int.MIN_VALUE
                    1 -> Int.MAX_VALUE
                    else -> random.nextInt(-20, 21)
                }
            }
            assertEquals(legacyOrder(candidates, frequency), computeOrderPermutation(candidates, mapOf("code" to frequency), "code"))
            assertEquals(before, candidates)
        }
    }

    @Test
    fun `duplicate candidate positions keep comments engine actions and temporary marks aligned`() {
        val candidates = listOf("同", "新", "同", "低", "未知")
        val comments = candidates.indices.map { "comment-$it" }
        val actions = listOf(CandidateAction.engine(0), CandidateAction.plugin("新"),
            CandidateAction.engine(2), CandidateAction.plugin("低"), CandidateAction.engine(4))
        val order = computeOrderPermutation(candidates,
            mapOf("code" to mapOf("同" to 9, "新" to 50, "低" to 1)), "code")!!
        assertEquals(listOf(1, 0, 2, 3, 4), order)
        assertEquals(listOf("comment-1", "comment-0", "comment-2", "comment-3", "comment-4"), order.map { comments[it] })
        val reorderedActions = order.map { actions[it] }
        assertSame(actions[0], reorderedActions[1])
        assertSame(actions[2], reorderedActions[2])
        assertEquals(setOf(0, 3), remapAutoWordTempIndices(order, setOf(1, 3)))
    }

    @Test
    fun `temporary index remapping scans the permutation only once`() {
        val values = (0 until 4096).reversed().toList()
        val permutation = CountingList(values)
        val oldValues = (0 until 4096 step 2).toSet()
        val old = CountingSet(oldValues)
        val result = remapAutoWordTempIndices(permutation, old)
        assertEquals(oldValues.mapTo(HashSet()) { 4095 - it }, result)
        assertEquals(values.size, permutation.reads)
        assertEquals(values.size, old.queries)
        assertEquals(0, old.iterations)
        val emptyInput = CountingList(values)
        assertTrue(remapAutoWordTempIndices(emptyInput, emptySet()).isEmpty())
        assertEquals(0, emptyInput.reads)
    }

    @Test
    fun `temporary index mapping matches indexOf reference including out of range old marks`() {
        val random = Random(66)
        repeat(200) {
            val size = random.nextInt(0, 129)
            val order = (0 until size).shuffled(random)
            val old = (-3..size + 3).filter { random.nextBoolean() }.toSet()
            val expected = old.map { order.indexOf(it) }.filter { it >= 0 }.toSet()
            assertEquals(expected, remapAutoWordTempIndices(order, old))
        }
    }
}
