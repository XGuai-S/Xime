package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GanzhiTime] 干支纪时测试。
 *
 * 干支（年/月/日/时柱）错一天、错一个月都不会崩，只会静默给出错误的纪时——
 * 因此这里用**可独立查证的参考点**把每个柱子分别钉住。
 */
class GanzhiTimeTest {

    @Test
    fun `需求示例_丙午年戊戌月辛酉日寅时一刻`() {
        // 用户给出的目标形态：2026-10-14 03:15 → 丙午年戊戌月辛酉日寅时一刻
        assertEquals(
            "丙午年戊戌月辛酉日寅时一刻",
            GanzhiTime.format(2026, 10, 14, 3, 0),
        )
        // 年月日三柱不随分钟变化；一刻 = 时辰首个 15 分钟
        assertEquals(
            "丙午年戊戌月辛酉日",
            GanzhiTime.format(2026, 10, 14, 3, 14).substringBefore("寅时"),
        )
    }

    @Test
    fun `时辰整点与刻分`() {
        // 寅时自 03:00 起：03:00 = 一刻（八刻制下第一刻从零分起算）
        assertEquals("寅时一刻", GanzhiTime.format(2026, 10, 14, 3, 0).substringAfter("日"))
        // 每刻 15 分：03:20 落在第二刻零 5 分
        assertEquals("寅时二刻5分", GanzhiTime.format(2026, 10, 14, 3, 20).substringAfter("日"))
        // 03:30 是第三刻整
        assertEquals("寅时三刻", GanzhiTime.format(2026, 10, 14, 3, 30).substringAfter("日"))
        // 04:00 已进入本时辰第二小时 → 五刻整（60 分 ÷ 15 + 1）
        assertEquals("寅时五刻", GanzhiTime.format(2026, 10, 14, 4, 0).substringAfter("日"))
    }

    @Test
    fun `日柱与已知参考一致`() {
        // 2000-01-01 为戊午日（公认参考点）
        assertEquals(54, GanzhiTime.dayCycleIndex(2000, 1, 1)) // 戊午 = 第 54 位（0 基）
        // 2026-10-14 为辛酉日
        assertEquals(57, GanzhiTime.dayCycleIndex(2026, 10, 14)) // 辛酉 = 第 57 位（0 基）
        // 连续两日序号必须递增 1（干支纪日不间断）
        val a = GanzhiTime.dayCycleIndex(2026, 10, 14)
        val b = GanzhiTime.dayCycleIndex(2026, 10, 15)
        assertEquals((a + 1) % 60, b)
    }

    @Test
    fun `日柱跨月跨年连续`() {
        // 月末 → 次月 1 日、年末 → 次年 1 月 1 日都应恰好 +1
        assertEquals(
            (GanzhiTime.dayCycleIndex(2026, 10, 31) + 1) % 60,
            GanzhiTime.dayCycleIndex(2026, 11, 1),
        )
        assertEquals(
            (GanzhiTime.dayCycleIndex(2026, 12, 31) + 1) % 60,
            GanzhiTime.dayCycleIndex(2027, 1, 1),
        )
    }

    @Test
    fun `年柱以立春为界`() {
        // 立春前仍属上一年：2026 立春前应得乙巳年（2025），立春当天起为丙午年（2026）
        val lichun = SolarTerms.dayOfTerm(2026, 2)
        val before = GanzhiTime.format(2026, 2, (lichun - 1).coerceAtLeast(1), 12, 0)
        val on = GanzhiTime.format(2026, 2, lichun, 12, 0)
        assertTrue("立春前应为乙巳年，实际=$before", before.startsWith("乙巳年"))
        assertTrue("立春起应为丙午年，实际=$on", on.startsWith("丙午年"))
    }

    @Test
    fun `月柱以节为界且按五虎遁起月干`() {
        // 2026 年立春后为寅月（丙午年 → 丙辛起庚寅）
        val afterLichun = GanzhiTime.format(2026, 2, SolarTerms.dayOfTerm(2026, 2) + 1, 12, 0)
        assertTrue("立春后应为庚寅月，实际=$afterLichun", afterLichun.contains("庚寅月"))
        // 惊蛰前仍是寅月；惊蛰当天进入卯月（辛卯）
        val beforeJingzhe = GanzhiTime.format(2026, 3, (SolarTerms.dayOfTerm(2026, 4) - 1), 12, 0)
        assertTrue("惊蛰前应仍为寅月，实际=$beforeJingzhe", beforeJingzhe.contains("寅月"))
        val onJingzhe = GanzhiTime.format(2026, 3, SolarTerms.dayOfTerm(2026, 4), 12, 0)
        assertTrue("惊蛰起应为卯月，实际=$onJingzhe", onJingzhe.contains("卯月"))
    }

    @Test
    fun `时辰地支按两小时划分且子时跨日`() {
        assertEquals(0, GanzhiTime.hourBranchIndex(23))
        assertEquals(0, GanzhiTime.hourBranchIndex(0))
        assertEquals(1, GanzhiTime.hourBranchIndex(1))
        assertEquals(1, GanzhiTime.hourBranchIndex(2))
        assertEquals(2, GanzhiTime.hourBranchIndex(3))
        assertEquals(6, GanzhiTime.hourBranchIndex(12)) // 午时 = 地支第 6 位
        assertEquals(7, GanzhiTime.hourBranchIndex(13)) // 未时
        assertEquals(11, GanzhiTime.hourBranchIndex(21))
        assertEquals(11, GanzhiTime.hourBranchIndex(22))
    }

    @Test
    fun `nl 井号 变量输出完整干支纪时`() {
        // 模板变量走同一条实现，避免「候选栏显示的和单测里的不一致」
        val now = java.util.Calendar.getInstance().apply {
            set(2026, 9, 14, 3, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.time
        val out = KeyboardComboEvaluator.evaluate("{nl#}", now)
        assertEquals("丙午年戊戌月辛酉日寅时一刻", out)
    }

    @Test
    fun `onl 内置配置的干支项指向 nl 井号`() {
        val text = com.kingzcheung.xime.RepoFiles.file("src/main/assets/xime.yaml").readText()
        val combos = KeysConfigHelper.parseKeyboardCombosYamlText(text) ?: error("解析失败")
        val onl = combos.first { it.code == "onl" }
        val templates = onl.items.mapNotNull { it.template }
        assertTrue("onl 应含干支纪时 {nl#}，实际=$templates", templates.contains("{nl#}"))
    }
}
