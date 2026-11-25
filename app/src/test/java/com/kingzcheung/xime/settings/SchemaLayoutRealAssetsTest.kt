package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 用**真实的内置方案资产**验证归纳效果。
 *
 * 前两轮教训：只用构造的小样本测试全绿，真实资产却不工作。
 * 所以这里所有用例都跑真实 assets。
 */
class SchemaLayoutRealAssetsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun assetsRime(): File =
        File("src/main/assets/rime").takeIf { it.isDirectory }
            ?: File("app/src/main/assets/rime")

    private fun copyAssets(target: File) {
        assetsRime().copyRecursively(target, overwrite = true)
    }

    @Test
    fun `scheme folders are named after display name`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        SchemaManager.migrateLayoutToSchemes(rime)
        val folders = SchemaLayout.discoverSchemeFiles(rime).keys

        // 用户要求：文件夹以方案名（显示名）命名
        assertTrue("应有『五笔86』文件夹，实际：$folders", folders.contains("五笔86"))
        assertTrue("应有『简体拼音』文件夹，实际：$folders", folders.contains("简体拼音"))
        assertTrue("应有『English』文件夹，实际：$folders", folders.contains("English"))
    }

    @Test
    fun `scheme folder contains its schema and config`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        // 造一个方案级配置补丁，验证它会被归纳进方案文件夹
        File(rime, "wubi86.custom.yaml").writeText("patch:\n  menu/page_size: 9\n")

        SchemaManager.migrateLayoutToSchemes(rime)
        val wubi86 = SchemaLayout.discoverSchemeFiles(rime)["五笔86"].orEmpty().map { it.name }

        assertTrue("方案文件夹必须含 schema，实际：$wubi86", wubi86.contains("wubi86.schema.yaml"))
        assertTrue("方案文件夹必须含方案配置补丁，实际：$wubi86", wubi86.contains("wubi86.custom.yaml"))
    }

    @Test
    fun `exclusive dict goes into its scheme folder`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        SchemaManager.migrateLayoutToSchemes(rime)

        val english = SchemaLayout.discoverSchemeFiles(rime)["English"].orEmpty().map { it.name }
        assertTrue("english.dict.yaml 只被 english 引用，应进目录，实际：$english",
            english.contains("english.dict.yaml"))

        val wubi98 = SchemaLayout.discoverSchemeFiles(rime)["五笔98"].orEmpty().map { it.name }
        assertTrue("wubi98.dict.yaml 应进目录，实际：$wubi98", wubi98.contains("wubi98.dict.yaml"))
    }

    @Test
    fun `shared dicts stay at root`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        SchemaManager.migrateLayoutToSchemes(rime)

        val allInFolders = SchemaLayout.discoverSchemeFiles(rime).values.flatten().map { it.name }
        listOf("wubi86.dict.yaml", "pinyin_simp.dict.yaml", "symbols.yaml").forEach { shared ->
            assertTrue("共享文件 $shared 不能进任何方案目录", allInFolders.none { it == shared })
            assertTrue("共享文件 $shared 必须留在根", File(rime, shared).isFile)
        }
    }

    @Test
    fun `global configs are not claimed by any scheme`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        val ownership = SchemaLayout.resolveOwnership(rime)
        listOf("default.custom.yaml", "trime.custom.yaml", "squirrel.custom.yaml").forEach { g ->
            assertTrue(
                "全局配置 $g 不能被当成某个方案的私有文件",
                ownership.keys.none { it.name == g },
            )
        }
    }

    @Test
    fun `upgrade adds missing files to existing schemes dir`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        // 模拟旧版本（3.4.25）建的目录：只有 schema + dict，没有方案配置
        val legacy = File(File(rime, "schemes"), "english")
        legacy.mkdirs()
        File(rime, "english.schema.yaml").copyTo(File(legacy, "english.schema.yaml"))
        File(rime, "english.dict.yaml").copyTo(File(legacy, "english.dict.yaml"))
        File(rime, "english.custom.yaml").writeText("patch:\n  menu/page_size: 9\n")

        // 新版本启动必须补齐（线上"还是没生效"的根因就是 schemes/ 存在就跳过迁移）
        SchemaManager.migrateLayoutToSchemes(rime)

        val folder = SchemaLayout.discoverSchemeFiles(rime)["English"].orEmpty().map { it.name }
        assertTrue("升级后必须补齐 schema，实际：$folder", folder.contains("english.schema.yaml"))
        assertTrue("升级后必须补齐方案配置，实际：$folder", folder.contains("english.custom.yaml"))
        // 旧目录 english 应被清理（改用显示名 English）。
        // 注意：Windows/macOS 文件系统大小写不敏感，`schemes/English` 建成后
        // `File("schemes/english").exists()` 也会为 true——这时断言没有区分力，
        // 只在大小写敏感的文件系统（Linux / Android）上校验目录名的确切形态。
        if (isCaseSensitiveFs()) {
            assertTrue(
                "旧目录 english 应被清理（改用显示名 English）",
                !File(File(rime, "schemes"), "english").exists(),
            )
            assertTrue(
                "应存在显示名目录 English",
                File(File(rime, "schemes"), "English").isDirectory,
            )
        } else {
            // 大小写不敏感：只能校验 schemes/ 里没有**额外**的旧目录条目
            val names = File(rime, "schemes").listFiles().orEmpty().filter { it.isDirectory }.map { it.name }
            assertEquals(
                "大小写不敏感文件系统上，english/English 只应存在一个目录条目，实际：$names",
                1,
                names.count { it.equals("english", ignoreCase = true) },
            )
        }
    }

    /** 探测文件系统是否区分大小写（Windows/macOS 默认不区分）。 */
    private fun isCaseSensitiveFs(): Boolean {
        val probe = tmp.newFolder("case-probe")
        File(probe, "Probe").mkdirs()
        return !File(probe, "probe").exists()
    }

    @Test
    fun `legacy folder is kept when root lacks the schema`() {
        val rime = tmp.newFolder("rime")
        val legacy = File(File(rime, "schemes"), "english")
        legacy.mkdirs()
        File(legacy, "english.schema.yaml").writeText("schema:\n  schema_id: english\n")

        SchemaLayout.removeLegacyIdFolders(rime)

        assertTrue(
            "顶层没有 schema 时不能删旧目录——那可能是唯一副本，删了方案就没了",
            legacy.isDirectory,
        )
    }

    @Test
    fun `ghost folder removal is disabled because it deleted disabled schemes`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        SchemaManager.migrateLayoutToSchemes(rime)
        val before = SchemaLayout.discoverSchemeFiles(rime).size
        assertTrue("前提：应归纳出多个目录，实际 $before", before >= 5)

        // 删掉部分顶层 schema，模拟"这些方案被禁用了、文件已归档"
        listOf("english", "numbers", "wubi98").forEach { id ->
            File(rime, "$id.schema.yaml").delete()
        }

        val removed = SchemaLayout.removeGhostSchemeFolders(rime, listOf("pinyin_simp"))

        assertEquals(
            "幽灵清理必须恒返回 0——原判据会把「未启用方案」误判成幽灵删掉，" +
                "3.4.30 就是这样丢了 6 个内置方案",
            0,
            removed,
        )
        assertEquals(
            "所有方案文件夹都必须保留（包括顶层已没有 schema 的未启用方案）",
            before,
            SchemaLayout.discoverSchemeFiles(rime).size,
        )
    }

    @Test
    fun `enabled scheme without top schema is not treated as ghost`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        SchemaManager.migrateLayoutToSchemes(rime)

        File(rime, "wubi98.schema.yaml").delete()
        SchemaLayout.removeGhostSchemeFolders(rime, listOf("wubi98"))

        val kept = SchemaLayout.discoverSchemeFiles(rime).keys.any { it == "五笔98" }
        assertTrue("已启用方案的目录不能被当幽灵删——顶层 schema 可能暂时缺失", kept)
    }

    @Test
    fun `installed schemes stay at root regardless of enabled state`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        SchemaManager.migrateLayoutToSchemes(rime)

        // 只启用一个方案，但**已安装**的方案文件必须都还在顶层：
        // updateBuiltinAssets 每次启动会把内置方案写回根目录，若这里删掉就变成
        // 删了又加的死循环，且 discoverSchemas 看不到它们 → 设置页无法重新启用。
        SchemaLayout.mirrorEnabledSchemes(rime, listOf("pinyin_simp"))

        assertTrue("启用方案的 schema 必须在根", File(rime, "pinyin_simp.schema.yaml").isFile)
        assertTrue(
            "未启用但已安装的方案也必须留在根——删了设置页就看不到、无法启用",
            File(rime, "wubi98.schema.yaml").isFile,
        )
        assertTrue("共享词典必须在根", File(rime, "wubi86.dict.yaml").isFile)
    }

    @Test
    fun `import_tables with trailing comments is parsed`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        // 真实词库的 import_tables 几乎每行都带行尾注释：
        //   - Flypy                     # 主词库(完整码表 23.3 万行)
        // 不剥注释的话资源名会带上 "# 主词库..."，与文件对不上、永远归不进方案目录。
        val text = """
            import_tables:
              - candidate_order     #手动排序词库
              - Flypy               # 主词库(完整码表 23.3 万行)
              - XGuai_user          # 用户词库
        """.trimIndent()
        val refs = SchemaLayout.referencedResources(text)
        assertEquals(
            "带行尾注释的 import_tables 必须解析出干净的资源名，实际：$refs",
            setOf("candidate_order", "Flypy", "XGuai_user"),
            refs,
        )
    }

    @Test
    fun `dict referenced with comment goes into scheme folder`() {
        val rime = tmp.newFolder("rime")
        // 造一个真实形态的方案：dictionary 带注释 + import_tables 带注释
        File(rime, "demo.schema.yaml").writeText(
            """
            schema:
              schema_id: demo
              name: 演示方案
            translator:
              dictionary: demo   # 主词库
            """.trimIndent()
        )
        File(rime, "demo.dict.yaml").writeText(
            """
            ---
            name: demo
            import_tables:
              - demo_extra     # 扩展词库
            ...
            """.trimIndent()
        )
        File(rime, "demo_extra.dict.yaml").writeText("---\nname: demo_extra\n...\n")

        SchemaManager.migrateLayoutToSchemes(rime)
        val files = SchemaLayout.discoverSchemeFiles(rime)["演示方案"].orEmpty().map { it.name }

        assertTrue("主词典应归进方案目录，实际：$files", files.contains("demo.dict.yaml"))
        assertTrue(
            "import_tables 引用且带注释的子词典也应归进方案目录，实际：$files",
            files.contains("demo_extra.dict.yaml"),
        )
    }

    @Test
    fun `all installed schemes get folders`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        // 用户要求「都放在方案文件夹」：所有**已安装**方案都要有自己的文件夹，
        // 不只是已启用的。未启用方案的文件归档进文件夹待命，不占 rime 顶层。
        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("pinyin_simp", "english"))

        val folders = SchemaLayout.discoverSchemeFiles(rime).keys
        assertTrue(
            "所有已安装方案都应有文件夹，实际只有：$folders",
            folders.size >= 10,
        )
        assertTrue("启用方案必须有文件夹：$folders", folders.contains("简体拼音"))
        assertTrue("启用方案必须有文件夹：$folders", folders.contains("English"))
        assertTrue("未启用方案也应有文件夹：$folders", folders.contains("五笔98"))
        assertTrue("未启用方案也应有文件夹：$folders", folders.contains("大写数字"))
    }

    @Test
    fun `enabled scheme folder contains all referenced dicts`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("pinyin_simp", "wubi86"))

        // pinyin_simp 的完整依赖：自身 schema + 主词典 + import 的扩展词典 + symbols
        val pinyinFiles = SchemaLayout.discoverSchemeFiles(rime)["简体拼音"].orEmpty().map { it.name }.toSet()
        assertTrue("应含 schema，实际：$pinyinFiles", pinyinFiles.contains("pinyin_simp.schema.yaml"))
        assertTrue("应含主词典，实际：$pinyinFiles", pinyinFiles.contains("pinyin_simp.dict.yaml"))
        assertTrue(
            "应含 import_tables 的扩展词典，实际：$pinyinFiles",
            pinyinFiles.contains("pinyin_simp_ext.dict.yaml"),
        )
        assertTrue("应含 __include 的 symbols，实际：$pinyinFiles", pinyinFiles.contains("symbols.yaml"))

        // wubi86 同理
        val wubiFiles = SchemaLayout.discoverSchemeFiles(rime)["五笔86"].orEmpty().map { it.name }.toSet()
        assertTrue("应含 wubi86 主词典，实际：$wubiFiles", wubiFiles.contains("wubi86.dict.yaml"))
        assertTrue(
            "应含 import_tables 的 wubi86_extra，实际：$wubiFiles",
            wubiFiles.contains("wubi86_extra.dict.yaml"),
        )
    }

    @Test
    fun `disabling a scheme keeps its folder`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("pinyin_simp"))
        assertTrue(SchemaLayout.discoverSchemeFiles(rime).containsKey("简体拼音"))

        // 换成只启用 wubi86：pinyin_simp 的文件夹**必须保留**
        // （删了的话，用户重新启用时无文件可恢复，方案等于丢了）
        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("wubi86"))
        val folders = SchemaLayout.discoverSchemeFiles(rime).keys
        assertTrue(
            "未启用方案的文件夹必须保留（否则重新启用时方案丢失），实际：$folders",
            folders.contains("简体拼音"),
        )
        assertTrue("启用方案文件夹应存在：$folders", folders.contains("五笔86"))
    }

    @Test
    fun `disabled scheme files are archived to their folders`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        // 先给所有方案建文件夹（模拟"曾经全部启用过"）
        SchemaLayout.syncEnabledSchemeFolders(
            rime,
            listOf("english", "numbers", "wubi98", "wubi86_pinyin", "wubi86_trad", "wubi86_trad_pinyin", "pinyin_simp"),
        )
        assertTrue("前提：顶层应有 english.schema.yaml", File(rime, "english.schema.yaml").isFile)

        // 只启用 pinyin_simp → 其余方案的文件应从顶层归档走
        val archived = SchemaLayout.archiveDisabledSchemeFiles(rime, listOf("pinyin_simp"))

        assertTrue("应归档未启用方案的文件，实际归档 $archived 个", archived >= 5)
        assertTrue(
            "未启用方案的 schema 不该再占顶层（用户要求「该归纳的归纳」）",
            !File(rime, "english.schema.yaml").exists(),
        )
        assertTrue(
            "启用方案的 schema 必须留在顶层（引擎只扫顶层）",
            File(rime, "pinyin_simp.schema.yaml").isFile,
        )
        assertTrue(
            "归档不是删除：方案本体必须还在 schemes/English/ 里，用户随时可重新启用",
            File(File(File(rime, "schemes"), "English"), "english.schema.yaml").isFile,
        )
    }

    @Test
    fun `archived scheme can be re-enabled`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("pinyin_simp", "english"))
        SchemaLayout.archiveDisabledSchemeFiles(rime, listOf("pinyin_simp"))
        assertTrue("前提：english 已被归档", !File(rime, "english.schema.yaml").exists())

        // 重新启用 english：必须能从 schemes/ 恢复回顶层
        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("pinyin_simp", "english"))
        SchemaLayout.mirrorEnabledSchemes(rime, listOf("pinyin_simp", "english"))

        assertTrue(
            "重新启用后 english.schema.yaml 必须回到顶层，否则引擎加载不到、方案等于丢了",
            File(rime, "english.schema.yaml").isFile,
        )
    }

    @Test
    fun `archive creates folder for never-enabled scheme`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        // 关键场景：**从未启用**过的方案（真机上 english/numbers/wubi98 就是这样）。
        // 它们的文件夹不存在——归档必须先建文件夹，否则文件永远归不了档。
        assertTrue(
            "前提：schemes 目录应不存在或为空",
            SchemaLayout.discoverSchemeFiles(rime).isEmpty(),
        )

        val archived = SchemaLayout.archiveDisabledSchemeFiles(rime, listOf("pinyin_simp"))

        assertTrue("应归档未启用方案的文件，实际归档 $archived 个", archived >= 5)
        val folders = SchemaLayout.discoverSchemeFiles(rime).keys
        assertTrue("应为未启用方案建出文件夹，实际：$folders", folders.contains("English"))
        assertTrue("应为未启用方案建出文件夹，实际：$folders", folders.contains("五笔98"))
        assertTrue(
            "归档后顶层不该再有 english.schema.yaml",
            !File(rime, "english.schema.yaml").exists(),
        )
        assertTrue(
            "方案本体必须在 schemes/English/ 里（归档不是删除）",
            File(File(File(rime, "schemes"), "English"), "english.schema.yaml").isFile,
        )
    }

    @Test
    fun `scheme folder shares inode with root file`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("wubi86"))

        val root = File(rime, "wubi86.dict.yaml")
        val copy = File(File(File(rime, "schemes"), "五笔86"), "wubi86.dict.yaml")
        assertTrue("前提：两边都应存在", root.isFile && copy.isFile)
        // 硬链接：同一 inode。若是复制，十几 MB 的词典会被复制 N 份（真机实测浪费 26MB）
        assertTrue(
            "文件夹里的词典应与顶层是同一实体（硬链接），否则磁盘占用翻倍",
            java.nio.file.Files.isSameFile(root.toPath(), copy.toPath()),
        )
    }

    @Test
    fun `existing independent copy is replaced by hardlink`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        // 模拟旧版本留下的独立副本：内容相同但 inode 不同
        val dir = File(File(rime, "schemes"), "五笔86")
        dir.mkdirs()
        val root = File(rime, "wubi86.dict.yaml")
        root.copyTo(File(dir, "wubi86.dict.yaml"))

        assertTrue(
            "前提：副本应是独立 inode",
            !java.nio.file.Files.isSameFile(root.toPath(), File(dir, "wubi86.dict.yaml").toPath()),
        )

        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("wubi86"))

        assertTrue(
            "已有的独立副本必须被替换成硬链接（否则永远不会收敛到省空间的状态）",
            java.nio.file.Files.isSameFile(
                root.toPath(), File(dir, "wubi86.dict.yaml").toPath(),
            ),
        )
    }

    @Test
    fun `lua and opencc are not copied into scheme folders`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        // lua 与 opencc 是跨方案共享资源，不属于任何单个方案。
        // 复制进每个文件夹既浪费空间，也会让"改一处要同步 N 份"变成维护负担。
        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("wubi86"))

        val base = File(File(rime, "schemes"), "五笔86")
        val files = SchemaLayout.discoverSchemeFiles(rime)["五笔86"].orEmpty()
            .map { it.relativeTo(base).path }

        assertTrue(
            "方案文件夹里不该有 lua 脚本（共享资源留在 rime/lua/），实际：$files",
            files.none { it.startsWith("lua/") },
        )
        assertTrue(
            "方案文件夹里不该有 opencc 词典（共享资源留在 rime/opencc/），实际：$files",
            files.none { it.startsWith("opencc/") },
        )
        // 但词典该在
        assertTrue("方案文件夹应含自己的词典，实际：$files", files.contains("wubi86.dict.yaml"))
    }

    @Test
    fun `all referenced dicts and configs are in the folder`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        // 用户要求：**引用的 dict 和配置**都要归类进方案文件夹。
        // 逐个方案核对：它的 schema、custom 配置、以及全部 dictionary/import_tables
        // 引用的词典，都必须能在文件夹里找到。
        val targets = listOf("wubi86" to "五笔86", "pinyin_simp" to "简体拼音", "english" to "English")
        SchemaLayout.syncEnabledSchemeFolders(rime, targets.map { it.first })

        targets.forEach { (sid, folder) ->
            val base = File(File(rime, "schemes"), folder)
            val files = SchemaLayout.discoverSchemeFiles(rime)[folder].orEmpty()
                .map { it.relativeTo(base).path }.toSet()

            assertTrue("[$sid] 文件夹应含自己的 schema，实际：$files", files.contains("$sid.schema.yaml"))

            // schema 里声明的 dictionary 与 import_tables 都要在文件夹里
            val schemaText = File(rime, "$sid.schema.yaml").readText()
            SchemaLayout.referencedResources(schemaText).forEach { res ->
                val dictFile = "$res.dict.yaml"
                if (!File(rime, dictFile).isFile) return@forEach   // 资源不存在则跳过
                assertTrue(
                    "[$sid] 引用的词典 $dictFile 必须在方案文件夹里，实际：$files",
                    files.contains(dictFile),
                )
            }
        }
    }

    @Test
    fun `nested import_tables dicts are all included`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("pinyin_simp"))

        val base = File(File(rime, "schemes"), "简体拼音")
        val files = SchemaLayout.discoverSchemeFiles(rime)["简体拼音"].orEmpty()
            .map { it.relativeTo(base).path }.toSet()

        // pinyin_simp.dict.yaml 通过 import_tables 引用 pinyin_simp_ext，
        // 后者必须跟着进文件夹（不能只收第一层）
        assertTrue("应含主词典 pinyin_simp.dict.yaml，实际：$files", files.contains("pinyin_simp.dict.yaml"))
        assertTrue(
            "应含 import_tables 的 pinyin_simp_ext.dict.yaml，实际：$files",
            files.contains("pinyin_simp_ext.dict.yaml"),
        )
        assertTrue("应含 __include 的 symbols.yaml，实际：$files", files.contains("symbols.yaml"))
    }

    @Test
    fun `empty dependency closure does not wipe folder`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("wubi86"))

        val base = File(File(rime, "schemes"), "五笔86")
        val before = SchemaLayout.discoverSchemeFiles(rime)["五笔86"].orEmpty().size
        assertTrue("前提：文件夹里应有文件", before > 0)

        // 破坏 schema：让 resolveSchemeDependencies 解析不出任何依赖（返回空集合）
        File(rime, "wubi86.schema.yaml").writeText("")   // 空文件 → 无 dictionary 引用

        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("wubi86"))

        val after = SchemaLayout.discoverSchemeFiles(rime)["五笔86"].orEmpty().size
        assertEquals(
            "依赖闭包为空时必须跳过清理，否则整个方案文件夹会被清空（方案文件全丢）",
            before,
            after,
        )
    }

    @Test
    fun `uninstalled scheme folder is not resurrected`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        SchemaLayout.syncEnabledSchemeFolders(rime, listOf("wubi86"))
        assertTrue(
            "前提：应有五笔86 文件夹",
            SchemaLayout.discoverSchemeFiles(rime).containsKey("五笔86"),
        )

        // 模拟卸载：删掉顶层 schema（deleteSchemaFiles 会这么做）
        File(rime, "wubi86.schema.yaml").delete()
        // 同时删掉文件夹（修复后的 deleteSchemaFiles 会这么做）
        File(File(rime, "schemes"), "五笔86").deleteRecursively()

        // 再跑一次布局整理
        SchemaLayout.syncEnabledSchemeFolders(rime, emptyList<String>())

        assertTrue(
            "卸载后不该再建出文件夹——否则用户看到「卸载了但方案又回来了」",
            !SchemaLayout.discoverSchemeFiles(rime).containsKey("五笔86"),
        )
    }

    @Test
    fun `scheme folder is not inferred from leftover folder alone`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        // 造一个「只有文件夹、顶层没有 schema」的残留（模拟卸载删文件夹失败）
        val dir = File(File(rime, "schemes"), "残留方案")
        dir.mkdirs()
        File(dir, "ghost.schema.yaml").writeText("schema:\n  schema_id: ghost\n  name: 残留方案\n")

        SchemaLayout.syncEnabledSchemeFolders(rime, emptyList<String>())

        // 关键：这个残留文件夹里的 ghost 方案不在顶层、也不在启用列表，
        // syncEnabledSchemeFolders 不该把它当成"已安装方案"去补全依赖。
        // 它本身可能还在（我们不主动删用户目录），但不能被"复活"成有效方案。
        val ghostDir = File(File(rime, "schemes"), "残留方案")
        val ghostSchema = File(rime, "ghost.schema.yaml")
        assertTrue(
            "残留方案不该被链接回顶层——那等于让卸载失败的方案复活",
            !ghostSchema.exists(),
        )
        // 顶层所有 schema 都必须是真实存在的（来自 assets 或用户导入）
        val topSchemas = rime.listFiles { f -> f.name.endsWith(".schema.yaml") }
            .orEmpty().map { it.name }.toSet()
        assertTrue(
            "顶层不该出现 ghost.schema.yaml，实际：$topSchemas",
            !topSchemas.contains("ghost.schema.yaml"),
        )
    }

    @Test
    fun `junk name detection covers real cases`() {
        // 真机实测的四个垃圾文件名
        assertTrue(SchemaLayout.isJunkFileName("xime.yaml.bak_width"))
        assertTrue(SchemaLayout.isJunkFileName("XGuai_flypy.schema.yaml.bak_dupkey"))
        assertTrue(SchemaLayout.isJunkFileName("probe_min.log"))
        assertTrue(SchemaLayout.isJunkFileName("reverse_wildcard_debug.log"))
        // 正常文件绝不能误判
        assertTrue(!SchemaLayout.isJunkFileName("pinyin_simp.schema.yaml"))
        assertTrue(!SchemaLayout.isJunkFileName("default.custom.yaml"))
        assertTrue(!SchemaLayout.isJunkFileName("symbols.yaml"))
        assertTrue(!SchemaLayout.isJunkFileName("rime.lua"))
        assertTrue(!SchemaLayout.isJunkFileName("custom_phrase.txt"))
        // 名字里含 bak 但是正常 yaml（如用户自己起的 data.bak.yaml）不能删
        assertTrue(!SchemaLayout.isJunkFileName("data.bak.yaml"))
    }

    @Test
    fun `junk files are cleaned`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)
        // 真机上实测存在的垃圾
        File(rime, "xime.yaml.bak_width").writeText("backup")
        File(rime, "XGuai_flypy.schema.yaml.bak_dupkey").writeText("backup")
        File(rime, "probe_min.log").writeText("")
        File(rime, "reverse_wildcard_debug.log").writeText("log")

        val removed = SchemaLayout.cleanJunkFiles(rime)

        assertEquals("应清掉 4 个垃圾文件", 4, removed)
        assertTrue("垃圾文件应被删除", !File(rime, "xime.yaml.bak_width").exists())
        assertTrue("垃圾文件应被删除", !File(rime, "reverse_wildcard_debug.log").exists())
        assertTrue(
            "正常的 yaml 不能被误删——那里面是方案本体",
            File(rime, "pinyin_simp.schema.yaml").isFile,
        )
        assertTrue("正常配置不能被误删", File(rime, "default.custom.yaml").isFile)
    }

    @Test
    fun `scheme dependencies include dict and shared symbols`() {
        val rime = tmp.newFolder("rime")
        copyAssets(rime)

        val deps = SchemaLayout.resolveSchemeDependencies(rime, "wubi86")
        assertTrue("依赖应含自己的 schema，实际：$deps", deps.contains("wubi86.schema.yaml"))
        assertTrue("依赖应含主词典，实际：$deps", deps.contains("wubi86.dict.yaml"))
        assertTrue("依赖应含 __include 的 symbols，实际：$deps", deps.contains("symbols.yaml"))
    }
}
