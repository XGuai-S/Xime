package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 方案目录布局的核心不变量。
 *
 * 这套机制一旦失效，**输入法一个方案都加载不了**（librime 只扫 rime 顶层），
 * 所以每条的断言消息都写清「失效后果」。
 */
class SchemaLayoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rimeDir(): File = tmp.newFolder("rime")

    private fun write(dir: File, name: String, content: String): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    /** 造一个最小方案：schema + 它独占的词典。 */
    private fun makeScheme(rimeDir: File, id: String) {
        write(rimeDir, "$id.schema.yaml", "schema:\n  schema_id: $id\n  name: $id\ntranslator:\n  dictionary: $id\n")
        write(rimeDir, "$id.dict.yaml", "---\nname: $id\n...\n")
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    @Test
    fun `schemeDir points into schemes`() {
        val rime = rimeDir()
        assertEquals(
            "方案目录必须在 rime/schemes/<id>，否则用户看到的还是散落平铺",
            File(File(rime, "schemes"), "pinyin_simp").path,
            SchemaLayout.schemeDir(rime, "pinyin_simp").path,
        )
    }

    @Test
    fun `schema name cannot escape schemes dir`() {
        val rime = rimeDir()
        listOf("../../etc", "../evil", "a/../../b", "", ".").forEach { evil ->
            val dir = SchemaLayout.schemeDir(rime, evil)
            assertTrue(
                "恶意方案名 '$evil' 逃出了 schemes 目录：${dir.path}",
                dir.canonicalPath.startsWith(File(rime, "schemes").canonicalPath),
            )
        }
    }

    // ------------------------------------------------------------------
    // 归属判定
    // ------------------------------------------------------------------

    @Test
    fun `exclusive dict belongs to its schema`() {
        val rime = rimeDir()
        makeScheme(rime, "alpha")

        val ownership = SchemaLayout.resolveOwnership(rime)
        val dict = File(rime, "alpha.dict.yaml")
        assertEquals(
            "独占词典应归 alpha，否则它不会被收进方案文件夹",
            "alpha",
            ownership[dict],
        )
        assertEquals(
            "方案自己的 schema 文件必须归自己",
            "alpha",
            ownership[File(rime, "alpha.schema.yaml")],
        )
    }

    @Test
    fun `shared dict stays at root`() {
        val rime = rimeDir()
        // 两个方案共用同一个词典 + 同一个 symbols
        write(rime, "a.schema.yaml", "schema:\n  schema_id: a\ntranslator:\n  dictionary: shared\n  __include: symbols\n")
        write(rime, "b.schema.yaml", "schema:\n  schema_id: b\ntranslator:\n  dictionary: shared\n  __include: symbols\n")
        write(rime, "shared.dict.yaml", "---\nname: shared\n...\n")
        write(rime, "symbols.yaml", "punctuator:\n  full_shape: {}\n")

        val ownership = SchemaLayout.resolveOwnership(rime)
        assertNull(
            "被 2 个方案引用的 shared.dict.yaml 不能归给任何一方（否则第二份会覆盖第一份的链接）",
            ownership[File(rime, "shared.dict.yaml")],
        )
        assertNull(
            "被 2 个方案引用的 symbols.yaml 必须留在 rime 根",
            ownership[File(rime, "symbols.yaml")],
        )
    }

    @Test
    fun `unreferenced file stays at root`() {
        val rime = rimeDir()
        makeScheme(rime, "alpha")
        write(rime, "custom_phrase.txt", "你好\tni hao\t1\n")
        assertNull(
            "没人引用的文件（如 custom_phrase.txt）不能猜归属，留在根最安全",
            SchemaLayout.resolveOwnership(rime)[File(rime, "custom_phrase.txt")],
        )
    }

    @Test
    fun `engine artifacts are never candidates`() {
        val rime = rimeDir()
        makeScheme(rime, "alpha")
        write(rime, "alpha.table.bin", "binary")
        write(rime, "build/alpha.prism.bin", "binary")
        write(rime, "alpha.userdb/data", "leveldb")

        val candidates = SchemaLayout.rootSchemaCandidates(rime).map { it.name }
        assertTrue(
            "引擎产物 .bin 不能进候选（搬走会让引擎全量重编译）",
            candidates.none { it.endsWith(".bin") },
        )
        assertTrue(
            "build/ 目录不能进候选",
            candidates.none { it.contains("build/") },
        )
        assertTrue(
            "*.userdb 目录不能进候选（那是 leveldb 用户词库）",
            candidates.none { it.contains("userdb") },
        )
    }

    // ------------------------------------------------------------------
    // 镜像：核心不变量
    // ------------------------------------------------------------------

    @Test
    fun `mirror makes schema visible at root`() {
        val rime = rimeDir()
        // 模拟：文件已在 schemes/alpha/ 里，但根目录没有
        val schemeDir = File(File(rime, "schemes"), "alpha")
        write(schemeDir, "alpha.schema.yaml", "schema:\n  schema_id: alpha\n")

        assertTrue(
            "镜像前根目录不应有 schema（前提校验）",
            rime.listFiles { f -> f.name.endsWith(".schema.yaml") }.orEmpty().isEmpty(),
        )

        SchemaLayout.mirrorToRoot(rime)

        val visible = rime.listFiles { f -> f.name.endsWith(".schema.yaml") }.orEmpty()
        assertEquals(
            "镜像后根目录必须能列出该方案——librime 只扫顶层，看不到就等于方案不存在",
            1,
            visible.size,
        )
        assertEquals("alpha.schema.yaml", visible[0].name)
    }

    @Test
    fun `mirror is idempotent`() {
        val rime = rimeDir()
        val schemeDir = File(File(rime, "schemes"), "alpha")
        write(schemeDir, "alpha.schema.yaml", "schema:\n  schema_id: alpha\n")
        write(schemeDir, "alpha.dict.yaml", "---\nname: alpha\n...\n")

        val first = SchemaLayout.mirrorToRoot(rime)
        assertEquals("首次镜像应创建 2 个文件", 2, first.linked + first.copied)

        val second = SchemaLayout.mirrorToRoot(rime)
        assertEquals(
            "第二次镜像必须全部跳过，否则每次启动都重写文件、触发引擎无谓重编译",
            0,
            second.linked + second.copied,
        )
        assertEquals(2, second.skipped)
    }

    @Test
    fun `mirror preserves subdirectory structure`() {
        val rime = rimeDir()
        val schemeDir = File(File(rime, "schemes"), "alpha")
        write(schemeDir, "opencc/my.ocd2", "dict")
        write(schemeDir, "lua/helper.lua", "code")

        SchemaLayout.mirrorToRoot(rime)

        assertTrue(
            "子目录结构必须保留，否则 __include 里的相对路径会断",
            File(rime, "opencc/my.ocd2").isFile,
        )
        assertTrue(File(rime, "lua/helper.lua").isFile)
    }

    @Test
    fun `mirror updates root when source changed`() {
        val rime = rimeDir()
        val schemeDir = File(File(rime, "schemes"), "alpha")
        val src = write(schemeDir, "alpha.schema.yaml", "v1")

        SchemaLayout.mirrorToRoot(rime)
        src.writeText("v2-changed")
        SchemaLayout.mirrorToRoot(rime)

        assertEquals(
            "源文件改了，根目录必须跟着更新（硬链接天然同步；复制的要覆盖）",
            "v2-changed",
            File(rime, "alpha.schema.yaml").readText(),
        )
    }

    // ------------------------------------------------------------------
    // 反镜像
    // ------------------------------------------------------------------

    @Test
    fun `unmirror keeps shared files`() {
        val rime = rimeDir()
        val schemeDir = File(File(rime, "schemes"), "alpha")
        write(schemeDir, "alpha.schema.yaml", "schema:\n  schema_id: alpha\n")
        write(rime, "symbols.yaml", "shared")

        SchemaLayout.mirrorToRoot(rime)
        assertTrue("前提：镜像后根目录有 schema", File(rime, "alpha.schema.yaml").exists())

        val removed = SchemaLayout.unmirrorFromRoot(rime)

        assertEquals(1, removed)
        assertTrue(
            "反镜像必须保留共享文件 symbols.yaml——删了所有方案都用不了符号",
            File(rime, "symbols.yaml").isFile,
        )
    }

    // ------------------------------------------------------------------
    // 从顶层重建
    // ------------------------------------------------------------------

    @Test
    fun `rebuild from root creates schemes and keeps root files`() {
        val rime = rimeDir()
        // 模拟备份恢复后的状态：只有顶层文件，没有 schemes/
        makeScheme(rime, "alpha")

        assertEquals(1, SchemaLayout.rebuildSchemesFromRoot(rime))

        assertTrue(
            "必须建出 schemes/alpha/alpha.schema.yaml",
            File(File(File(rime, "schemes"), "alpha"), "alpha.schema.yaml").isFile,
        )
        assertTrue(
            "顶层文件必须原样保留——引擎读顶层，删了输入法就没方案了",
            File(rime, "alpha.schema.yaml").isFile,
        )
        assertTrue(File(rime, "alpha.dict.yaml").isFile)
    }

    @Test
    fun `rebuild from root is idempotent`() {
        val rime = rimeDir()
        makeScheme(rime, "alpha")

        SchemaLayout.rebuildSchemesFromRoot(rime)
        val second = SchemaLayout.rebuildSchemesFromRoot(rime)

        assertEquals(
            "第二次重建应无实际写入（全部已存在）",
            0,
            second,
        )
    }

    @Test
    fun `discoverSchemeFiles returns per-scheme grouping`() {
        val rime = rimeDir()
        write(File(File(rime, "schemes"), "a"), "a.schema.yaml", "x")
        write(File(File(rime, "schemes"), "a"), "a.dict.yaml", "y")
        write(File(File(rime, "schemes"), "b"), "b.schema.yaml", "z")

        val found = SchemaLayout.discoverSchemeFiles(rime)
        assertEquals(setOf("a", "b"), found.keys)
        assertEquals(2, found.getValue("a").size)
        assertEquals(1, found.getValue("b").size)
    }

    // ------------------------------------------------------------------
    // 与备份/同步的集成约束
    // ------------------------------------------------------------------

    @Test
    fun `backup excludes schemes dir`() {
        // 顶层那份已经在包里；两份都收会让备份体积翻倍、恢复时互相覆盖
        assertTrue(
            "备份必须排除 schemes/，否则同一份方案内容会被打两次",
            !com.kingzcheung.xime.settings.RimeExportManager.includeRimeEntry(
                "schemes/alpha/alpha.schema.yaml", "inst",
            ),
        )
        assertTrue(
            "schemes/ 目录本身也不能进包",
            !com.kingzcheung.xime.settings.RimeExportManager.includeRimeEntry("schemes", "inst"),
        )
    }

    @Test
    fun `sync excludes schemes dir`() {
        assertTrue(
            "可见目录同步必须排除 schemes/，否则同一内容在可见目录出现两次且互相覆盖",
            !com.kingzcheung.xime.settings.RimeUserFileSync.isSyncable(
                "schemes/alpha/alpha.schema.yaml",
            ),
        )
    }

    @Test
    fun `referenced resources parses dictionary and include`() {
        val refs = SchemaLayout.referencedResources(
            """
            schema:
              schema_id: x
            translator:
              dictionary:  wubi86
              __include: symbols:/punctuator
            reverse_lookup:
              dictionary: pinyin_simp
            """.trimIndent()
        )
        assertTrue("应解析出 wubi86", refs.contains("wubi86"))
        assertTrue("应解析出 symbols（取 __include 的第一段）", refs.contains("symbols"))
        assertTrue("应解析出 pinyin_simp", refs.contains("pinyin_simp"))
    }
}
