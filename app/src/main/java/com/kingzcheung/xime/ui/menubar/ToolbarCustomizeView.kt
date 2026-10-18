package com.kingzcheung.xime.ui.menubar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.keyboard.isBuiltinToolbarButtonAvailable
import com.kingzcheung.xime.ui.keyboard.ToolbarButtonIcon
import com.kingzcheung.xime.ui.keyboard.WIDE_CONTAINER_WIDTH

@Composable
fun ToolbarCustomizeView(
    toolbarButtons: List<String>,
    pluginButtons: List<ToolbarButtonItem.Plugin> = emptyList(),
    isSttEnabled: Boolean = true,
    keyTextColor: Color,
    backgroundColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    onUpdateToolbarButtons: ((List<String>) -> Unit)?,
    onDismiss: () -> Unit,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 与候选栏显示同一口径：关掉语音转文本、未装手写模型时，定制列表里也不该出现
    val builtinButtons = ToolbarButton.entries
        .filter {
            isBuiltinToolbarButtonAvailable(
                it,
                isSttEnabled = isSttEnabled,
                hasHandwritingModel = { com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(context) },
            )
        }
        .map { ToolbarButtonItem.Builtin(it) }
    val allButtons = builtinButtons + pluginButtons
    val itemById = remember(allButtons) { allButtons.associateBy { it.id } }
    val originalButtons = remember { toolbarButtons }
    var enabledIds by remember(toolbarButtons) { mutableStateOf(toolbarButtons.toSet()) }
    // 排序态：长按已启用按钮进入；网格切换为垂直拖拽列表，换位即写盘
    var reorderMode by remember { mutableStateOf(false) }
    // 排序态中的当前顺序（独立于外部 toolbarButtons，避免写盘回传导致重组抖动）
    var reorderOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    // 拖拽状态（排序列表内）
    var reorderDraggingIndex by remember { mutableStateOf<Int?>(null) }
    var reorderDragAccumY by remember { mutableStateOf(0f) }
    val reorderRowHeightPx = with(LocalDensity.current) { 44.dp.toPx() }
    // 排序列表滚动状态：拖到上下边缘时自动滚动，支持跨长距离移动排序
    val reorderListState = rememberLazyListState()
    // 排序列表视口在窗口中的纵向范围（供边缘自动滚动判定）
    var reorderListTop by remember { mutableStateOf(Float.NaN) }
    var reorderListBottom by remember { mutableStateOf(Float.NaN) }
    val reorderEdgeZonePx = with(LocalDensity.current) { 40.dp.toPx() }
    val reorderEdgeStepPx = with(LocalDensity.current) { 24.dp.toPx() }
    // 拖动中手指相对排序列表容器窗口的 y（NaN = 未在拖动）；边缘自动滚动帧循环读它
    var reorderFingerWindowY by remember { mutableStateOf(Float.NaN) }

    // 换位落盘公共操作：以给定累积位移换位；滚动推力与手指位移都经它落定
    fun applyReorder(accumY: Float) {
        val from = reorderDraggingIndex ?: return
        val result = computeDragReorder(
            order = reorderOrder,
            from = from,
            accumY = accumY,
            rowHeightPx = reorderRowHeightPx,
        )
        reorderDragAccumY = result.remainingAccumY
        if (result.swapped) {
            reorderOrder = result.order
            reorderDraggingIndex = result.from
            // 换位即写盘：持久化并应用（服务层同步 UI 态）
            onUpdateToolbarButtons?.invoke(result.order)
        }
    }

    // 拖拽激活时帧循环：手指压到排序列表上下边缘就在现节奏下持续滚动列表，
    // 每次把实际滚动量折算成换位推力——手指停住不动也能长距离移动排序
    LaunchedEffect(reorderDraggingIndex != null) {
        while (reorderDraggingIndex != null) {
            val suggested = computeEdgeAutoScroll(
                fingerWindowY = reorderFingerWindowY,
                containerTop = reorderListTop,
                containerBottom = reorderListBottom,
                edgeZonePx = reorderEdgeZonePx,
                stepPx = reorderEdgeStepPx,
            )
            if (suggested != 0f) {
                // dispatchRawDelta 返回实际滚动量（列表已到尽头时为 0）
                val actual = reorderListState.dispatchRawDelta(suggested)
                if (actual != 0f) {
                    applyReorder(reorderDragAccumY + actual)
                }
            }
            withFrameNanos { }
        }
    }

    fun toggleButton(item: ToolbarButtonItem) {
        enabledIds = if (item.id in enabledIds) enabledIds - item.id else enabledIds + item.id
        val newList = toolbarButtons.toMutableList()
        if (item.id in toolbarButtons) newList.remove(item.id) else newList.add(item.id)
        onUpdateToolbarButtons?.invoke(newList)
    }

    /**
     * 进入排序态：以当前已启用顺序为初始顺序。
     *
     * [reorderOrder] 独立于外部 [toolbarButtons]——拖拽写盘回传后外部列表会更新，
     * 若直接读外部列表会让本视图重组时抖动。
     */
    fun enterReorderMode() {
        // 只保留网格里可渲染的按钮：排序列表按 itemById 过滤渲染，
        // 若配置残留不可用 id（如手写模型被清理），不过滤会导致
        // 渲染下标与 reorderOrder 下标错位（点这行却拖/高亮另一行）
        reorderOrder = toolbarButtons.filter { it in itemById }
        reorderMode = true
        reorderDraggingIndex = null
        reorderDragAccumY = 0f
    }

    // 图标按钮容器色：按键背景与强调色的混合色调（带主题色但不过于强烈）
    val iconButtonContainer = androidx.compose.ui.graphics.lerp(
        keyBgColor,
        accentColor,
        0.25f
    )

    // 布局按父容器真实宽度自适应（悬浮卡片/键盘收窄/分屏的容器宽 ≠ 屏幕宽），
    // 不读屏幕方向：宽容器（横屏全屏）用大边距，其余按竖屏形态
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= WIDE_CONTAINER_WIDTH

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                    .clickable {
                        if (reorderMode) {
                            // 排序态「完成」：退出排序并落定当前顺序（换位已实时写盘）
                            reorderMode = false
                            reorderDraggingIndex = null
                            reorderDragAccumY = 0f
                        } else {
                            onDismiss()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = if (reorderMode) "完成" else "确定",
                    tint = keyTextColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (reorderMode) {
                // 排序态：右侧提示（不显示预览行，避免与拖拽列表混淆）
                Text(
                    text = "长按已启用按钮拖动调整顺序",
                    fontSize = 13.sp,
                    color = keyTextColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val previewButtons = toolbarButtons.mapNotNull { itemById[it] }
                if (previewButtons.isNotEmpty()) {
                    previewButtons.forEach { button ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(iconButtonContainer)
                                // 点预览行的已启用按钮也进入排序态（与网格长按同一入口）
                                .clickable { enterReorderMode() },
                            contentAlignment = Alignment.Center
                        ) {
                            ToolbarButtonIcon(
                                item = button,
                                tint = keyTextColor.copy(0.6f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(keyBgColor)
                .padding(16.dp,10.dp)
        ) {
            if (reorderMode) {
                // ── 排序态：已启用按钮垂直列表，长按拖拽换位即写盘 ──
                // LazyColumn：已启用按钮多于一屏时可上下滑动查看（不裁掉列表尾部）
                val reorderButtons = reorderOrder.mapNotNull { itemById[it] }
                LazyColumn(
                    state = reorderListState,
                    modifier = Modifier
                        .fillMaxSize()
                        // 记录排序列表视口在窗口中的上下边界：拖拽到边缘时据此自动滚动
                        .onGloballyPositioned { coords ->
                            reorderListTop = coords.positionInWindow().y
                            reorderListBottom = coords.positionInWindow().y + coords.size.height
                        },
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        count = reorderButtons.size,
                        key = { reorderButtons[it].id },
                    ) { position ->
                        val button = reorderButtons[position]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (reorderDraggingIndex == position) accentColor.copy(alpha = 0.2f)
                                    else Color.Transparent
                                )
                                .pointerInput(button.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            // 实时按按钮 id 查位置，不能用闭包捕获的 position：
                                            // 换位后该值已陈旧，会导致「点这行却拖了另一行」
                                            val startPosition = reorderOrder.indexOf(button.id)
                                            reorderDraggingIndex = startPosition
                                            reorderDragAccumY = 0f
                                            // 记录拖动起始时手指相对排序列表容器窗口的 y
                                            val rowOffset =
                                                reorderListState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.index == startPosition }
                                                    ?.offset ?: 0
                                            reorderFingerWindowY = reorderListTop + rowOffset
                                        },
                                        onDragCancel = {
                                            reorderDraggingIndex = null
                                            reorderDragAccumY = 0f
                                            reorderFingerWindowY = Float.NaN
                                        },
                                        onDragEnd = {
                                            reorderDraggingIndex = null
                                            reorderDragAccumY = 0f
                                            reorderFingerWindowY = Float.NaN
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            // 更新手指相对容器窗口的 y：change.position 是相对本行的局部坐标，
                                            // 加上本行在视口中的 offset 即换算成容器窗口坐标（供边缘滚动帧循环读取）
                                            val from = reorderDraggingIndex ?: return@detectDragGesturesAfterLongPress
                                            val rowOffsetInViewport =
                                                reorderListState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.index == from }
                                                    ?.offset ?: 0
                                            reorderFingerWindowY = reorderListTop + rowOffsetInViewport + change.position.y
                                            // 手指位移换位（边缘滚动由上面的帧循环补推力）
                                            applyReorder(reorderDragAccumY + dragAmount.y)
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
                            ToolbarButtonIcon(
                                item = button,
                                tint = accentColor,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = button.label,
                                fontSize = 15.sp,
                                color = keyTextColor,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (reorderDraggingIndex == position) {
                                Text(
                                    text = "拖动中…",
                                    fontSize = 11.sp,
                                    color = accentColor,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                            }
                        }
                    }
                    if (reorderButtons.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(80.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "没有已启用的按钮",
                                    fontSize = 14.sp,
                                    color = keyTextColor.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            } else {
            // 纵向可滚动网格（4 个一行）：按钮多于一屏时上下滑动查看，
            // 不再横向翻页——横屏/小高度容器下翻页会把行裁掉导致「显示不全」
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    count = allButtons.size,
                    key = { allButtons[it].id },
                ) { index ->
                    val button = allButtons[index]
                    val isEnabled = button.id in enabledIds
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(
                                    if (isEnabled) accentColor.copy(0.2f)
                                    else Color.Transparent
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isEnabled) Color.Transparent
                                    else keyTextColor.copy(alpha = 0.15f),
                                    shape = CircleShape
                                )
                                // 长按已启用按钮进入排序态（未启用按钮长按无意义）
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { toggleButton(button) },
                                    onLongClick = {
                                        if (isEnabled) {
                                            enterReorderMode()
                                        }
                                    }
                                ),

                            contentAlignment = Alignment.Center
                        ) {
                            ToolbarButtonIcon(
                                item = button,
                                tint = if (isEnabled) accentColor else keyTextColor.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Text(
                            text = button.label,
                            fontSize = 10.sp,
                            color = keyTextColor.copy(alpha = 0.8f),
                            lineHeight = 1.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            }
        }
    }
    }
}
