package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SelectionChangePolicy 决策：
 * - 用户移动光标 → 打断待上屏组合态（避免旧编码与新输入合并上屏到错误位置）
 * - 选区未变化 / 光标仍在 composing 区域内（自身写入）/ 无效选区信息 → 不打断
 */
class SelectionChangePolicyTest {

    private fun shouldBreak(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int = -1,
        candidatesEnd: Int = -1,
    ): Boolean = shouldBreakCompositionOnSelectionChange(
        oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
    )

    @Test
    fun `选区未变化不打断`() {
        assertFalse(shouldBreak(3, 3, 3, 3))
    }

    @Test
    fun `光标移到新位置应打断_无composing区域`() {
        assertTrue(shouldBreak(5, 5, 2, 2))
    }

    @Test
    fun `光标落在composing区域末尾不打断_input框模式自身写入`() {
        // setComposingText 写入编码后光标紧跟编码末尾（10 = candidatesEnd）
        assertFalse(shouldBreak(4, 4, 10, 10, candidatesStart = 3, candidatesEnd = 10))
    }

    @Test
    fun `光标在composing区域中间不打断_编码编辑光标`() {
        assertFalse(shouldBreak(10, 10, 5, 5, candidatesStart = 3, candidatesEnd = 10))
    }

    @Test
    fun `光标移出composing区域应打断_用户点击别处`() {
        assertTrue(shouldBreak(10, 10, 1, 1, candidatesStart = 3, candidatesEnd = 10))
    }

    @Test
    fun `拖动选区应打断`() {
        assertTrue(shouldBreak(8, 8, 2, 6))
    }

    @Test
    fun `无有效选区信息不打断`() {
        assertFalse(shouldBreak(-1, -1, -1, -1))
        assertFalse(shouldBreak(3, 3, -1, -1))
    }

    private fun removalCaret(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ): Int? = restoreCaretAfterInputBoxComposingRemoval(
        newSelStart, newSelEnd, candidatesStart, candidatesEnd
    )

    @Test
    fun `编码在光标之后_删除后光标保持原位`() {
        // 文本：你好[g j]，光标点在句首 （1 < 编码起点 2）
        assertEquals(1, removalCaret(1, 1, 2, 5))
    }

    @Test
    fun `编码在光标之前_删除后光标前移编码长度`() {
        // 文本：[g j]你好，光标点在字后 （4 > 编码末尾 3）
        assertEquals(1, removalCaret(4, 4, 0, 3))
    }

    @Test
    fun `光标紧邻编码两端_恢复到删除后的等效位置`() {
        assertEquals(2, removalCaret(2, 2, 2, 5))
        assertEquals(2, removalCaret(5, 5, 2, 5))
    }

    @Test
    fun `光标仍在编码内不删除`() {
        assertNull(removalCaret(3, 3, 2, 5))
    }

    @Test
    fun `拖动选区不删除编码`() {
        assertNull(removalCaret(1, 4, 2, 5))
    }

    @Test
    fun `无组合区不删除`() {
        assertNull(removalCaret(1, 1, -1, -1))
        assertNull(removalCaret(1, 1, 5, 5))
    }
}
