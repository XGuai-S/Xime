package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoWordStoreTest {

    private val now = 1_750_000_000_000L
    private val dayMs = 24L * 60L * 60L * 1000L

    // ── record ──

    @Test
    fun `首次上屏入临时库_次数1`() {
        val temp = AutoWordStore.record(emptyMap(), "jt", "今天", now)
        val entry = temp["jt"]?.get("今天")
        assertEquals(1, entry?.count)
        assertEquals(now, entry?.firstSeen)
        assertEquals(now, entry?.lastSeen)
    }

    @Test
    fun `再次上屏同词次数累加_首见时间不变`() {
        var temp = AutoWordStore.record(emptyMap(), "jt", "今天", now)
        temp = AutoWordStore.record(temp, "jt", "今天", now + 1000)
        val entry = temp["jt"]?.get("今天")
        assertEquals(2, entry?.count)
        assertEquals(now, entry?.firstSeen)
        assertEquals(now + 1000, entry?.lastSeen)
    }

    @Test
    fun `不可计入的词不进库`() {
        assertTrue(AutoWordStore.record(emptyMap(), "a", "单", now) === emptyMap<String, Map<String, AutoWordStore.Entry>>())
        assertTrue(AutoWordStore.record(emptyMap(), "", "今天", now) === emptyMap<String, Map<String, AutoWordStore.Entry>>())
    }
    @Test
    fun `组合生词已存在_不计数不刷新`() {
        // 用户连打「虾苗」两次，组合逻辑自动拼出「虾苗虾苗」（initialCount=0）：
        // 若词库已有该组合词，不应把它当成「用户选中」计数 +1、也不刷新 lastSeen。
        var temp = AutoWordStore.record(emptyMap(), "xmxm", "虾苗虾苗", now, initialCount = 0)
        val first = temp["xmxm"]?.get("虾苗虾苗")
        assertEquals(0, first?.count)
        assertEquals(now, first?.lastSeen)
        // 再次自动拼接（initialCount=0）：已存在 → 原样返回，不计数不刷新
        val again = AutoWordStore.record(temp, "xmxm", "虾苗虾苗", now + 5000, initialCount = 0)
        assertTrue(again === temp)
        assertEquals(0, again["xmxm"]?.get("虾苗虾苗")?.count)
        assertEquals(now, again["xmxm"]?.get("虾苗虾苗")?.lastSeen)
    }
    @Test
    fun `组合生词被用户选中后_按普通收录计数`() {
        // 组合词先在词库（0 次），用户从候选栏选中它上屏 → 走普通收录路径（initialCount=1）
        var temp = AutoWordStore.record(emptyMap(), "xmxm", "虾苗虾苗", now, initialCount = 0)
        temp = AutoWordStore.record(temp, "xmxm", "虾苗虾苗", now + 1000, initialCount = 1)
        val entry = temp["xmxm"]?.get("虾苗虾苗")
        assertEquals(1, entry?.count)
        assertEquals(now + 1000, entry?.lastSeen)
    }

    // ── promote ──

    @Test
    fun `窗口内两次上屏晋升固定词库`() {
        var temp = AutoWordStore.record(emptyMap(), "jt", "今天", now)
        temp = AutoWordStore.record(temp, "jt", "今天", now + 1000)
        val promotion = AutoWordStore.promote(temp, emptyMap(), now + 1000)
        assertEquals(1, promotion.promoted.size)
        assertEquals("今天", promotion.promoted[0].word)
        // 临时库清空，固定库获得该词
        assertTrue(promotion.temp.isEmpty())
        assertEquals(20, promotion.fixed["jt"]?.get("今天"))
    }

    @Test
    fun `窗口内一次上屏即晋升_门槛为1`() {
        // PROMOTE_MIN_COUNT=1：每个词选中 1 次就够门槛，两个词都会晋升
        var temp = AutoWordStore.record(emptyMap(), "jt", "今天", now)
        temp = AutoWordStore.record(temp, "jt", "天气", now + 1000)
        val promotion = AutoWordStore.promote(temp, emptyMap(), now + 1000)
        assertEquals(2, promotion.promoted.size)
        assertEquals(2, promotion.fixed["jt"]?.size)
        assertNull(promotion.temp["jt"])
    }

    @Test
    fun `超过窗口未满两次淘汰`() {
        var temp = AutoWordStore.record(emptyMap(), "jt", "今天", now)
        temp = AutoWordStore.record(temp, "jt", "今天", now + 1000)
        // 1.5 个月后：虽两次上屏但窗口已过 → 淘汰
        val later = now + 46 * dayMs
        val promotion = AutoWordStore.promote(temp, emptyMap(), later)
        assertTrue(promotion.promoted.isEmpty())
        assertTrue(promotion.temp.isEmpty())
    }

    @Test
    fun `窗口内第二次上屏_超过窗口的旧词被淘汰`() {
        var temp = AutoWordStore.record(emptyMap(), "jt", "旧词", now)
        // 新词晚 29 天进库：检查时相对它只在窗口内
        temp = AutoWordStore.record(temp, "tq", "新词", now + 29 * dayMs)
        // 31 天后（相对旧词）：旧词过期淘汰；新词仍在窗口（相对仅 2 天）
        val later = now + 31 * dayMs
        val promotion = AutoWordStore.promote(temp, emptyMap(), later)
        assertNull(promotion.temp["jt"])
        // 新词 count=1 已达晋升门槛：移出临时库进固定库
        assertNull(promotion.temp["tq"])
        assertEquals(1, promotion.fixed["tq"]?.size)
    }

    @Test
    fun `超过窗口没再上屏则淘汰`() {
        val temp = AutoWordStore.record(emptyMap(), "jt", "今天", now)
        // 31 天后检查：最后一次上屏已超过窗口 → 删除该词
        val promotion = AutoWordStore.promote(temp, emptyMap(), now + 31 * dayMs)
        assertTrue(promotion.temp.isEmpty())
    }

    @Test
    fun `首次上屏超窗口但最近仍在用_可晋升固定词库`() {
        val entry = AutoWordStore.Entry("今天", "jt", now - 100 * dayMs, 2, now - dayMs)
        val temp = mapOf("jt" to mapOf("今天" to entry))
        val promotion = AutoWordStore.promote(temp, emptyMap(), now)
        assertEquals(1, promotion.promoted.size)
        assertEquals("今天", promotion.promoted[0].word)
        assertEquals(20, promotion.fixed["jt"]?.get("今天"))
    }

    @Test
    fun `晋升门槛为1次_选中一次即晋升固定词库`() {
        val entry = AutoWordStore.Entry("今天", "jt", now - 100 * dayMs, 1, now - dayMs)
        val temp = mapOf("jt" to mapOf("今天" to entry))
        val promotion = AutoWordStore.promote(temp, emptyMap(), now)
        // PROMOTE_MIN_COUNT=1：选中 1 次就够晋升，不再留在临时库
        assertEquals(1, promotion.promoted.size)
        assertEquals("今天", promotion.promoted[0].word)
        assertEquals(10, promotion.fixed["jt"]?.get("今天"))
    }

    @Test
    fun `首次上屏很久但窗口内仍在用_照常晋升`() {
        // 首次上屏在 100 天前，但 1 天前刚用过：淘汰按最后上屏算，仍在窗口内 → 达门槛即晋升
        val entry = AutoWordStore.Entry("今天", "jt", now - 100 * dayMs, 1, now - dayMs)
        val temp = mapOf("jt" to mapOf("今天" to entry))
        val promotion = AutoWordStore.promote(temp, emptyMap(), now)
        assertEquals(1, promotion.promoted.size)
        assertEquals("今天", promotion.promoted[0].word)
    }

    @Test
    fun `prune只删超期词_窗口内的词原样保留`() {
        val stale = AutoWordStore.Entry("旧词", "jt", now - 40 * dayMs, 1, now - 31 * dayMs)
        val fresh = AutoWordStore.Entry("新词", "tq", now - 2 * dayMs, 1, now - 2 * dayMs)
        val temp = mapOf(
            "jt" to mapOf("旧词" to stale),
            "tq" to mapOf("新词" to fresh),
        )
        val pruned = AutoWordStore.prune(temp, now)
        assertNull(pruned["jt"])
        assertEquals(1, pruned["tq"]?.size)
    }

    @Test
    fun `prune无变化时返回原表`() {
        val temp = AutoWordStore.record(emptyMap(), "jt", "今天", now)
        assertTrue(AutoWordStore.prune(temp, now) === temp)
        assertTrue(
            AutoWordStore.prune(
                emptyMap<String, Map<String, AutoWordStore.Entry>>(), now
            ) === emptyMap<String, Map<String, AutoWordStore.Entry>>()
        )
    }

    @Test
    fun `晋升权重随次数增加`() {
        assertEquals(20, AutoWordStore.promoteWeight(2))
        assertEquals(30, AutoWordStore.promoteWeight(3))
        assertEquals(50, AutoWordStore.promoteWeight(5))
    }

    @Test
    fun `promote幂等_无变化返回原表`() {
        // 空表无事可做：必须原样返回，不做任何拷贝
        val emptyTemp = emptyMap<String, Map<String, AutoWordStore.Entry>>()
        val promotion = AutoWordStore.promote(emptyTemp, emptyMap(), now)
        assertTrue(promotion.temp === emptyTemp)
        assertTrue(promotion.fixed === emptyMap<String, Map<String, Int>>())
    }

    @Test
    fun `promote未达门槛_内容不变且不晋升`() {
        // 用 minCount=2 显式构造「次数不够」的场景：不晋升，临时库内容原样保留。
        // 注意判据是内容相等（==）而非引用相等：promote 内部重建了 map，
        // 只在内容一致时回退返回原表。
        val entry = AutoWordStore.Entry("今天", "jt", now, 1, now)
        val temp = mapOf("jt" to mapOf("今天" to entry))
        val promotion = AutoWordStore.promote(temp, emptyMap(), now, minCount = 2)
        assertTrue(promotion.promoted.isEmpty())
        assertEquals(temp, promotion.temp)
    }

    // ── 一词一码（固定词库不出现一词多音） ──

    @Test
    fun `promote_同词多编码_只晋升一个`() {
        // 模拟「阿红」在临时库挂了 4 个编码（组合生词按单字规则生成的全部组合）
        val codes = listOf("aags", "aahs", "eegs", "eehs")
        var temp = emptyMap<String, Map<String, AutoWordStore.Entry>>()
        for (c in codes) temp = AutoWordStore.record(temp, c, "阿红", now, 2)
        val promotion = AutoWordStore.promote(temp, emptyMap(), now)
        // 固定词库只能有 1 条「阿红」
        val fixedCount = promotion.fixed.values.sumOf { if (it.containsKey("阿红")) 1 else 0 }
        assertEquals(1, fixedCount)
        // 临时库里的多编码条目都被取走
        assertEquals(4, promotion.promoted.size)
    }

    @Test
    fun `promote_优先收用户实际按键的编码`() {
        val codes = listOf("aags", "aahs")
        var temp = emptyMap<String, Map<String, AutoWordStore.Entry>>()
        for (c in codes) temp = AutoWordStore.record(temp, c, "阿红", now, 2)
        // 用户这次实际按的是 aahs（即使 aags 先入表）
        val promotion = AutoWordStore.promote(temp, emptyMap(), now, preferCode = "aahs")
        assertEquals(1, promotion.fixed["aahs"]?.size)
        assertNull(promotion.fixed["aags"])
    }

    @Test
    fun `promote_未指定编码时取次数最多的`() {
        var temp = AutoWordStore.record(emptyMap(), "aags", "阿红", now, 1)
        temp = AutoWordStore.record(temp, "aahs", "阿红", now, 5)
        val promotion = AutoWordStore.promote(temp, emptyMap(), now)
        assertEquals(1, promotion.fixed["aahs"]?.size)
        assertNull(promotion.fixed["aags"])
    }

    @Test
    fun `promote_清掉历史遗留的多音条目`() {
        // 历史固定词库已有多音脏数据：晋升时把旧编码上的同名条目清掉
        val oldFixed = mapOf(
            "aags" to mapOf("阿红" to 20),
            "aahs" to mapOf("阿红" to 20),
        )
        val temp = AutoWordStore.record(emptyMap(), "eegs", "阿红", now, 3)
        val promotion = AutoWordStore.promote(temp, oldFixed, now, preferCode = "eegs")
        assertEquals(1, promotion.fixed["eegs"]?.size)
        assertNull(promotion.fixed["aags"]?.get("阿红"))
        assertNull(promotion.fixed["aahs"]?.get("阿红"))
    }

    @Test
    fun `dedupeFixed_收敛一词多音_保留最高权重`() {
        val dirty = mapOf(
            "aags" to mapOf("阿红" to 10),
            "aahs" to mapOf("阿红" to 20),
            "eegs" to mapOf("阿红" to 20),
            "bmuj" to mapOf("白名单" to 20),
            "bmdj" to mapOf("白名单" to 20),
            "izbc" to mapOf("臭宝" to 20),
        )
        val clean = AutoWordStore.dedupeFixed(dirty)
        // 每个词只剩一条
        assertEquals(1, clean.values.sumOf { if (it.containsKey("阿红")) 1 else 0 })
        assertEquals(1, clean.values.sumOf { if (it.containsKey("白名单")) 1 else 0 })
        assertEquals(1, clean["izbc"]?.size)
        // 保留权重最高的那条（首个 weight=20 的编码）
        assertEquals(20, clean.values.first { it.containsKey("阿红") }["阿红"])
    }

    @Test
    fun `dedupeFixed_本就一词一码时返回原表`() {
        val clean = mapOf("aags" to mapOf("阿红" to 20), "izbc" to mapOf("臭宝" to 20))
        assertTrue(clean === AutoWordStore.dedupeFixed(clean))
    }


    // ── parse / serialize ──

    @Test
    fun `临时词库序列化回读一致`() {
        var temp = AutoWordStore.record(emptyMap(), "jt", "今天", now)
        temp = AutoWordStore.record(temp, "jt", "今天", now + 1000)
        temp = AutoWordStore.record(temp, "tq", "天气", now)
        val text = AutoWordStore.serializeTemp(temp)
        assertEquals(temp, AutoWordStore.parseTemp(text))
    }

    @Test
    fun `固定词库序列化回读一致`() {
        val fixed = mapOf("jt" to mapOf("今天" to 20, "天气" to 30))
        val text = AutoWordStore.serializeFixed(fixed)
        assertEquals(fixed, AutoWordStore.parseFixed(text))
    }

    @Test
    fun `解析容错_坏行跳过`() {
        val text = "# 临时词库\n今天\tjt\t1750000000000\t2\t1750000001000\n坏行\tbroken\n"
        val temp = AutoWordStore.parseTemp(text)
        assertEquals(1, temp["jt"]?.size)
        assertEquals(2, temp["jt"]?.get("今天")?.count)
    }

    @Test
    fun `解析保留0次条目_组合生词重启不丢`() {
        // 组合生词刚拼出来还没被选中，次数是 0：必须能回读，否则重启后候选栏就没它了
        val temp = AutoWordStore.record(emptyMap(), "xzml", "现在目录", now, initialCount = 0)
        val reparsed = AutoWordStore.parseTemp(AutoWordStore.serializeTemp(temp))
        assertEquals(0, reparsed["xzml"]?.get("现在目录")?.count)
        // 次数被改成负数（人工改坏的残行）才丢弃
        assertTrue(
            AutoWordStore.parseTemp("现在目录\txzml\t$now\t-1\t$now").isEmpty()
        )
    }

    // ── 组合生词 ──

    @Test
    fun `相邻汉字词拼接成生词`() {
        assertEquals("现在目录", AutoWordStore.comboWord("现在", "目录"))
    }

    @Test
    fun `拼接结果超上限不收录`() {
        // "现在"(2) + "目录"(2) = 4 字，默认上限 4 收录
        assertEquals("现在目录", AutoWordStore.comboWord("现在", "目录"))
        // "现在"(2) + "文件夹"(3) = 5 字，超上限
        assertNull(AutoWordStore.comboWord("现在", "文件夹"))
    }

    @Test
    fun `字数下限与上限可自定义`() {
        // 1 字词拼接成 2 字：默认下限 2 可收录
        assertEquals("现在", AutoWordStore.comboWord("现", "在"))
        // 自定义 3–5：2 字组合被拒
        assertNull(AutoWordStore.comboWord("现", "在", minChars = 3, maxChars = 5))
        // 自定义 3–5：3 字组合通过
        assertEquals("现在就", AutoWordStore.comboWord("现在", "就", minChars = 3, maxChars = 5))
    }

    @Test
    fun `非汉字词不参与组合`() {
        assertNull(AutoWordStore.comboWord("abc", "目录"))
        assertNull(AutoWordStore.comboWord("现在", "123"))
        assertNull(AutoWordStore.comboWord("现在", "word"))
    }

    @Test
    fun `空词不组合`() {
        assertNull(AutoWordStore.comboWord("", "目录"))
        assertNull(AutoWordStore.comboWord("现在", ""))
    }

    @Test
    fun `组合词可进临时词库并参与晋升`() {
        val comboWord = AutoWordStore.comboWord("现在", "目录")!!
        val temp = AutoWordStore.record(emptyMap(), "xzml", comboWord, now)
        val entry = temp["xzml"]?.get(comboWord)
        assertEquals(1, entry?.count)
        assertEquals(now, entry?.firstSeen)
        // 组合词自身再上屏两次，窗口内满 3 次应晋升
        var t = AutoWordStore.record(temp, "xzml", comboWord, now + 1000)
        t = AutoWordStore.record(t, "xzml", comboWord, now + 2000)
        val promotion = AutoWordStore.promote(t, emptyMap(), now + 2000)
        assertTrue(promotion.promoted.any { it.word == "现在目录" })
        // 3 次上屏 → 权重 = 3 × 10
        assertEquals(30, promotion.fixed["xzml"]?.get(comboWord))
    }

    @Test
    fun `组合生词初始次数为0_被选中后才计数`() {
        val comboWord = AutoWordStore.comboWord("现在", "目录")!!
        // 组合生词进临时词库：初始 0 次（自动拼接，还没被用户选中过）
        val temp = AutoWordStore.record(emptyMap(), "xzml", comboWord, now, initialCount = 0)
        assertEquals(0, temp["xzml"]?.get(comboWord)?.count)
        // 被用户选中一次 → 计 1 次
        val once = AutoWordStore.record(temp, "xzml", comboWord, now + 1000)
        assertEquals(1, once["xzml"]?.get(comboWord)?.count)
    }

    @Test
    fun `初始0次的词未达晋升次数_留在临时库`() {
        val temp = AutoWordStore.record(emptyMap(), "xzml", "现在目录", now, initialCount = 0)
        val promotion = AutoWordStore.promote(temp, emptyMap(), now + 1000)
        assertTrue(promotion.promoted.isEmpty())
        assertEquals(1, promotion.temp["xzml"]?.size)
    }

    // ── 候选合并 ──

    @Test
    fun `候选合并_固定词权重降序_临时词垫底`() {
        val fixed = mapOf("jt" to mapOf("今天" to 30, "今天啊" to 10))
        val temp = mapOf("jt" to mapOf("今天哦" to AutoWordStore.Entry("今天哦", "jt", now, 0, now)))
        val got = AutoWordStore.candidatesFor(temp, fixed, "jt", emptySet())
        assertEquals(listOf("今天", "今天啊", "今天哦"), got)
    }

    @Test
    fun `候选合并_引擎已有该词_不重复合并`() {
        val fixed = mapOf("xzml" to mapOf("现在目录" to 20))
        val temp = mapOf("xzml" to mapOf("现在目录" to AutoWordStore.Entry("现在目录", "xzml", now, 0, now)))
        // 引擎候选里已有「现在目录」→ 不再重复合并
        assertTrue(AutoWordStore.candidatesFor(temp, fixed, "xzml", setOf("现在目录")).isEmpty())
    }

    @Test
    fun `候选合并_编码无匹配_返回空`() {
        assertTrue(AutoWordStore.candidatesFor(emptyMap(), emptyMap(), "xyz", emptySet()).isEmpty())
    }

    // ── hitsFor：带来源的命中（候选栏区分临时词/固定词） ──

    @Test
    fun `hitsFor_区分临时与固定_并带选中次数`() {
        val fixed = mapOf("jt" to mapOf("今天" to 30))
        val temp = mapOf(
            "jt" to mapOf(
                "今天哦" to AutoWordStore.Entry("今天哦", "jt", now, 0, now),
                "今天吧" to AutoWordStore.Entry("今天吧", "jt", now, 2, now),
            )
        )
        val got = AutoWordStore.hitsFor(temp, fixed, "jt", emptySet())
        assertEquals(listOf("今天", "今天哦", "今天吧"), got.map { it.word })
        assertEquals(false, got[0].fromTemp)
        assertEquals(true, got[1].fromTemp)
        assertEquals(0, got[1].count)
        assertEquals(true, got[2].fromTemp)
        assertEquals(2, got[2].count)
    }

    @Test
    fun `hitsFor_与candidatesFor结果完全一致`() {
        val fixed = mapOf("xm" to mapOf("现在" to 20), "xmz" to mapOf("现在做" to 10))
        val temp = mapOf(
            "xm" to mapOf("现在吗" to AutoWordStore.Entry("现在吗", "xm", now, 1, now)),
            "xmz" to mapOf("现在走" to AutoWordStore.Entry("现在走", "xmz", now, 0, now)),
        )
        for (code in listOf("xm", "xmz", "x")) {
            for (prefix in listOf(false, true)) {
                val viaWords = AutoWordStore.candidatesFor(temp, fixed, code, emptySet(), prefix)
                val viaHits = AutoWordStore.hitsFor(temp, fixed, code, emptySet(), prefix)
                    .map { it.word }
                assertEquals("code=$code prefix=$prefix", viaWords, viaHits)
            }
        }
    }

    @Test
    fun `hitsFor_前缀命中也带来源与次数`() {
        val temp = mapOf(
            "xmzd" to mapOf("现在知道" to AutoWordStore.Entry("现在知道", "xmzd", now, 3, now))
        )
        val got = AutoWordStore.hitsFor(emptyMap(), emptyMap(), "xm", emptySet(), prefixMatch = true)
            .filter { it.word == "现在知道" }
        val gotTemp = AutoWordStore.hitsFor(temp, emptyMap(), "xm", emptySet(), prefixMatch = true)
        assertEquals(1, gotTemp.size)
        assertEquals("现在知道", gotTemp[0].word)
        assertEquals(true, gotTemp[0].fromTemp)
        assertEquals(3, gotTemp[0].count)
        assertTrue(got.isEmpty())
    }

    @Test
    fun `hitsFor_引擎已有词不重复`() {
        val fixed = mapOf("xzml" to mapOf("现在目录" to 20))
        assertTrue(
            AutoWordStore.hitsFor(fixed = fixed, temp = emptyMap(), code = "xzml",
                known = setOf("现在目录")).isEmpty()
        )
    }

    @Test
    fun `候选合并_编码没打完也出词_前缀命中固定库`() {
        val fixed = mapOf("xmzd" to mapOf("现在" to 20))
        // 输入 "xm"（双拼第一个音节，还没敲完）：前缀命中
        assertEquals(listOf("现在"), AutoWordStore.candidatesFor(emptyMap(), fixed, "xm", emptySet(), prefixMatch = true))
        // 输入 "xmz"：仍前缀命中
        assertEquals(listOf("现在"), AutoWordStore.candidatesFor(emptyMap(), fixed, "xmz", emptySet(), prefixMatch = true))
    }

    @Test
    fun `候选合并_前缀命中临时库按最近上屏降序`() {
        val older = AutoWordStore.Entry("旧词", "abcd", now, 0, now - 5000)
        val newer = AutoWordStore.Entry("新词", "abce", now, 0, now)
        val temp = mapOf("abcd" to mapOf("旧词" to older), "abce" to mapOf("新词" to newer))
        assertEquals(listOf("新词", "旧词"), AutoWordStore.candidatesFor(temp, emptyMap(), "ab", emptySet(), prefixMatch = true))
    }

    @Test
    fun `候选合并_单个字母不扫前缀`() {
        // 第一个键就扫前缀会命中半个词库，候选栏尾部被灌满
        val fixed = mapOf("xmzd" to mapOf("现在" to 20))
        assertTrue(AutoWordStore.candidatesFor(emptyMap(), fixed, "x", emptySet(), prefixMatch = true).isEmpty())
    }

    @Test
    fun `候选合并_前缀命中总量封顶`() {
        val words = (1..20).associate { "词$it" to it * 10 }
        val fixed = mapOf("abcd" to words)
        assertEquals(8, AutoWordStore.candidatesFor(emptyMap(), fixed, "ab", emptySet(), prefixMatch = true).size)
    }

    @Test
    fun `候选合并_前缀命中_引擎已有不重复`() {
        val fixed = mapOf("xmzd" to mapOf("现在" to 20))
        assertTrue(AutoWordStore.candidatesFor(emptyMap(), fixed, "xm", setOf("现在"), prefixMatch = true).isEmpty())
    }

    @Test
    fun `候选合并_完整编码优先于前缀`() {
        val fixed = mapOf(
            "ab" to mapOf("完整词" to 5),
            "abcd" to mapOf("前缀词" to 100),
        )
        // 完整编码命中的词排在前，即使权重更低
        assertEquals(listOf("完整词", "前缀词"), AutoWordStore.candidatesFor(emptyMap(), fixed, "ab", emptySet(), prefixMatch = true))
    }

    @Test
    fun `候选合并_同词完整与前缀都有_只出现一次`() {
        val fixed = mapOf(
            "ab" to mapOf("同词" to 5),
            "abcd" to mapOf("同词" to 100),
        )
        assertEquals(listOf("同词"), AutoWordStore.candidatesFor(emptyMap(), fixed, "ab", emptySet(), prefixMatch = true))
    }

    @Test
    fun `候选合并_默认完整命中_前缀不提前出词`() {
        val fixed = mapOf("xmzd" to mapOf("现在" to 20))
        // 默认 prefixMatch=false：输入前缀（未按满）不提前出词
        assertTrue(AutoWordStore.candidatesFor(emptyMap(), fixed, "xm", emptySet()).isEmpty())
        assertTrue(AutoWordStore.candidatesFor(emptyMap(), fixed, "xmz", emptySet()).isEmpty())
        // 输入完整编码才出现
        assertEquals(listOf("现在"), AutoWordStore.candidatesFor(emptyMap(), fixed, "xmzd", emptySet()))
    }
    @Test
    fun `候选合并_开启前缀命中_前缀提前出词`() {
        val fixed = mapOf("xmzd" to mapOf("现在" to 20))
        assertEquals(listOf("现在"), AutoWordStore.candidatesFor(emptyMap(), fixed, "xm", emptySet(), prefixMatch = true))
    }
}
