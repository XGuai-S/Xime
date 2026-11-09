package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.util.CapitalAmountExpression
import com.kingzcheung.xime.util.RmbCapitalFormatter

/**
 * 数字键盘「大写金额模式」的输入状态机（纯逻辑，无 Compose / Android 依赖）。
 *
 * 从 [NumberKeyboardLayout] 里提出来单独成类，是为了让「逐键上屏序列」可以被 JVM 单测直接跑：
 * 该模式的正确性体现在**一整串交互的结果**上（按 1、2、3、退格、再按 5 …每一步输入框里
 * 应该是什么），而真机验证成本高且不可回归。状态机只负责决策，真正的文本写入由调用方
 * 通过 [press] 的 `write` 参数注入，测试时即可用一个内存字符串当输入框。
 *
 * ## 与输入框的契约
 *
 * 大写金额不是「按键 → 追加一段文本」的增量关系：每按一键整串都可能变
 * （`123` → `壹佰贰拾叁元整`，再按 `4` → `壹仟贰佰叁拾肆元整`），
 * 因此必须能回删上一次结果再重提。[committed] 就是这条替换基线。
 *
 * `write(expected, replacement)` 要求「光标前文本恰好是 expected」时才替换，
 * 否则返回 false（用户移动过光标或退格过）。此时本状态机**不误删旧文本**：
 * 本次按键改为一笔**新金额**的开头追加写入——宁可多出一段金额，
 * 也不能删掉用户输入框里无关的内容，更不能让按键看起来失灵。
 *
 * 另一条不变量：**只有真正写进输入框才推进 [raw]/[committed]**。
 * 写入失败时保持原状，使 [committed] 始终等于「本状态机最后写进去的那段文本」，
 * 否则后续按键会去替换一段从未上屏的内容。
 *
 * ## 加减乘除
 *
 * 数字键盘左列的 `+ - * /` 在本模式下不被交回宿主，而是继续累积到 [raw] 里成为算式
 * （见 [CapitalAmountExpression]）：上屏的始终是**算式当前值**的大写金额，
 * 因此 `1 2 3 + 4 5` 得到「壹佰陆拾捌元整」。
 * 按下运算符本身不改变数值，屏幕暂时不动，下一个数字才体现结果。
 *
 * @param isActive 读取当前是否处于大写金额模式（由调用方持有，键盘侧是 Compose state）
 * @param setActive 写入模式开关
 */
class CapitalAmountInputState(
    private val isActive: () -> Boolean,
    private val setActive: (Boolean) -> Unit,
) {

    /**
     * 已累积的原始输入：数字、至多一个位于操作数中间的小数点，以及运算符，如 `"123+4.5"`。
     */
    var raw: String = ""
        private set

    /** 当前已上屏的大写金额，同时是下一次重写的替换基线。 */
    var committed: String = ""
        private set

    /**
     * 长按 / 上滑「0」键：切换模式。
     *
     * 进入与退出都清空累积：退出后已上屏的金额保留（用户可能接着输普通数字），
     * 但基线必须作废，否则下次进入会去替换一段早已改变的文本。
     */
    fun toggle() {
        setActive(!isActive())
        raw = ""
        committed = ""
    }

    /**
     * 结束本次金额输入并作废累积基线（候选栏点选结果后由调用方触发）。
     *
     * 与 [toggle] 的区别是这里**只退出、不反向进入**：候选栏点选意味着「这笔金额输完了」，
     * 若复用 toggle，用户点完结果会被反向按回模式里，下一个数字又接在旧算式上。
     *
     * 已上屏的金额不动——用户可能接着在输入框里续写别的内容。
     */
    fun exit() {
        setActive(false)
        raw = ""
        committed = ""
    }

    /**
     * 处理一次按键。
     *
     * @param key 键值；数字键传原数字（`"1"`…`"0"`，与键面显示的大写无关）
     * @param write 写输入框：`(expected, replacement) -> 是否成功`；
     *   `expected` 为空串表示「直接追加，不删任何东西」
     * @param passthrough 未被本模式接管的按键，原样交回宿主
     */
    fun press(
        key: String,
        write: (expected: String, replacement: String) -> Boolean,
        passthrough: (String) -> Unit,
    ) {
        if (!isActive()) {
            passthrough(key)
            return
        }
        when {
            // 数字、小数点与运算符都由本模式接管（运算符用于算式，不是交回宿主的计算器）
            CapitalAmountExpression.isOperandInput(key) || CapitalAmountExpression.isOperator(key) ->
                applyKey(key, write)

            key == "delete" -> backspace(write, passthrough)

            key == "space" -> space(passthrough)

            else -> {
                // 其余按键（确定/符号/表情/返回/清空等）退出模式：
                // 它们要么终结本次金额，要么直接改动文本，累积状态不再可信。
                // 退格/空格的去留见各自分支（不退出）；模式开关只在「0」键。
                exitAndPass(key, passthrough)
            }
        }
    }

    /**
     * 追加一个操作数或运算符，并把**算式当前值**的大写金额写进输入框。
     *
     * 被拒绝的输入（算式结构非法、操作数超限）直接返回，不动输入框也不推进累积——
     * 与「按了键但金额没变」相比，这至少不会让退格要按两次才见效。
     */
    private fun applyKey(key: String, write: (String, String) -> Boolean) {
        val newRaw = CapitalAmountExpression.append(raw, key) ?: return
        val value = CapitalAmountExpression.value(newRaw)
        if (value == null) {
            // 还没有可显示的数值（如仅按下小数点）：只累积，不动输入框
            raw = newRaw
            return
        }
        // 负数由 RmbCapitalFormatter 输出为「负…」（人民币大写没有规范负号写法，但直接当成正数会错钱）；
        // 仅当数值超出可表达量级（超过 15 位）时 format 返回 null。
        val newText = RmbCapitalFormatter.format(value)
        if (newText == null) {
            // 数值超出了大写金额能表达的范围（超过 15 位量级）：
            // 退出模式并把普通数值直接写进输入框——既不静默丢弃用户算出来的数，
            // 也不会在屏幕上留下一笔与算式对不上的陈旧金额
            exitWithPlainValue(value, write)
            return
        }
        when {
            // 无可见变化（如按下运算符、或仅按下小数点）：只更新累积，不动输入框
            newText == committed -> raw = newRaw
            // 首次上屏：追加，不删任何东西
            committed.isEmpty() -> if (write("", newText)) {
                raw = newRaw
                committed = newText
            }
            write(committed, newText) -> {
                raw = newRaw
                committed = newText
            }
            // 基线失效（用户移动过光标或退格过）：本次按键改为一笔
            // **新金额**的开头，绝不误删输入框里的旧内容
            else -> startNewAmount(key, write)
        }
    }

    /**
     * 以 [key] 作为一笔新金额的开头（追加写入，不删任何东西）。
     *
     * 只在替换失败——即输入框光标前的内容已不是本状态机写的那笔金额——时使用。
     * 此时继续「先删后写」会删掉用户无关的内容，而丢弃本次按键又会让按键看起来失灵。
     * 运算符不能作为金额开头（[CapitalAmountExpression.append] 会拒绝），此时按键不生效。
     */
    private fun startNewAmount(key: String, write: (String, String) -> Boolean) {
        raw = ""
        committed = ""
        val fresh = CapitalAmountExpression.append("", key) ?: return
        val freshValue = CapitalAmountExpression.value(fresh) ?: return
        val freshText = RmbCapitalFormatter.format(freshValue).orEmpty()
        if (write("", freshText)) {
            raw = fresh
            committed = freshText
        }
    }

    /**
     * 算出的数值无法用大写金额表示：退出模式，把普通数值写进输入框。
     *
     * 写入失败（基线已被用户改动）时不再强行追加——此时退出模式已足够表达
     * 「本次算式结束了」，而乱写会破坏用户自己输入的内容。
     */
    private fun exitWithPlainValue(value: String, write: (String, String) -> Boolean) {
        if (committed.isEmpty()) write("", value) else write(committed, value)
        setActive(false)
        raw = ""
        committed = ""
    }

    /** 退出模式并把 [key] 交回宿主，同时作废累积基线。 */
    private fun exitAndPass(key: String, passthrough: (String) -> Unit) {
        setActive(false)
        raw = ""
        committed = ""
        passthrough(key)
    }

    /**
     * 空格：照常上屏一个空格，但**不退出**模式（模式只能由「0」键切换）。
     *
     * 上屏的空格改动了光标前文本，旧金额的整串重写基线随之作废：
     * 这里主动清掉累积，下一个数字会作为一笔新金额追加，
     * 而不是去替换一段已经对不上的旧金额。
     */
    private fun space(passthrough: (String) -> Unit) {
        raw = ""
        committed = ""
        passthrough("space")
    }

    /**
     * 退格：算式回退一位并同步重写；累积已空或基线失效时把退格交回宿主，但**不退出模式**。
     *
     * 回退后算式已无任何数值（如从 `"1"` 退到 `""`、从 `"."` 退到 `""`）时把已上屏的金额
     * 清掉但**留在模式内**，用户可以接着重输，退格也不必按两次才见效。
     *
     * 交回宿主是必要的——否则退格会被静默吞掉（用户按了没反应），
     * 而且再也删不掉输入框里已有的内容；模式只能由「0」键切换。
     */
    private fun backspace(
        write: (expected: String, replacement: String) -> Boolean,
        passthrough: (String) -> Unit,
    ) {
        if (raw.isEmpty()) {
            // 累积已空：退格交回宿主真正删掉输入框里的内容，但**留在模式内**——
            // 模式只能由「0」键切换，点击退格不该把键盘退回普通数字模式。
            passthrough("delete")
            return
        }
        val newRaw = raw.dropLast(1)
        val value = CapitalAmountExpression.value(newRaw)
        // value == null 表示回退后还没任何数值（如从 "." 退到 ""）：清空已上屏金额。
        // 负数同样由 formatter 输出「负…」，与 applyKey 一致。
        val newText = if (value == null) "" else RmbCapitalFormatter.format(value).orEmpty()
        when {
            newText == committed -> raw = newRaw
            committed.isNotEmpty() && write(committed, newText) -> {
                raw = newRaw
                committed = newText
            }
            // 基线失效（用户移动过光标）或无可重写基线：无法安全重写，
            // 把退格交回宿主真正生效，同时作废累积但**留在模式内**。
            else -> {
                raw = ""
                committed = ""
                passthrough("delete")
            }
        }
    }
}
