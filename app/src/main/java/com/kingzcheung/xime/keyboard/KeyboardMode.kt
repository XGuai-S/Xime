package com.kingzcheung.xime.keyboard

/**
 * 键盘模式
 *
 * @deprecated 已由 [KeyboardLayoutState] 取代。
 * [KeyboardLayoutState] 将全键盘进一步拆分为 Chinese / English / Split 等模式，
 * 消除原本的 [KeyboardView] 中依赖 [isAsciiMode] + 横屏检测的复杂分支。
 * 详见 [KeyboardLayoutState.transition]。
 */
@Deprecated("Use KeyboardLayoutState instead")
enum class KeyboardMode {
    FULL,       // 全键盘（字母）
    NINEKEY,    // 九宫格（T9）
    NUMBER,     // 九宫格数字键盘
    SYMBOL      // 符号键盘
}