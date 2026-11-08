package com.kingzcheung.xime.service

/**
 * 退格键「单击 / 连按」判定（纯逻辑，便于单测）。
 *
 * 背景：删除会话（[XimeInputMethodService.beginDeleteSession]）要跨进程读一次光标前文本，
 * 只为「下滑撤回删除」记账。单击一次就为撤回读屏并不划算，用户 2026-11 明确只优化点击态：
 * 单击 = 纯删除、不读屏；检测到连按（长按重复派发）时才开账。
 *
 * 判定口径：两次「删除键按下」的间隔不超过 [DELETE_CONTINUOUS_WINDOW_MS]。
 * 长按重复间隔约 80ms，远小于窗口；人手连点两次通常也在窗口内（当成连删无害）。
 */
internal const val DELETE_CONTINUOUS_WINDOW_MS = 250L

/**
 * 本次删除按键是否属于连按（含长按重复）。
 *
 * @param previousDownAtMs 上一次删除键按下时刻（uptimeMillis）；0 表示本会话内还没有过
 * @param nowMs            本次按下时刻
 * @param windowMs         连按判定窗口
 */
internal fun isContinuousDeletePress(
    previousDownAtMs: Long,
    nowMs: Long,
    windowMs: Long = DELETE_CONTINUOUS_WINDOW_MS,
): Boolean {
    if (previousDownAtMs <= 0L) return false
    if (nowMs < previousDownAtMs) return false
    return nowMs - previousDownAtMs <= windowMs
}
