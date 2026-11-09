package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部修改合并（三方合并）纯逻辑测试。
 *
 * 约定：base = 上次写入的文件内容，ours = 内存表，theirs = 用户手改后的文件内容。
 * 合并规则见 [UserDataMerge] 类文档。
 */
class UserDataMergeTest {

    // ── externalChanged ──

    @Test
    fun `外部修改判定_基线为空无从判断`() {
        assertFalse(UserDataMerge.externalChanged(null, "任何内容"))
        assertFalse(UserDataMerge.externalChanged(null, null))
    }

    @Test
    fun `外部修改判定_文件没了不算外部修改`() {
        assertFalse(UserDataMerge.externalChanged("旧内容", null))
    }

    @Test
    fun `外部修改判定_内容相同没改过`() {
        assertFalse(UserDataMerge.externalChanged("同一内容", "同一内容"))
    }

    @Test
    fun `外部修改判定_内容不同即改过`() {
        assertTrue(UserDataMerge.externalChanged("旧内容", "新内容"))
    }

    // ── mergeTable：词表三方合并 ──

    private fun tableOf(vararg codes: Pair<String, Map<String, Int>>): Map<String, Map<String, Int>> =
        linkedMapOf(*codes.map { (c, w) -> c to LinkedHashMap(w) }.toTypedArray())

    @Test
    fun `用户新增词_保留`() {
        val base = tableOf("ni" to mapOf("你" to 5))
        val ours = base
        val theirs = tableOf("ni" to mapOf("你" to 5, "祢" to 3))
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(mapOf("你" to 5, "祢" to 3), merged["ni"])
    }

    @Test
    fun `用户改词_取用户版`() {
        val base = tableOf("ni" to mapOf("你" to 5))
        val ours = tableOf("ni" to mapOf("你" to 7)) // 应用又计了两次
        val theirs = tableOf("ni" to mapOf("你" to 9)) // 用户手改成 9
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(mapOf("你" to 9), merged["ni"])
    }

    @Test
    fun `用户删词_尊重删除_即使内存还有`() {
        val base = tableOf("ni" to mapOf("你" to 5, "尼" to 3))
        val ours = base // 内存没删
        val theirs = tableOf("ni" to mapOf("你" to 5)) // 用户删了「尼」
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(mapOf("你" to 5), merged["ni"])
    }

    @Test
    fun `用户删整个编码_该编码不再出现`() {
        val base = tableOf("ni" to mapOf("你" to 5), "zh" to mapOf("中" to 2))
        val ours = base
        val theirs = tableOf("zh" to mapOf("中" to 2))
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertTrue("ni" !in merged)
        assertEquals(mapOf("中" to 2), merged["zh"])
    }

    @Test
    fun `应用期间新增词_保留`() {
        val base = tableOf("ni" to mapOf("你" to 5))
        val ours = tableOf("ni" to mapOf("你" to 5, "拟" to 2))
        val theirs = base // 用户没动这个文件
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(mapOf("你" to 5, "拟" to 2), merged["ni"])
    }

    @Test
    fun `应用改计数_用户没动_取内存版`() {
        val base = tableOf("ni" to mapOf("你" to 5))
        val ours = tableOf("ni" to mapOf("你" to 8)) // 应用又计了 3 次
        val theirs = base // 用户文件里还是 5（没改）
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(mapOf("你" to 8), merged["ni"])
    }

    @Test
    fun `双方都改同一词_用户优先`() {
        val base = tableOf("ni" to mapOf("你" to 5))
        val ours = tableOf("ni" to mapOf("你" to 8)) // 应用改成 8
        val theirs = tableOf("ni" to mapOf("你" to 9)) // 用户改成 9
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(mapOf("你" to 9), merged["ni"])
    }

    @Test
    fun `用户删了应用期间才删的词_不保留`() {
        val base = tableOf("ni" to mapOf("你" to 5, "尼" to 3))
        val ours = tableOf("ni" to mapOf("你" to 5)) // 应用淘汰了「尼」
        val theirs = tableOf("ni" to mapOf("你" to 5)) // 用户文件里也没有「尼」
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(mapOf("你" to 5), merged["ni"])
    }

    @Test
    fun `用户删了但应用期间又加回来_仍按用户删除`() {
        // base 没有「祢」；应用期间新增了「祢」；用户文件里也没有
        // （没加回来）—— 应用新增的词保留
        val base = tableOf("ni" to mapOf("你" to 5))
        val ours = tableOf("ni" to mapOf("你" to 5, "祢" to 1))
        val theirs = base
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(mapOf("你" to 5, "祢" to 1), merged["ni"])
    }

    @Test
    fun `基线为空表_用户文件全收`() {
        val base = emptyMap<String, Map<String, Int>>()
        val ours = base
        val theirs = tableOf("ni" to mapOf("你" to 5, "祢" to 3))
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(mapOf("你" to 5, "祢" to 3), merged["ni"])
    }

    @Test
    fun `用户清空文件_只剩应用期间新增`() {
        val base = tableOf("ni" to mapOf("你" to 5))
        val ours = tableOf("ni" to mapOf("你" to 5, "祢" to 2)) // 应用新增了「祢」
        val theirs = emptyMap<String, Map<String, Int>>() // 用户把文件清空了
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(mapOf("祢" to 2), merged["ni"])
    }

    @Test
    fun `输出编码顺序稳定_内存序在前_用户独有编码续接`() {
        val base = tableOf("ni" to mapOf("你" to 5))
        val ours = tableOf("ni" to mapOf("你" to 5), "zh" to mapOf("中" to 2))
        val theirs = tableOf("ni" to mapOf("你" to 5), "xi" to mapOf("西" to 1))
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(listOf("ni", "zh", "xi"), merged.keys.toList())
    }

    // ── reconcileTable：文本级入口 ──

    private val wordFreqParse = WordFreqStore::parse

    @Test
    fun `文本级_无外部修改_原样返回内存表`() {
        val memory = tableOf("ni" to mapOf("你" to 5))
        val text = WordFreqStore.serialize(memory)
        val merged = UserDataMerge.reconcileTable(text, text, memory, wordFreqParse)
        assertTrue("无外部修改应返回同一实例", merged === memory)
    }

    @Test
    fun `文本级_文件读不到_不合并`() {
        val memory = tableOf("ni" to mapOf("你" to 5))
        val merged = UserDataMerge.reconcileTable("基线", null, memory, wordFreqParse)
        assertTrue(merged === memory)
    }

    @Test
    fun `文本级_基线为空_不合并`() {
        val memory = tableOf("ni" to mapOf("你" to 5))
        val merged = UserDataMerge.reconcileTable(null, "文件内容", memory, wordFreqParse)
        assertTrue(merged === memory)
    }

    @Test
    fun `文本级_用户手改一行_合并进内存`() {
        val memory = tableOf("ni" to mapOf("你" to 5))
        val baseline = WordFreqStore.serialize(memory)
        // 用户在文件里加了一行「祢」
        val edited = baseline + "祢\tni\t3\n"
        val merged = UserDataMerge.reconcileTable(baseline, edited, memory, wordFreqParse)
        assertEquals(mapOf("你" to 5, "祢" to 3), merged["ni"])
    }

    @Test
    fun `文本级_用户只写注释和坏行_合并结果为空`() {
        val memory = emptyMap<String, Map<String, Int>>()
        val baseline = WordFreqStore.serialize(memory) // 只有表头
        val edited = baseline + "# 我自己加的注释\n这行没有制表符\n"
        val merged = UserDataMerge.reconcileTable(baseline, edited, memory, wordFreqParse)
        assertTrue(merged.isEmpty())
    }

    // ── mergeSet / reconcileSet：黑名单 ──

    @Test
    fun `集合合并_用户加词`() {
        val merged = UserDataMerge.mergeSet(setOf("想的"), setOf("想的"), setOf("想的", "想得"))
        assertEquals(setOf("想的", "想得"), merged)
    }

    @Test
    fun `集合合并_用户删词_内存里还有也删`() {
        val merged = UserDataMerge.mergeSet(setOf("想的", "想得"), setOf("想的", "想得"), setOf("想的"))
        assertEquals(setOf("想的"), merged)
    }

    @Test
    fun `集合合并_应用期间加的词_用户文件里没有_保留`() {
        // 应用长按删除加了「新词」；用户编辑文件时没提它——它本来就不在文件里，
        // 缺席不是删除信号
        val merged = UserDataMerge.mergeSet(setOf("想的"), setOf("想的", "新词"), setOf("想的"))
        assertEquals(setOf("想的", "新词"), merged)
    }

    @Test
    fun `集合合并_应用删了但用户文件还留着_按用户保留`() {
        val merged = UserDataMerge.mergeSet(
            base = setOf("想的", "想得"),
            ours = setOf("想的"), // 应用把「想得」移出了黑名单
            theirs = setOf("想的", "想得"), // 用户文件里还有
        )
        assertEquals(setOf("想的", "想得"), merged)
    }

    @Test
    fun `集合合并_两边都加_取并集`() {
        val merged = UserDataMerge.mergeSet(
            base = setOf("a"),
            ours = setOf("a", "b"), // 应用加的
            theirs = setOf("a", "c"), // 用户加的
        )
        assertEquals(setOf("a", "b", "c"), merged)
    }

    @Test
    fun `集合文本级_无外部修改_原样返回`() {
        val memory = mapOf("xxmn" to mapOf("霞喵" to 1))
        val text = WordBlacklistStore.serialize(memory)
        val merged = UserDataMerge.reconcileTable(text, text, memory, WordBlacklistStore::parse)
        assertTrue(merged === memory)
    }
    @Test
    fun `集合文本级_用户删一行_合并后消失`() {
        val memory = mapOf("xxmn" to mapOf("霞喵" to 1, "虾苗" to 1))
        val baseline = WordBlacklistStore.serialize(memory)
        val edited = WordBlacklistStore.serialize(mapOf("xxmn" to mapOf("霞喵" to 1))) // 用户删了「虾苗」
        val merged = UserDataMerge.reconcileTable(baseline, edited, memory, WordBlacklistStore::parse)
        assertEquals(mapOf("xxmn" to mapOf("霞喵" to 1)), merged)
    }

    // ── 泛型值：自动造词 Entry（数据类，结构相等） ──

    @Test
    fun `Entry值_用户改次数_取用户版`() {
        fun entry(count: Int, lastSeen: Long) =
            AutoWordStore.Entry(word = "目录", code = "ml", firstSeen = 1000L, count = count, lastSeen = lastSeen)
        val base = mapOf("ml" to mapOf("目录" to entry(1, 1000L)))
        val ours = mapOf("ml" to mapOf("目录" to entry(3, 5000L))) // 应用又计了两次
        val theirs = mapOf("ml" to mapOf("目录" to entry(9, 1000L))) // 用户手改成 9 次
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(entry(9, 1000L), merged["ml"]?.get("目录"))
    }

    @Test
    fun `Entry值_用户没动_取内存版`() {
        fun entry(count: Int, lastSeen: Long) =
            AutoWordStore.Entry(word = "目录", code = "ml", firstSeen = 1000L, count = count, lastSeen = lastSeen)
        val base = mapOf("ml" to mapOf("目录" to entry(1, 1000L)))
        val ours = mapOf("ml" to mapOf("目录" to entry(2, 6000L)))
        val theirs = base
        val merged = UserDataMerge.mergeTable(base, ours, theirs)
        assertEquals(entry(2, 6000L), merged["ml"]?.get("目录"))
    }
}
