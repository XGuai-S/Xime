package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.OverlayRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGestureRoutingTest {

    @Test
    fun `emoji value 映射到 Emoji 面板`() {
        assertEquals(OverlayRoute.Emoji, switchRouteOverlay("emoji"))
    }

    @Test
    fun `symbol value 映射到 Symbol 面板`() {
        assertEquals(OverlayRoute.Symbol, switchRouteOverlay("symbol"))
    }

    @Test
    fun `clipboard value 映射到剪贴板面板首页`() {
        assertEquals(OverlayRoute.Clipboard(0), switchRouteOverlay("clipboard"))
    }

    @Test
    fun `edit value 映射到编辑面板`() {
        // shift 长按进入编辑面板走这个映射；漏了会让长按静默无反应
        assertEquals(OverlayRoute.Edit, switchRouteOverlay("edit"))
    }

    @Test
    fun `未知 value 返回 null`() {
        assertNull(switchRouteOverlay("plugin_panel"))
        assertNull(switchRouteOverlay(""))
    }

    @Test
    fun `命令名白名单覆盖文档 command 表`() {
        assertTrue(
            SUPPORTED_COMMAND_NAMES.containsAll(
                listOf(
                    "clear_composition", "show_ime_picker",
                    "mode_change", "mode_change_number", "mode_change_common_symbol",
                    "shift_single", "shift_caps", "emoji", "symbol",
                )
            )
        )
        assertFalse("symobl" in SUPPORTED_COMMAND_NAMES)
    }

    @Test
    fun `dispatchCommandAction 只放行白名单命令`() {
        val pressed = mutableListOf<String>()
        dispatchCommandAction("symbol", { pressed += it })
        dispatchCommandAction("symobl", { pressed += it })
        assertEquals(listOf("symbol"), pressed)
    }
}