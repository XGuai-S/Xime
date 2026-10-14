package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页共享外壳 [SettingsScreenScaffold] 的迁移契约。
 *
 * 原先这段「Scaffold + 返回箭头 TopAppBar」在 27 个文件里重复了 36 份，
 * 每份 20 行且只有标题 / 返回回调不同，正在逐批收敛到 [SettingsScreenScaffold]。
 */
class SettingsScreenScaffoldContractTest {

    @Test
    fun `外壳只有一份实现`() {
        assertEquals(
            "SettingsScreenScaffold 应只有一处定义",
            listOf("SettingsScreenScaffold.kt"),
            RepoFiles.mainFilesContaining("fun SettingsScreenScaffold("),
        )
    }

    @Test
    fun `迁移后的调用点没有落回第 0 列`() {
        // 迁移是把 20 行块替换成 1 行调用。曾因缩进串里混进了换行，
        // 导致替换行被写到第 0 列（缩进被留成了上一行）。
        // 注意：本仓存在历史遗留的「纯空白行」，不能作为判据，只看缩进本身。
        val offenders = mutableListOf<String>()
        for (file in RepoFiles.mainKotlinSources) {
            if (file.name == "SettingsScreenScaffold.kt") continue
            file.readLines().forEachIndexed { index, line ->
                if (!line.contains("SettingsScreenScaffold(")) return@forEachIndexed
                val indent = line.takeWhile { it == ' ' }.length
                if (indent < 4 || indent % 4 != 0) {
                    offenders += "${file.name}:${index + 1} 缩进=$indent"
                }
            }
        }
        assertTrue("调用点缩进异常: $offenders", offenders.isEmpty())
    }

    @Test
    fun `迁移后的调用点只允许显式 background 覆盖`() {
        // 少数页面沿用 Material3 Scaffold 的默认底色 background，迁移时必须按原值传入；
        // 出现别的底色就说明迁移时改了外观。
        val bad = mutableListOf<String>()
        for (file in RepoFiles.mainKotlinSources) {
            if (file.name == "SettingsScreenScaffold.kt") continue
            file.readLines().forEachIndexed { index, line ->
                if (line.contains("SettingsScreenScaffold(") &&
                    line.contains("containerColor =") &&
                    !line.contains("containerColor = MaterialTheme.colorScheme.background")
                ) {
                    bad += "${file.name}:${index + 1}"
                }
            }
        }
        assertTrue("调用点出现了非预期的 containerColor: $bad", bad.isEmpty())
    }

    /**
     * 内联外壳的**单调**守卫：剩余文件数只允许减少，不允许新增。
     *
     * 剩下的 6 处是结构上不同的 chrome，不适合套用本组件：
     * - LogViewerScreen / RimeFileBrowserScreen(×2) / SchemaSettingsScreen：标题是多行组合 Text，不是字符串
     * - SettingsMainScreen：用的是 MediumTopAppBar + scrollBehavior（可折叠标题栏）
     * - SetupWizardScreen：向导页没有返回键
     *
     * 新增设置页请直接用 [SettingsScreenScaffold]，不要复制这 20 行。
     */
    @Test
    fun `内联外壳文件数只减不增`() {
        val inline = RepoFiles.mainKotlinSources
            .filter { it.name != "SettingsScreenScaffold.kt" }
            .filter { it.readText().contains("titleContentColor = MaterialTheme.colorScheme.onSurface") }
            .map { it.name }
        assertTrue(
            "内联外壳文件数不得超过 6（当前 ${inline.size} 个：$inline）。新增设置页请用 SettingsScreenScaffold。",
            inline.size <= 6,
        )
    }
}
