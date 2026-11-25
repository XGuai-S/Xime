package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.AutoWordConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 1 回归：自动造词的单字码表必须跟随**当前启用方案**。
 *
 * 缺陷：`auto_word.char_dict` 默认写死 `Flypy_char`（小鹤单字表），且不看当前方案。
 * 用户启用五笔/其它方案后，造词仍按小鹤字码拼码，造出的词在自己方案里打不出来——
 * 用户反馈「自动造词识别到了次方案，导致有些词组不了」。
 */
class AutoWordCharDictTest {
    private fun config(
        charDict: String = "Flypy_char",
        bySchema: Map<String, String> = emptyMap(),
    ) = AutoWordConfig(charDict = charDict, charDictBySchema = bySchema)

    @Test
    fun `schema specific dict wins over global dict`() {
        val cfg = config(bySchema = mapOf("wubi86" to "Wubi_char"))
        assertEquals(
            "按方案配置的单字表优先",
            "Wubi_char",
            AutoWordCharDict.resolveDictName("wubi86", cfg, "wubi86"),
        )
    }

    @Test
    fun `schema without its own dict falls back to global dict`() {
        val cfg = config(bySchema = mapOf("wubi86" to "Wubi_char"))
        assertEquals(
            "没配专属表的方案退回全局配置（兼容旧行为）",
            "Flypy_char",
            AutoWordCharDict.resolveDictName("xime_flypy", cfg, "xime_flypy"),
        )
    }

    @Test
    fun `blank schema dict is ignored`() {
        // 空白方案配置不能覆盖全局表：此时应回退到全局/主词典的正常解析路径。
        // wubi86 用 Flypy_char 属于跨方案，因此最终解析到该方案主词典。
        val cfg = config(bySchema = mapOf("wubi86" to "   "))
        assertEquals(
            "空白方案配置不得当作有效覆盖",
            "wubi86",
            AutoWordCharDict.resolveDictName("wubi86", cfg, "wubi86"),
        )
        // 同一份配置下小鹤方案仍应拿到 Flypy_char
        assertEquals(
            "Flypy_char",
            AutoWordCharDict.resolveDictName("XGuai_flypy", cfg, "XGuai_flypy"),
        )
    }

    @Test
    fun `blank global dict falls back to the schema root dict`() {
        val cfg = config(charDict = "")
        assertEquals(
            "全局表为空时用当前方案主词典兜底，保证造出的码在本方案能打出来",
            "XGuai_flypy",
            AutoWordCharDict.resolveDictName("xime_flypy", cfg, "XGuai_flypy"),
        )
    }

    @Test
    fun `nothing configured yields null so no codes are built`() {
        val cfg = config(charDict = "")
        assertNull(
            "连主词典都没有时返回 null（调用方据此不造词，而不是用错方案的字码）",
            AutoWordCharDict.resolveDictName("", cfg, null),
        )
    }

    @Test
    fun `switching schema switches the char dict`() {
        // 核心场景：同一份配置下，不同方案解析出不同单字表，不再串方案。
        val cfg = config(
            charDict = "Flypy_char",
            bySchema = mapOf("wubi86" to "Wubi_char", "xime_flypy" to "Flypy_char"),
        )
        assertEquals("Wubi_char", AutoWordCharDict.resolveDictName("wubi86", cfg, "wubi86"))
        assertEquals("Flypy_char", AutoWordCharDict.resolveDictName("xime_flypy", cfg, "xime_flypy"))
    }

    @Test
    fun `host wires schema aware char dict into scheme cache`() {
        // 接线契约：生产代码必须用 AutoWordCharDict 解析，而不是直接读 cfg.charDict。
        val source = com.kingzcheung.xime.RepoFiles.mainKotlinSources
            .single { it.name == "XimeInputMethodService.kt" }
            .readText()
            .replace("\r\n", "\n")
        assertTrue(
            "造词基底必须按当前方案解析",
            source.contains("AutoWordCharDict.resolveDictName(activeSchemaId, cfg, activeRootDict)"),
        )
        assertFalse(
            "不得再直接用全局 charDict，否则切换方案后继续串方案",
            source.contains("buildCharCodes(loadCharDictEntries(cfg.charDict"),
        )
        assertTrue(
            "方案字表变化必须让缓存失效",
            source.contains("schemeDictCache?.charDictName == expectedCharDict"),
        )
    }

    @Test
    fun `flypy schema keeps the global flypy char dict`() {
        // 小鹤用户不配任何东西：全局 Flypy_char 与当前方案匹配，直接沿用（行为不变）。
        val cfg = config(charDict = "Flypy_char")
        assertEquals(
            "Flypy_char",
            AutoWordCharDict.resolveDictName("XGuai_flypy", cfg, "XGuai_flypy"),
        )
    }

    @Test
    fun `non-flypy schema must not inherit the flypy char dict`() {
        // 用户换到五笔但全局仍写 Flypy_char：不能拿小鹤字码给五笔造词
        // （这正是「自动造词识别到次方案」的根因），应改走该方案主词典兜底。
        val cfg = config(charDict = "Flypy_char")
        assertEquals(
            "wubi86",
            AutoWordCharDict.resolveDictName("wubi86", cfg, "wubi86"),
        )
    }

    @Test
    fun `pinyin schema also falls back to its own root dict`() {
        val cfg = config(charDict = "Flypy_char")
        assertEquals(
            "pinyin_simp",
            AutoWordCharDict.resolveDictName("pinyin_simp", cfg, "pinyin_simp"),
        )
    }

    @Test
    fun `matching family dict is still honoured`() {
        val cfg = config(charDict = "Wubi_char")
        assertEquals("Wubi_char", AutoWordCharDict.resolveDictName("wubi86", cfg, "wubi86"))
    }
}
