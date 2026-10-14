package com.kingzcheung.xime.settings

import java.util.Calendar

/**
 * 公历 → 农历转换（1900–2100）。
 *
 * 数据表与算法**逐字移植自** `app/src/main/jni/librime-t9/src/t9_date_translator.cc`
 * （其本身参考 lunar.lua）：九键日期翻译器已用它多年，这里复用同一份表可保证
 * 「九键的 nl 候选」与「组合键的农历候选」不会给出两个答案。
 *
 * 为什么不在 Kotlin 侧另找库：农历数据表是 202 项 7 位十六进制的紧凑编码，
 * 内嵌即可；引入第三方库只为一句候选不值得，且会与九键侧的数据源分叉。
 */
object LunarCalendar {


    /** 数据表基准年：索引 0 对应 1899 年（1900 年春节前计算用）。 */
    private const val DATA_BASE_YEAR = 1899

    private val LUNAR_DATA = arrayOf(
        "AB500D2", "4BD0883", "4AE00DB", "A5700D0", "54D0581", "D2600D8", "D9500CC", "655147D", "56A00D5", "9AD00CA",
        "55D027A", "4AE00D2", "A5B0682", "A4D00DA", "D2500CE", "D25157E", "B5500D6", "56A00CC", "ADA027B", "95B00D3",
        "49717C9", "49B00DC", "A4B00D0", "B4B0580", "6A500D8", "6D400CD", "AB5147C", "2B600D5", "95700CA", "52F027B",
        "49700D2", "6560682", "D4A00D9", "EA500CE", "6A9157E", "5AD00D6", "2B600CC", "86E137C", "92E00D3", "C8D1783",
        "C9500DB", "D4A00D0", "D8A167F", "B5500D7", "56A00CD", "A5B147D", "25D00D5", "92D00CA", "D2B027A", "A9500D2",
        "B550781", "6CA00D9", "B5500CE", "535157F", "4DA00D6", "A5B00CB", "457037C", "52B00D4", "A9A0883", "E9500DA",
        "6AA00D0", "AEA0680", "AB500D7", "4B600CD", "AAE047D", "A5700D5", "52600CA", "F260379", "D9500D1", "5B50782",
        "56A00D9", "96D00CE", "4DD057F", "4AD00D7", "A4D00CB", "D4D047B", "D2500D3", "D550883", "B5400DA", "B6A00CF",
        "95A1680", "95B00D8", "49B00CD", "A97047D", "A4B00D5", "B270ACA", "6A500DC", "6D400D1", "AF40681", "AB600D9",
        "93700CE", "4AF057F", "49700D7", "64B00CC", "74A037B", "EA500D2", "6B50883", "5AC00DB", "AB600CF", "96D0580",
        "92E00D8", "C9600CD", "D95047C", "D4A00D4", "DA500C9", "755027A", "56A00D1", "ABB0781", "25D00DA", "92D00CF",
        "CAB057E", "A9500D6", "B4A00CB", "BAA047B", "AD500D2", "55D0983", "4BA00DB", "A5B00D0", "5171680", "52B00D8",
        "A9300CD", "795047D", "6AA00D4", "AD500C9", "5B5027A", "4B600D2", "96E0681", "A4E00D9", "D2600CE", "EA6057E",
        "D5300D5", "5AA00CB", "76A037B", "96D00D3", "4AB0B83", "4AD00DB", "A4D00D0", "D0B1680", "D2500D7", "D5200CC",
        "DD4057C", "B5A00D4", "56D00C9", "55B027A", "49B00D2", "A570782", "A4B00D9", "AA500CE", "B25157E", "6D200D6",
        "ADA00CA", "4B6137B", "93700D3", "49F08C9", "49700DB", "64B00D0", "68A1680", "EA500D7", "6AA00CC", "A6C147C",
        "AAE00D4", "92E00CA", "D2E0379", "C9600D1", "D550781", "D4A00D9", "DA400CD", "5D5057E", "56A00D6", "A6C00CB",
        "55D047B", "52D00D3", "A9B0883", "A9500DB", "B4A00CF", "B6A067F", "AD500D7", "55A00CD", "ABA047C", "A5A00D4",
        "52B00CA", "B27037A", "69300D1", "7330781", "6AA00D9", "AD500CE", "4B5157E", "4B600D6", "A5700CB", "54E047C",
        "D1600D2", "E960882", "D5200DA", "DAA00CF", "6AA167F", "56D00D7", "4AE00CD", "A9D047D", "A2D00D4", "D1500C9",
        "F250279", "D5200D1",
    )

    private val TIAN_GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val DI_ZHI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    private val SHU_XIANG = arrayOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

    private val LUNAR_MONTH_NAMES = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月",
    )

    private val LUNAR_DAY_NAMES = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    private val CHINESE_DIGITS = arrayOf("〇", "一", "二", "三", "四", "五", "六", "七", "八", "九")

    /** 一次农历换算的结果。 */
    data class LunarDate(
        val year: Int,
        val month: Int,
        val day: Int,
        val isLeap: Boolean,
    )

    /**
     * 公历 → 农历。超出支持范围（1900 前 / 2100 后）返回 null。
     *
     * @param year 公历年
     * @param month 公历月（1–12）
     * @param day 公历日
     */
    fun fromGregorian(year: Int, month: Int, day: Int): LunarDate? {
        if (year < 1900 || year > 2100 || month !in 1..12 || day !in 1..31) return null
        val idx1 = year - DATA_BASE_YEAR
        val idx0 = idx1 - 1
        if (idx1 < 0 || idx1 >= LUNAR_DATA.size || idx0 < 0) return null

        var data = analyze(LUNAR_DATA[idx1])
        val gregorian = year * 10000 + month * 100 + day
        var date3 = diffDate(year * 10000 + data.newYear, gregorian)
        var lunarYear = year
        if (date3 < 0) {
            // 公历日在农历新年之前：用上一年的数据
            data = analyze(LUNAR_DATA[idx0])
            lunarYear = year - 1
            date3 = diffDate(lunarYear * 10000 + data.newYear, gregorian)
        }
        date3 += 1

        val monthInfo = if (data.leap > 0) {
            data.monthInfo.substring(0, data.leap) + data.leapInfo + data.monthInfo.substring(data.leap)
        } else {
            data.monthInfo
        }

        var lunarMonth = 0
        var isLeap = false
        var lunarDay = 0
        for (i in monthInfo.indices) {
            val days = if (monthInfo[i] == '1') 30 else 29
            if (date3 <= days) {
                if (data.leap > 0 && i == data.leap) {
                    lunarMonth = i
                    isLeap = true
                } else {
                    lunarMonth = if (data.leap > 0 && i > data.leap) i else i + 1
                    isLeap = false
                }
                lunarDay = date3
                break
            }
            date3 -= days
        }
        if (lunarMonth == 0 || lunarDay == 0) return null
        return LunarDate(year = lunarYear, month = lunarMonth, day = lunarDay, isLeap = isLeap)
    }

    /** 农历完整写法：`二〇二六年九月初四`（闰月加「闰」）。 */
    fun formatFull(date: LunarDate): String {
        val yearText = yearToChinese(date.year)
        val monthText = (if (date.isLeap) "闰" else "") +
            LUNAR_MONTH_NAMES.getOrElse(date.month - 1) { "${date.month}月" }
        val dayText = LUNAR_DAY_NAMES.getOrElse(date.day - 1) { "${date.day}" }
        return yearText + "年" + monthText + dayText
    }

    /** 农历短写法（月日）：`九月初四`。 */
    fun formatShort(date: LunarDate): String {
        val monthText = (if (date.isLeap) "闰" else "") +
            LUNAR_MONTH_NAMES.getOrElse(date.month - 1) { "${date.month}月" }
        val dayText = LUNAR_DAY_NAMES.getOrElse(date.day - 1) { "${date.day}" }
        return monthText + dayText
    }

    /** 农历年干支 + 生肖：`丙午（马）`。 */
    fun ganzhi(date: LunarDate): String {
        val gan = ((date.year - 4) % 10 + 10) % 10
        val zhi = ((date.year - 4) % 12 + 12) % 12
        return TIAN_GAN[gan] + DI_ZHI[zhi] + "（" + SHU_XIANG[zhi] + "）"
    }

    /**
     * 干支纪年 + 农历月日：`丙午年九月初五`。
     *
     * 传统农历写法用干支纪年（今年是什么年），而不是公历数字年；闰月同样加「闰」。
     */
    fun formatGanzhi(date: LunarDate): String {
        val gan = ((date.year - 4) % 10 + 10) % 10
        val zhi = ((date.year - 4) % 12 + 12) % 12
        val yearText = TIAN_GAN[gan] + DI_ZHI[zhi] + "年"
        return yearText + formatShort(date)
    }

    /** 4 位年份 → 中文数字（2026 → 二〇二六）。 */
    private fun yearToChinese(year: Int): String =
        year.toString().map { CHINESE_DIGITS[it - '0'] }.joinToString("")

    // ── 数据表解析（与 C++ 侧 AnalyzeLunarData 等价）──

    private class LunarData(
        /** 12 位月大小串：1 = 30 天、0 = 29 天。 */
        val monthInfo: String,
        /** 闰月信息字符（十六进制）。 */
        val leapInfo: String,
        /** 闰月位置（0 = 无闰月）。 */
        val leap: Int,
        /** 农历新年对应的公历月日（如 210 表示 2 月 10 日）。 */
        val newYear: Int,
    )

    private fun analyze(hex: String): LunarData {
        val monthInfo = hexToBin(hex.substring(0, 3))
        val leapInfo = hex.substring(3, 4)
        val leap = hex.substring(4, 5).toInt(16)
        val newYear = hex.substring(5, 7).toInt(16)
        return LunarData(monthInfo, leapInfo, leap, newYear)
    }

    /** 十六进制 → 12 位二进制串（月大小表）。 */
    private fun hexToBin(hex: String): String =
        hex.toInt(16).toString(2).padStart(12, '0')

    // ── 日期差（与 C++ 侧 DiffDate/LeaveDate 等价）──

    private fun isLeapYear(year: Int): Int =
        if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 366 else 365

    private fun leaveDate(year: Int, month: Int, day: Int): Int {
        val daysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (isLeapYear(year) == 366) daysInMonth[1] = 29
        var total = 0
        for (i in 0 until month - 1) total += daysInMonth[i]
        return total + day
    }

    /** date2 - date1（两者均为 YYYYMMDD）；date2 < date1 返回 -1。 */
    private fun diffDate(date1: Int, date2: Int): Int {
        if (date2 < date1) return -1
        if (date2 == date1) return 0
        val y1 = date1 / 10000
        val m1 = (date1 / 100) % 100
        val d1 = date1 % 100
        val y2 = date2 / 10000
        val m2 = (date2 / 100) % 100
        val d2 = date2 % 100
        val n = y2 - y1
        return if (n > 1) {
            var total = 0
            for (i in 1 until n) total += isLeapYear(y1 + i)
            total + isLeapYear(y1) - leaveDate(y1, m1, d1) + leaveDate(y2, m2, d2)
        } else if (n == 1) {
            isLeapYear(y1) - leaveDate(y1, m1, d1) + leaveDate(y2, m2, d2)
        } else {
            leaveDate(y2, m2, d2) - leaveDate(y1, m1, d1)
        }
    }

    /** 便捷入口：按时间戳取农历。 */
    fun fromDate(date: java.util.Date): LunarDate? {
        val calendar = Calendar.getInstance().apply { time = date }
        return fromGregorian(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }
}
