package com.kingzcheung.xime.ui.keyboard

/**
 * 按键手势阈值（dp）。
 *
 * 这些数值原先以字面量散落在 KeyboardView / KeyButton / KeyboardLayout 三个渲染器里，
 * 其中一处是**必须保持一致**的耦合值，此前只靠注释约束：
 *
 * > 与 KeyboardView 光标手势激活阈值（activationThresholdPx = 60dp）对齐，
 * > 消除 30~60dp 位移区间"点击被取消但光标手势未激活"的死区（打字吃键）
 *
 * 收敛到此处后，「对齐」不再是约定而是同一个常量：改一处不可能漏另一处。
 *
 * 横屏渲染器（SwipeableKeyButtonLandscape）的阈值**故意**比竖屏小：横屏键高更矮，
 * 沿用竖屏的 50dp 会让竖向滑动几乎触发不了。因此按渲染器分别命名，不做统一。
 */
internal object KeyGestureThresholds {

    /** 光标手势激活阈值：横向位移超过它，父层 KeyboardView 接管为移动光标。 */
    const val CURSOR_ACTIVATION_DP = 60f

    /**
     * 按键的横向点击取消阈值。
     *
     * **必须等于** [CURSOR_ACTIVATION_DP]：二者不一致会在「点击已被取消、但光标手势
     * 尚未激活」的位移区间产生死区，表现为打字吃键。这里直接引用同一常量。
     */
    const val TAP_CANCEL_DP = CURSOR_ACTIVATION_DP

    /** 光标手势每次移动的步长。 */
    const val CURSOR_STEP_DP = 25f

    /** 按键上/下滑触发阈值（删除键的清空/撤销危险区同值）。 */
    const val VERTICAL_SWIPE_DP = 50f

    /** 按键左/右滑触发阈值。 */
    const val HORIZONTAL_SWIPE_DP = 50f

    /** 横向滑动抑制阈值：横向位移超过它就不再判定上/下滑。 */
    const val HORIZONTAL_SWIPE_SUPPRESS_DP = 30f

    /** 拖动取消阈值：位移超过它即认为不是点击。 */
    const val DRAG_CANCEL_DP = 5f

    /** 长按后滑动取方向的判定阈值（[awaitSwipeDirection] 用）。 */
    const val SWIPE_DIRECTION_DP = 40f

    /** 滑动气泡显示的提前量：相对触发阈值的比例。 */
    const val BUBBLE_SHOW_RATIO = 0.3f

    /** 代码键盘（T9 / 笔画）空格键上滑的累计位移阈值。 */
    const val CODE_KEYBOARD_SWIPE_UP_DP = 50f

    // ---------- 横屏渲染器（比竖屏灵敏，见类注释） ----------

    /**
     * 横屏：上/下滑触发阈值。
     *
     * 比竖屏小（横屏键高更矮，沿用竖屏 50dp 会让竖向滑动几乎触发不了），但也不能太小：
     * 快速打字时手指自然抖动约 10~20dp，若阈值只有 15dp（键高的 1/3），轻抖即触发滑动
     * 动作或取消点击，表现为「点 A 打出 B」。22dp 约为横屏键高（35~45dp）的一半，
     * 既能触发滑动又不至于误触。
     */
    const val LANDSCAPE_VERTICAL_SWIPE_DP = 22f

    /**
     * 横屏：左/右滑触发阈值。横屏键宽约 64dp，30dp（不足一半）在快速打字时
     * 容易被手指横向抖动误触发；放宽到 36dp（键宽 56%）兼顾触发与防误触。
     */
    const val LANDSCAPE_HORIZONTAL_SWIPE_DP = 36f

    /**
     * 横屏：横向接管阈值。**故意低于** [CURSOR_ACTIVATION_DP]，
     * 使配置了左右滑的键优先接管横向滑动，而不是被父层光标手势抢走。
     * 随触发阈值上调至 22dp，仍远低于 60dp 光标阈值。
     */
    const val LANDSCAPE_HORIZONTAL_SWIPE_SUPPRESS_DP = 22f
}
