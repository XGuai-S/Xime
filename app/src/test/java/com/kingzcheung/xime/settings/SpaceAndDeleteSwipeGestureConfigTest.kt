package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第二项手势需求的内置配置核验：
 * - 空格键左右滑移动文本光标（新增）
 * - 删除键左滑快速删除文本（历史已有，此处回归钉住，防止被误删）
 */
class SpaceAndDeleteSwipeGestureConfigTest {

    private fun section(layout: String): Map<String, KeyBinding> {
        val text = RepoFiles.file("src/main/assets/xime.yaml").readText()
        return KeysConfigHelper.parseKeyboardYamlSection(text, layout, emptyMap())
            ?: error("解析 keyboard.$layout.keys 失败")
    }

    @Test
    fun `中英键盘空格键左右滑均为光标移动`() {
        for (layout in listOf("qwerty", "qwerty_en")) {
            val space = section(layout)["space"] ?: error("$layout 缺少 space")
            assertEquals(
                "$layout 空格左滑应为光标左移",
                GestureAction.CURSOR_LEFT, space.swipeLeft?.action,
            )
            assertEquals(
                "$layout 空格右滑应为光标右移",
                GestureAction.CURSOR_RIGHT, space.swipeRight?.action,
            )
        }
    }

    @Test
    fun `空格键上下滑仍为中英切换`() {
        // 新增左右滑不应挤掉原有的上下滑中英切换
        val space = section("qwerty")["space"] ?: error("qwerty 缺少 space")
        assertEquals(GestureAction.TOGGLE_ASCII, space.swipeUp?.action)
        assertEquals(GestureAction.TOGGLE_ASCII, space.swipeDown?.action)
    }

    @Test
    fun `删除键左滑为按住快速删除`() {
        val delete = section("qwerty")["delete"] ?: error("qwerty 缺少 delete")
        assertEquals(GestureAction.REPEAT_DELETE, delete.swipeLeft?.action)
    }
}
