package com.kingzcheung.xime.ui.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘 UI 的「单一实现」契约。
 *
 * 这些组件原先在多个键盘布局里各存一份逐字重复的实现。重复本身还不是最糟的——
 * 真正的问题是它会**漂移**：扫描时发现 `ResetKey` 的笔画版比 T9 版多一个
 * `fontFamily = AppFonts.keyFontFamily`，同一次复制出来的两个"同一个组件"已经不一样了。
 *
 * 本测试把「只能有一处实现」钉死，避免再次分叉。
 */
class KeyboardUiDedupContractTest {

    /** 主源码目录下的全部 .kt（只读一次，避免每个用例重复遍历）。 */
    private val mainSources: List<File> by lazy { findMainSources() }

    /** 定位主源码目录（单测 workingDir 可能是模块目录或仓库根目录，逐级向上查找）。 */
    private fun findMainSources(): List<File> {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            for (rel in listOf("app/src/main/java", "src/main/java")) {
                val root = File(dir, rel)
                if (root.isDirectory) {
                    return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                }
            }
            dir = dir.parentFile ?: error("找不到主源码目录")
        }
    }

    // ---------- crisp 阴影 ----------

    @Test
    fun `内联阴影构造已全部收敛到统一实现`() {
        // 只匹配被收敛的那一种形式（签名含 shadowEnabled/shadowElevation/shadowShapeRadius/density）。
        // EditKeyboardLayout / ThemePreviewSheet 用的是另一套 ShadowStyle 参数化形式，签名不同、不在此列。
        val marker = "val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density,"
        val offenders = mainSources
            .filter { it.name != "KeyboardShadow.kt" }
            .filter { it.readText().contains(marker) }
            .map { it.name }
        assertTrue(
            "以下文件又出现了内联阴影构造，应改用 rememberCrispShadowModifier：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `按键组件都接入了统一阴影实现`() {
        // T9KeyboardLayout 不在此列：它唯一的阴影调用点在 ResetKey 里，
        // 抽取后通过共享的 ResetKey 间接受益，不再直接调用助手。
        val expected = listOf(
            "KeyboardLayout.kt",
            "KeyButton.kt",
            "NumberKeyboardLayout.kt",
            "SpaceKeyButton.kt",
            "StrokeKeyboardLayout.kt",
            "ResetKey.kt",
        )
        val missing = expected.filterNot { name ->
            mainSources.any { it.name == name && it.readText().contains("rememberCrispShadowModifier(") }
        }
        assertTrue("这些文件没有接入统一阴影实现（抽取后接线断了）：$missing", missing.isEmpty())
    }

    // ---------- 重输键 ----------

    @Test
    fun `ResetKey 只有一处实现`() {
        val definition = Regex("""fun\s+ResetKey\s*\(""")
        val definers = mainSources
            .filter { definition.containsMatchIn(it.readText()) }
            .map { it.name }
        assertEquals("ResetKey 应只在 ResetKey.kt 中定义：$definers", listOf("ResetKey.kt"), definers)
    }

    @Test
    fun `两个键盘布局都复用共享的 ResetKey`() {
        val users = listOf("T9KeyboardLayout.kt", "StrokeKeyboardLayout.kt").filterNot { name ->
            mainSources.any { it.name == name && it.readText().contains("ResetKey(") }
        }
        assertTrue("这些键盘不再调用 ResetKey（接线断了）：$users", users.isEmpty())
    }

    @Test
    fun `笔画键盘保留了它的自定义按键字体`() {
        // 抽取时按调用方原样保留历史差异：笔画版带 AppFonts.keyFontFamily，T9 版不带。
        // 若此处失败，说明有人改动了字体传递，需要确认是有意统一还是回归。
        val stroke = mainSources.first { it.name == "StrokeKeyboardLayout.kt" }.readText()
        assertTrue(
            "StrokeKeyboardLayout 的 ResetKey 调用丢了 labelFontFamily = AppFonts.keyFontFamily",
            stroke.contains("labelFontFamily = AppFonts.keyFontFamily"),
        )
    }
}
