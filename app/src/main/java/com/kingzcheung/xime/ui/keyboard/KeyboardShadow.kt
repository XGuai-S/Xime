package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** 由按键背景色推导阴影色：有彩色倾向就压暗同色，浅色底用黑、深色底用白。 */
private val shadowColorCache = HashMap<Color, Color>()

internal fun crispShadowColor(backgroundColor: Color): Color {
    return shadowColorCache.getOrPut(backgroundColor) {
        val r = backgroundColor.red
        val g = backgroundColor.green
        val b = backgroundColor.blue
        val maxChroma = maxOf(r, g, b) - minOf(r, g, b)
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        if (maxChroma > 0.05f) {
            Color(r * 0.95f, g * 0.95f, b * 0.95f, backgroundColor.alpha)
        } else if (luminance > 0.5f) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }
    }
}

/**
 * 键盘按键的立体感阴影 —— 兼容入口。
 *
 * 3.3.10 曾把这里改成「什么都不画」：旧实现每个按键每帧提交一条带模糊的圆角 path，
 * 实测 30 个键时中文键盘整帧 p90 16.24 → 9.70ms、掉帧 8% → 1%，瓶颈在每帧 path 绘制。
 *
 * 现在立体感改由 [rememberKeyShadowModifier]（阴影+键面）与 [rememberKeyBevelModifier]
 * （高光+压暗）提供，两者都是一次性烘焙的位图，每帧只做一次 `drawImage`。
 *
 * 本兼容入口只返回**阴影层**：调用方把 `.then(shadowModifier)` 放在 `.background()` 之前，
 * 阴影即可正常外扩显示。高光层需要单独接入（见 KeyButton 的用法）。
 */
@Composable
internal fun rememberCrispShadowModifier(
    backgroundColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
): Modifier {
    return rememberKeyShadowModifier(
        backgroundColor = backgroundColor,
        shadowEnabled = shadowEnabled,
        shadowShapeRadius = shadowShapeRadius,
        pressed = false,
        strengthPercent = 100,
    )
}