package com.kingzcheung.xime.ui.menubar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.alpha
import com.kingzcheung.xime.ui.keyboard.WIDE_CONTAINER_WIDTH

/**
 * 候选排序面板。
 *
 * 用户在候选栏长按候选 → 菜单选「调整顺序」后进入此面板：
 * - 顶部「完成」条：点完成保存并退出；
 * - 候选列表按当前顺序垂直平铺，长按某项上下拖动换位，**换位即写盘**
 *   （每次换位调用 [onReorderSave] 把该编码的全序写入本地词频文件）。
 *
 * 拖拽用 `detectDragGesturesAfterLongPress`：长按某行后该行进入拖动，
 * 手指纵向位移跨越一行高度即与相邻候选换位；松手结束本次拖动。
 * 与候选栏自身的左边 logo / 右侧按钮等可点击区域互不冲突（覆盖层全屏）。
 */
@Composable
fun CandidateReorderView(
    code: String,
    initialOrder: List<String>,
    keyTextColor: Color,
    backgroundColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    onReorderSave: ((code: String, ordered: List<String>) -> Unit)?,
    onDone: () -> Unit,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
) {
    var order by remember { mutableStateOf(initialOrder) }
    // 正在拖动的行的当前位置；null = 无拖动
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    // 当前拖动已累积的纵向位移（px）
    var dragAccumY by remember { mutableStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { 44.dp.toPx() }
    // 候选列表滚动状态：拖到上下边缘时自动滚动，支持跨很长距离移动排序
    val listState = rememberLazyListState()
    // 列表容器在窗口中的纵向范围（供边缘自动滚动判定）
    var listContainerTop by remember { mutableStateOf(Float.NaN) }
    var listContainerBottom by remember { mutableStateOf(Float.NaN) }
    // 边缘判定带（px）与单帧滚动步长（px）
    val edgeZonePx = with(LocalDensity.current) { 40.dp.toPx() }
    val edgeStepPx = with(LocalDensity.current) { 24.dp.toPx() }
    // 拖动中手指相对列表容器窗口的 y（NaN = 未在拖动）；边缘自动滚动帧循环读它
    var dragFingerWindowY by remember { mutableStateOf(Float.NaN) }

    // 换位落盘公共操作：以给定累积位移换位；滚动推力与手指位移都经它落定
    fun applyReorder(accumY: Float) {
        val from = draggingIndex ?: return
        val result = computeDragReorder(
            order = order,
            from = from,
            accumY = accumY,
            rowHeightPx = rowHeightPx,
        )
        dragAccumY = result.remainingAccumY
        if (result.swapped) {
            order = result.order
            draggingIndex = result.from
            onReorderSave?.invoke(code, order)
        }
    }

    // 拖拽激活时帧循环：手指压到列表上下边缘就在现节奏下持续滚动列表，
    // 每次把实际滚动量折算成换位推力——手指停住不动也能长距离移动排序
    LaunchedEffect(draggingIndex != null) {
        while (draggingIndex != null) {
            val suggested = computeEdgeAutoScroll(
                fingerWindowY = dragFingerWindowY,
                containerTop = listContainerTop,
                containerBottom = listContainerBottom,
                edgeZonePx = edgeZonePx,
                stepPx = edgeStepPx,
            )
            if (suggested != 0f) {
                // dispatchRawDelta 返回实际滚动量（列表已到尽头时为 0）
                val actual = listState.dispatchRawDelta(suggested)
                if (actual != 0f) {
                    applyReorder(dragAccumY + actual)
                }
            }
            withFrameNanos { }
        }
    }

    val iconButtonContainer = androidx.compose.ui.graphics.lerp(
        keyBgColor,
        accentColor,
        0.25f
    )

    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= WIDE_CONTAINER_WIDTH

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 顶部「完成」条 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = if (isWide) 50.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(iconButtonContainer)
                        .alpha(if (draggingIndex == null) 1f else 0.4f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "完成",
                        tint = keyTextColor.copy(alpha = if (draggingIndex == null) 1f else 0.4f),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "调整排序（长按候选上下拖动）",
                    fontSize = 14.sp,
                    color = keyTextColor.copy(alpha = 0.8f),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.weight(1f))
                if (draggingIndex == null) {
                    Text(
                        text = if (code.isEmpty()) "候选排序" else "编码：$code",
                        fontSize = 12.sp,
                        color = keyTextColor.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }

            // ── 可拖拽候选列表（LazyColumn：候选多于一屏时可上下滑动查看，
            //    长按拖拽排序与滚动天然协同；拖到边缘自动滚动，支持跨长距离移动）──
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = if (isWide) 50.dp else 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(keyBgColor)
                    // 记录列表视口在窗口中的上下边界：拖拽到边缘时据此自动滚动
                    .onGloballyPositioned { coords ->
                        listContainerTop = coords.positionInWindow().y
                        listContainerBottom = coords.positionInWindow().y + coords.size.height
                    },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
            ) {
                items(order, key = { it }, contentType = { "word" }) { word ->
                    val position = order.indexOf(word)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (draggingIndex == position) accentColor.copy(alpha = 0.2f)
                                else Color.Transparent
                            )
                            .pointerInput(word) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        // 实时按词查位置，不能用闭包捕获的 position：换位后该值已陈旧，
                                        // 会导致「点这行却拖了另一行」
                                        val startPosition = order.indexOf(word)
                                        draggingIndex = startPosition
                                        dragAccumY = 0f
                                        // 记录拖动起始时手指相对容器窗口的 y
                                        val rowOffset =
                                            listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.index == startPosition }
                                                ?.offset ?: 0
                                        dragFingerWindowY = listContainerTop + rowOffset
                                    },
                                    onDragCancel = {
                                        draggingIndex = null
                                        dragAccumY = 0f
                                        dragFingerWindowY = Float.NaN
                                    },
                                    onDragEnd = {
                                        draggingIndex = null
                                        dragAccumY = 0f
                                        dragFingerWindowY = Float.NaN
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        // 更新手指相对容器窗口的 y：change.position 是相对本行的局部坐标，
                                        // 加上本行在视口中的 offset 即换算成容器窗口坐标（供边缘滚动帧循环读取）
                                        val from = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                        val rowOffsetInViewport =
                                            listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.index == from }
                                                ?.offset ?: 0
                                        dragFingerWindowY = listContainerTop + rowOffsetInViewport + change.position.y
                                        // 手指位移换位（边缘滚动由上面的帧循环补推力）
                                        applyReorder(dragAccumY + dragAmount.y)
                                    }
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 序号
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${position + 1}",
                                fontSize = 11.sp,
                                color = keyTextColor.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = word,
                            fontSize = 19.sp,
                            fontWeight = if (position == 0) FontWeight.Medium else FontWeight.Normal,
                            color = keyTextColor,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (draggingIndex == position) {
                            Text(
                                text = "拖动中…",
                                fontSize = 11.sp,
                                color = accentColor,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    }
                }
                if (order.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "没有可排序的候选",
                                fontSize = 14.sp,
                                color = keyTextColor.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isWide) 15.dp else bottomPaddingDp.dp))

            // ── 底部「完成」按钮（拖拽中禁用防误触） ──
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .height(44.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (draggingIndex == null) accentColor else keyBgColor)
                    .clickable(enabled = draggingIndex == null, onClick = onDone),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "完成",
                    fontSize = 16.sp,
                    color = if (draggingIndex == null) {
                        keyTextColor.copy(alpha = 0.9f)
                    } else {
                        keyTextColor.copy(alpha = 0.4f)
                    },
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(if (isWide) 15.dp else bottomPaddingDp.dp))
        }
    }
}