package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 重输键 —— 文本在上、图标在下，参考功能键风格。
 *
 * T9 与笔画键盘此前各有一份**逐字重复**的私有实现，并且已经漂移：笔画版给标签加了
 * `AppFonts.keyFontFamily`，T9 版漏了。收敛到此处后只保留一份实现。
 *
 * [labelFontFamily] 用于按调用方**原样保留**这一历史差异：改字体是可见行为变化，
 * 不在这里擅自统一（是否统一需单独确认）。
 */
@Composable
internal fun ResetKey(
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    compactMode: Boolean = false,
    /** 标签字体。默认 `null` ＝ 沿用 `Text` 自身默认（继承 style）；笔画键盘传 `AppFonts.keyFontFamily`。 */
    labelFontFamily: FontFamily? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val shape = RoundedCornerShape(shadowShapeRadius)
    val shadowModifier = rememberCrispShadowModifier(backgroundColor, shadowEnabled, shadowElevation, shadowShapeRadius)

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .then(shadowModifier)
            .clip(shape)
            .background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "重输",
            tint = textColor,
            modifier = Modifier.size(if (compactMode) 16.dp else 20.dp)
        )

        if (!compactMode) {
            Text(
                text = "重输",
                color = textColor.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.offset(y = (-14).dp),
                fontFamily = labelFontFamily,
            )
        }
    }
}
