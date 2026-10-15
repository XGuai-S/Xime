package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.util.FileLogger

/**
 * `switch_route` 手势动作的 value → 目标面板映射（唯一实现）。
 *
 * 键盘有三个 UI 手势入口：屏幕层下发（T9/笔画等）、26 键竖屏、26 键紧凑（横屏）。
 * 三处都调用本函数，避免同一映射各抄一份导致行为漂移——历史上 `clipboard`
 * 只存在于屏幕层那一份里，26 键竖屏/横屏配置 `switch_route: clipboard` 会被静默丢弃
 * （外层 `when` 已命中 SWITCH_ROUTE 分支，不会回落到服务层动作分发）。
 *
 * `edit` 供 26 键的 `shift` 键长按进入编辑面板（见 `ShiftCell`）；写成配置值
 * 而不是直接调 `showOverlay`，是为了让面板入口都走这一处映射。
 *
 * @return 目标面板；`value` 不在支持范围时返回 null 并记录告警，便于排查配置错写。
 */
internal fun switchRouteOverlay(value: String): OverlayRoute? = when (value) {
    "emoji" -> OverlayRoute.Emoji
    "symbol" -> OverlayRoute.Symbol
    "clipboard" -> OverlayRoute.Clipboard(0)
    "edit" -> OverlayRoute.Edit
    else -> {
        FileLogger.w("XimeKeyboard", "switch_route 未知 value: \"$value\"（支持 emoji / symbol / clipboard / edit）")
        null
    }
}

/**
 * `action: command` 支持的命令名（对齐文档《全键盘配置教程（新版）》的 `command` 表，闭集）。
 *
 * 命令值走键盘按键路由，与「点击对应按键」等价。
 */
internal val SUPPORTED_COMMAND_NAMES: Set<String> = setOf(
    "clear_composition",
    "show_ime_picker",
    "mode_change",
    "mode_change_number",
    "mode_change_common_symbol",
    "shift_single",
    "shift_caps",
    "emoji",
    "symbol",
)

/**
 * `command` 值 → 按键路由（唯一入口：tap/上滑与手势分发两条链共用）。
 *
 * 不在 [SUPPORTED_COMMAND_NAMES] 内的值只告警不上屏——否则会被按键路由当成普通文本/编码打出。
 */
internal fun dispatchCommandAction(value: String, onKeyPress: (String) -> Unit) {
    if (value in SUPPORTED_COMMAND_NAMES) {
        onKeyPress(value)
    } else {
        FileLogger.w("XimeKeyboard", "command 未知 value: \"$value\"（已忽略，不上屏）")
    }
}
