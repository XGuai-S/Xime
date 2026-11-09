package com.kingzcheung.xime.calculator

import net.objecthunter.exp4j.ExpressionBuilder
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 表达式求值（纯函数，无状态）。
 *
 * 从 [CalculatorEngine.evaluate] 抽出：数字键盘的**大写金额模式**也要算同一个算式，
 * 抽出来才能保证只有一处实现——否则两处 exp4j 用法会各自漂移（非法输入过滤规则、
 * 精度处理、四舍五入位数任意一处不同，同一个算式就会给出两个答案）。
 *
 * [CalculatorEngine] 保持原有的公开 `evaluate` 方法委托到这里，行为逐字不变。
 */
internal object ExpressionEvaluator {

    /** 连续运算符（`2++++3`）：exp4j 容忍但无意义，直接判为非法。 */
    private val CONSECUTIVE_OPERATORS = Regex(".*[+\\-*/]{2,}.*")

    /** 科学计数法（`1e5` 合法）：不能按「连续运算符」误判（`e` 后跟 `-` 会命中）。 */
    private val SCIENTIFIC_NOTATION = Regex(".*[eE].*")

    /** 结果保留位数：消除浮点误差（如 `0.1+0.2`）。 */
    private const val RESULT_SCALE = 10

    /**
     * 完整表达式求值，支持混合运算、括号、优先级。
     *
     * @return 结果字符串（整数不带小数点）；表达式非法、除零、结果非有限数时返回 null
     */
    fun evaluate(expression: String): String? {
        if (CONSECUTIVE_OPERATORS.matches(expression) && !SCIENTIFIC_NOTATION.matches(expression)) return null
        return try {
            val raw = ExpressionBuilder(expression).build().evaluate()
            if (raw.isInfinite() || raw.isNaN()) return null
            // 四舍五入到 10 位消除浮点误差
            val bd = BigDecimal(raw).setScale(RESULT_SCALE, RoundingMode.HALF_UP).stripTrailingZeros()
            bd.toPlainString()
        } catch (_: Exception) {
            null
        }
    }
}
