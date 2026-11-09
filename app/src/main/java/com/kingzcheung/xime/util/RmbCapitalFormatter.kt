package com.kingzcheung.xime.util

/**
 * 人民币金额大写转换（纯函数，无 Android 依赖）。
 *
 * 用于数字键盘的「大写金额模式」：用户按顺序输入数字，本类把原始数字串转成
 * 规范的人民币大写金额，例如：
 *
 * | 输入 | 输出 |
 * |---|---|
 * | `123` | 壹佰贰拾叁元整 |
 * | `123.45` | 壹佰贰拾叁元肆角伍分 |
 * | `1005` | 壹仟零伍元整 |
 * | `0.05` | 零伍分 |
 * | `0.40` | 肆角整 |
 * | `0` | 零元整 |
 *
 * ## 位值自动补全
 *
 * 输出的位值单位（拾/佰/仟/万/亿）与「零」的取舍全部自动生成，用户只输数字。
 * 零的规则按汉语语言规律处理：
 * - 中间连续多个 0 只写一个「零」（`1005` → 壹仟零伍）；
 * - 节内末尾的 0 不写（`1100` → 壹仟壹佰）；
 * - 整节为 0 但下方有非零节时补一个「零」（`100001000` → 壹亿零壹仟）；
 * - 角位为 0 而分位不为 0 时，元后写「零」（`123.05` → 壹佰贰拾叁元零伍分）。
 *
 * ## 关于「整」
 *
 * 按《正确填写票据和结算凭证的基本规定》：
 * - 到「元」为止 → 元后写「整」（壹佰贰拾叁元整）；
 * - 到「角」为止 → 角后写「整」（壹佰贰拾叁元肆角整）；
 * - 有「分」→ 分后不写「整」（壹佰贰拾叁元肆角伍分）。
 *
 * 金额小于一元时不写「元」：`0.45` → 肆角伍分；`0.05` → 零伍分（角位为 0 需补零）。
 */
object RmbCapitalFormatter {

    /** 大写数字（下标即数字值）。 */
    private val CAPITAL_DIGITS = charArrayOf('零', '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖')

    /** 节内位值单位（下标 = 该位在节内的位置，0 为个位）。 */
    private val SECTION_POSITION_UNITS = arrayOf("", "拾", "佰", "仟")

    /**
     * 整数部分允许的最大位数。
     *
     * 15 位已到「仟万亿」量级（10^14），远超任何真实金额；同时保证分节后
     * 最高节的下标落在 [sectionUnit] 已定义范围内（见该函数）。
     * 超过该位数的输入 [format] 返回 null，键盘侧也会在到达上限时拒绝继续输入。
     */
    const val MAX_INTEGER_DIGITS = 15

    /** 小数部分最多保留位数：人民币最小单位是「分」，第 3 位起无意义。 */
    const val MAX_DECIMAL_DIGITS = 2

    /**
     * 数字键面 → 大写数字，如 `"5"` → `"伍"`。
     *
     * 非单个数字字符原样返回，便于调用方直接用于键面渲染。
     */
    fun capitalDigit(digit: String): String {
        val d = digit.toIntOrNull() ?: return digit
        return if (d in 0..9) CAPITAL_DIGITS[d].toString() else digit
    }

    /**
     * 把一次按键追加到原始输入串上。
     *
     * 由本方法（而非调用方）决定拒绝哪些输入，使键盘只负责渲染、规则只有一处：
     * - 小数点最多一个——第二个小数点无意义，直接拒绝而不是静默忽略；
     * - 小数位最多 [MAX_DECIMAL_DIGITS] 位——第 3 位起 [format] 会忽略，
     *   若放行则用户会看到「按了键但金额没变」，且退格要按两次才见效；
     * - 整数位最多 [MAX_INTEGER_DIGITS] 位——超出可表达量级，放行只会得到错位金额。
     *
     * @param raw 已累积的原始输入（可能为空，或仅有一个小数点）
     * @param input 本次按下的键面字符
     * @return 追加后的原始串；该输入应被拒绝时返回 null
     */
    fun appendInput(raw: String, input: String): String? {
        if (input.length != 1) return null
        val c = input[0]
        if (c == '.') return if (raw.contains('.')) null else raw + c
        if (!c.isDigit()) return null
        val dot = raw.indexOf('.')
        if (dot >= 0) {
            if (raw.length - dot - 1 >= MAX_DECIMAL_DIGITS) return null
        } else {
            // 前导零不占位（"000" 仍是零元，不该把整数位额吃光）
            if (raw.trimStart('0').length >= MAX_INTEGER_DIGITS) return null
        }
        return raw + c
    }

    /**
     * 把原始数字串转成人民币大写金额。
     *
     * 容错优先：只提取数字与**第一个**小数点，其余字符忽略（如误触产生的多余小数点）。
     * 因此 `"1.2.3"` 视作 `"1.23"`，`"1a2"` 视作 `"12"`。
     *
     * @param raw 用户在数字键盘上累积的原始输入
     * @return 大写金额；**不含任何数字**时返回 null（调用方据此不上屏，如仅输入了一个小数点）
     */
    fun format(raw: String): String? {
        val sanitized = sanitize(raw) ?: return null
        // 负号是算式的合法结果（减法可得到负数）。人民币大写没有「负」的规范写法，
        // 但直接把 -50 当 50 输出会给出错钱的结果，因此在金额前加「负」字。
        val negative = sanitized.startsWith('-')
        val magnitude = if (negative) sanitized.drop(1) else sanitized
        val dot = magnitude.indexOf('.')
        val intRaw = if (dot >= 0) magnitude.substring(0, dot) else magnitude
        val decRaw = if (dot >= 0) magnitude.substring(dot + 1) else ""

        // 去掉整数部分的前导零；全零或空则视为 0
        val intDigits = intRaw.trimStart('0').ifEmpty { "0" }
        if (intDigits.length > MAX_INTEGER_DIGITS) return null

        // 小数位只取前两位（分之后无意义）
        val jiao = decRaw.getOrNull(0)?.digitToIntOrNull() ?: 0
        val fen = decRaw.getOrNull(1)?.digitToIntOrNull() ?: 0

        val intIsZero = intDigits == "0"
        val sb = StringBuilder()
        if (negative) sb.append('负')
        if (!intIsZero) {
            sb.append(formatInteger(intDigits)).append('元')
        }
        when {
            jiao == 0 && fen == 0 ->
                // 无小数：整数非零写「整」，整数为零则补「零元整」
                sb.append(if (intIsZero) "零元整" else "整")

            fen == 0 -> sb.append(CAPITAL_DIGITS[jiao]).append("角整")

            jiao == 0 ->
                // 角位为 0 而分位不为 0：需补「零」，否则会被读成错位金额
                sb.append('零').append(CAPITAL_DIGITS[fen]).append('分')

            else -> sb.append(CAPITAL_DIGITS[jiao]).append('角')
                .append(CAPITAL_DIGITS[fen]).append('分')
        }
        return sb.toString()
    }

    /**
     * 提取有效输入：保留全部数字与第一个小数点。
     *
     * 全角句号「。」一并当作小数点——中文输入习惯下用户可能误输，宽容处理比报错好。
     *
     * @return 清洗后的串；不含任何数字时返回 null
     */
    internal fun sanitize(raw: String): String? {
        val sb = StringBuilder()
        var dotSeen = false
        for (c in raw) {
            when {
                c.isDigit() -> sb.append(c)
                (c == '.' || c == '。') && !dotSeen -> {
                    dotSeen = true
                    sb.append('.')
                }
            }
        }
        if (!sb.any { it.isDigit() }) return null
        // 负号：算式的求值结果可能为负（如 100-150 = -50）。保留前导负号供 [format]
        // 输出「负…」；写法与 JSON 数字一致（负号紧贴首位），其余位置的负号忽略。
        return if (raw.trimStart() .startsWith('-')) "-$sb" else sb.toString()
    }

    /**
     * 整数部分转大写（不含「元」）。
     *
     * 按 4 位一节从右往左切分，节间补「万」「亿」等节权，并处理跨节的「零」。
     *
     * @param digits 仅含数字、已去前导零、非空的整数部分
     */
    internal fun formatInteger(digits: String): String {
        if (digits.isEmpty() || digits.all { it == '0' }) return "零"

        // 从右往左每 4 位切一节；sections[0] 为最高节
        val sections = ArrayList<String>()
        var end = digits.length
        while (end > 0) {
            val start = (end - 4).coerceAtLeast(0)
            sections.add(0, digits.substring(start, end))
            end = start
        }

        val sb = StringBuilder()
        // 上一节（或中间跳过的空节）是否需要补「零」
        var pendingZero = false
        for ((index, section) in sections.withIndex()) {
            // 节权下标：0 = 个级、1 = 万级、2 = 亿级、3 = 万亿级
            val groupIndex = sections.size - 1 - index
            val value = section.toInt()
            if (value == 0) {
                // 整节为零：本节不输出，但若下方还有内容则需要一个「零」
                if (sb.isNotEmpty()) pendingZero = true
                continue
            }
            // 跨节补零有两种来源：中间有整节为零，或本节自身高位为 0（如 壹亿零壹仟）
            if (sb.isNotEmpty() && (pendingZero || value < 1000)) sb.append('零')
            pendingZero = false
            sb.append(formatSection(section)).append(sectionUnit(groupIndex))
        }
        return sb.toString()
    }

    /**
     * 节权单位（4 位一节）。
     *
     * 只定义到「万亿」（10^12 量级），与 [MAX_INTEGER_DIGITS] 保证的最大节下标一致；
     * 更大的量级在汉语中写法不统一（亿亿/万亿亿），刻意不支持而非给出可能错误的写法。
     */
    private fun sectionUnit(groupIndex: Int): String = when (groupIndex) {
        0 -> ""
        1 -> "万"
        2 -> "亿"
        3 -> "万亿"
        else -> ""
    }

    /**
     * 单个节（1~4 位，可含前导零）转大写，不含节权。
     *
     * 节内零的处理：只有「已经输出过非零位」之后的零才可能产生一个「零」，
     * 且连续零只写一个——`1005` → 壹仟零伍、`1010` → 壹仟零壹拾、`1100` → 壹仟壹佰。
     */
    private fun formatSection(section: String): String {
        val sb = StringBuilder()
        var pendingZero = false
        for ((i, c) in section.withIndex()) {
            val digit = c - '0'
            // 位在节内的位置：0 = 个位、3 = 千位
            val position = section.length - 1 - i
            if (digit == 0) {
                if (sb.isNotEmpty()) pendingZero = true
                continue
            }
            if (pendingZero) {
                sb.append('零')
                pendingZero = false
            }
            sb.append(CAPITAL_DIGITS[digit]).append(SECTION_POSITION_UNITS[position])
        }
        return sb.toString()
    }
}
