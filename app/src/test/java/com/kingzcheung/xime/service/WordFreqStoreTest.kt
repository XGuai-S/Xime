package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordFreqStoreTest {

    // ── isCountableWord ──

    @Test
    fun `汉字词可计入_含单字与连续汉字串`() {
        assertTrue(WordFreqStore.isCountableWord("今天"))
        assertTrue(WordFreqStore.isCountableWord("天气不错"))
        // 单字汉字也计入（词频表是上屏历史镜像，只按字面记录）
        assertTrue(WordFreqStore.isCountableWord("今"))
    }

    @Test
    fun `非汉字与空串超长不可计入`() {
        // 单字汉字 "今" 现在计入，不再列入不可计入项
        assertFalse(WordFreqStore.isCountableWord("，")) // 标点
        assertFalse(WordFreqStore.isCountableWord(" ")) // 空白
        assertFalse(WordFreqStore.isCountableWord("")) // 空串
        assertFalse(WordFreqStore.isCountableWord("a")) // 英文
        assertFalse(WordFreqStore.isCountableWord("123456789012345678901234567890123")) // 33 字数字串
    }

    @Test
    fun `含空白或标点的整句不可计入`() {
        assertFalse(WordFreqStore.isCountableWord("你好 世界"))
        assertFalse(WordFreqStore.isCountableWord("你好，世界"))
    }

    // ── parse / serialize ──

    @Test
    fun `解析词频表`() {
        val text = "# 上屏词频：词\t编码\t频数（每次上屏自动 +1）\n" +
            "今天\tjt\t5\n" +
            "天气\tjt\t2\n" +
            "坏行被跳过\tbad-line\n"
        val table = WordFreqStore.parse(text)
        assertEquals(mapOf("今天" to 5, "天气" to 2), table["jt"])
    }

    @Test
    fun `序列化后回读一致`() {
        val table = WordFreqStore.withCommit(
            WordFreqStore.withCommit(emptyMap(), "jt", "今天"),
            "jt", "今天"
        )
        val text = WordFreqStore.serialize(table)
        assertEquals(table, WordFreqStore.parse(text))
    }

    // ── withCommit ──

    @Test
    fun `同一词上屏两次频数累加`() {
        var table = emptyMap<String, Map<String, Int>>()
        table = WordFreqStore.withCommit(table, "jt", "今天")
        table = WordFreqStore.withCommit(table, "jt", "今天")
        assertEquals(2, table["jt"]?.get("今天"))
    }

    @Test
    fun `不同词同编码各自计数`() {
        var table = emptyMap<String, Map<String, Int>>()
        table = WordFreqStore.withCommit(table, "jt", "今天")
        table = WordFreqStore.withCommit(table, "jt", "天气")
        assertEquals(1, table["jt"]?.get("今天"))
        assertEquals(1, table["jt"]?.get("天气"))
    }

    @Test
    fun `不可计入的词不改表`() {
        val table = emptyMap<String, Map<String, Int>>()
        // 编码为空：不记录（无法回查）
        assertTrue(WordFreqStore.withCommit(table, "", "今天") === table)
        // 非汉字内容（含空白/标点/英文/数字）不计，返回原表
        assertTrue(WordFreqStore.withCommit(table, "a", "abc") === table)
    }

    // ── reorder ──

    @Test
    fun `按频数降序重排候选`() {
        // 「天气」频数 10 更高，应排到「今天」前
        val table = mapOf("jt" to mapOf("天气" to 10, "今天" to 5))
        val result = WordFreqStore.reorder(
            code = "jt",
            candidates = listOf("今天", "天气"),
            comments = listOf("", ""),
            table = table,
        )
        assertEquals(listOf("天气", "今天"), result?.candidates)
        // 显示位置变了，点选必须回到原引擎下标
        assertEquals(listOf(CandidateAction.engine(1), CandidateAction.engine(0)), result?.actions)
    }

    @Test
    fun `无统计记录不干预`() {
        assertNull(
            WordFreqStore.reorder("jt", listOf("今天"), listOf(""), emptyMap())
        )
        assertNull(
            WordFreqStore.reorder("jt", listOf("今天"), listOf(""), mapOf("orq" to mapOf("x" to 1)))
        )
    }

    @Test
    fun `顺序本就无需变化返回null`() {
        val table = mapOf("jt" to mapOf("今天" to 10, "天气" to 5))
        assertNull(
            WordFreqStore.reorder("jt", listOf("今天", "天气"), listOf("", ""), table)
        )
    }

    @Test
    fun `未统计的词保持原顺序排在后`() {
        // 候选顺序 [不明词, 今天]：今天有频数排前，不明词无频数保持原顺序排后
        val table = mapOf("jt" to mapOf("今天" to 10))
        val result = WordFreqStore.reorder(
            code = "jt",
            candidates = listOf("不明词", "今天"),
            comments = listOf("", ""),
            table = table,
        )
        assertEquals(listOf("今天", "不明词"), result?.candidates)
    }
}