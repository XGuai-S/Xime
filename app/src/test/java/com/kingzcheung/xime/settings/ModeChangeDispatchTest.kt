package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三项第 1 条：控制行首键 `mode_change` 的**运行时落点**。
 *
 * [ModeChangeKeyConfigTest] 只锁了 YAML 配置；本测试锁「配置值 → 实际打开的键盘」这一段，
 * 因为中间任何一环改名都会让按键静默失效（配置对、点下去没反应）。
 *
 * 链路：
 * ```
 * xime.yaml keys.mode_change.tap{command, mode_change_number}
 *   → invokeKeyAction → GestureAction.COMMAND → onKeyPress("mode_change_number")
 *   → KeyboardView.fullScreenOnKeyPress → KeyboardLayoutState.Number（九键数字键盘）
 *
 * xime.yaml keys.mode_change.long_press.values[0]{command, mode_change_common_symbol}
 *   → SwipeableKeyButton 长按气泡 → onLongPressSelect → onKeyPress("mode_change_common_symbol")
 *   → KeyboardView.fullScreenOnKeyPress → SwitchToCommonSymbol（符号键盘）
 * ```
 */
class ModeChangeDispatchTest {

    private fun sourceOf(fileName: String): String =
        RepoFiles.mainKotlinSources.first { it.name == fileName }
            .readText().replace("\r\n", "\n")

    private val viewSource = sourceOf("KeyboardView.kt")
    private val layoutSource = sourceOf("KeyboardLayout.kt")
    private val numberSource = sourceOf("NumberKeyboardLayout.kt")
    private val keyButtonSource = sourceOf("KeyButton.kt")

    /** 截取 `fullScreenOnKeyPress` 的 lambda 体。 */
    private val fullScreenOnKeyPress: String =
        viewSource.substringAfter("val fullScreenOnKeyPress: (String) -> Unit = { key ->")
            .substringBefore("\n                        val numberOnKeyPress")

    @Test
    fun `单击 mode_change_number 打开九键数字键盘`() {
        val branch = fullScreenOnKeyPress.substringAfter("\"mode_change_number\" -> {")
            .substringBefore("\"mode_change_common_symbol\" -> {")
        assertTrue(
            "mode_change_number 必须切到 KeyboardLayoutState.Number",
            branch.contains("KeyboardLayoutState.Number"),
        )
        assertTrue(
            "切走前应先上屏已选中的候选词",
            branch.contains("onCommitCandidateBeforeModeChange"),
        )
    }

    @Test
    fun `长按 mode_change_common_symbol 打开符号键盘`() {
        val branch = fullScreenOnKeyPress.substringAfter("\"mode_change_common_symbol\" -> {")
            .substringBefore("\"emoji\" ->")
        assertTrue(
            "mode_change_common_symbol 必须切到 SwitchToCommonSymbol",
            branch.contains("KeyboardLayoutAction.SwitchToCommonSymbol"),
        )
    }

    @Test
    fun `两个命令都走 COMMAND 动作分发到 onKeyPress`() {
        // invokeKeyAction：COMMAND / SEND_RIME → onKeyPress(value)，配置里的 command 才能落到
        // fullScreenOnKeyPress 的字符串路由上
        val body = layoutSource.substringAfter("internal fun invokeKeyAction(")
            .substringBefore("\n/**\n * 可上屏键")
        assertTrue(
            "COMMAND 必须分发到 onKeyPress",
            body.contains("GestureAction.SEND_RIME, GestureAction.COMMAND -> onKeyPress(value)"),
        )
    }

    @Test
    fun `中英键盘共用同一个 onKeyPress 路由`() {
        // Chinese / English 都走 fullScreenOnKeyPress，两套键盘行为一致
        val routing = viewSource.substringAfter("val currentOnKeyPress = when (keyboardState) {")
            .substringBefore("CompositionLocalProvider(")
        val chineseEnglish = routing.substringAfter("is KeyboardLayoutState.Chinese,")
            .substringBefore("is KeyboardLayoutState.Number")
        assertTrue(
            "中英键盘都应路由到 fullScreenOnKeyPress",
            chineseEnglish.contains("is KeyboardLayoutState.English -> fullScreenOnKeyPress"),
        )
    }

    @Test
    fun `数字键盘是九键数字布局`() {
        // 九键数字键盘 = 1..9 三行三列 + 0 行；顺序锁死，避免退化成别的排布
        for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))) {
            val literal = row.joinToString(", ") { "\"$it\"" }
            assertTrue("数字键盘缺少 $literal 行", numberSource.contains("listOf($literal)"))
        }
    }

    @Test
    fun `长按气泡单项时抬手即选中 无需横向滑动`() {
        // 长按项只有「符号」一项时，selectedIdx 必须恒为 0：
        // items.size == 1 时不加 0.5f 偏移，横向漂移不会把选中项挪走，
        // 因此「长按 → 抬手」即可打开符号键盘，不需要再滑到某一项。
        assertTrue(
            "单项长按应恒选中第 0 项",
            keyButtonSource.contains("if (items.size > 1) 0.5f else 0f"),
        )
        assertTrue(
            "抬手时触发选中项",
            keyButtonSource.contains("currentOnLongPressSelect?.invoke(selected)"),
        )
    }

    @Test
    fun `长按判定时间取自 gestures_long_press_ms`() {
        // 竖屏与横屏渲染器共用同一可配值，不再是散落的 400ms 字面量
        assertTrue(
            "竖屏长按气泡应读配置",
            keyButtonSource.contains("delay(KeysConfigHelper.getKeyboardGestures().longPressMs.toLong())"),
        )
        assertTrue(
            "横屏长按气泡应读配置",
            layoutSource.contains("delay(KeysConfigHelper.getKeyboardGestures().longPressMs.toLong())"),
        )
        assertFalse(
            "不应再有硬编码 400ms 的长按判定",
            keyButtonSource.contains("delay(400L)") || layoutSource.contains("delay(400L)"),
        )
    }

    @Test
    fun `长按项配了图标 气泡不会张冠李戴`() {
        // mode_change 的两个命令各有对应图标；命令值不在已知集合时退化为「气泡只显文字」
        val fn = layoutSource.substringAfter("private fun modeChangeDrawableFor(action: KeyAction): Int? = when (action.value) {")
            .substringBefore("\n@Composable\nprivate fun ModeChangeCell")
        assertTrue(fn.contains("\"mode_change_number\" -> com.kingzcheung.xime.R.drawable.t9"))
        assertTrue(fn.contains("\"mode_change_common_symbol\" -> com.kingzcheung.xime.R.drawable.t26"))
        assertTrue("未知命令值必须返回 null", fn.contains("else -> null"))
        assertEquals(2, fn.lines().count { it.contains("-> com.kingzcheung.xime.R.drawable") })
    }
}
