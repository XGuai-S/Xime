package com.kingzcheung.xime.keyboard

/** Shared by portrait, landscape and compact QWERTY rows, including legacy configs. */
internal object AxcvLongPressPolicy {
    fun actionFor(key: String): GestureAction? = when (key) {
        "a", "A" -> GestureAction.SELECT_ALL
        "x", "X" -> GestureAction.CUT
        "c", "C" -> GestureAction.COPY
        "v", "V" -> GestureAction.PASTE
        else -> null
    }
}
