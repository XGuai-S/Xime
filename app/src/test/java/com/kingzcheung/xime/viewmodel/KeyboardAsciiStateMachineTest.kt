package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.ui.keyboard.AsciiKeyboardContext
import com.kingzcheung.xime.ui.keyboard.KeyboardViewState.CommonSymbolPanel
import com.kingzcheung.xime.ui.keyboard.KeyboardViewState.NumberPanel
import com.kingzcheung.xime.ui.keyboard.asciiContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ascii 记忆状态机：主键盘 / 数字面板 各自记忆，切换键盘时同步引擎中英态。
 *
 * 符号面板**不参与**（中英共用一套布局、无 En 键），见 [符号面板没有 ascii 上下文也未参与同步]。
 */
class KeyboardAsciiStateMachineTest {

    private val machine = KeyboardAsciiStateMachine()

    @Test
    fun `number panel follows engine`() {
        assertNull(machine.targetFor(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = true))
        assertNull(machine.targetFor(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = false))
        machine.saveMemory(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = true)
        assertNull(machine.targetFor(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = true))
    }

    @Test
    fun `exit panel restores main keyboard memory`() {
        // 主键盘中文，进入数字面板（保存主键盘记忆）
        machine.saveMemory(AsciiKeyboardContext.MAIN, engineAscii = false)
        assertNull(machine.targetFor(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = false))
        // 数字面板内切英文
        machine.saveMemory(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = true)
        // 退出面板：目标为主键盘记忆（中文）
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = true))
    }

    @Test
    fun `panel switch does not affect main keyboard memory`() {
        machine.saveMemory(AsciiKeyboardContext.MAIN, engineAscii = false)
        machine.saveMemory(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = true)
        machine.saveMemory(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = false)
        // 主键盘记忆仍为中文
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = true))
    }

    @Test
    fun `main keyboard with memory unset defaults to chinese`() {
        // MAIN 记忆缺失时 fallback 默认中文（不跟随引擎——引擎可能停留在面板态）
        assertNull(machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = false))
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = true))
    }

    @Test
    fun `main keyboard memory lost after reset restores chinese even if engine stuck on panel ascii`() {
        // 中文主键盘进面板（引擎变英文）→ 发送消息触发会话刷新 → reset 清掉记忆：
        // 退出时引擎仍为英文，应切回中文而不是沿用引擎的面板态
        machine.saveMemory(AsciiKeyboardContext.MAIN, engineAscii = false)
        machine.saveMemory(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = true)
        machine.reset()
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = true))
    }

    @Test
    fun `english main keyboard restored after panel`() {
        machine.saveMemory(AsciiKeyboardContext.MAIN, engineAscii = true)
        machine.saveMemory(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = false)
        // 退出：恢复英文
        assertEquals(true, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = false))
    }

    @Test
    fun `reset clears all memories`() {
        machine.saveMemory(AsciiKeyboardContext.MAIN, engineAscii = true)
        machine.saveMemory(AsciiKeyboardContext.NUMBER_PANEL, engineAscii = true)
        machine.reset()
        // MAIN 记忆缺失 fallback 默认中文
        assertEquals(false, machine.targetFor(AsciiKeyboardContext.MAIN, engineAscii = true))
    }

    // ── 符号面板不参与 ascii 同步 ─────────────────────────

    @Test
    fun `符号面板没有 ascii 上下文也未参与同步`() {
        // 符号面板中英共用一套布局：进出都不碰引擎中英态（旧设计会切成英文显示半角段）
        assertNull(CommonSymbolPanel(MainType.FULL).asciiContext())
        // 数字面板仍是独立上下文
        assertEquals(AsciiKeyboardContext.NUMBER_PANEL, NumberPanel(MainType.FULL).asciiContext())
    }
}
