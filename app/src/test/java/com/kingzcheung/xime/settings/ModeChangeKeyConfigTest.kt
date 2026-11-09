package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第三项第 1 条：控制行首键 `mode_change` 的点击/长按分工。
 *
 * 单击 → 九键数字键盘（`mode_change_number`）；长按 → 符号面板（`mode_change_common_symbol`），
 * 长按不再弹出「数字 / 常用符号」二选一菜单。
 */
class ModeChangeKeyConfigTest {

    private fun section(layout: String): Map<String, KeyBinding> {
        val text = RepoFiles.file("src/main/assets/xime.yaml").readText()
        return KeysConfigHelper.parseKeyboardYamlSection(text, layout, emptyMap())
            ?: error("解析 keyboard.$layout.keys 失败")
    }

    @Test
    fun `中英键盘 mode_change 单击跳九键数字键盘`() {
        for (layout in listOf("qwerty", "qwerty_en")) {
            val tap = section(layout)["mode_change"]?.tap ?: error("$layout 缺少 mode_change.tap")
            assertEquals("$layout 单击应跳九键数字键盘", "mode_change_number", tap.value)
        }
    }

    @Test
    fun `中英键盘 mode_change 长按为单项符号面板`() {
        for (layout in listOf("qwerty", "qwerty_en")) {
            val values = section(layout)["mode_change"]?.longPress?.values
                ?: error("$layout 缺少 mode_change.long_press")
            assertEquals("$layout 长按应只剩符号面板一项", 1, values.size)
            assertEquals(
                "$layout 长按项应为符号面板命令",
                "mode_change_common_symbol", values.first().value,
            )
        }
    }

    @Test
    fun `mode_change 长按不再包含数字键盘菜单项`() {
        // 数字键盘已改由单击直达，长按菜单里不应再出现它（否则点击/长按语义重复）
        val values = section("qwerty")["mode_change"]!!.longPress!!.values
        assertEquals(emptyList<String>(), values.map { it.value }.filter { it == "mode_change_number" })
    }

    @Test
    fun `中英键盘 mode_change 键面显示 123`() {
        // 键面文案由 tap.label 决定；缺省会回落到 KeyboardLayout 的 "123"。
        // 出厂配置显式写出，避免用户改配置时不知道这个键叫什么。
        for (layout in listOf("qwerty", "qwerty_en")) {
            val tap = section(layout)["mode_change"]?.tap ?: error("$layout 缺少 mode_change.tap")
            assertEquals("$layout 键面应显示 123", "123", tap.label)
            // label 只是显示文案，不得顶掉命令值（invokeKeyAction 取 value 优先）
            assertEquals(
                "$layout 的 label 不得改变单击命令",
                "mode_change_number", tap.value,
            )
        }
    }
}
