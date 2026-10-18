package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组合生词编码生成规则测试。
 *
 * 码只从方案码表的**单字条目**取，不借用多字词编码；拼码规则：
 * 2 字各取前 2 码、3 字给两个编码（各首码 + 前两字首码加第三字前 2 码）、
 * 4 字及以上取前三字首码 + 尾字首码。数据取自小鹤音形码表实测。
 */
class SchemeCodeIndexTest {

    /** 真实小鹤音形单字条目取码结果（最短且长度 ≥ 2）。 */
    private val charCodes = mapOf(
        "现" to "xm", "在" to "zdut", "目" to "mu", "录" to "lue",
        "高" to "gc", "低" to "dir", "漂" to "pnd", "亮" to "llw",
        "输" to "uui", "入" to "rup", "法" to "fa",
    )

    @Test
    fun `两字词各取前两码`() {
        assertEquals(listOf("xmzd"), SchemeCodeIndex.composeCodes("现在", charCodes))
        assertEquals(listOf("mulu"), SchemeCodeIndex.composeCodes("目录", charCodes))
        assertEquals(listOf("pnll"), SchemeCodeIndex.composeCodes("漂亮", charCodes))
        assertEquals(listOf("gcdi"), SchemeCodeIndex.composeCodes("高低", charCodes))
    }

    @Test
    fun `三字词同时生成两个编码`() {
        // 各字首码 + 前两字首码加第三字前 2 码（与小鹤码表中「输入法」的 urf/urfa 一致）
        assertEquals(listOf("urf", "urfa"), SchemeCodeIndex.composeCodes("输入法", charCodes))
    }

    @Test
    fun `四字词取前三字首码加尾字首码`() {
        assertEquals(listOf("xzml"), SchemeCodeIndex.composeCodes("现在目录", charCodes))
    }

    @Test
    fun `五字词只取前三字首码与尾字首码`() {
        // 现 在 目 录 高 → x + z + m + g（跳过"录"）
        assertEquals(listOf("xzmg"), SchemeCodeIndex.composeCodes("现在目录高", charCodes))
    }

    @Test
    fun `任一字取不到码则整个词不生成`() {
        assertTrue(SchemeCodeIndex.composeCodes("现在目录", charCodes - "录").isEmpty())
        assertTrue(SchemeCodeIndex.composeCodes("现在", charCodes - "在").isEmpty())
    }

    @Test
    fun `空词不生成`() {
        assertTrue(SchemeCodeIndex.composeCodes("", charCodes).isEmpty())
    }

    @Test
    fun `单字码只认单字条目_不借用多字词编码`() {
        // 只给词条（二字词），不给单字条目 → 建不出字码
        val built = SchemeCodeIndex.buildCharCodes(
            sequenceOf(
                "现在" to "xmzd",
                "目录" to "mulu",
            )
        )
        assertTrue(built.isEmpty())
    }

    @Test
    fun `单字码优先取最短且长度不小于二的码`() {
        val built = SchemeCodeIndex.buildCharCodes(
            sequenceOf(
                "在" to "z",
                "在" to "zdut",
                "可" to "k",
                "可" to "kedk",
                "现" to "xm",
                "现" to "xmw",
                "现" to "xmwe",
            )
        )
        assertEquals("zdut", built["在"])
        assertEquals("kedk", built["可"])
        assertEquals("xm", built["现"])
    }

    @Test
    fun `只有单码的字用该码`() {
        val built = SchemeCodeIndex.buildCharCodes(
            sequenceOf(
                "了" to "l",
                "目" to "m",
            )
        )
        assertEquals("l", built["了"])
        assertEquals("m", built["目"])
    }

    @Test
    fun `非字母编码与空词条被忽略`() {
        val built = SchemeCodeIndex.buildCharCodes(
            sequenceOf(
                "" to "xm",
                "现" to "x m",
                "在" to "",
                "目" to "mu",
            )
        )
        assertNull(built["现"])
        assertNull(built["在"])
        assertEquals("mu", built["目"])
    }

    @Test
    fun `单字条目建码后可直接生成两字词编码`() {
        val built = SchemeCodeIndex.buildCharCodes(
            sequenceOf(
                "现" to "xm",
                "在" to "z",
                "在" to "zdut",
                "目" to "m",
                "目" to "mu",
                "录" to "lue",
            )
        )
        assertEquals(listOf("xmzd"), SchemeCodeIndex.composeCodes("现在", built))
        assertEquals(listOf("mulu"), SchemeCodeIndex.composeCodes("目录", built))
    }
}
