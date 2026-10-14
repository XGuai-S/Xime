package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选栏顶部「编码气泡」的显示条件回归。
 *
 * 背景：编码显示位置有两档——「显示在输入框」与「显示在候选栏」，另有「候选首词占用
 * 输入框」的让位模式。候选栏模式下编码只能靠这条气泡显示，气泡被误关会让编码
 * 「两处都看不到」（例如输入 `osl` 这类无候选编码时整条候选栏全空）。
 *
 * 本测试锁住极性：气泡在「编码不在输入框」时显示，在「编码已在输入框」时不重复。
 */
class PreeditBubbleVisibilityTest {

    @Test
    fun `编码显示在候选栏时气泡必须显示`() {
        // 默认档：编码不在输入框（codeInInputBox=false），有编码就应显示气泡，
        // 与是否有候选无关——无候选编码（如 osl）同样要能看到。
        assertTrue(
            shouldShowPreeditBubble(
                showInputTextRow = true,
                hasPreeditText = true,
                codeInInputBox = false,
            )
        )
    }

    @Test
    fun `编码已在输入框时不重复显示气泡`() {
        assertFalse(
            shouldShowPreeditBubble(
                showInputTextRow = true,
                hasPreeditText = true,
                codeInInputBox = true,
            )
        )
    }

    @Test
    fun `没有编码文本时不显示气泡`() {
        assertFalse(
            shouldShowPreeditBubble(
                showInputTextRow = true,
                hasPreeditText = false,
                codeInInputBox = false,
            )
        )
    }

    @Test
    fun `非编码行页面不显示气泡`() {
        // 剪贴板等覆盖页没有编码行，不应画出悬空气泡。
        assertFalse(
            shouldShowPreeditBubble(
                showInputTextRow = false,
                hasPreeditText = true,
                codeInInputBox = false,
            )
        )
    }
}
