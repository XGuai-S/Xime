package com.kingzcheung.xime.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具栏按钮可用性：候选栏显示与定制列表必须同一口径，
 * 否则会出现「定制里能勾选、键盘上却不显示」的幽灵按钮。
 */
class ToolbarButtonAvailabilityTest {

    @Test
    fun `语音转文本关闭时语音按钮不可用`() {
        assertFalse(available(ToolbarButton.VOICE, isSttEnabled = false))
    }

    @Test
    fun `语音转文本开启时语音按钮可用`() {
        assertTrue(available(ToolbarButton.VOICE, isSttEnabled = true))
    }

    @Test
    fun `语音按钮不受手写模型影响`() {
        assertTrue(available(ToolbarButton.VOICE, isSttEnabled = true, hasModel = false))
    }

    @Test
    fun `手写找字按钮取决于本地模型`() {
        assertFalse(available(ToolbarButton.HANDWRITING_LOOKUP, hasModel = false))
        assertTrue(available(ToolbarButton.HANDWRITING_LOOKUP, hasModel = true))
    }

    @Test
    fun `其余内置按钮始终可用`() {
        assertTrue(available(ToolbarButton.EMOJI, isSttEnabled = false, hasModel = false))
        assertTrue(available(ToolbarButton.CLIPBOARD, isSttEnabled = false))
    }

    private fun available(
        button: ToolbarButton,
        isSttEnabled: Boolean = true,
        hasModel: Boolean = true,
    ) = isBuiltinToolbarButtonAvailable(
        button,
        isSttEnabled = isSttEnabled,
        hasHandwritingModel = { hasModel },
    )
}
