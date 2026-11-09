package com.kingzcheung.xime.service

/**
 * 悬浮卡片 / 调节层的纯几何状态（与 [InputUIState] 分离的高频变化字段镜像）。
 *
 * 为什么要拆出来：拖动调节左/右手柄、拖动悬浮卡片、下手柄锚定补偿时，
 * 这些值每帧都在变。若写进 [InputUIState]，`uiState.value` 每帧换新实例，
 * 服务层 `kbState = remember(state, ...)` 就跟着每帧重建，整个 KeyboardView
 * （全部按键、候选栏）重组 —— 表现为「每个手柄移动都很卡」。
 *
 * 拆成独立 `MutableState<KeyboardGeometry>` 后：
 *  - 拖动只写这里，[InputUIState] 保持不动 ⇒ kbState / KeyboardView 不重建；
 *  - 只有读 `geometry.value` 的悬浮卡片容器（外框）重绘；
 *  - 松手/确定/取消时再一次性同步回 [InputUIState] 并落盘（低频）。
 *
 * 注意：`resizePreviewHeightDp`（影响按键区高度，重组必要）与
 * `showKeyboardResize`（低频开关）不在此处，仍留在 [InputUIState]。
 */
data class KeyboardGeometry(
    /** 键盘左边距（dp）：>0 时键盘左缘内收（宽度调节的落盘值，左右独立可偏移）。 */
    val keyboardMarginStartDp: Int = 0,
    /** 键盘右边距（dp）：>0 时键盘右缘内收。 */
    val keyboardMarginEndDp: Int = 0,
    /** 调节进行中的左边距预览值（确认后写入 [keyboardMarginStartDp]）。 */
    val resizePreviewMarginStartDp: Int = 0,
    /** 调节进行中的右边距预览值（确认后写入 [keyboardMarginEndDp]）。 */
    val resizePreviewMarginEndDp: Int = 0,
    /** 悬浮卡片水平偏移（dp，卡片拖动）。 */
    val floatingOffsetX: Int = 0,
    /** 悬浮卡片垂直偏移（dp）= 卡片底边离窗口底边的距离（卡片拖动 / 下手柄补偿）。 */
    val floatingOffsetY: Int = 0,
) {
    /** 读取口径：调节中取预览值，否则取已保存值。 */
    fun effectiveMarginStart(showResize: Boolean): Int =
        if (showResize) resizePreviewMarginStartDp else keyboardMarginStartDp

    fun effectiveMarginEnd(showResize: Boolean): Int =
        if (showResize) resizePreviewMarginEndDp else keyboardMarginEndDp
}

/**
 * 在**独立的重组作用域**里读取几何 State 并渲染 [content]，用于避免几何高频变化
 * 重启父作用域。
 *
 * 关键点：`state.value` 的读取发生在本函数体内（[GeometryObserver] 自己的
 * 重组作用域），所以只有本函数会因几何变化重启；调用方（服务层那个巨大的
 * Composable）不会重启，KeyboardView 也就不会被重新调用 → 键盘按键区不动。
 *
 * 若直接在父作用域里写 `geometryState.value.xxx`，读操作会订阅父作用域，
 * 每帧重启整个键盘内容（含全部按键）——正是之前卡顿的根源。
 */
@androidx.compose.runtime.Composable
fun <T> GeometryObserver(
    state: androidx.compose.runtime.State<T>,
    content: @androidx.compose.runtime.Composable (T) -> Unit,
) {
    content(state.value)
}
