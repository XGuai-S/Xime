package com.kingzcheung.xime.ui.keyboard

/**
 * 「按住持续重复」的抽象：生产实现为 [SwipeRepeatRunner]。
 *
 * 抽成接口只为让横向滑动触发逻辑（[triggerSwipeLeft]）能在纯 JVM 单测里注入假实现，
 * 从而把「图标键不做重复删除」这类**有意为之**的差异固定成测试，而不是散落在三处调用点。
 */
internal interface SwipeRepeater {

    /** 用当前滑动幅度（dp）刷新重复间隔；幅度越大间隔越短。 */
    fun updateInterval(amplitudeDp: Float)

    /** 启动重复循环（先停掉上一次）。首次触发由调用方自行执行，本循环只负责「之后」的重复。 */
    fun start(invoke: () -> Unit)
}

/**
 * 左滑触发（含按住持续重复）。
 *
 * 这段逻辑原先在 SwipeableKeyButton、SwipeableIconKeyButton、KeyboardLayout 三处各写一遍，
 * 其中「按住期间实时跟随幅度调整速率」只在两处存在。收敛到此处后不再可能漏改。
 *
 * 注意：`repeats = false` 是**有意保留**的差异——图标键（数字/符号面板的删除键）走的是
 * 非重复路径，与标准键不同。这里显式传参，把该差异从"少写了一段"变成"明确的策略"。
 *
 * @param alreadyTriggered 调用前该键的左滑是否已触发过（跨帧保持的状态）
 * @param onSwipeLeft 左滑动作；为 null 表示该键未配置左滑
 * @param repeatAction 每次重复时调用；**必须**由调用方传 `{ currentOnSwipeLeft?.invoke() }`
 *                     这类重读 `rememberUpdatedState` 的 lambda，而不是复用 [onSwipeLeft]——
 *                     否则按住期间若回调被替换，重复循环会一直用触发瞬间的旧回调。
 *                     仅在 [repeats] 为 true 时使用。
 * @param dragOffsetX 当前横向拖动位移（px，向左为负）
 * @param threshold 左滑触发阈值（px，负值）
 * @param repeats 是否启用按住持续重复
 * @param amplitudeDp 当前滑动幅度（dp，正值）；由调用方用 density 换算后传入，
 *                    使本函数不依赖 Compose，可在纯 JVM 单测覆盖。仅在 [repeats] 为 true 时使用
 * @param repeater 重复执行器；仅在 [repeats] 为 true 时使用，因此未持有执行器的调用点
 *                 （图标键）可传 null。为 null 时不会发生重复
 * @return 更新后的「是否已触发」状态
 */
internal fun triggerSwipeLeft(
    alreadyTriggered: Boolean,
    onSwipeLeft: (() -> Unit)?,
    repeatAction: () -> Unit = {},
    dragOffsetX: Float,
    threshold: Float,
    repeats: Boolean,
    amplitudeDp: Float = 0f,
    repeater: SwipeRepeater? = null,
): Boolean {
    if (dragOffsetX < threshold && !alreadyTriggered && onSwipeLeft != null) {
        onSwipeLeft()
        if (repeats) {
            // 首次触发由上面那次调用完成；这里定好速率后开始「按住持续重复」
            repeater?.updateInterval(amplitudeDp)
            repeater?.start(repeatAction)
        }
        return true
    } else if (repeats && alreadyTriggered) {
        // 按住期间实时跟随幅度：滑得越远删得越快
        repeater?.updateInterval(amplitudeDp)
    }
    return alreadyTriggered
}

/**
 * 右滑触发（一次性，不重复）。
 *
 * 与 [triggerSwipeLeft] 同源抽出，三处调用点共用。
 *
 * @param alreadyTriggered 调用前该键的右滑是否已触发过
 * @param onSwipeRight 右滑动作；为 null 表示该键未配置右滑
 * @param dragOffsetX 当前横向拖动位移（px，向右为正）
 * @param threshold 右滑触发阈值（px，正值）
 * @return 更新后的「是否已触发」状态
 */
internal fun triggerSwipeRight(
    alreadyTriggered: Boolean,
    onSwipeRight: (() -> Unit)?,
    dragOffsetX: Float,
    threshold: Float,
): Boolean {
    if (dragOffsetX > threshold && !alreadyTriggered && onSwipeRight != null) {
        onSwipeRight()
        return true
    }
    return alreadyTriggered
}
