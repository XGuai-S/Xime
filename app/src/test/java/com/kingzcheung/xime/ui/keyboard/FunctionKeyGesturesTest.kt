package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能键四向滑动装配（[functionKeyGestures]）。
 *
 * 锁定槽位语义：未配置不接管；COMMIT 直接上屏；其余走 [invokeKeyAction]（含 `none` 吸收、
 * `command` 白名单）；左右滑额外抑制父层光标手势。
 */
class FunctionKeyGesturesTest {

    private class Recorder {
        val pressed = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val gestures = mutableListOf<Pair<GestureAction, String>>()
        var horizontalSwipeCount = 0

        fun build(
            swipeUp: KeyAction? = null,
            swipeDown: KeyAction? = null,
            swipeLeft: KeyAction? = null,
            swipeRight: KeyAction? = null,
        ): FunctionKeyGestures = functionKeyGestures(
            swipeUp = swipeUp,
            swipeDown = swipeDown,
            swipeLeft = swipeLeft,
            swipeRight = swipeRight,
            onKeyPress = { pressed += it },
            onCommitText = { committed += it },
            onGestureAction = { a, v -> gestures += a to v },
            onHorizontalSwipe = { horizontalSwipeCount++ },
        )
    }

    @Test
    fun `未配置任何滑动时四向都不接管`() {
        val g = Recorder().build()
        assertNull(g.onUp)
        assertNull(g.onDown)
        assertNull(g.onLeft)
        assertNull(g.onRight)
    }

    @Test
    fun `action 为 null 的槽位不接管`() {
        val g = Recorder().build(swipeUp = KeyAction(value = "x"))
        assertNull(g.onUp)
    }

    @Test
    fun `上滑 COMMIT 直接上屏`() {
        val r = Recorder()
        val g = r.build(swipeUp = KeyAction(action = GestureAction.COMMIT, value = "。"))
        assertNotNull(g.onUp)
        g.onUp!!()
        assertEquals(listOf("。"), r.committed)
        assertTrue(r.pressed.isEmpty())
    }

    @Test
    fun `value 为空时 COMMIT 用 label 兜底`() {
        val r = Recorder()
        val g = r.build(swipeUp = KeyAction(action = GestureAction.COMMIT, label = "，"))
        g.onUp!!()
        assertEquals(listOf("，"), r.committed)
    }

    @Test
    fun `下滑 COMMIT 直接上屏且不抑制光标手势`() {
        val r = Recorder()
        val g = r.build(swipeDown = KeyAction(action = GestureAction.COMMIT, value = "x"))
        g.onDown!!()
        assertEquals(listOf("x"), r.committed)
        assertEquals(0, r.horizontalSwipeCount)
    }

    @Test
    fun `左右滑走手势分发并抑制父层光标手势`() {
        val r = Recorder()
        val g = r.build(
            swipeLeft = KeyAction(action = GestureAction.COPY),
            swipeRight = KeyAction(action = GestureAction.COMMIT, value = "y"),
        )
        g.onLeft!!()
        g.onRight!!()
        assertEquals(2, r.horizontalSwipeCount)
        assertEquals(listOf(GestureAction.COPY to ""), r.gestures)
        assertEquals(listOf("y"), r.committed)
    }

    @Test
    fun `action none 吸收滑动且不上屏`() {
        val r = Recorder()
        val g = r.build(swipeUp = KeyAction(action = GestureAction.NONE, label = "无"))
        assertNotNull(g.onUp)
        g.onUp!!()
        assertTrue(r.pressed.isEmpty())
        assertTrue(r.committed.isEmpty())
        assertTrue(r.gestures.isEmpty())
    }

    @Test
    fun `send_rime 走按键路由`() {
        val r = Recorder()
        val g = r.build(swipeUp = KeyAction(action = GestureAction.SEND_RIME, value = "q"))
        g.onUp!!()
        assertEquals(listOf("q"), r.pressed)
    }

    @Test
    fun `command 走白名单按键路由`() {
        val r = Recorder()
        val g = r.build(swipeUp = KeyAction(action = GestureAction.COMMAND, value = "symbol"))
        g.onUp!!()
        assertEquals(listOf("symbol"), r.pressed)
    }

    @Test
    fun `未登记 command 不上屏`() {
        val r = Recorder()
        val g = r.build(swipeUp = KeyAction(action = GestureAction.COMMAND, value = "symobl"))
        g.onUp!!()
        assertTrue(r.pressed.isEmpty())
        assertTrue(r.committed.isEmpty())
    }

    @Test
    fun `withSwipeTextArg 适配 SwipeableKeyButton 的回调签名`() {
        var called = 0
        val once: () -> Unit = { called++ }
        val cb: ((String) -> Unit)? = once.withSwipeTextArg()
        assertNotNull(cb)
        cb!!("提示")
        assertEquals(1, called)

        val none: (() -> Unit)? = null
        assertNull(none.withSwipeTextArg())
    }
}
