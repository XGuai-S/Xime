package com.kingzcheung.xime.ai.ime

/**
 * 编辑器快捷动作（全选/剪切/复制/粘贴）。
 *
 * 移植自 TypeFree 的 BuiltInQwertyKeyboard.kt。TypeFree 把它和自绘键盘放在同一文件，
 * 但 Xime 用 Rime 键盘，不需要那份自绘实现；而 AI 面板的按键路由（ImeUiAction）会
 * 引用这个枚举，故单独成文件，保持与上游语义一致。
 */
enum class EditorShortcutAction {
    SELECT_ALL,
    CUT,
    COPY,
    PASTE
}
