package com.kingzcheung.xime.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CapitalAmountExpression] 的算式累积规则测试。
 *
 * 大写金额模式下的加减乘除全部经过这里，规则错了不会崩，只会算出一笔错的钱
 * （例如把 `123+45-` 里的数字拼成 12345，得到「壹万贰仟叁佰肆拾伍元整」）。
 */
class CapitalAmountExpressionTest {

    // ── 追加 ──

    @Test
    fun `数字与小数点按金额规则追加`() {
        assertEquals("1", CapitalAmountExpression.append("", "1"))
        assertEquals("12", CapitalAmountExpression.append("1", "2"))
        assertEquals("1.", CapitalAmountExpression.append("1", "."))
        assertEquals("1.5", CapitalAmountExpression.append("1.", "5"))
    }

    @Test
    fun `运算符接在操作数之后`() {
        assertEquals("123+", CapitalAmountExpression.append("123", "+"))
        assertEquals("123-", CapitalAmountExpression.append("123", "-"))
        assertEquals("123*", CapitalAmountExpression.append("123", "*"))
        assertEquals("123/", CapitalAmountExpression.append("123", "/"))
    }

    @Test
    fun `算式不能以运算符开头`() {
        // 没有左操作数，算了也没有结果；若放行则用户会看到金额莫名变化
        CapitalAmountExpression.OPERATORS.forEach { op ->
            assertNull("$op 不该能作为算式开头", CapitalAmountExpression.append("", op))
        }
    }

    @Test
    fun `拒绝连续运算符`() {
        assertNull(CapitalAmountExpression.append("123+", "+"))
        assertNull(CapitalAmountExpression.append("123+", "-"))
        assertNull(CapitalAmountExpression.append("123-", "*"))
    }

    @Test
    fun `每个操作数各自受金额位数限制`() {
        // 关键：限制作用在「当前操作数」上，不是整串。
        // 若按整串判断，第二个操作数的小数点会被误判成重复小数点而输不进去。
        assertEquals("1.5+2.", CapitalAmountExpression.append("1.5+2", "."))
        assertEquals("1.5+2.5", CapitalAmountExpression.append("1.5+2.", "5"))
        // 同一操作数内重复小数点仍然被拒绝
        assertNull(CapitalAmountExpression.append("1.5", "."))
        assertNull(CapitalAmountExpression.append("1.5+2.5", "."))
        // 同一操作数内的小数位上限仍然生效
        assertNull(CapitalAmountExpression.append("1.5+2.55", "5"))
    }

    @Test
    fun `非操作数非运算符的输入被拒绝`() {
        assertNull(CapitalAmountExpression.append("123", "delete"))
        assertNull(CapitalAmountExpression.append("123", "abc"))
        assertNull(CapitalAmountExpression.append("123", ""))
    }

    // ── 取值 ──

    @Test
    fun `无运算符时原样返回以保留前导零写法`() {
        // 交由 RmbCapitalFormatter 处理（007 → 柒元整），此处不得先行求值成 7
        assertEquals("007", CapitalAmountExpression.value("007"))
        assertEquals("123", CapitalAmountExpression.value("123"))
        assertEquals("0.05", CapitalAmountExpression.value("0.05"))
    }

    @Test
    fun `尾部未完成的运算符按左值取值`() {
        // 按下运算符时金额不该变化
        assertEquals("123", CapitalAmountExpression.value("123+"))
        assertEquals("123", CapitalAmountExpression.value("123*"))
        // 链式：取上一段的结果，而不是把算式里的数字拼成 12345
        assertEquals("168", CapitalAmountExpression.value("123+45-"))
    }

    @Test
    fun `尾部未完成的小数点按整数取值`() {
        assertEquals("123", CapitalAmountExpression.value("123."))
        assertEquals("1", CapitalAmountExpression.value("1."))
    }

    @Test
    fun `完整算式求值`() {
        assertEquals("168", CapitalAmountExpression.value("123+45"))
        assertEquals("22", CapitalAmountExpression.value("4*5+2"))
        assertEquals("14", CapitalAmountExpression.value("2+3*4"))
        assertEquals("0.3", CapitalAmountExpression.value("0.1+0.2"))
        assertEquals("127.5", CapitalAmountExpression.value("123+4.5"))
    }

    @Test
    fun `减法可以得到负数`() {
        // 负数由调用方判定为「大写金额无法表示」并退出模式；本层如实返回
        assertEquals("-50", CapitalAmountExpression.value("100-150"))
        assertEquals("-3", CapitalAmountExpression.value("2-5"))
    }

    @Test
    fun `还没有数值时返回 null`() {
        assertNull(CapitalAmountExpression.value(""))
        assertNull(CapitalAmountExpression.value("."))
    }

    @Test
    fun `除零等非法算式返回 null`() {
        assertNull(CapitalAmountExpression.value("5/0"))
    }

    // ── 操作数切分 ──

    @Test
    fun `取当前操作数`() {
        assertEquals("123", CapitalAmountExpression.currentOperand("123"))
        assertEquals("", CapitalAmountExpression.currentOperand("123+"))
        assertEquals("45", CapitalAmountExpression.currentOperand("123+45"))
        assertEquals("5", CapitalAmountExpression.currentOperand("123*4-5"))
    }

    @Test
    fun `识别运算符与操作数键`() {
        CapitalAmountExpression.OPERATORS.forEach {
            assertTrue("$it 应被识别为运算符", CapitalAmountExpression.isOperator(it))
        }
        assertFalse(CapitalAmountExpression.isOperator("."))
        assertFalse(CapitalAmountExpression.isOperator("delete"))
        (0..9).forEach { assertTrue(CapitalAmountExpression.isOperandInput(it.toString())) }
        assertTrue(CapitalAmountExpression.isOperandInput("."))
        assertFalse(CapitalAmountExpression.isOperandInput("+"))
        assertFalse(CapitalAmountExpression.isOperandInput("12"))
    }
}
