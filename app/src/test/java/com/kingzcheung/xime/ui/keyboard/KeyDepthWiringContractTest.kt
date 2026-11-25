package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 立体感两层必须**成对接线**。
 *
 * 背景：立体感拆成两层位图后才不会互相遮挡——
 * - 阴影层（`shadowLayer`）放在 `.clip()` 之前，负责外扩软阴影与键面底色；
 * - 高光层（`bevelLayer`）必须放在 `.background()` 之后，否则被键面底色整块盖住。
 *
 * 曾经出现过的真实缺陷：9 个渲染器里有 4 个（数字/面板/Reset/笔画）只接了阴影层、
 * 定义了 `bevelLayer` 却从不在 Modifier 链上使用，结果是「有的键有立体感、有的键是平的」，
 * 而且从截图上很难一眼看出差别。本测试把「定义即使用」钉死。
 */
class KeyDepthWiringContractTest {

    /** 所有包含按键渲染的文件名。 */
    private val rendererFiles = listOf(
        "KeyButton.kt",
        "KeyboardLayout.kt",
        "NumberKeyboardLayout.kt",
        "PanelKeyboardLayout.kt",
        "ResetKey.kt",
        "SpaceKeyButton.kt",
        "StrokeKeyboardLayout.kt",
        "T9KeyboardLayout.kt",
    )

    private fun source(name: String): String =
        RepoFiles.mainKotlinSources.single { it.name == name }.readText().replace("\r\n", "\n")

    @Test
    fun `every defined bevel layer is actually applied`() {
        val offenders = mutableListOf<String>()
        for (name in rendererFiles) {
            val text = runCatching { source(name) }.getOrNull() ?: continue
            val defs = Regex("""val bevelLayer = rememberKeyBevelModifier""").findAll(text).count()
            val uses = Regex("""\.then\(bevelLayer\)""").findAll(text).count()
            if (defs > uses) offenders += "$name(定义 $defs，使用 $uses)"
        }
        assertTrue(
            "以下渲染器定义了高光层却没用上（会被键面底色盖住，表现为该键没有立体感）：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every defined shadow layer is actually applied`() {
        val offenders = mutableListOf<String>()
        for (name in rendererFiles) {
            val text = runCatching { source(name) }.getOrNull() ?: continue
            val defs = Regex("""val shadowLayer = rememberKeyShadowModifier""").findAll(text).count()
            val uses = Regex("""\.then\(shadowLayer\)""").findAll(text).count()
            if (defs > uses) offenders += "$name(定义 $defs，使用 $uses)"
        }
        assertTrue("以下渲染器定义了阴影层却没用上：$offenders", offenders.isEmpty())
    }

    @Test
    fun `bevel is applied after the key background`() {
        // 顺序回归：高光层若排在 .background() 之前，会被同色背景整块覆盖。
        val text = source("KeyButton.kt")
        val shadowIdx = text.indexOf(".then(shadowLayer)")
        val bgIdx = text.indexOf(".background(", shadowIdx)
        val bevelIdx = text.indexOf(".then(bevelLayer)", bgIdx)

        assertTrue("阴影层必须存在", shadowIdx >= 0)
        assertTrue("背景必须紧跟在阴影层之后", bgIdx > shadowIdx)
        assertTrue("高光层必须排在 background 之后", bevelIdx > bgIdx)
    }

    @Test
    fun `operator column draws depth on the whole panel instead of each key`() {
        // +-*/ 四个键紧贴成一条，立体感**只在整体外框**上：
        // 单键若也画阴影会叠出接缝暗线；若也画高光/压暗，每个键的「顶部亮 + 底部暗」
        // 会在接缝处连成一条明暗分界线，看起来像键之间夹了阴影（用户实测反馈过）。
        val text = source("NumberKeyboardLayout.kt")
        assertTrue(
            "运算符列必须有整体外框阴影",
            text.contains("symbolPanelShadow") && text.contains(".then(symbolPanelShadow)"),
        )
        assertTrue(
            "运算符列必须有整体外框高光",
            text.contains("symbolPanelBevel") && text.contains(".then(symbolPanelBevel)"),
        )

        // 取单键渲染器（NumberSymbolKey）函数体，确认里面两层都没接
        val keyBody = text.substringAfter("private fun NumberSymbolKey(")
            .substringBefore("\nprivate fun ")
        assertTrue(
            "单个运算符键不得再画阴影层（否则接缝叠出暗线）",
            !keyBody.contains(".then(shadowLayer)") && !keyBody.contains("rememberKeyShadowModifier("),
        )
        assertTrue(
            "单个运算符键不得再画高光层（否则接缝出现明暗分界）",
            !keyBody.contains(".then(bevelLayer)") && !keyBody.contains("rememberKeyBevelModifier("),
        )
    }

    @Test
    fun `operator column is scrollable and wired to adaptive ordering`() {
        val text = source("NumberKeyboardLayout.kt")

        assertTrue("必须能滚动查看更多符号（LazyColumn）", text.contains("LazyColumn("))
        assertTrue(
            "符号顺序必须来自使用记录存储，而不是写死列表",
            text.contains("SymbolUsageStore.counts(") && text.contains("SymbolUsagePolicy.orderedSymbols("),
        )
        assertTrue(
            "点击后必须立刻重排（用到 recordAndOrder 并回写状态）",
            text.contains("SymbolUsageStore.recordAndOrder(") && text.contains("symbols = SymbolUsageStore"),
        )
        assertTrue(
            "LazyColumn 必须用符号本身作 key：用下标会让重排序后的所有项被当成新项重建",
            text.contains("items(symbols, key = { it })"),
        )
        assertTrue("符号数量应显著多于 4 个", SymbolUsagePolicy.DEFAULT_SYMBOLS.size >= 20)
        assertEquals(
            "四则固定在最前，上手位置不能变",
            listOf("+", "-", "*", "/"),
            SymbolUsagePolicy.DEFAULT_SYMBOLS.take(4),
        )
    }

    @Test
    fun `compact inline space key has a pressed state`() {
        // 空格键曾整段没有 isPressed：按下去只有缩放，键面颜色与立体感都不变。
        val text = source("KeyboardLayout.kt")
        assertTrue("空格渲染器必须有按下态变量", text.contains("var spacePressed by remember"))
        assertTrue("按下时必须置位", text.contains("spacePressed = true"))
        assertTrue("抬手必须在 finally 复位", text.contains("} finally {\n                        spacePressed = false"))
        assertTrue("键面颜色必须随按下态变化", text.contains(".background(if (spacePressed)"))
    }
}