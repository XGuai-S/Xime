package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color

/**
 * 按键按下/高亮时把底色压深（向黑色混合，保持 alpha 不变）。
 *
 * 合并自 KeyButton.kt 与 KeyboardLayout.kt 内 5 处逐字相同的局部实现：
 * 它们原本各自在每个 @Composable 体内重新声明一个同名局部函数，函数体完全一致
 * （仅闭合括号位置略有差异），属于纯重复实现。
 *
 * [factor] 为压深比例：0 表示不变，越大越深。各调用点沿用原值
 * （按下 0.1 / 0.2，高亮 0.2，缺省 0.15），故可见效果不变。
 */
internal fun darkenKeyColor(color: Color, factor: Float = 0.15f): Color {
    return Color(
        red = (color.red * (1 - factor)).coerceIn(0f, 1f),
        green = (color.green * (1 - factor)).coerceIn(0f, 1f),
        blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
        alpha = color.alpha
    )
}
