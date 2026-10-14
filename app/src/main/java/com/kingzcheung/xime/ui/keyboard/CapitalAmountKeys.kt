package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.util.RmbCapitalFormatter

/**
 * 数字键盘「大写金额模式」的键面与角标规则（纯函数，无 Compose 依赖，便于单测）。
 *
 * 之所以单独放一个对象而不是内联在 [NumberKeyboardLayout] 里：这两条规则直接对应用户
 * 可见的需求，内联在 Composable 的私有函数里就无法用 JVM 单测锁住，只能靠肉眼看渲染结果。
 */
object CapitalAmountKeys {

    /**
     * 进入/退出大写金额模式的键：长按或上滑该键。
     *
     * 长按与上滑两条路径都能触发（用户对「长按」还是「上滑」的直觉不一致）。
     */
    const val TOGGLE_DIGIT = "0"

    /**
     * 数字键的键面文字：大写金额模式下转大写（壹贰叁…），否则保持原数字。
     */
    fun face(digit: String, capitalMode: Boolean): String =
        if (capitalMode) RmbCapitalFormatter.capitalDigit(digit) else digit

    /**
     * 数字键的右上角角标。
     *
     * 角标始终显示「键面上没有的那一种写法」，两种模式的**范围**不同：
     * - 普通模式：**只有 [TOGGLE_DIGIT] 键**有角标「零」，提示长按/上滑可进入大写金额模式。
     *   其余键无角标——若每个键都长角标，「哪个键能切模式」的指示作用就消失了。
     * - 大写模式：**全部数字键**有角标，内容为原数字（键面已是壹贰叁…）。
     *   此时角标是「这串大写对应哪个数字」的对照表，全键给出才完整。
     *
     * 非数字键（如小数点、退格）不参与，返回 null——不能仅靠 `capitalMode` 放行，
     * 否则大写模式下小数点键会凭空长出一个「.」角标。
     */
    fun badge(digit: String, capitalMode: Boolean): String? {
        if (digit.length != 1 || !digit[0].isDigit()) return null
        return when {
            capitalMode -> digit
            digit == TOGGLE_DIGIT -> RmbCapitalFormatter.capitalDigit(digit)
            else -> null
        }
    }

    /**
     * 候选栏该不该显示回显。
     *
     * 只在模式内**且已经有金额上屏**时显示：刚进入模式、或退格退到没有数值时，
     * 输入框里没有金额，候选栏再挂一条只会是噪音。
     */
    fun shouldDisplay(amount: String, capitalMode: Boolean): Boolean =
        capitalMode && amount.isNotEmpty()

    /**
     * 候选栏的两个候选：纯金额、以及「算式 = 金额」。
     *
     * 与普通计算器同形（第一个纯结果、第二个带算式），因此候选栏可以直接复用
     * 同一套渲染与点选路径，不需要第二套外观。
     *
     * 之所以要带上算式，是因为输入框此刻显示的是大写金额——用户看不见自己按了
     * 哪些数字，`123+45` 才是「运算过程」。
     */
    fun candidates(expression: String, amount: String): List<String> =
        listOf(amount, "$expression = $amount")
}
