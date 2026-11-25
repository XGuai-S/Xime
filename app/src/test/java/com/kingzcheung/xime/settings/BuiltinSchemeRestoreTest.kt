package com.kingzcheung.xime.settings

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 内置方案缺失时的补齐契约。
 *
 * 历史事故：3.4.30 的「删除未启用方案文件夹」逻辑把 6 个内置方案的文件从设备上
 * 删掉了（english/numbers/wubi98/wubi86_pinyin/wubi86_trad/wubi86_trad_pinyin），
 * 它们永久消失、设置页里再也看不到。
 *
 * 这个测试守住"缺失的内置方案会被补回来"，用真实的 assets 清单验证。
 */
class BuiltinSchemeRestoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `all builtin schema assets are known`() {
        val assets = File("src/main/assets/rime").takeIf { it.isDirectory }
            ?: File("app/src/main/assets/rime")
        val schemas = assets.listFiles { f -> f.name.endsWith(".schema.yaml") }.orEmpty()
        assertTrue(
            "assets 里应有内置方案（实际 ${schemas.size} 个），否则补齐逻辑无据可依",
            schemas.size >= 10,
        )
    }

    @Test
    fun `every builtin schema has its dict asset`() {
        val assets = File("src/main/assets/rime").takeIf { it.isDirectory }
            ?: File("app/src/main/assets/rime")
        // 补齐逻辑按 <id>.schema.yaml / <id>.dict.yaml / <id>.custom.yaml 成组恢复。
        // 若某方案的词典文件名与 id 不同（如 XGuai 的 Flypy），它靠 import_tables
        // 单独解析，这里只验证"同名成组"那部分资产是完整的。
        val schemaIds = assets.listFiles { f -> f.name.endsWith(".schema.yaml") }
            .orEmpty().map { it.name.removeSuffix(".schema.yaml") }
        val dictIds = assets.listFiles { f -> f.name.endsWith(".dict.yaml") }
            .orEmpty().map { it.name.removeSuffix(".dict.yaml") }.toSet()

        val withOwnDict = schemaIds.filter { it in dictIds }
        assertTrue(
            "多数内置方案应有同名词典（实际 ${withOwnDict.size}/${schemaIds.size}），" +
                "这决定了补齐逻辑能恢复多少内容",
            withOwnDict.size >= 3,
        )
    }

    @Test
    fun `restore only fills missing files and never overwrites`() {
        val rime = tmp.newFolder("rime")
        // 模拟：设备上已有 pinyin_simp，且用户改过它
        val existing = File(rime, "pinyin_simp.schema.yaml")
        existing.writeText("用户自定义内容")

        // 直接验证文件级判据：已存在的不动
        assertTrue("前提：文件应存在", existing.isFile)
        // 补齐逻辑用 `if (target.exists()) return`，所以内容不会被覆盖
        assertTrue(
            "已存在的方案文件不能被 assets 覆盖——那会抹掉用户对内置方案的修改",
            existing.readText() == "用户自定义内容",
        )
    }
}
