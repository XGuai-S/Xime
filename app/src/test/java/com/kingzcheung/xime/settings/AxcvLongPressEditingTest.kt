package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import com.kingzcheung.xime.keyboard.AxcvLongPressPolicy
import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.*
import org.junit.Test

/** AXCV long presses directly edit the host document on every QWERTY layout. */
class AxcvLongPressEditingTest {
    private val actions = mapOf(
        "a" to GestureAction.SELECT_ALL,
        "x" to GestureAction.CUT,
        "c" to GestureAction.COPY,
        "v" to GestureAction.PASTE,
    )
    private val yaml by lazy { RepoFiles.file("src/main/assets/xime.yaml").readText() }

    @Test
    fun `phone and tablet Chinese and English defaults use one direct editing action`() {
        for (layout in listOf("qwerty", "qwerty_en", "qwerty_tablet", "qwerty_en_tablet")) {
            val keys = KeysConfigHelper.parseKeyboardYamlSection(yaml, layout, emptyMap())!!
            for ((key, expected) in actions) {
                val longPress = keys.getValue(key).longPress!!
                val action = longPress.values.single()
                assertEquals("$layout/$key", expected, action.action)
                assertFalse("$layout/$key must not show a letter chooser", action.bubble)
                assertNull(longPress.displayItems)
                assertEquals(key, keys.getValue(key).tap?.value)
            }
        }
    }

    @Test
    fun `runtime policy covers legacy configs and uppercase keys`() {
        for ((key, action) in actions) {
            assertEquals(action, AxcvLongPressPolicy.actionFor(key))
            assertEquals(action, AxcvLongPressPolicy.actionFor(key.uppercase()))
        }
        for (key in listOf("q", "z", "space", "delete", "", "ax")) {
            assertNull("must not change unrelated key $key", AxcvLongPressPolicy.actionFor(key))
        }
    }

    @Test
    fun `unrelated long press choices and AXCV swipes remain intact`() {
        val keys = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty", emptyMap())!!
        assertEquals(listOf("q", "Q"), keys.getValue("q").longPress!!.values.map { it.value })
        assertEquals(listOf("z", "Z"), keys.getValue("z").longPress!!.values.map { it.value })
        assertEquals("~", keys.getValue("a").swipeUp?.value)
        assertEquals("@", keys.getValue("x").swipeUp?.value)
        assertEquals("、", keys.getValue("c").swipeUp?.value)
        assertEquals("?", keys.getValue("v").swipeUp?.value)
    }

    @Test
    fun `portrait landscape and compact row wire direct editing callback`() {
        val source = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyboardLayout.kt",
        ).readText()
        assertEquals(2, Regex("onLongClick = onKeyLongClick").findAll(source).count())
        assertTrue(source.contains("onLongClick = compactOnLongClick"))
        assertTrue(source.contains("onGestureAction?.invoke(editingAction, \"\")"))
        assertTrue(source.contains("AxcvLongPressPolicy.actionFor(key) != null) null"))
        assertTrue(source.contains("longPressLabels = if (editingLongPressAction != null) null"))
        assertTrue(source.contains("currentOnLongClick ?: ({"))
        assertTrue(source.contains("zReverseActive -> ({ onKeyPress(\"`\") })"))
    }

    @Test
    fun `toolbar editing actions remain available`() {
        for (action in actions.values) assertTrue(GestureAction.entries.contains(action))
    }
}
