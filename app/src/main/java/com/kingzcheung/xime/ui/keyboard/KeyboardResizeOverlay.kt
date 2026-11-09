package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 键盘调节层（微信输入法式交互）。
 *
 * 跟手性（重要）：所有手柄都用「拖动开始采集基准 + 协程内浮点累加 + 最后取整一次」。
 * 早期写法是「增量 + 状态回流绝对值」(latestHeight - dy).roundToInt()：回流有一帧
 * 延迟、每帧 roundToInt 抹掉不足 1dp 的位移，两者叠加导致累积位移被反复吞掉，
 * 手感表现为「发涩、拖了不动、跟不上手」。
 * 现在基准与累积都在 pointerInput 协程局部（不受重组影响），位移不丢，只取整一次。
 */
@Composable
fun KeyboardResizeOverlay(
    heightDp: Int,
    bottomPaddingDp: Int,
    marginStartDp: Int,
    marginEndDp: Int,
    onHeightChange: (Int) -> Unit,
    onBottomPaddingChange: (Int) -> Unit,
    onMarginStartChange: (Int) -> Unit,
    onMarginEndChange: (Int) -> Unit,
    onReset: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    heightBaseDp: Int = 0,
    widthBaseDp: Int = 0,
    heightScaleFactor: Float = 1f,
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    val isLandscape = screenWidthDp > screenHeightDp

    val heightBounds = remember(screenHeightDp, isLandscape, heightBaseDp) {
        KeyboardResizeBounds.heightBoundsDp(screenHeightDp, isLandscape, heightBaseDp)
    }
    val effectiveWidthBaseDp = if (widthBaseDp > 0) widthBaseDp else screenWidthDp
    val maxMargin = remember(effectiveWidthBaseDp) {
        KeyboardResizeBounds.maxHorizontalMarginDp(screenWidthDp, effectiveWidthBaseDp)
    }
    val minMargin = remember(effectiveWidthBaseDp) {
        KeyboardResizeBounds.minHorizontalMarginDp(screenWidthDp, effectiveWidthBaseDp)
    }
    val maxBottomPadding = remember(widthBaseDp, screenHeightDp, heightDp, heightScaleFactor) {
        if (widthBaseDp > 0) {
            val visibleCardHeight = (heightDp * heightScaleFactor).roundToInt() + FloatingCardGeometry.DRAG_BAR_HEIGHT_DP
            (screenHeightDp - visibleCardHeight).coerceAtLeast(KeyboardResizeBounds.MAX_BOTTOM_PADDING_DP)
        } else KeyboardResizeBounds.MAX_BOTTOM_PADDING_DP
    }

    androidx.compose.runtime.LaunchedEffect(heightBounds.first, heightBounds.last) {
        if (heightBounds.last >= heightBounds.first) {
            val clamped = heightDp.coerceIn(heightBounds.first, heightBounds.last)
            if (clamped != heightDp) onHeightChange(clamped)
        }
    }
    val latestHeight by rememberUpdatedState(heightDp)
    val latestBottomPadding by rememberUpdatedState(bottomPaddingDp)
    val latestMarginStart by rememberUpdatedState(marginStartDp)
    val latestMarginEnd by rememberUpdatedState(marginEndDp)
    val latestOnHeightChange by rememberUpdatedState(onHeightChange)
    val latestOnBottomPaddingChange by rememberUpdatedState(onBottomPaddingChange)
    val latestOnMarginStartChange by rememberUpdatedState(onMarginStartChange)
    val latestOnMarginEndChange by rememberUpdatedState(onMarginEndChange)
    val latestHeightBounds by rememberUpdatedState(heightBounds)
    val latestMaxMargin by rememberUpdatedState(maxMargin)
    val latestMinMargin by rememberUpdatedState(minMargin)
    val latestMaxBottomPadding by rememberUpdatedState(maxBottomPadding)

    // 底部手柄双基准（高度 + 留白）：拖动手势开始采集一次，之后协程内复用
    val bottomBase = remember { floatArrayOf(0f, 0f) }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        // 顶部手柄：调高度（上拖变高、下拖变矮，顶边跟手）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDLE_TOUCH_DP)
                .dragging(base = { latestHeight.toFloat() }) { base, _, dyAccum ->
                    val hb = latestHeightBounds
                    val newHeight = if (hb.last >= hb.first)
                        (base - dyAccum).coerceIn(hb.first.toFloat(), hb.last.toFloat()).roundToInt()
                    else latestHeight
                    latestOnHeightChange(newHeight)
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            GripPill(
                modifier = Modifier.padding(top = 6.dp),
                verticalBars = false,
            )
        }

        // 底部手柄：高度 ⇄ 底部留白耦合（总和恒定 ⇒ 顶边不动）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset(y = (-bottomPaddingDp * heightScaleFactor).dp)
                .height(HANDLE_TOUCH_DP)
                .dragging(
                    base = { latestHeight.toFloat() },
                    onDragStart = {
                        bottomBase[0] = latestHeight.toFloat()
                        bottomBase[1] = latestBottomPadding.toFloat()
                    },
                ) { base, _, dyAccum ->
                    val hb = latestHeightBounds
                    val delta = KeyboardResizeBounds.bottomEdgeDragDelta(
                        dyDp = dyAccum,
                        heightDp = bottomBase[0].roundToInt(),
                        heightBounds = hb,
                        bottomPaddingDp = bottomBase[1].roundToInt(),
                        maxBottomPaddingDp = latestMaxBottomPadding,
                    )
                    latestOnHeightChange((bottomBase[0] + delta).roundToInt())
                    latestOnBottomPaddingChange((bottomBase[1] - delta).roundToInt())
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            GripPill(
                modifier = Modifier.padding(bottom = 6.dp),
                verticalBars = false,
            )
        }

        // 左侧手柄：只调左边距
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(HANDLE_TOUCH_DP)
                .dragging(base = { latestMarginStart.toFloat() }) { base, dxAccum, _ ->
                    val minM = latestMinMargin.toFloat()
                    val maxM = latestMaxMargin.toFloat()
                    val newStart = (base + dxAccum).coerceIn(minM, maxM).roundToInt()
                    latestOnMarginStartChange(newStart)
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            GripPill(
                modifier = Modifier.padding(start = 6.dp),
                verticalBars = true,
            )
        }

        // 右侧手柄：只调右边距
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(HANDLE_TOUCH_DP)
                .dragging(base = { latestMarginEnd.toFloat() }) { base, dxAccum, _ ->
                    val minM = latestMinMargin.toFloat()
                    val maxM = latestMaxMargin.toFloat()
                    val newEnd = (base - dxAccum).coerceIn(minM, maxM).roundToInt()
                    latestOnMarginEndChange(newEnd)
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            GripPill(
                modifier = Modifier.padding(end = 6.dp),
                verticalBars = true,
            )
        }

        // 操作按钮：遮罩内均分，垂直锚定按键区中心
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (heightDp * heightScaleFactor / 2 - ACTION_BLOCK_HALF_HEIGHT).dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ResizeActionButton(
                icon = Icons.Default.RestartAlt,
                label = "重置",
                onClick = onReset,
            )
            ResizeActionButton(
                icon = Icons.Default.Close,
                label = "取消",
                onClick = onCancel,
            )
            ResizeActionButton(
                icon = Icons.Default.Check,
                label = "确定",
                onClick = onConfirm,
            )
        }
    }
}

/** 手柄触控区边长（dp） */
private val HANDLE_TOUCH_DP = 48.dp

/** 按钮组总高的一半，用于垂直居中 */
private const val ACTION_BLOCK_HALF_HEIGHT = 39

/**
 * 拖拽手势修饰符（本地累积）：
 * - [base]：拖动开始采集的基准值（用 rememberUpdatedState 保证最新）；
 * - [onDragStart]：拖动真正开始时回调一次，供调用处补充采集基准；
 * - [onValue]：每帧回调 (base, dxAccum, dyAccum)，其中 base/累积都在 pointerInput
 *   协程局部（不受重组影响），位移不丢失、取整只在调用处做一次。
 *
 * 解决早期写法「增量 + 状态回流绝对值」：回流一帧延迟 + 每帧 roundToInt 抹掉
 * 不足 1dp 位移，累积被反复吞掉 → 手柄发涩、拖了不动、跟不上手。
 */
@Composable
private fun Modifier.dragging(
    base: () -> Float = { 0f },
    onDragStart: () -> Unit = {},
    onValue: (base: Float, dxAccum: Float, dyAccum: Float) -> Unit,
): Modifier {
    val density = LocalDensity.current
    val latestBase by rememberUpdatedState(base)
    val latestStart by rememberUpdatedState(onDragStart)
    val latestOnValue by rememberUpdatedState(onValue)
    return pointerInput(density) {
        var base0 = 0f
        var accX = 0f
        var accY = 0f
        detectDragGestures(
            onDragStart = {
                base0 = latestBase()
                accX = 0f
                accY = 0f
                latestStart()
            },
            onDrag = { change, dragAmount ->
                change.consume()
                accX += with(density) { dragAmount.x.toDp().value }
                accY += with(density) { dragAmount.y.toDp().value }
                latestOnValue(base0, accX, accY)
            },
        )
    }
}

/** 三道握条（≡ / |||） */
@Composable
private fun GripPill(verticalBars: Boolean, modifier: Modifier = Modifier) {
    val barColor = Color.White.copy(alpha = 0.95f)
    val barShape = RoundedCornerShape(1.dp)
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.32f), RoundedCornerShape(9.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        if (verticalBars) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) {
                    Box(Modifier.size(2.dp, 16.dp).clip(barShape).background(barColor))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) {
                    Box(Modifier.size(16.dp, 2.dp).clip(barShape).background(barColor))
                }
            }
        }
    }
}

/** 调节操作按钮：深色圆形衬底 + 白色图标 + 下方带阴影的文字标签 */
@Composable
private fun ResizeActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium.copy(
                shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), blurRadius = 8f),
            ),
        )
    }
}
