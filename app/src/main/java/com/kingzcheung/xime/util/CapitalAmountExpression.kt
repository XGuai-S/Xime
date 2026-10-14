package com.kingzcheung.xime.util

import com.kingzcheung.xime.calculator.ExpressionEvaluator

/**
 * 大写金额模式下的算式累积（纯函数，无 Android 依赖）。
 *
 * 数字键盘左列就是 `+ - * /`，普通模式下它们交给 [com.kingzcheung.xime.calculator.CalculatorEngine]
 * 出算式候选；大写金额模式下按键被本模式接管，因此这里提供同一套「加减乘除」能力，
 * 让用户按 `1 2 3 + 4 5` 时屏幕上出现的是**结果**的大写金额（壹佰陆拾捌元整），
 * 而不是把算式原样当成金额。
 *
 * ## 为什么单独一层
 *
 * 算式与「一笔金额」是两种不同的累积：金额只允许数字与一个小数点，
 * 算式还要允许运算符、并且要能取出「当前值」用于上屏。把规则放在这里，
 * 键盘、状态机、渲染层都不会各自手写一套判断而漂移。
 *
 * ## 与 [RmbCapitalFormatter] 的分工
 *
 * 本层只管**算式结构**（运算符位置、当前操作数是谁）；每个操作数内部的
 * 位数限制（重复小数点、小数位/整数位上限）仍然委托 [RmbCapitalFormatter.appendInput]，
 * 使「一笔金额能输什么」的规则只有一处。
 */
object CapitalAmountExpression {

    /** 支持的运算符，与数字键盘左列一致。 */
    val OPERATORS = listOf("+", "-", "*", "/")

    /** 是否运算符键。 */
    fun isOperator(input: String): Boolean = input in OPERATORS

    /** 是否操作数键（单个数字或小数点）。 */
    fun isOperandInput(input: String): Boolean =
        input.length == 1 && (input[0].isDigit() || input == ".")

    /**
     * 最后一个运算符之后的当前操作数（没有运算符时就是整串）。
     *
     * 每个操作数各自受金额位数限制，因此必须按操作数切分后再交给
     * [RmbCapitalFormatter.appendInput]，否则 `1.5+2.5` 里的第二个小数点会被误判为重复。
     */
    fun currentOperand(expr: String): String {
        val idx = expr.indexOfLast { isOperatorChar(it) }
        return if (idx < 0) expr else expr.substring(idx + 1)
    }

    /**
     * 把一次按键追加到算式上。
     *
     * 拒绝的输入（返回 null，调用方据此不改动任何状态）：
     * - 以运算符开头——算式没有左操作数，算了也没有结果；
     * - 连续两个运算符——`123++` 无意义（想换运算符应先退格）；
     * - 当前操作数不接受该字符（重复小数点、小数位/整数位超限）。
     *
     * @param expr 已累积的算式，如 `"123+4"`
     * @param input 本次按下的键面字符
     * @return 追加后的算式；该输入应被拒绝时返回 null
     */
    fun append(expr: String, input: String): String? {
        if (isOperator(input)) {
            if (expr.isEmpty()) return null
            if (isOperatorChar(expr.last())) return null
            return expr + input
        }
        if (!isOperandInput(input)) return null
        if (RmbCapitalFormatter.appendInput(currentOperand(expr), input) == null) return null
        return expr + input
    }

    /**
     * 算式当前的数值（去掉尾部还没写完的部分后求值）。
     *
     * 尾部未完成的部分指末尾的运算符或小数点——用户刚按下它们，算式还不完整：
     * - `"123+"` → 取 `"123"`（左操作数），按下运算符时金额不应变化；
     * - `"123+45-"` → 取 `"123+45"` 的**计算结果**，而不是把算式里的数字拼成一串
     *   （否则 `123+45-` 会被读成 12345，得到一笔错位金额）；
     * - `"123."` → 取 `"123"`。
     *
     * 无运算符时原样返回（保留前导零等写法，交由 [RmbCapitalFormatter.format] 处理）。
     *
     * @return 数值字符串（可能带负号）；算式还没有任何数值时返回 null
     */
    fun value(expr: String): String? {
        var body = expr
        while (body.isNotEmpty() && (isOperatorChar(body.last()) || body.last() == '.')) {
            body = body.dropLast(1)
        }
        if (body.isEmpty()) return null
        if (body.none { isOperatorChar(it) }) return body
        return ExpressionEvaluator.evaluate(body)
    }

    private fun isOperatorChar(c: Char): Boolean = c == '+' || c == '-' || c == '*' || c == '/'
}
