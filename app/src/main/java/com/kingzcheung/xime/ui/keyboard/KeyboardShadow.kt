package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * 键盘按键的 crisp（硬）阴影 —— 统一实现在此，供各键盘布局与按键组件复用。
 *
 * 收敛前：「圆角矩形阴影 Modifier」在 6 个文件里被内联复制了 12 份，逻辑逐字一致，
 * 只有颜色来源变量名不同（`backgroundColor` / `keyBackgroundColor`）。任何阴影算法调整
 * 都要改 12 处，漏改一处就会出现「某个键盘的阴影和别的不一样」这类难查的视觉不一致。
 */

private val shadowColorCache = HashMap<Color, Color>()

/** 由按键背景色推导阴影色：有彩色倾向就压暗同色，浅色底用黑、深色底用白。 */
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
 * 按键底部的圆角矩形硬阴影 Modifier。
 *
 * 与原先 12 处内联实现语义完全一致：同样的 remember 键集合
 * （shadowEnabled / shadowElevation / shadowShapeRadius / density / backgroundColor），
 * 同样的绘制参数与分支。
 */
@Composable
internal fun rememberCrispShadowModifier(
    backgroundColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
): Modifier {
    val density = LocalDensity.current
    return remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
}
