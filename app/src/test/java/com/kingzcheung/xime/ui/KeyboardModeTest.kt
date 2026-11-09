package com.kingzcheung.xime.ui

import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutAction
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.initialKeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardModeTest {

    @Test
    fun `initial state is Chinese in non-ASCII mode`() {
        val state = initialKeyboardLayoutState(isAsciiMode = false)
        assertEquals(KeyboardLayoutState.Chinese, state)
    }

    @Test
    fun `initial state is English in ASCII mode`() {
        val state = initialKeyboardLayoutState(isAsciiMode = true)
        assertEquals(KeyboardLayoutState.English, state)
    }

    @Test
    fun `transition to Number works from any state`() {
        val state = KeyboardLayoutState.Chinese
        val next = state.transition(
            KeyboardLayoutAction.SwitchToNumber, isAsciiMode = true
        )
        assertEquals(KeyboardLayoutState.Number, next)
    }

    @Test
    fun `transition to Symbol works from any state`() {
        val state = KeyboardLayoutState.Number
        val next = state.transition(
            KeyboardLayoutAction.SwitchToSymbol, isAsciiMode = false
        )
        assertEquals(KeyboardLayoutState.Symbol, next)
    }

    @Test
    fun `transition to Full returns Chinese in non-ASCII mode`() {
        val state = KeyboardLayoutState.Number
        val next = state.transition(
            KeyboardLayoutAction.SwitchToFull, isAsciiMode = false
        )
        assertEquals(KeyboardLayoutState.Chinese, next)
    }

    @Test
    fun `transition to Full returns English in ASCII mode`() {
        val state = KeyboardLayoutState.Symbol
        val next = state.transition(
            KeyboardLayoutAction.SwitchToFull, isAsciiMode = true
        )
        assertEquals(KeyboardLayoutState.English, next)
    }

    @Test
    fun `isFullKeyboard returns true for Chinese and English`() {
        assertTrue(KeyboardLayoutState.Chinese.isFullKeyboard)
        assertTrue(KeyboardLayoutState.English.isFullKeyboard)
    }

    @Test
    fun `isFullKeyboard returns false for Number and Symbol`() {
        assertFalse(KeyboardLayoutState.Number.isFullKeyboard)
        assertFalse(KeyboardLayoutState.Symbol.isFullKeyboard)
    }
}
