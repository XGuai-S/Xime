package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.KeyAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「语音转文本」开关是语音总闸：xime.yaml 配置的 `voice` 动作（默认空格长按）也受其约束。
 */
class KeyboardVoiceGateTest {

    @Test
    fun `开关关闭时拦截 voice 动作`() {
        assertTrue(shouldSkipVoiceKeyAction(KeyAction(GestureAction.VOICE), isSttEnabled = false))
    }

    @Test
    fun `开关开启时放行 voice 动作`() {
        assertFalse(shouldSkipVoiceKeyAction(KeyAction(GestureAction.VOICE), isSttEnabled = true))
    }

    @Test
    fun `其他动作与空动作不受开关影响`() {
        assertFalse(
            shouldSkipVoiceKeyAction(
                KeyAction(GestureAction.REPEAT_SPACE, value = "5"),
                isSttEnabled = false,
            )
        )
        assertFalse(shouldSkipVoiceKeyAction(null, isSttEnabled = false))
    }
}
