package com.kingzcheung.xime.ui.keyboard

/**
 * 键盘调节的边界纯逻辑（无 Compose 依赖，可单测）。
 *
 * 两个可调维度：
 *  - 高度：键盘内容高度（候选栏 + 按键区），竖屏 25%~60%、横屏 30%~70% 屏高；
 *  - 左右边距：键盘两侧各自收窄的距离（宽度调节，左右独立、可整体偏移），
 *    每侧上限取「屏宽 25%」与「(屏宽 - 最小键盘宽)/2」的较小值。
 *
 * 底部手柄 = 底边跟手（顶边锚定）：高度与底部留白此消彼长、总和恒定，
 * 见 [bottomEdgeDragDelta]。
 */
object KeyboardResizeBounds {

    /** 键盘最窄总宽度（dp）：低于此值按键过小不可用。 */
    const val MIN_KEYBOARD_WIDTH_DP = 240

    /** 底部留白默认上限（dp）：非悬浮时底边最多抬离屏幕底部这么远。 */
    const val MAX_BOTTOM_PADDING_DP = 120

    /**
     * 高度边界 [min, max]（dp）。
     *
     * [baseHeightDp] = 该形态下高度值的基准屏高：
     *  - 非悬浮：竖屏取短边、横屏取短边（键盘高度以当前屏幕高为基准，占满屏高）；
     *  - 竖屏悬浮：基准即屏高（竖屏时屏高 = 短边 = 长边）；
     *  - **横屏悬浮**：卡片是竖屏形态缩放，高度基准是竖屏长边（见
     *    SettingsPreferences.getDefaultKeyboardHeightDp 的横屏分支），若仍按横屏短边
     *    算边界，卡片可用范围会被压到很小（拖动几乎没反应）。故基准改用长边。
     */
    fun heightBoundsDp(
        screenHeightDp: Int,
        isLandscape: Boolean,
        // 横屏悬浮的卡片高度基准（竖屏长边）。非悬浮 / 竖屏悬浮传 0，
        // 内部回落到 screenHeightDp。注意不能默认 = screenHeightDp：
        // 那样 screenHeightDp > 0 时 isCardBased 恒为 true，横屏非悬浮会被
        // 误判成「卡片」而套用竖屏 25%~60%（实测 400 → 100..240 而非 120..280）。
        baseHeightDp: Int = 0,
    ): IntRange {
        // 横屏悬浮（baseHeightDp > 0）的高度值是「竖屏长边 × 百分比」口径
        // （见 SettingsPreferences.getDefaultKeyboardHeightDp），因此百分比区间
        // 必须沿用竖屏的 25%~60%，不能套横屏的 30%~70%——否则把竖屏口径的值
        // 塞进横屏短边的区间里，当前值会落在区间外，第一下拖动就被 coerce 跳变。
        val isCardBased = baseHeightDp > 0
        val (minPercent, maxPercent) = when {
            isCardBased -> 25 to 60
            isLandscape -> 30 to 70
            else -> 25 to 60
        }
        val base = if (isCardBased) baseHeightDp else screenHeightDp
        // 显式传 0（非卡片）时回落当前屏高；只有横屏悬浮传入长边 > 0 才是卡片基准
        val min = (base * minPercent) / 100
        val max = (base * maxPercent) / 100
        return min..max.coerceAtLeast(min)
    }

    /**
     * 左右边距上限（dp，每侧）。
     *
     * 非悬浮：取「屏宽 25%」与「(屏宽 - 最小键盘宽)/2」的较小值，保证键盘始终
     * 有最小可用宽度。
     *
     * 横屏悬浮（[cardWidthDp] > 0）：卡片宽约 0.85×短边，居中时左/右缘距屏幕
     * 边界还有半幅距离（约 330dp），若沿用屏宽 25% 上限（≈84dp）卡片根本推不到
     * 屏幕边界——表现为「左右还没到屏幕边界就拉不动」。此处上限放宽为**卡片水平
     * 可移动半幅 + 卡片半宽**，即卡片边缘最多能推到屏幕对应边界。
     */
    fun maxHorizontalMarginDp(screenWidthDp: Int, cardWidthDp: Int = 0): Int {
        if (cardWidthDp > 0) {
            // 悬浮卡片：边距是「卡片宽度的缩减量」，卡片实际宽 = 卡宽 - 左右边距。
            // 上限取「卡宽 - 最小可用卡宽」：单侧最多把卡片收到最小宽度，
            // 既保证左右都能拖得动（此前上限 84dp 太小、卡片收不动），
            // 又不会收成 0 宽或负宽（旧算法按屏宽算 534dp 会让卡片宽度变负）。
            return (cardWidthDp - MIN_KEYBOARD_WIDTH_DP).coerceAtLeast(0)
        }
        val byPercent = (screenWidthDp * 25) / 100
        val byMinWidth = (screenWidthDp - MIN_KEYBOARD_WIDTH_DP) / 2
        return minOf(byPercent, byMinWidth).coerceAtLeast(0)
    }
    /**
     * 左右边距下限（dp，可为负数）。
     *
     * 非悬浮：0（键盘不能超出屏幕）。
     *
     * 悬浮卡片：允许**负边距**表示向外扩展卡片宽度。默认卡片宽只有
     * 0.85×短边（横屏 ≈408dp），远小于窗口宽（≈1067dp），若下限锁 0，
     * 左右手柄只能把卡片调窄、永远调不宽——表现为「只能变小，宽度被限制」。
     * 单侧扩展上限取「(窗口宽 - 卡宽)/2」：负到这个值时卡片边缘正好贴到
     * 屏幕对应边界，再大就会推出屏幕。
     */
    fun minHorizontalMarginDp(screenWidthDp: Int, cardWidthDp: Int = 0): Int {
        if (cardWidthDp > 0) {
            return -((screenWidthDp - cardWidthDp) / 2).coerceAtLeast(0)
        }
        return 0
    }

    /**
     * 底部手柄拖动的耦合增量（dp，向下为正）：高度 += delta、底部留白 -= delta。
     * 两者总和恒定 ⇒ 顶边严格不动，底边精确跟手。
     * 边界：留白收敛到 [0, MAX_BOTTOM_PADDING_DP]，高度收敛到 [heightBounds]；
     * 任一边到界后增量归零（如留白为 0 时往下拉 = 底边已贴屏幕底部，不再动作）。
     */
    fun bottomEdgeDragDelta(
        dyDp: Float,
        heightDp: Int,
        heightBounds: IntRange,
        bottomPaddingDp: Int,
        maxBottomPaddingDp: Int = MAX_BOTTOM_PADDING_DP,
        // 是否与底部留白耦合（默认 true）。
        // 非悬浮键盘底边贴屏幕底，下手柄「底边跟手」只能靠「高度 ⇄ 留白」此消彼长，
        // 因此留白为 0 时下拉必须停下（否则底边会穿出屏幕）。
        // 横屏悬浮卡片是**浮动**的，底边不贴屏幕底，下手柄应当直接调卡片高度
        // （与上手柄同效）。若仍走耦合逻辑，默认 bottomPadding=0 会让
        // upper=min(0, ...)=0 → 下拉完全被锁死，表现为「下手柄拉不动、
        // 只有上拖能变矮」。此时传 false 走纯高度调节。
        couplePadding: Boolean = true,
    ): Float {
        if (!couplePadding) {
            // 纯高度调节（横屏悬浮下手柄）：**底边跟手**语义。
            // 拖下手柄往下（dy>0）= 底边往下长 ⇒ 高度增加；往上拖 = 高度减小。
            // 注意与上手柄相反：上手柄是顶边跟手（上拖变高，height += -dy），
            // 下手柄是底边跟手（下拖变高，height += +dy）。
            // 此前照搬上手柄的 height -= dy，导致下拉变矮、卡片整体反向平移，
            // 表现为「一拉整个键盘都动」。
            val newHeight = (heightDp + dyDp).coerceIn(
                heightBounds.first.toFloat(), heightBounds.last.toFloat()
            )
            return newHeight - heightDp
        }
        val lower = maxOf(bottomPaddingDp - maxBottomPaddingDp, heightBounds.first - heightDp)
        val upper = minOf(bottomPaddingDp, heightBounds.last - heightDp)
        // 区间反转防护：heightDp 与 heightBounds 必须是同一单位口径（都未缩放）。
        // 若两者错配（例如 heightDp 传了已缩放的显示高度），lower 会大于 upper，
        // coerceIn 直接抛 IllegalArgumentException 崩溃——历史日志里
        // "Cannot coerce value to an empty range: maximum 25.0 is less than minimum 32.0"
        // 即此。此时视为不可拖动（增量 0），把崩溃降级为「拖不动」。
        if (upper < lower) return 0f
        return dyDp.coerceIn(lower.toFloat(), upper.toFloat())
    }
}
