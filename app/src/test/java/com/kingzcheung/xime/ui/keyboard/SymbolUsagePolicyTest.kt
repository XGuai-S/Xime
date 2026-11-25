package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运算符列的自适应排序（纯逻辑，不碰 Android）。
 *
 * 用户要求「用了立刻刷新位置排序」，核心不变量有三条：
 * 1. 四则 `+ - * /` 永远在最前且顺序固定（肌肉记忆位置不能跳）；
 * 2. 其余符号按点击次数降序，越常用越靠上；
 * 3. 同次数时保持默认表的原始顺序（新用户看到的是按日常频率排好的顺序，不会乱）。
 */
class SymbolUsagePolicyTest {

    @Test
    fun `arithmetic stays pinned at the front in fixed order`() {
        // 即便别的符号用了很多次，四则也不让位、不改相对顺序
        val counts = mapOf("=" to 999, "@" to 500, "#" to 300)
        val ordered = SymbolUsagePolicy.orderedSymbols(counts)

        assertEquals(
            "四则必须固定在最前，实际 ${ordered.take(4)}",
            listOf("+", "-", "*", "/"),
            ordered.take(4),
        )
    }

    @Test
    fun `most used symbol moves right behind the arithmetic block`() {
        val ordered = SymbolUsagePolicy.orderedSymbols(mapOf("=" to 10, "." to 3))

        assertEquals("使用最多的符号应紧跟四则之后", "=", ordered[4])
        assertEquals("次数次之的排在其后", ".", ordered[5])
    }

    @Test
    fun `unused symbols keep default order`() {
        // 一次都没点过：结果必须与默认表完全一致
        assertEquals(
            "无使用记录时应保持默认表顺序",
            SymbolUsagePolicy.DEFAULT_SYMBOLS,
            SymbolUsagePolicy.orderedSymbols(emptyMap()),
        )
    }

    @Test
    fun `equal counts preserve default order`() {
        val base = SymbolUsagePolicy.orderedSymbols(emptyMap())
        val counts = mapOf("=" to 5, "%" to 5, "." to 5)
        val ordered = SymbolUsagePolicy.orderedSymbols(counts)

        // 三者次数相同 → 相对顺序应与默认表一致
        val baseRel = base.filter { it in counts.keys }
        val nowRel = ordered.filter { it in counts.keys }
        assertEquals("同次数必须保持默认相对顺序（稳定排序）", baseRel, nowRel)
    }

    @Test
    fun `increment is immutable and accumulates`() {
        val first = SymbolUsagePolicy.withIncrement(emptyMap(), "=")
        val second = SymbolUsagePolicy.withIncrement(first, "=")

        assertEquals("原表不可被修改", 0, emptyMap<String, Int>()["="] ?: 0)
        assertEquals(1, first["="])
        assertEquals(2, second["="])
    }

    @Test
    fun `serialize and parse round trip`() {
        val counts = mapOf("=" to 7, "." to 2, "@" to 1)
        val text = SymbolUsagePolicy.serialize(counts)
        val parsed = SymbolUsagePolicy.parse(text)

        assertEquals("序列化后必须能原样解析回来", counts.toMap(), parsed.toMap())
        assertTrue("序列化结果应带说明头", text.startsWith("#"))
        // 次数降序输出，便于人工查看
        val order = parsed.entries.sortedByDescending { it.value }.map { it.key }
        assertEquals(listOf("=", ".", "@"), order)
    }

    @Test
    fun `parse skips malformed and zero lines`() {
        val text = """
            # comment
            无次数
            =	0
            %	3
            @	not-a-number
        """.trimIndent()
        assertEquals(mapOf("%" to 3), SymbolUsagePolicy.parse(text))
    }

    @Test
    fun `default table contains no duplicates and covers pinned symbols`() {
        val list = SymbolUsagePolicy.DEFAULT_SYMBOLS
        assertEquals("默认表不能有重复符号", list.size, list.toSet().size)
        assertTrue("默认表必须包含四则", list.containsAll(SymbolUsagePolicy.PINNED))
    }
}