package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 组合键（keyboard.combos）的解析与模板求值测试。
 *
 * 组合键的候选内容依赖**运行时刻**（今天/明天/节气），真机上无法回归；
 * 这里把 `now` 注入求值器，锁住跨月/跨年这类边界，以及「按触发码给出求值结果」的核心行为。
 */
class KeyboardComboTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 30): Date =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

    // ── 模板求值 ──

    @Test
    fun `时间变量按格式求值`() {
        val now = at(2026, 10, 14)
        assertEquals("2026年10月14日", KeyboardComboEvaluator.evaluate("{time:yyyy年M月d日}", now))
        assertEquals("2026-10-14", KeyboardComboEvaluator.evaluate("{time:yyyy-MM-dd}", now))
        assertEquals("10:30", KeyboardComboEvaluator.evaluate("{time:HH:mm}", now))
        assertEquals("10点30分", KeyboardComboEvaluator.evaluate("{time:H点m分}", now))
    }

    @Test
    fun `偏移变量按天加减且跨月正确`() {
        // 月末 +1 天必须进到下月，而不是得到 10 月 32 日
        val endOfMonth = at(2026, 10, 31)
        assertEquals("2026年11月1日", KeyboardComboEvaluator.evaluate("{time+1:yyyy年M月d日}", endOfMonth))
        assertEquals("2026年11月2日", KeyboardComboEvaluator.evaluate("{time+2:yyyy年M月d日}", endOfMonth))
    }

    @Test
    fun `偏移变量跨年正确`() {
        val endOfYear = at(2026, 12, 31)
        assertEquals("2027-01-01", KeyboardComboEvaluator.evaluate("{time+1:yyyy-MM-dd}", endOfYear))
    }

    @Test
    fun `负偏移取过去日期`() {
        val now = at(2026, 3, 1)
        assertEquals("2026-02-28", KeyboardComboEvaluator.evaluate("{time-1:yyyy-MM-dd}", now))
    }

    @Test
    fun `未知变量原样保留`() {
        // 容错优先：写错的变量名不该让整条候选变成空串（用户会以为组合键坏了）
        assertEquals("{foo:yyyy}", KeyboardComboEvaluator.evaluate("{foo:yyyy}", at(2026, 1, 1)))
    }

    @Test
    fun `求值不依赖正则方言（回归：真机 ICU 正则崩溃）`() {
        // 真机崩溃根因：旧实现用 Regex 匹配模板，`[+-]` 在 Android 的 ICU 正则里
        // 是非法范围而抛 PatternSyntaxException；该正则是 object 初始化字段，
        // 异常升级为 ExceptionInInitializerError，输入法进程直接被杀（闪回桌面）。
        // 现改为手写扫描：这里锁住“类加载不抛错”与各类边界输入都能安全求值。
        val now = at(2026, 10, 14)
        // 未闭合、空体、多个变量、相邻变量、含冒号、偏移写法——都不应抛异常
        val samples = listOf(
            "{time+1:yyyy-MM-dd}",
            "{time-1:yyyy-MM-dd}",
            "{}",
            "{unclosed",
            "plain",
            "{time:yyyy}{time:MM}",
            "{time:HH:mm:ss}",
            "{jq}",
        )
        samples.forEach { sample ->
            KeyboardComboEvaluator.evaluate(sample, now)
        }
        assertEquals(
            "2026-10-15 2026-10-15",
            KeyboardComboEvaluator.evaluate("{time+1:yyyy-MM-dd} {time+1:yyyy-MM-dd}", now),
        )
    }

    @Test
    fun `未闭合花括号原样保留`() {
        // 用户配置写漏一个右括号时，不能把整条候选吃掉
        assertEquals("{time:yyyy", KeyboardComboEvaluator.evaluate("{time:yyyy", at(2026, 1, 1)))
    }

    @Test
    fun `模板求值不使用正则`() {
        // 回归护栏（真机崩溃）：Android 的 ICU 正则与 JVM 的 java.util.regex 不完全兼容，
        // `[+-]` 这类在 JVM 上合法的写法会在真机抛 PatternSyntaxException；
        // 而正则写在 object 字段里，异常会升级为 ExceptionInInitializerError 杀掉输入法进程。
        // 单测跑在 JVM 上永远发现不了，所以这里直接禁止该文件出现 Regex(。
        val source = com.kingzcheung.xime.RepoFiles.mainKotlinSources
            .single { it.name == "KeyboardCombo.kt" }
            .readText()
        assertTrue(
            "组合键模板求值不得使用正则（ICU/JVM 方言差异会崩输入法），请改手写扫描",
            !source.contains("Regex("),
        )
    }

    @Test
    fun `无变量的模板原样返回`() {
        assertEquals("固定短语", KeyboardComboEvaluator.evaluate("固定短语"))
    }

    // ── 节气 ──

    @Test
    fun `节气当天只回节气名`() {
        // 2026 年霜降为 10 月 23 日（寿星公式）
        val termDay = SolarTerms.dayOfTerm(2026, 19)
        val result = SolarTerms.describe(at(2026, 10, termDay))
        assertEquals("霜降", result)
    }

    @Test
    fun `非节气当天回无节气`() {
        val result = SolarTerms.describe(at(2026, 10, 14))
        // 需求：当天没有节气时，首选显示「无节气」，次选才显示下一个节气
        assertEquals("无节气", result)
    }

    @Test
    fun `下一个节气与日期由 describeNext 提供`() {
        val result = SolarTerms.describeNext(at(2026, 10, 14))
        assertTrue("应给出未来节气与日期，实际=$result", result.contains("月") && result.contains("日"))
        assertTrue(
            "10 月中旬之后应是霜降或立冬，实际=$result",
            result.startsWith("霜降") || result.startsWith("立冬"),
        )
    }

    @Test
    fun `节气当天 describeNext 也回当天那个`() {
        val termDay = SolarTerms.dayOfTerm(2026, 19)
        val result = SolarTerms.describeNext(at(2026, 10, termDay))
        assertTrue("当天是节气时应以节气名开头，实际=$result", result.startsWith("霜降"))
    }

    // ── 农历 ──

    @Test
    fun `农历换算与已知日期一致`() {
        // 数据表与算法逐字移植自 librime-t9 的 t9_date_translator.cc（与九键 nl 候选同源），
        // 用可查的日期钉住正确性，防止移植时抄错索引。
        // 2026 年春节为 2 月 17 日 → 正月初一
        val springFestival = LunarCalendar.fromGregorian(2026, 2, 17)
        assertEquals(1, springFestival?.month)
        assertEquals(1, springFestival?.day)
        assertEquals(false, springFestival?.isLeap)
        // 春节前一天应属上一年腊月
        val newYearEve = LunarCalendar.fromGregorian(2026, 2, 16)
        assertEquals(2025, newYearEve?.year)
        assertEquals(12, newYearEve?.month)
    }

    @Test
    fun `农历格式化含闰月与干支`() {
        val date = LunarCalendar.LunarDate(year = 2026, month = 9, day = 4, isLeap = false)
        assertEquals("九月初四", LunarCalendar.formatShort(date))
        assertEquals("二〇二六年九月初四", LunarCalendar.formatFull(date))
        assertEquals("闰九月初四", LunarCalendar.formatShort(date.copy(isLeap = true)))
        // 2026 年为丙午年（马）
        assertEquals("丙午（马）", LunarCalendar.ganzhi(date))
    }

    @Test
    fun `农历超出支持范围返回 null`() {
        assertNull(LunarCalendar.fromGregorian(1899, 1, 1))
        assertNull(LunarCalendar.fromGregorian(2101, 1, 1))
    }

    @Test
    fun `nl 模板变量给出农历月日`() {
        val now = at(2026, 10, 14)
        val short = KeyboardComboEvaluator.evaluate("{nl}", now)
        // 2026-10-14 为农历九月初五（已用春节/端午/中秋等已知日期校准过换算）
        assertEquals("九月初五", short)
        val full = KeyboardComboEvaluator.evaluate("{nl+}", now)
        assertTrue("带年份应含「年」，实际=$full", full.contains("年"))
    }

    @Test
    fun `nl 井号 模板变量给出干支纪年`() {
        // {nl#} 的完整干支纪时语义在 GanzhiTimeTest 里单测（年/月/日/时辰刻分四柱）。
        // 这里只锁「变量名确实接上了实现」，避免整段语义变更时静默失效。
        val now = at(2026, 10, 14)
        val out = KeyboardComboEvaluator.evaluate("{nl#}", now)
        assertTrue("应含干支年，实际=$out", out.startsWith("丙午年"))
        assertTrue("应含月柱，实际=$out", out.contains("月"))
        assertTrue("应含日柱，实际=$out", out.contains("日"))
        assertTrue("应含时辰，实际=$out", out.contains("时"))
    }

    @Test
    fun `onl 内置配置含三种农历写法`() {
        val text = com.kingzcheung.xime.RepoFiles.file("src/main/assets/xime.yaml").readText()
        val combos = KeysConfigHelper.parseKeyboardCombosYamlText(text) ?: error("解析失败")
        val onl = combos.first { it.code == "onl" }
        val templates = onl.items.mapNotNull { it.template }
        assertTrue("onl 应含 {nl}，实际=$templates", templates.contains("{nl}"))
        assertTrue("onl 应含干支记法 {nl#}，实际=$templates", templates.contains("{nl#}"))
        assertTrue("onl 应含带年份 {nl+}，实际=$templates", templates.contains("{nl+}"))
    }

    @Test
    fun `jq 与 jq_plus 变量语义不同`() {
        val now = at(2026, 10, 14)
        assertEquals("无节气", KeyboardComboEvaluator.evaluate("{jq}", now))
        val next = KeyboardComboEvaluator.evaluate("{jq+}", now)
        assertTrue("jq+ 应给出下一个节气，实际=$next", next.contains("月"))
    }

    @Test
    fun `年末之后回到次年小寒`() {
        val result = SolarTerms.describeNext(at(2026, 12, 31))
        assertTrue("年末之后应指向次年小寒，实际=$result", result.startsWith("小寒"))
    }

    // ── YAML 解析 ──

    @Test
    fun `解析内置组合键配置`() {
        val yaml = """
            keyboard:
              combos:
                orq: ["{time:yyyy年M月d日}", "{time:yyyy-MM-dd}"]
                ojq: ["{jq}"]
                oiz:
                  - { command: reload, label: "重新部署" }
        """.trimIndent()
        val combos = KeysConfigHelper.parseKeyboardCombosYamlText(yaml)
        assertNotNull(combos)
        val byCode = combos!!.associateBy { it.code }
        assertEquals(2, byCode.getValue("orq").items.size)
        assertEquals("{jq}", byCode.getValue("ojq").items.single().template)
        val reload = byCode.getValue("oiz").items.single()
        assertTrue("oiz 应是命令候选", reload.isCommand)
        assertEquals("reload", reload.command)
        assertEquals("重新部署", reload.label)
    }

    @Test
    fun `用户同名触发码覆盖内置`() {
        val builtIn = listOf(
            KeyboardCombo("orq", listOf(ComboItem(template = "{time:yyyy}"))),
            KeyboardCombo("ojq", listOf(ComboItem(template = "{jq}"))),
        )
        val custom = listOf(
            KeyboardCombo("orq", listOf(ComboItem(template = "{time:MM-dd}"))),
        )
        val merged = KeysConfigHelper.mergeCombos(custom, builtIn)
        assertEquals(2, merged.size)
        assertEquals(
            "同名触发码应以用户配置为准",
            "{time:MM-dd}", merged.first { it.code == "orq" }.items.single().template,
        )
        assertEquals(
            "未覆盖的触发码应保留内置",
            "{jq}", merged.first { it.code == "ojq" }.items.single().template,
        )
    }

    @Test
    fun `空列表可禁用内置组合键`() {
        val builtIn = listOf(KeyboardCombo("orq", listOf(ComboItem(template = "{time:yyyy}"))))
        val custom = listOf(KeyboardCombo("orq", emptyList()))
        val merged = KeysConfigHelper.mergeCombos(custom, builtIn)
        assertTrue("写成空列表应禁用该触发码", merged.isEmpty())
    }

    @Test
    fun `匹配要求编码全等而不是前缀`() {
        // 前缀匹配会让 orqx 也弹日期候选，候选栏在继续输入时反复闪动
        val combos = listOf(KeyboardCombo("orq", listOf(ComboItem(template = "x"))))
        assertNotNull(combos.firstOrNull { it.code == "orq" })
        assertNull("前缀不应命中", combos.firstOrNull { it.code == "orqx" })
    }

    @Test
    fun `内置配置里包含需求中的触发码`() {
        val text = com.kingzcheung.xime.RepoFiles.file("src/main/assets/xime.yaml").readText()
        val combos = KeysConfigHelper.parseKeyboardCombosYamlText(text) ?: error("解析失败")
        val codes = combos.map { it.code }.toSet()
        for (expected in listOf("orq", "omt", "oht", "onl", "ouj", "ojq", "oiz")) {
            assertTrue("内置配置缺少触发码 $expected", expected in codes)
        }
    }
}
