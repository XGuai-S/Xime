package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import com.kingzcheung.xime.settings.AutoWordConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 造词配置变更必须能让方案词典缓存失效。
 *
 * ## 缺陷
 *
 * `SchemeDictCache` 用 `dictSnapshot`（词典文件的 mtime+size）判断能否复用上次解析结果。
 * 但 `auto_word.char_dict` / `char_dict_by_schema` / `no_auto_word_dicts` 这些配置住在
 * `xime.yaml` 里——**词典文件一个都没变**，所以改完配置缓存照样命中，
 * 用户必须重启输入法才会生效。
 *
 * ## 修法
 *
 * 把 `xime.yaml`（可见目录与私有目录两侧）也纳入 `dictSnapshot`。
 */
class AutoWordConfigCacheInvalidationTest {
    private val source: String = RepoFiles.mainKotlinSources
        .single { it.name == "XimeInputMethodService.kt" }
        .readText()
        .replace("\r\n", "\n")

    private val refresh: String = source
        .substringAfter("private fun doRefreshSchemeDictWords()")
        .substringBefore("private fun loadCharDictEntries(")

    @Test
    fun `auto word config file participates in the cache snapshot`() {
        assertTrue(
            "xime.yaml 必须纳入快照，否则改配置后缓存永远命中",
            refresh.contains("java.io.File(it, \"xime.yaml\")"),
        )
        assertTrue(
            "私有 rime 目录下的 xime.yaml 也要纳入",
            refresh.contains("java.io.File(rimeDir, \"xime.yaml\")"),
        )
    }

    @Test
    fun `snapshot entries are recorded before the cache is stored`() {
        val lastSnapshotAdd = refresh.lastIndexOf("snapshot +=")
        val storeSnapshot = refresh.indexOf("dictSnapshot = snapshot.toList()")
        assertTrue("必须存在快照落库", storeSnapshot > 0)
        assertTrue("所有快照项都要在落库之前加入", lastSnapshotAdd < storeSnapshot)
    }

    @Test
    fun `char dict name is part of the cache identity`() {
        assertTrue(
            "缓存命中判定必须包含解析出的单字表名",
            source.contains("schemeDictCache?.charDictName == expectedCharDict"),
        )
    }

    @Test
    fun `schema switch invalidates the cache through root schema`() {
        assertTrue(
            "缓存命中判定必须包含当前方案主词典",
            source.contains("schemeDictCache?.rootSchema == activeRootDict"),
        )
    }
}

/**
 * 全局单字表不能跨方案复用。
 *
 * `AutoWordConfig.charDict` 的默认值就是 `"Flypy_char"`，所以「没配」与「配了小鹤」
 * 无法区分。旧实现里有「全局表非空就直接用」的短路，导致：
 * 用户切到五笔后，造词仍按小鹤字码拼码 → 造出的词在五笔里打不出来
 * （用户反馈「自动造词识别到次方案」）。
 *
 * 修法：全局表与当前方案不属于同一族时，改走该方案主词典兜底。
 */
class AutoWordCharDictSchemaFamilyTest {
    @Test
    fun `default flypy dict is kept for flypy family schemas`() {
        val cfg = AutoWordConfig()
        assertEquals("Flypy_char", AutoWordCharDict.resolveDictName("xime_flypy", cfg, "xime_flypy"))
        assertEquals("Flypy_char", AutoWordCharDict.resolveDictName("XGuai_flypy", cfg, "XGuai_flypy"))
    }

    @Test
    fun `default flypy dict is dropped for wubi schema`() {
        val cfg = AutoWordConfig()
        assertEquals(
            "切到五笔后必须改用五笔自己的词典，否则造词码打不出来",
            "wubi86",
            AutoWordCharDict.resolveDictName("wubi86", cfg, "wubi86"),
        )
    }

    @Test
    fun `default flypy dict is dropped for pinyin schema`() {
        val cfg = AutoWordConfig()
        assertEquals("pinyin_simp", AutoWordCharDict.resolveDictName("pinyin_simp", cfg, "pinyin_simp"))
    }

    @Test
    fun `explicit schema mapping always wins`() {
        val cfg = AutoWordConfig(charDictBySchema = mapOf("wubi86" to "MyWubi_char"))
        assertEquals("MyWubi_char", AutoWordCharDict.resolveDictName("wubi86", cfg, "wubi86"))
    }

    @Test
    fun `unrecognised dict family keeps legacy behaviour`() {
        // 无法归类的自定义表名：保守沿用（不擅自改变用户配置语义）。
        val cfg = AutoWordConfig(charDict = "MyCustom_table")
        assertEquals(
            "MyCustom_table",
            AutoWordCharDict.resolveDictName("wubi86", cfg, "wubi86"),
        )
    }

    @Test
    fun `no root dict and mismatched global yields null`() {
        // 既没有匹配的全局表、也拿不到方案主词典时返回 null（调用方据此不造词，
        // 而不是用错方案的码）。
        val cfg = AutoWordConfig()
        assertEquals(null, AutoWordCharDict.resolveDictName("wubi86", cfg, null))
    }
}
