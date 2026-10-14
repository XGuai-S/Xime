package com.kingzcheung.xime.settings

import java.util.Calendar
import java.util.Date

/**
 * 干支纪时：年柱 / 月柱 / 日柱 + 时辰刻分。
 *
 * 输出形如 `丙午年戊戌月辛酉日寅时一刻`（分不为零时追加「N分」，如 `寅时一刻五分`）。
 *
 * ## 规则
 *
 * - **年柱**：以**立春**为界（八字惯例），不是农历正月初一。立春前仍算上一年。
 * - **月柱**：以十二「节」（立春/惊蛰/清明/立夏/芒种/小暑/立秋/白露/寒露/立冬/大雪/小寒）
 *   为界；月干由年干按「五虎遁」推出（甲己之年丙作首、乙庚之岁戊为头、丙辛必定寻庚起、
 *   丁壬壬位顺行流、戊癸甲寅之上好追求）。
 * - **日柱**：连续干支纪日，用儒略日数推算——干支日与公历日一一对应、不随年份重置。
 * - **时辰**：两小时一时辰，子时自 23:00 起；每时辰八刻、每刻十五分。
 *
 * 之所以把月柱单独按节气算，而不是直接用农历月：干支月的分界是**节令**（如十月初未必
 * 已进戌月），用农历月会让「同一天在不同年份给出不同月柱」这类错误静默出现。
 */
object GanzhiTime {

    private val GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val ZHI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")

    /** 十二「节」在 [SolarTerms] 的索引（只取节、不取气），按公历月份顺序。 */
    private val JIE_INDICES = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22)

    /**
     * 每个「节」对应的月支下标（0 = 子）。
     *
     * 小寒 → 丑(1)、立春 → 寅(2)、惊蛰 → 卯(3)……大雪 → 子(0)。
     */
    private val JIE_MONTH_BRANCH = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0)

    private val KE_NAMES = arrayOf(
        "一刻", "二刻", "三刻", "四刻", "五刻", "六刻", "七刻", "八刻",
    )

    /** 格式化：`丙午年戊戌月辛酉日寅时一刻`。 */
    fun format(date: Date = Date()): String {
        val calendar = Calendar.getInstance().apply { time = date }
        return format(
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH) + 1,
            day = calendar.get(Calendar.DAY_OF_MONTH),
            hour = calendar.get(Calendar.HOUR_OF_DAY),
            minute = calendar.get(Calendar.MINUTE),
        )
    }

    /** 格式化（显式时间分量，便于单测注入）。 */
    fun format(year: Int, month: Int, day: Int, hour: Int, minute: Int): String {
        val gYear = ganzhiYear(year, month, day)
        val yearStem = stemIndex(gYear)
        val yearBranch = branchIndex(gYear)
        val monthBranch = monthBranchIndex(year, month, day)
        // 五虎遁：寅月的月干 = 年干 × 2 + 2；其后逐月顺行
        val monthStem = (yearStem * 2 + 2 + monthBranchFromYin(monthBranch)) % 10
        val dayCycle = dayCycleIndex(year, month, day)
        val hourBranch = hourBranchIndex(hour)

        return buildString {
            append(GAN[yearStem]).append(ZHI[yearBranch]).append('年')
            append(GAN[monthStem]).append(ZHI[monthBranch]).append('月')
            append(GAN[dayCycle % 10]).append(ZHI[dayCycle % 12]).append('日')
            append(ZHI[hourBranch]).append('时')
            append(keText(hour, minute))
        }
    }

    /** 立春纪年：立春（含）之后算本年，之前算上一年。 */
    internal fun ganzhiYear(year: Int, month: Int, day: Int): Int {
        val lichunDay = SolarTerms.dayOfTerm(year, 2)
        val beforeLichun = month < 2 || (month == 2 && day < lichunDay)
        return if (beforeLichun) year - 1 else year
    }

    private fun stemIndex(year: Int): Int = ((year - 4) % 10 + 10) % 10

    private fun branchIndex(year: Int): Int = ((year - 4) % 12 + 12) % 12

    /**
     * 月支下标（0 = 子）：取**已过去或当天**的最后一个「节」。
     *
     * 若当年还没有任何「节」（一月初、小寒之前），仍属上一年大雪起的子月。
     */
    internal fun monthBranchIndex(year: Int, month: Int, day: Int): Int {
        var result = 0 // 缺省 = 子月（大雪起，跨年）
        for (i in JIE_INDICES.indices) {
            val termIndex = JIE_INDICES[i]
            val termMonth = SolarTerms.monthOfTerm(termIndex)
            val termDay = SolarTerms.dayOfTerm(year, termIndex)
            if (termMonth < month || (termMonth == month && termDay <= day)) {
                result = JIE_MONTH_BRANCH[i]
            }
        }
        return result
    }

    /** 月支相对寅月的序号（寅 = 0、卯 = 1 …… 丑 = 11），供五虎遁推算月干。 */
    private fun monthBranchFromYin(branch: Int): Int = ((branch - 2) % 12 + 12) % 12

    /**
     * 日柱在六十甲子中的序号（0 = 甲子）。
     *
     * 儒略日数 + 49 对 60 取模：干支纪日自公元前起连续不断，与公历日期一一对应。
     */
    internal fun dayCycleIndex(year: Int, month: Int, day: Int): Int {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        val jdn = day + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045
        return (((jdn + 49) % 60 + 60) % 60).toInt()
    }

    /** 时辰地支下标：子时 23:00–00:59、丑时 01:00–02:59…… */
    internal fun hourBranchIndex(hour: Int): Int = ((hour + 1) / 2) % 12

    /** 时辰内的「刻分」：每时辰八刻、每刻十五分；分为零时只写刻。 */
    private fun keText(hour: Int, minute: Int): String {
        // 时辰自奇数点起算：子时 23 点、丑时 1 点、寅时 3 点……
        // 起始小时 = (2 × 地支下标 − 1) mod 24；落在第二个小时时再补 60 分。
        val branch = hourBranchIndex(hour)
        val startHour = (2 * branch - 1 + 24) % 24
        val hourOffset = if (hour == startHour) 0 else 1
        val minutesIntoBranch = hourOffset * 60 + minute
        val ke = minutesIntoBranch / 15
        val fen = minutesIntoBranch % 15
        val keName = KE_NAMES[ke.coerceIn(0, 7)]
        // 注意用 ${fen}：中文是合法的标识符字符，直接写 $fen分 会被解析成变量名「fen分」
        return if (fen == 0) keName else "$keName${fen}分"
    }
}
