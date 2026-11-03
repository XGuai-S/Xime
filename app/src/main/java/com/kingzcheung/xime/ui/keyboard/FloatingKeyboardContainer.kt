package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.kingzcheung.xime.service.KeyboardGeometry
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

import kotlin.math.roundToInt

private val DRAG_BAR_HEIGHT_DP = FloatingCardGeometry.DRAG_BAR_HEIGHT_DP

/**
 * 悬浮键盘卡片容器：卡片宽 = [FloatingCardGeometry.widthFraction] × 窗口宽，
 * 高 = 父容器（服务层内容 Box）高，位置由 offsetX/offsetY 控制
 * （offsetY = 卡片底边离窗口底边的距离）。
 *
 * [onDrag] 上报**原始屏幕位移**（dp，+x 向右、+y 向下），不做任何方向变换，
 * 位置语义（offsetY 与屏幕 y 相反）由回调侧（ImeKeyboardCallbacks）决定——
 * 与 KeyboardResizeOverlay 的手势约定一致。
 *
 * 卡片实测矩形（窗口坐标）经 [onCardPositioned] 回传，是触摸区与拖动钳制的唯一真源。
 */
@Composable
fun FloatingKeyboardContainer(
    isFloatingMode: Boolean,
    scaleFactor: Float,
    fontScaleFactor: Float = scaleFactor,
    /**
     * 几何镜像 State（边距、偏移量）+ 是否调节中。
     *
     * 这些值拖动时每帧都在变。以 `State` 传入并**只在本 Composable 体内**读
     * `geometryState.value` —— 读取点即订阅作用域，几何变化只重绘本容器
     * （卡片外框），不会波及父作用域（KeyboardView）与键盘按键区（content）。
     * 若从父作用域读，整个键盘会每帧重组（用户实测「每个手柄移动都很卡」）。
     */
    geometryState: State<KeyboardGeometry>,
    showResize: Boolean = false,
    backgroundColor: Color = Color.Transparent,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onCardPositioned: (left: Int, top: Int, right: Int, bottom: Int) -> Unit = { _: Int, _: Int, _: Int, _: Int -> },
    keyboardContent: @Composable () -> Unit,
) {
    if (!isFloatingMode) {
        keyboardContent()
        return
    }

    val density = LocalDensity.current
    // 唯一几何读取点（本容器作用域）：几何变化只重绘本容器，键盘按键区不重组
    val geo = geometryState.value
    val marginStart = geo.effectiveMarginStart(showResize)
    val marginEnd = geo.effectiveMarginEnd(showResize)
    val offX = geo.floatingOffsetX
    val offY = geo.floatingOffsetY

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val cardTotalHeight = maxHeight
        // 卡片实际宽度 = 标准卡片宽 - 左右边距。
        // 标准卡片宽 = 窗口宽 × scaleFactor；边距是 dp 绝对值，无法用 fillMaxWidth
        // 的比例表达，这里用 maxWidth（父约束宽的 dp 值）算出实际宽后按 dp 指定，
        // 让卡片外框（阴影+圆角+背景）本身就是新宽度，而不是只有内容内缩。
        // 水平位置：卡片左缘随 marginStart 右移、右缘随 marginEnd 左移（边跟手），
        // 整体额外位移 = (marginStart - marginEnd) / 2，再叠加 offsetX 用户拖动。
        val standardWidthDp = maxWidth.value * scaleFactor
        val actualWidthDp = (standardWidthDp - marginStart - marginEnd)
            .coerceAtLeast(120f)
        val marginShiftX = (marginStart - marginEnd) / 2f
        Box(
            modifier = Modifier
                .width(actualWidthDp.dp)
                .height(cardTotalHeight)
                .offset(x = (offX + marginShiftX).dp, y = (-offY).dp)
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    val size = coords.size
                    onCardPositioned(
                        pos.x.roundToInt(),
                        pos.y.roundToInt(),
                        (pos.x + size.width).roundToInt(),
                        (pos.y + size.height).roundToInt()
                    )
                }
        ) {
            Column {
                DragBar(
                    backgroundColor = backgroundColor,
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val dxDp = with(density) { dragAmount.x.toDp().value }
                        val dyDp = with(density) { dragAmount.y.toDp().value }
                        onDrag(dxDp, dyDp)
                    },
                    onDragEnd = onDragEnd
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(
                        // 键盘内容整体等比缩放：density（dp 尺寸：键高/间距/圆角/菜单图标/候选栏高）
                        // 与 fontScale（字号 sp）同步乘 fontScaleFactor，保证布局与菜单栏跟随
                        // 悬浮卡片大小自适应，而不是只缩字号、dp 尺寸停留在全尺寸（表现为
                        // 菜单图标/按键偏大、内容溢出卡片）。
                        LocalDensity provides Density(
                            density = density.density,
                            fontScale = density.fontScale * fontScaleFactor
                        )
                    ) {
                        keyboardContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun DragBar(
    backgroundColor: Color,
    onDrag: (change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DRAG_BAR_HEIGHT_DP.dp)
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = onDrag,
                    onDragEnd = onDragEnd,
                    // 手势被系统打断视同松手：保证"底部松手切换"的提示态被消费、光效不残留
                    onDragCancel = onDragEnd,
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // 白色握条垫深色胶囊衬底：浅色主题键盘上也可见（与 KeyboardResizeOverlay 的 GripPill 同款）
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.32f), RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.95f))
            )
        }
    }
}
