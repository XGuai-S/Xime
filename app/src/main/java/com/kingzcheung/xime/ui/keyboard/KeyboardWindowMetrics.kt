package com.kingzcheung.xime.ui.keyboard

/**
 * 键盘窗口高度与底部留白相关的共享常量。
 *
 * 这些数值原先以字面量散落在 XimeInputMethodService 的**两处平行实现**里
 * （onCreateInputView 的 Compose 侧计算，与首帧预设容器高度时的那份副本），
 * 其中 `120 / 8 / 18` 与 `8/10` 是逐字复制的两份。收敛到此处后，
 * 「两处必须一致」不再依赖人肉核对。
 *
 * 注意：两处实现**故意**不完全相同——Compose 侧的底部收缩量横屏取 16dp、竖屏取 8dp，
 * 而首帧预设那份恒用竖屏值 [BOTTOM_INSET_SHRINK_PORTRAIT_DP]。本对象只统一
 * **取值相同**的常量，不合并两份逻辑（合并会改变横屏首帧行为）。
 */
internal object KeyboardWindowMetrics {

    /** 底部 inset 收缩量（竖屏，dp）：键盘背景已 edge-to-edge 延伸到系统栏后，留白可小于系统栏高度。 */
    const val BOTTOM_INSET_SHRINK_PORTRAIT_DP = 8

    /**
     * 底部 inset 收缩量（横屏，dp）。
     *
     * 横屏手势区 inset 更大（约 32dp vs 竖屏 16dp），沿用竖屏值会留下过厚的底条（24dp，为竖屏 3 倍）。
     */
    const val BOTTOM_INSET_SHRINK_LANDSCAPE_DP = 16

    /** 标准（三键）导航栏 inset 判定阈值（dp）：达到它说明不是手势条，需额外多减一档。 */
    const val STANDARD_NAV_BAR_INSET_THRESHOLD_DP = 120

    /** 标准（三键）导航栏的额外收缩量（dp）：让标准模式高度更接近抬高模式，但保留可辨识的差异。 */
    const val STANDARD_NAV_BAR_EXTRA_SHRINK_DP = 8

    /** 底部留白兜底值（dp）：彻底检测不到任何底部 inset（全屏沉浸）时使用。 */
    const val MIN_BOTTOM_SPACE_DP = 18

    /** 导航栏已被系统排除的容差（dp）：物理屏高与可用屏高之差达到「状态栏 + 导航栏 − 容差」即认定已排除。 */
    const val NAV_BAR_EXCLUDED_TOLERANCE_DP = 3

    /** 键盘最大高度占屏幕高度的百分比：避免键盘占满整屏。 */
    const val DISPLAY_HEIGHT_MAX_PERCENT = 80

    /** 横屏键盘高度占屏幕高度的百分比（比竖屏更矮，给应用留出可视区）。 */
    const val LANDSCAPE_DISPLAY_HEIGHT_PERCENT = 70

    /** 快捷发送表单撑高量（dp）：需与表单实际渲染高度一致，否则容器比表单多/少一截，键盘被拉高。 */
    const val QUICK_SEND_FORM_EXTRA_HEIGHT_DP = 200
}
