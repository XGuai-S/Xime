package com.kingzcheung.xime.ui.menubar

/**
 * 长按拖拽换位的纯计算（候选排序面板 / 工具栏排序态共用）。
 *
 * 拖拽换位规则：手指纵向位移每跨越半行高度，就与相邻项交换一次；
 * 一次拖动可以连续跨多行。调用方在 onDrag 里把 `accumY` 累加后调用
 * [computeDragReorder]，得到本次应落定的顺序与剩余位移（跨过整行后
 * 的零头留给后续帧继续累加）。
 */
internal data class DragReorderResult(
    /** 换位后的顺序（未发生换位时为原顺序的浅拷贝）。 */
    val order: List<String>,
    /** 拖动源在 [order] 中的新位置（跟随被拖项移动）。 */
    val from: Int,
    /** 本次未消耗完的剩余位移（px），调用方应保存供下帧累加。 */
    val remainingAccumY: Float,
    /** 是否发生了换位（false 时 [order] 与传入列表同序，无需写盘）。 */
    val swapped: Boolean,
)

/** 换位计算的纯实现，见 [DragReorderResult]。 */
internal fun computeDragReorder(
    order: List<String>,
    from: Int,
    accumY: Float,
    rowHeightPx: Float,
): DragReorderResult {
    if (from !in order.indices || rowHeightPx <= 0f) {
        return DragReorderResult(order, from, accumY, swapped = false)
    }
    var target = from
    var remaining = accumY
    var swapped = false
    // 向下拖动（位移为正）：项跟随手指下移，跨越半行与下方一项换位（目标位置增大）
    while (remaining > rowHeightPx * 0.5 && target < order.size - 1) {
        target++
        remaining -= rowHeightPx
        swapped = true
    }
    // 向上拖动（位移为负）：项跟随手指上移，跨越半行与上方一项换位（目标位置减小）
    while (remaining < -rowHeightPx * 0.5 && target > 0) {
        target--
        remaining += rowHeightPx
        swapped = true
    }
    if (!swapped) {
        return DragReorderResult(order, from, accumY, swapped = false)
    }
    val next = order.toMutableList().apply { add(target, removeAt(from)) }
    return DragReorderResult(next, target, remaining, swapped = true)
}

/**
 * 拖到列表上下边缘时，建议的自动滚动量（px，正值 = 内容上滚、看到更下面的项）。
 *
 * 用途：候选/按钮很多、一屏放不下时，用户把正在拖的项推过可视区边缘——
 * 此时列表本身应在 [stepPx] 的节奏下持续滚动，让被拖项能跨很长距离移动，
 * 而不是卡在边缘。返回值是**建议量**：拖拽激活期间应由一个帧循环每帧调用，
 * 用 [androidx.compose.foundation.lazy.LazyListState.dispatchRawDelta] 执行，
 * 并把实际滚动量（列表已到尽头时为 0）折算成换位推力累加进 dragAccumY——
 * 手指即使停在边缘不动，列表也会持续滚动、被拖项持续换位。
 */
internal fun computeEdgeAutoScroll(
    fingerWindowY: Float,
    containerTop: Float,
    containerBottom: Float,
    edgeZonePx: Float,
    stepPx: Float,
): Float {
    if (containerBottom <= containerTop || edgeZonePx <= 0f || stepPx <= 0f) {
        return 0f
    }
    // 手指压到上边缘：往上滚（内容下移，看到更靠前的项）
    if (fingerWindowY <= containerTop + edgeZonePx) return -stepPx
    // 手指压到下边缘：往下滚（内容上移，看到更靠后的项）
    if (fingerWindowY >= containerBottom - edgeZonePx) return stepPx
    return 0f
}