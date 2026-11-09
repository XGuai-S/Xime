package com.kingzcheung.xime.ui.keyboard

/**
 * 长按「直接上屏角标」判定（纯函数，便于单测）。
 *
 * 用户 2026-11 需求：长按按键不再弹气泡让人滑选，而是**直接上屏键面上原有的那个
 * 角标字符**（右上角上滑提示，如 q 键的 `0`、`～`）。角标本来就在键面上，不需要额外绘制。
 *
 * 只有「有角标 + 有上滑处理体」的键才走这条路径；没有角标（或没配上滑动作）的键
 * 保持原来的长按行为（气泡 / 长按回调）。删除、语音这类单项长按也不受影响。
 */
internal object LongPressDirectPolicy {

    fun shouldCommitBadge(
        enabled: Boolean,
        hasBadge: Boolean,
        hasSwipeHandler: Boolean,
    ): Boolean = enabled && hasBadge && hasSwipeHandler
}
