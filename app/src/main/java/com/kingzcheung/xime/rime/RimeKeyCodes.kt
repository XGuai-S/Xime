package com.kingzcheung.xime.rime

/**
 * Rime 引擎的按键码（X11 keysym）。
 *
 * 这些值直接交给 [RimeEngine.processKey] / [RimeEngine.processKeyAndGetResult]，
 * 与 Android 的 `KeyEvent.KEYCODE_*` **不是同一套编码**，切勿混用：
 * 例如退格在 Rime 是 0xff08，而在 Android 是 `KEYCODE_DEL`。
 *
 * 原先以字面量散落在 ImeKeyRouter 与 T9InputController 中（退格 0xff08 共 4 处），
 * 收敛到此处后不再有「改一处漏一处」的风险。
 */
internal object RimeKeyCodes {

    /** 退格（BackSpace）：组合态下清空一个字母/联想词。 */
    const val BACKSPACE = 0xff08

    /** 撇号（Apostrophe）：在 Rime 中作为音节分隔符使用（如 `ni'hao`）。 */
    const val APOSTROPHE = 0x27
}
