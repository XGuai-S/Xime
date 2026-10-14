package com.kingzcheung.xime.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RmbCapitalFormatter] 的人民币大写转换规则测试。
 *
 * 覆盖重点是**零的取舍**与**跨节位值**：这两处是人民币大写最容易写错的地方，
 * 而且错了不会崩、只会静默输出一个错位金额（如把 1005 写成 壹仟伍），
 * 用在票据/报销场景是实质性的错误。
 */
class RmbCapitalFormatterTest {

    private fun assertFormat(raw: String, expected: String) {
        assertEquals("format(\"$raw\") 应为 $expected", expected, RmbCapitalFormatter.format(raw))
    }

    // ── 基本整数 ──

    @Test
    fun `个位与十位`() {
        assertFormat("0", "零元整")
        assertFormat("1", "壹元整")
        assertFormat("5", "伍元整")
        assertFormat("9", "玖元整")
        assertFormat("10", "壹拾元整")
        assertFormat("15", "壹拾伍元整")
        assertFormat("99", "玖拾玖元整")
    }

    @Test
    fun `百位与千位`() {
        assertFormat("100", "壹佰元整")
        assertFormat("123", "壹佰贰拾叁元整")
        assertFormat("1000", "壹仟元整")
        assertFormat("1234", "壹仟贰佰叁拾肆元整")
    }

    @Test
    fun `万位与亿位`() {
        assertFormat("10000", "壹万元整")
        assertFormat("100000", "壹拾万元整")
        assertFormat("1000000", "壹佰万元整")
        assertFormat("10000000", "壹仟万元整")
        assertFormat("100000000", "壹亿元整")
        assertFormat("1000000000000", "壹万亿元整")
    }

    @Test
    fun `长整数跨多节`() {
        assertFormat(
            "123456789",
            "壹亿贰仟叁佰肆拾伍万陆仟柒佰捌拾玖元整",
        )
        assertFormat(
            "123456789012",
            "壹仟贰佰叁拾肆亿伍仟陆佰柒拾捌万玖仟零壹拾贰元整",
        )
    }

    // ── 零的取舍（最容易写错的部分）──

    @Test
    fun `节内中间连续零只写一个零`() {
        assertFormat("1005", "壹仟零伍元整")
        assertFormat("10005", "壹万零伍元整")
        assertFormat("100005", "壹拾万零伍元整")
        assertFormat("1000005", "壹佰万零伍元整")
    }

    @Test
    fun `节内末尾零不写`() {
        // 反证：若把末尾零也写成「零」，会得到「壹仟壹佰零元整」这种错位读法
        assertFormat("1100", "壹仟壹佰元整")
        assertFormat("110", "壹佰壹拾元整")
        assertFormat("10", "壹拾元整")
    }

    @Test
    fun `节内零之后仍有非零位时补零`() {
        assertFormat("1010", "壹仟零壹拾元整")
        assertFormat("1001", "壹仟零壹元整")
        assertFormat("10010", "壹万零壹拾元整")
    }

    @Test
    fun `整节为零时跨节补一个零`() {
        assertFormat("100000001", "壹亿零壹元整")
        assertFormat("100001000", "壹亿零壹仟元整")
        assertFormat("100010000", "壹亿零壹万元整")
        assertFormat("100100000", "壹亿零壹拾万元整")
    }

    @Test
    fun `整节为零且下方无非零内容时不补零`() {
        // 反证：多补一个零会得到「壹亿零元整」
        assertFormat("100000000", "壹亿元整")
        assertFormat("1000000000000", "壹万亿元整")
        assertFormat("10000000", "壹仟万元整")
    }

    // ── 小数（角分）──

    @Test
    fun `角分齐全`() {
        assertFormat("123.45", "壹佰贰拾叁元肆角伍分")
        assertFormat("1.01", "壹元零壹分")
        assertFormat("1.11", "壹元壹角壹分")
    }

    @Test
    fun `只有角时角后写整`() {
        // 规定：大写金额到「角」为止的，角后可以写「整」
        assertFormat("123.4", "壹佰贰拾叁元肆角整")
        assertFormat("1.5", "壹元伍角整")
    }

    @Test
    fun `角位为零而分位非零时元后补零`() {
        // 规定：角位是「0」而分位不是「0」时，「元」后面应写「零」字，
        // 否则「壹佰贰拾叁元伍分」会被误读为伍角
        assertFormat("123.05", "壹佰贰拾叁元零伍分")
        assertFormat("100.09", "壹佰元零玖分")
    }

    @Test
    fun `小数为零时按整处理`() {
        assertFormat("123.00", "壹佰贰拾叁元整")
        assertFormat("123.0", "壹佰贰拾叁元整")
    }

    @Test
    fun `金额小于一元时不写元`() {
        assertFormat("0.45", "肆角伍分")
        assertFormat("0.40", "肆角整")
        assertFormat("0.05", "零伍分")
        assertFormat("0.00", "零元整")
    }

    @Test
    fun `小数超过两位时忽略分之后`() {
        // 人民币最小单位是分，第 3 位起无意义
        assertFormat("1.234", "壹元贰角叁分")
        assertFormat("1.999", "壹元玖角玖分")
    }

    // ── 输入容错 ──

    @Test
    fun `无数字输入返回 null`() {
        // 调用方据此不上屏：只按了一个小数点时不该凭空上屏「零元整」
        assertNull(RmbCapitalFormatter.format(""))
        assertNull(RmbCapitalFormatter.format("."))
        assertNull(RmbCapitalFormatter.format("abc"))
        assertNull(RmbCapitalFormatter.format("。"))
    }

    @Test
    fun `前导零被忽略`() {
        assertFormat("007", "柒元整")
        assertFormat("000", "零元整")
        assertFormat("00.50", "伍角整")
    }

    @Test
    fun `仅有前导小数点时按零元处理`() {
        assertFormat(".4", "肆角整")
        assertFormat(".45", "肆角伍分")
    }

    @Test
    fun `末尾小数点按整数处理`() {
        // 用户刚按下小数点还没输小数位，此时应显示整数金额而不是空
        assertFormat("123.", "壹佰贰拾叁元整")
    }

    @Test
    fun `非数字字符被忽略`() {
        assertFormat("1a2", "壹拾贰元整")
        assertFormat("1 2 3", "壹佰贰拾叁元整")
    }

    @Test
    fun `多余小数点只认第一个`() {
        // 键盘已阻止第二次输入小数点，此处是防御性行为
        assertFormat("1.2.3", "壹元贰角叁分")
    }

    @Test
    fun `全角句号当作小数点`() {
        assertFormat("1。5", "壹元伍角整")
    }

    @Test
    fun `超出整数位上限返回 null`() {
        // 15 位是上限（仟万亿量级）；键盘侧也会在到达上限时拒绝继续输入
        assertTrue(
            "15 位应可转换",
            RmbCapitalFormatter.format("1" + "0".repeat(14)) != null,
        )
        assertNull(
            "16 位超出上限",
            RmbCapitalFormatter.format("1" + "0".repeat(15)),
        )
    }

    // ── 键面映射 ──
    // ── 负数（算式减法结果）──

    @Test
    fun `负数输出负字前缀而不是静默当成正数`() {
        // 反证：若忽略负号，-50 会变成「伍拾元整」，在票据场景是实质错钱。
        assertFormat("-50", "负伍拾元整")
        assertFormat("-1", "负壹元整")
        assertFormat("-123.45", "负壹佰贰拾叁元肆角伍分")
        assertFormat("-0", "负零元整")
    }

    @Test
    fun `负号只在首位生效`() {
        // 容错优先：只有前导负号是算式求值的输出形态，中间负号不应产生两个负字
        assertFormat("1-2", "壹拾贰元整")
    }


    @Test
    fun `数字转大写键面`() {
        val expected = listOf("零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖")
        expected.forEachIndexed { digit, capital ->
            assertEquals(capital, RmbCapitalFormatter.capitalDigit(digit.toString()))
        }
    }

    @Test
    fun `非数字键面原样返回`() {
        assertEquals("abc", RmbCapitalFormatter.capitalDigit("abc"))
        assertEquals("", RmbCapitalFormatter.capitalDigit(""))
        assertEquals("delete", RmbCapitalFormatter.capitalDigit("delete"))
    }

    // ── 反证：关键差异必须真实存在 ──

    @Test
    fun `壹拾与壹佰不可混淆`() {
        // 位值必须真的不同，否则整串输出都会错
        val ten = RmbCapitalFormatter.format("10")
        val hundred = RmbCapitalFormatter.format("100")
        assertTrue("10 与 100 的输出必须不同", ten != hundred)
        assertTrue("10 应含拾", ten!!.contains("拾"))
        assertTrue("100 应含佰", hundred!!.contains("佰"))
    }

    @Test
    fun `补零与不补零的结果必须不同`() {
        // 1005（壹仟零伍）与 1050（壹仟零伍拾）不能撞成同一个串
        val a = RmbCapitalFormatter.format("1005")
        val b = RmbCapitalFormatter.format("1050")
        assertTrue("1005 与 1050 必须可区分", a != b)
        assertEquals("壹仟零伍元整", a)
        assertEquals("壹仟零伍拾元整", b)
    }

    // ── 按键累积（键盘逐键调用）──

    private fun appendAll(keys: String): String? {
        var raw: String? = ""
        keys.forEach { c ->
            raw = RmbCapitalFormatter.appendInput(raw!!, c.toString()) ?: return null
        }
        return raw
    }

    @Test
    fun `逐键累积得到与一次性输入相同的结果`() {
        // 边打边自动补位值的基础：逐键累积的原始串必须与直接输入等价
        assertEquals("1234", appendAll("1234"))
        assertEquals("123.45", appendAll("123.45"))
        assertEquals("壹仟贰佰叁拾肆元整", RmbCapitalFormatter.format(appendAll("1234")!!))
    }

    @Test
    fun `重复小数点被拒绝`() {
        // 反证：若放行，raw 会变成 "1..2"，清洗后虽仍是 1.2，
        // 但退格要按两次才见效，用户会以为键盘卡了
        assertNull("第二个小数点应被拒绝", RmbCapitalFormatter.appendInput("1.", "."))
        assertNull(RmbCapitalFormatter.appendInput("1.2", "."))
        assertNull(RmbCapitalFormatter.appendInput(".", "."))
    }

    @Test
    fun `小数位最多两位`() {
        assertEquals("1.2", RmbCapitalFormatter.appendInput("1.", "2"))
        assertEquals("1.23", RmbCapitalFormatter.appendInput("1.2", "3"))
        // 人民币最小单位是分：第 3 位起 format 会忽略，放行会造成"按了没反应"
        assertNull("第三位小数应被拒绝", RmbCapitalFormatter.appendInput("1.23", "4"))
    }

    @Test
    fun `整数位超出上限被拒绝`() {
        val fifteen = "1" + "0".repeat(14)
        assertEquals(fifteen, appendAll(fifteen))
        assertNull("第 16 位应被拒绝", RmbCapitalFormatter.appendInput(fifteen, "0"))
    }

    @Test
    fun `前导零不占整数位额`() {
        // "000" 仍是零元，不该把 15 位额度吃光
        val raw = "0".repeat(20) + "1"
        assertEquals(raw, appendAll(raw))
    }

    @Test
    fun `非数字非小数点被拒绝`() {
        assertNull(RmbCapitalFormatter.appendInput("", "a"))
        assertNull(RmbCapitalFormatter.appendInput("", "delete"))
        assertNull(RmbCapitalFormatter.appendInput("1", ""))
    }

    @Test
    fun `先按小数点再按数字得到角分金额`() {
        // 用户先按「.」时 format 返回 null（不上屏），但累积必须保留小数点，
        // 否则再按 5 会得到「伍元整」而不是「伍角整」
        val raw = RmbCapitalFormatter.appendInput("", ".")
        assertEquals(".", raw)
        assertNull("仅小数点时不上屏", RmbCapitalFormatter.format(raw!!))
        val raw2 = RmbCapitalFormatter.appendInput(raw, "5")
        assertEquals("伍角整", RmbCapitalFormatter.format(raw2!!))
    }

    @Test
    fun `退格回退一位后重写金额`() {
        // 键盘退格走 dropLast + 重新 format；这里锁住中间态不会输出错位金额
        assertEquals("壹佰贰拾叁元整", RmbCapitalFormatter.format("123"))
        assertEquals("壹拾贰元整", RmbCapitalFormatter.format("123".dropLast(1)))
        assertEquals("壹元整", RmbCapitalFormatter.format("12".dropLast(1)))
    }
}
