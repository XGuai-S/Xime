package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Rect
import com.kingzcheung.xime.util.SubcharHelper

/**
 * 把按键上报的滑动状态转换为气泡所需的坐标并刷新气泡。
 *
 * 合并自 KeyboardLayout、StrokeKeyboardLayout、T9KeyboardLayout 内三处逐字相同的
 * 局部 `processSwipeState`：函数体完全一致，仅重复放置（历史上改一处容易漏另两处）。
 *
 * 两件事：
 * 1. 下滑时把 `swipeText` 解析成逐字信息（供气泡逐格显示）；
 * 2. 把按键在窗口坐标下的矩形换算为相对键盘区域的坐标后交给气泡。
 *
 * @param keyboardBounds 键盘区域在窗口坐标下的矩形，作为气泡坐标系原点
 * @param bubble 气泡控制器（三个布局各自的 rememberSwipeBubbleController 实例）
 */
internal fun processKeySwipeState(
    state: SwipeState,
    bounds: Rect,
    keyboardBounds: Rect,
    bubble: SwipeBubbleController,
) {
    val newState = if (state.isSwipeDown && state.swipeText != null) {
        state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
    } else {
        state
    }
    bubble.update(
        newState,
        Rect(
            left = bounds.left - keyboardBounds.left,
            top = bounds.top - keyboardBounds.top,
            right = bounds.right - keyboardBounds.left,
            bottom = bounds.bottom - keyboardBounds.top
        )
    )
}
