package com.kingzcheung.xime.settings

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 「组合键」：输入一段编码（如 `orq`）后，候选栏直接给出**求值后**的候选（如
 * `2026年10月14日` / `2026-10-14`），点选即上屏；候选也可以是一个内置命令
 * （如 `重新部署`）。
 *
 * ## 为什么是宿主侧实现而不是插件
 *
 * 组合键的候选内容依赖**运行时刻**（当前日期、节气）与**宿主命令**（重新部署），
 * 这两样插件侧都拿不到（插件是纯文本变换，且无部署/时间 API）。因此这里做成宿主能力，
 * 配置随 `xime.yaml` 一起走用户的统一配置目录，改完即生效。
 *
 * ## 模板语法
 *
 * 候选写成模板字符串，支持 `{变量:格式}`：
 * - `{time}`：当前日期时间，`格式` 用 Java `SimpleDateFormat` 的字母（`yyyy年M月d日`、
 *   `HH:mm`、`H点m分` …）；
 * - `{time+N}` / `{time-N}`：以今天为基准偏移 N 天（`{time+1}` 明天、`{time+2}` 后天）；
 * - `{jq}`：当天所属的节气名（如 `霜降`）；不在节气当天时给出**最近的未来节气**
 *   与其日期（`立冬 11月7日`）。
 *
 * 不含 `{...}` 的模板原样上屏（如固定的常用语），因此组合键也可以当"带触发码的快捷短语"用。
 */
data class KeyboardCombo(
    /** 触发编码（用户完整输入该串后候选栏给出 [items]）。 */
    val code: String,
    /** 候选列表（按声明顺序显示）。 */
    val items: List<ComboItem>,
)

/**
 * 组合键的一个候选项。
 *
 * - [template] 非空：[ComboItem] 是文本候选，点选把求值后的文本上屏；
 * - [command] 非空：候选是命令（如 `reload`），点选执行命令而不是上屏文本。
 */
data class ComboItem(
    val template: String? = null,
    val command: String? = null,
    /** 候选栏显示文本（命令项用；文本项留空由模板求值结果充当）。 */
    val label: String? = null,
) {
    val isCommand: Boolean get() = !command.isNullOrEmpty()
}

/**
 * 模板求值（纯函数，无 Android 依赖）。
 *
 * 之所以单独成对象：候选内容依赖当前时刻，必须能把 `now` 注入进来才能在 JVM 单测里
 * 锁住「明天 = 今天 +1 天」这类跨月/跨年的边界，否则只能靠真机看日历。
 */
object KeyboardComboEvaluator {

    /**
     * 解析模板里的 `{变量:格式}`。**刻意不用正则**。
     *
     * 教训（真机崩溃）：Android 用的是 ICU 正则，与 JVM 的 java.util.regex 并不完全兼容——
     * `[+-]`、未转义的 `}` 等写法在 JVM 上能过、在 ICU 上直接抛 PatternSyntaxException；
     * 而正则若写成 object 的初始化字段，异常会升级为 ExceptionInInitializerError，
     * 把整个输入法进程干掉（表现为「切换输入法闪回桌面」「组合键一按就崩」）。
     * 手写扫描没有正则方言差异，也不可能在类初始化阶段抛错。
     */
    private const val VARIABLE_OPEN = '{'
    private const val VARIABLE_CLOSE = '}'
    private const val VARIABLE_SEPARATOR = ':'

    /**
     * 求值一个候选模板。
     *
     * @param template 候选模板
     * @param now 基准时刻（测试注入；生产传当前时间）
     * @return 求值结果；模板为空返回空串
     */
    fun evaluate(template: String, now: Date = Date()): String {
        if (template.isEmpty()) return ""
        // 最后一道防线：求值链路里的日期格式化（用户可写任意格式串）与农历表查询都不应该
        // 把异常抛回输入法主流程。真机曾因正则兼容性在此处崩溃并杀掉输入法进程，
        // 这里对“求值本身”再包一层，保证任何输入都只得到降级结果。
        return runCatching { evaluateInternal(template, now) }.getOrDefault(template)
    }

    private fun evaluateInternal(template: String, now: Date): String {
        val out = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val open = template.indexOf(VARIABLE_OPEN, i)
            if (open < 0) {
                out.append(template, i, template.length)
                break
            }
            out.append(template, i, open)
            val close = template.indexOf(VARIABLE_CLOSE, open + 1)
            if (close < 0) {
                // 未闭合：原样保留剩余内容（容错优先，不能让整条候选变空）
                out.append(template, open, template.length)
                break
            }
            val body = template.substring(open + 1, close)
            val separator = body.indexOf(VARIABLE_SEPARATOR)
            val name = if (separator >= 0) body.substring(0, separator) else body
            val format = if (separator >= 0) body.substring(separator + 1) else ""
            val resolved = resolveVariable(name, format, now)
            if (resolved != null) {
                out.append(resolved)
            } else {
                // 未知变量：原样保留（用户能看到自己写错了什么）
                out.append(template, open, close + 1)
            }
            i = close + 1
        }
        return out.toString()
    }

    private fun resolveVariable(variable: String, format: String, now: Date): String? {
        // {jq}（节气）：当天是节气 → 节气名；否则 → 「无节气」（首个候选由配置显式给出）
        if (variable.equals("jq", ignoreCase = true)) {
            return SolarTerms.describe(now, format)
        }
        // {jq+}（节气·下一个）：当天不是节气时给出下一个节气与日期；是节气当天则同样回节气名
        if (variable.equals("jq+", ignoreCase = true)) {
            return SolarTerms.describeNext(now, format)
        }
        // {nl}（农历）：当天农历月日（闰月加「闰」）；{nl+} 带年份
        if (variable.startsWith("nl", ignoreCase = true)) {
            val lunar = LunarCalendar.fromDate(now) ?: return null
            return when (variable.lowercase()) {
                // {nl}：月日（九月初五）
                "nl" -> LunarCalendar.formatShort(lunar)
                // {nl+}：公历数字年 + 月日（二〇二六年九月初五）
                "nl+" -> LunarCalendar.formatFull(lunar)
                // {nl#}：干支纪年 + 月日（丙午年九月初五）
                // {nl#}：完整干支纪时（丙午年戊戌月辛酉日寅时一刻）
                "nl#" -> GanzhiTime.format(now)
                else -> null
            }
        }
        val base = variable.substringBefore('+').substringBefore('-')
        if (!base.equals("time", ignoreCase = true)) return null
        val offset = variable.drop(base.length).toIntOrNull() ?: 0
        val target = Calendar.getInstance().apply {
            time = now
            add(Calendar.DAY_OF_YEAR, offset)
        }.time
        val pattern = format.ifEmpty { "yyyy-MM-dd HH:mm:ss" }
        return runCatching {
            SimpleDateFormat(pattern, Locale.getDefault()).format(target)
        }.getOrNull()
    }
}

/**
 * 二十四节气（近似计算）。
 *
 * 用通行的「寿星公式」：`日期 = ⌊Y×0.2422 + C⌋ − ⌊Y/4⌋`（Y 为年份后两位），
 * C 为每个节气在 21 世纪的常数。该公式对 2000–2099 年有效，个别年份有一天的偏差，
 * 用于输入法的日期候选足够——精确到分钟的节气需要天文历算，不值得为一句候选引入。
 *
 * 日期表按公历月份索引（1–12）。每个节气给出月份与当月的近似日。
 */
object SolarTerms {

    /** 节气名（与 [C21] 一一对应）。 */
    private val NAMES = listOf(
        "小寒", "大寒", "立春", "雨水", "惊蛰", "春分",
        "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
        "小暑", "大暑", "立秋", "处暑", "白露", "秋分",
        "寒露", "霜降", "立冬", "小雪", "大雪", "冬至",
    )

    /** 各节气所在月份（与 [NAMES] 一一对应）。 */
    private val MONTHS = intArrayOf(
        1, 1, 2, 2, 3, 3,
        4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9,
        10, 10, 11, 11, 12, 12,
    )

    /** 21 世纪（2000–2099）的 C 常数（与 [NAMES] 一一对应）。 */
    private val C21 = doubleArrayOf(
        5.4055, 20.12, 3.87, 18.73, 5.63, 20.646,
        4.81, 20.1, 5.52, 21.04, 5.678, 21.37,
        7.108, 22.83, 7.5, 23.13, 7.646, 23.042,
        8.318, 23.438, 7.438, 22.36, 7.18, 21.94,
    )

    /**
     * 当天节气描述。
     *
     * - 当天正好是某个节气 → 节气名（`霜降`）；
     * - 否则 → `无节气`。
     *
     * 「下一个节气」由 [describeNext] 单独提供：两者是两个候选（无节气 / 下一个节气），
     * 用户先看到今天到底有没有节气，再看下一个是哪天——合成一句会答非所问。
     */
    fun describe(now: Date, format: String? = null): String {
        val calendar = Calendar.getInstance().apply { time = now }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        for (i in NAMES.indices) {
            if (MONTHS[i] == month && dayOfTerm(year, i) == day) return NAMES[i]
        }
        return "无节气"
    }

    /**
     * 下一个节气与其日期（`立冬 11月7日`）；当天是节气时也返回当天那个。
     *
     * 跨年同样成立：年末之后是次年小寒。
     */
    fun describeNext(now: Date, format: String? = null): String {
        val calendar = Calendar.getInstance().apply { time = now }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        for (i in NAMES.indices) {
            val termMonth = MONTHS[i]
            val termDay = dayOfTerm(year, i)
            if (termMonth == month && termDay == day) {
                return "${NAMES[i]} ${formatTermDate(termMonth, termDay, format)}"
            }
        }
        for (i in NAMES.indices) {
            val termMonth = MONTHS[i]
            val termDay = dayOfTerm(year, i)
            if (termMonth > month || (termMonth == month && termDay > day)) {
                return "${NAMES[i]} ${formatTermDate(termMonth, termDay, format)}"
            }
        }
        val nextYear = year + 1
        return "${NAMES[0]} ${formatTermDate(MONTHS[0], dayOfTerm(nextYear, 0), format)}"
    }

    /** 计算 [year] 年第 [index] 个节气的日期（当月几号）。 */
    internal fun dayOfTerm(year: Int, index: Int): Int {
        val y = year % 100
        val raw = (y * 0.2422 + C21[index])
        val day = raw.toInt() - y / 4
        return day.coerceIn(1, 31)
    }

    /** [index] 号节气所在的公历月份（1–12）；[GanzhiTime] 按月柱分界需要它。 */
    internal fun monthOfTerm(index: Int): Int = MONTHS.getOrElse(index) { 1 }

    private fun formatTermDate(month: Int, day: Int, format: String?): String {
        if (format.isNullOrEmpty()) return "${month}月${day}日"
        val calendar = Calendar.getInstance().apply {
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
        }
        return runCatching {
            SimpleDateFormat(format, Locale.getDefault()).format(calendar.time)
        }.getOrDefault("${month}月${day}日")
    }
}
