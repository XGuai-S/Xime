package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.util.CapitalAmountExpression
import com.kingzcheung.xime.util.RmbCapitalFormatter
import com.kingzcheung.xime.util.SubcharHelper

/**
 * 九宫格数字键盘布局
 * 第1行：+ | 1 | 2 | 3 | 退格
 * 第2行：- | 4 | 5 | 6 | 符号切换
 * 第3行：* | 7 | 8 | 9 | 表情
 * 第4行：ABC | / | 0 | . | 确定
 *
 * [backKeyOnLeft] 为 true 时，左下角的「符号」键与数字区底行的「返回(ABC)」键互换位置：
 * 从全键盘 123 键（位于左下角）进入数字键盘时，返回键保持在用户的进入位置；
 * 默认 false 保持九键布局习惯（符号键在最左下角）。
 *
 * ## 大写金额模式
 *
 * **长按或上滑「0」键**进入/退出：键面数字转为大写（壹贰叁…），全部数字键的角标回显原数字。
 * 角标在两种模式下的范围不同：普通模式只有「0」键带角标「零」（模式开关的指示），
 * 大写模式全部数字键带角标（这串大写对应哪个数字的对照表）。
 * 输入期间每按一键都按人民币大写规范重写已上屏的金额，位值单位（拾/佰/仟/万/亿）
 * 与「零」的取舍、以及「元整/角分」全部由 [RmbCapitalFormatter] 自动补全，
 * 例如依次按 1 2 3 得到「壹元整」→「壹拾贰元整」→「壹佰贰拾叁元整」。
 *
 * 退格在累积期间回退一位并同步重写；累积已空时只把退格交回宿主，**不退出**该模式。
 * 空格同样照常上屏并作废累积基线（下一个数字作为一笔新金额），但**不退出**该模式。
 * 模式开关只由「0」键（长按/上滑）承担。
 * 其余按键（确定/符号/表情/返回等）会退出该模式，避免累积状态与输入框内容脱节。
 * [onReplaceCommittedText] 为 null 时该手势不生效（键盘仅渲染，无法安全重写文本）。
 *
 * ### 加减乘除
 *
 * 本模式下左列的 `+ - * /` 不被交回宿主的计算器，而是继续累积成算式，上屏的始终是
 * **算式当前值**的大写金额——按 1 2 3 + 4 5 得到「壹佰陆拾捌元整」。
 * 算式结构与取值规则见 [CapitalAmountExpression]；按下运算符本身不改变数值，
 * 屏幕要到下一个数字才体现结果。
 */
@Composable
fun NumberKeyboardLayout(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    backKeyOnLeft: Boolean = false,
    /** 大写金额模式重写已上屏文本；见 [KeyboardCallbacks.onReplaceCommittedText] */
    onReplaceCommittedText: ((expected: String, replacement: String) -> Boolean)? = null,
    /** 大写金额模式的候选栏回显（算式 + 大写金额）；见 [KeyboardCallbacks.onCapitalAmountDisplay] */
    onCapitalAmountDisplay: ((expression: String, amount: String) -> Unit)? = null,
    /** 「结束本次金额输入」信号（候选栏点选后自增）；
     *  见 [com.kingzcheung.xime.service.InputUIState.capitalAmountExitSignal] */
    capitalAmountExitSignal: Long = 0L,
) {

    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.screenWidthDp > configuration.screenHeightDp
    val commonSymbols = listOf(
        "~", "!", "#", "$", "%", "^", "&", "?",
        "(", ")", "_", "=", "[", "]", "{", "}",
        "\\", "|", ";", ":", "'", "\"", "<", ">"
    )

    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)

    // ── 大写金额模式 ──
    // 决策（按键 → 该上屏什么）全部在 CapitalAmountInputState 里，键盘只负责渲染与写入；
    // 单独成类是为了让逐键上屏序列能被单测直接跑（真机验证成本高且不可回归）。
    var capitalMode by remember { mutableStateOf(false) }
    val capitalInput = remember {
        CapitalAmountInputState(isActive = { capitalMode }, setActive = { capitalMode = it })
    }

    val replaceCommitted = onReplaceCommittedText

    // 候选栏回显：输入框里此刻是大写金额，用户看不见自己按了哪些数字，
    // 把算式挂到候选栏（`123+45 = 壹佰陆拾捌元整`）才看得见运算过程。
    // 每次按键/切模式后统一上报一次：模式开关、算式、金额都可能刚变化。
    val reportCapitalDisplay: () -> Unit = {
        val show = CapitalAmountKeys.shouldDisplay(capitalInput.committed, capitalMode)
        onCapitalAmountDisplay?.invoke(
            if (show) capitalInput.raw else "",
            if (show) capitalInput.committed else "",
        )
    }

    // 无重写能力时（预览/独立渲染）不开放该手势，避免进入一个"按了没反应"的模式
    val toggleCapital: (() -> Unit)? = if (replaceCommitted == null) null else ({
        capitalInput.toggle()
        reportCapitalDisplay()
    })

    val dispatchKey: (String) -> Unit = { key ->
        capitalInput.press(
            key = key,
            write = { expected, replacement -> replaceCommitted?.invoke(expected, replacement) ?: false },
            passthrough = onKeyPress,
        )
        reportCapitalDisplay()
    }

    // 键盘切走（换面板/收起）时本 Composable 被销毁，模式状态随之丢失；
    // 不回清会让候选栏留着一行对不上任何输入的陈旧算式。
    DisposableEffect(Unit) {
        onDispose {
            if (capitalMode) onCapitalAmountDisplay?.invoke("", "")
        }
    }

    // 候选栏点选后服务层自增该信号：这笔金额输完了，退出模式并作废累积，
    // 否则下一个数字会接在旧算式上继续算。
    // 与 t9ResetSignal 同款单向信号；记下已消费的值，避免首次组合就误退出。
    val consumedExitSignal = remember { mutableStateOf(capitalAmountExitSignal) }
    LaunchedEffect(capitalAmountExitSignal) {
        if (capitalAmountExitSignal != consumedExitSignal.value) {
            consumedExitSignal.value = capitalAmountExitSignal
            capitalInput.exit()
            reportCapitalDisplay()
        }
    }

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeBubble.state,
        keyBounds = swipeBubble.keyBounds,
        keyBackgroundColor = bubbleBackgroundColor,
        keyTextColor = keyTextColor,
        accentColor = specialKeyTextColor,
        keyWidth = if (swipeBubble.state.isSwiping || swipeBubble.state.isPressed) swipeBubble.keyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width
    )

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius) {
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                bubbleData?.let { drawSwipeBubble(it) }
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp)) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 2.dp, horizontal = 50.dp),
            ) {
                // 左侧：常用符号区（6列 × 4行）
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                ) {
                    CompositionLocalProvider(
                        LocalKeyVisualPadding provides PaddingValues(
                            horizontal = keySpacingX ?: 2.dp,
                            vertical = keySpacingY ?: 2.dp,
                        )
                    ) {
                    commonSymbols.chunked(6).forEach { rowSymbols ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            rowSymbols.forEach { sym ->
                                KeyButton(
                                    text = sym,
                                    onClick = { onKeyPress(sym) },
                                    backgroundColor = keyBackgroundColor,
                                    textColor = keyTextColor,
                                    modifier = Modifier.weight(1f),
                                    onPress = { onKeyPressDown?.invoke(sym) },
                                    shadowEnabled = shadowEnabled,
                                    shadowElevation = shadowElevation,
                                    shadowShapeRadius = shadowShapeRadius,
                                    fontSize = 14.sp,
                                )
                            }
                            repeat(6 - rowSymbols.size) {
                                Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    }
                }

                Spacer(modifier = Modifier.weight(0.16f))

                // 右侧：数字键盘（与竖屏完全一致）
                Box(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                ) {
                    CompositionLocalProvider(
                        LocalKeyVisualPadding provides PaddingValues(
                            horizontal = keySpacingX ?: 2.dp,
                            vertical = keySpacingY ?: 2.dp,
                        )
                    ) {
                    NumberRows(
                        onKeyPress = dispatchKey,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        compactMode = true,
                        specialKeyTextColor = specialKeyTextColor,
                        backKeyOnLeft = backKeyOnLeft,
                        capitalMode = capitalMode,
                        onToggleCapital = toggleCapital,
                        onSwipeStateChange = { state, bounds -> processKeySwipeState(state, bounds, keyboardBounds, swipeBubble) },
                    )
                    }
                }
            }
        } else {
            // 竖屏：原有布局
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            ) {
                NumberRows(
                    onKeyPress = dispatchKey,
                    keyBackgroundColor = keyBackgroundColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBackgroundColor,
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    onKeyPressDown = onKeyPressDown,
                    specialKeyTextColor = specialKeyTextColor,
                    backKeyOnLeft = backKeyOnLeft,
                    capitalMode = capitalMode,
                    onToggleCapital = toggleCapital,
                    onSwipeStateChange = { state, bounds -> processKeySwipeState(state, bounds, keyboardBounds, swipeBubble) },
                )
            }
            }
        }

    }
    }
}

@Composable
private fun NumberRows(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    onKeyPressDown: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    compactMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    backKeyOnLeft: Boolean = false,
    /** 大写金额模式：键面转大写、角标回显原数字 */
    capitalMode: Boolean = false,
    /** 长按/上滑「0」键切换大写金额模式；null 表示不开放该手势 */
    onToggleCapital: (() -> Unit)? = null,
) {
    val symFontSize = if (compactMode) 14.sp else 18.sp
    val keyFontSize = if (compactMode) 16.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val ctrlFontSize = if (compactMode) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val suppressCursorMove = LocalSuppressCursorMove.current
    val symbols = listOf("+", "-", "*", "/")
    Row(
        modifier = Modifier
            .fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .weight(0.8f),
            verticalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .weight(3f),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Column(
                    modifier = Modifier
//                        .padding(vertical = 2.dp)
                        .fillMaxHeight()
                        .weight(0.8f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(3f)
                            .padding(LocalKeyVisualPadding.current),
                    ) {
                        symbols.forEach { symbol ->
                            NumberSymbolKey(
                                text = symbol,
                                onClick = { onKeyPress(symbol) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke(symbol) },
                                isFirst = symbol == "+",
                                isLast = symbol == "/",
                                fontSize = symFontSize,
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f),
                    ) {
                        if (backKeyOnLeft) {
                            IconKeyButton(
                                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack),
                                onClick = { onKeyPress("abc") },
                                backgroundColor = specialKeyBackgroundColor,
                                iconColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                a11yDescription = "返回字母键盘",
                                onPress = { onKeyPressDown?.invoke("abc") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        } else {
                            KeyButton(
                                text = "符号",
                                onClick = { onKeyPress("symbol") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke("symbol") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = ctrlFontSize,
                            )
                        }
                    }

                }

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(3.4f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        listOf("1", "2", "3").forEach { key ->
                            KeyButton(
                                text = CapitalAmountKeys.face(key, capitalMode),
                                onClick = { onKeyPress(key) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                onPress = { onKeyPressDown?.invoke(key) },
                                badgeText = CapitalAmountKeys.badge(key, capitalMode),
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                modifier = Modifier
                                    .weight(1f),
                                fontSize = keyFontSize,
                            )
                        }

                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {

                        listOf("4", "5", "6").forEach { key ->
                            KeyButton(
                                text = CapitalAmountKeys.face(key, capitalMode),
                                onClick = { onKeyPress(key) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                onPress = { onKeyPressDown?.invoke(key) },
                                badgeText = CapitalAmountKeys.badge(key, capitalMode),
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                modifier = Modifier
                                    .weight(1f),
                                fontSize = keyFontSize,
                            )
                        }

                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {

                        listOf("7", "8", "9").forEach { key ->
                            KeyButton(
                                text = CapitalAmountKeys.face(key, capitalMode),
                                onClick = { onKeyPress(key) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                modifier = Modifier
                                    .weight(1f),
                                onPress = { onKeyPressDown?.invoke(key) },
                                badgeText = CapitalAmountKeys.badge(key, capitalMode),
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = keyFontSize,
                            )
                        }

                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {


                        if (backKeyOnLeft) {
                            KeyButton(
                                text = "符号",
                                onClick = { onKeyPress("symbol") },
                                backgroundColor = specialKeyBackgroundColor,
                                textColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                onPress = { onKeyPressDown?.invoke("symbol") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                                fontSize = ctrlFontSize,
                            )
                        } else {
                            IconKeyButton(
                                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack),
                                onClick = { onKeyPress("abc") },
                                backgroundColor = specialKeyBackgroundColor,
                                iconColor = specialKeyTextColor,
                                modifier = Modifier.weight(1f),
                                a11yDescription = "返回字母键盘",
                                onPress = { onKeyPressDown?.invoke("abc") },
                                shadowEnabled = shadowEnabled,
                                shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius,
                            )
                        }
                        // 「0」键承载大写金额模式的进入手势（长按 / 上滑），角标「零」即该手势的提示
                        val isZeroToggleKey =
                            CapitalAmountKeys.TOGGLE_DIGIT == "0" && onToggleCapital != null
                        KeyButton(
                            text = CapitalAmountKeys.face("0", capitalMode),
                            onClick = { onKeyPress("0") },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke("0") },
                            badgeText = CapitalAmountKeys.badge("0", capitalMode),
                            onLongClick = if (isZeroToggleKey) onToggleCapital else null,
                            // 上滑目标与角标同字：KeyButton 据此只画角标、不重复画上滑提示，
                            // 上滑触发与手势本身不受影响（与九键/笔画的数字浮标同一处理）
                            swipeText = if (isZeroToggleKey) CapitalAmountKeys.badge("0", capitalMode) else null,
                            onSwipe = if (isZeroToggleKey) {
                                { _: String -> onToggleCapital() }
                            } else {
                                null
                            },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            fontSize = keyFontSize,
                        )
                        KeyButton(
                            text = ".",
                            onClick = { onKeyPress(".") },
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f),
                            onPress = { onKeyPressDown?.invoke(".") },
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            fontSize = keyFontSize,
                        )

                    }

                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .weight(0.8f),
                ) {
                    SwipeableIconKeyButton(
                        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                        onClick = { onKeyPress("delete") },
                        backgroundColor = specialKeyBackgroundColor,
                        iconColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f),
                        a11yDescription = "退格",
                        swipeText = "清空",
                        onSwipe = { onKeyPress("clear_composition") },
                        onLongClick = { onKeyPress("delete") },
                        onPress = { onKeyPressDown?.invoke("delete") },
                        swipeUpLabel = "上滑清空",
                        swipeDownLabel = "下滑撤回",
                        onSwipeUp = { onKeyPress("clear_all") },
                        onSwipeDown = { onKeyPress("undo_clear") },
                        onSwipeLeft = {
                            suppressCursorMove.value = true
                            onKeyPress("clear_composition")
                        },
                        onSwipeStateChange = onSwipeStateChange,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )

                    KeyButton(
                        text = "空格",
                        onClick = { onKeyPress("space") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("space") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = ctrlFontSize,
                    )
                    IconKeyButton(
                        icon = rememberVectorPainter(Icons.Default.EmojiEmotions),
                        onClick = { onKeyPress("emoji") },
                        backgroundColor = specialKeyBackgroundColor,
                        iconColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f),
                        a11yDescription = "表情",
                        onPress = { onKeyPressDown?.invoke("emoji") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                    KeyButton(
                        text = "确定",
                        onClick = { onKeyPress("enter") },
                        backgroundColor = specialKeyBackgroundColor,
                        textColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f),
                        onPress = { onKeyPressDown?.invoke("enter") },
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        fontSize = ctrlFontSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberSymbolKey(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 18.sp,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val cornerRadius = LocalKeyCornerRadius.current
    val shape = RoundedCornerShape(
        topStart = if (isFirst) cornerRadius else 0.dp,
        topEnd = if (isFirst) cornerRadius else 0.dp,
        bottomStart = if (isLast) cornerRadius else 0.dp,
        bottomEnd = if (isLast) cornerRadius else 0.dp
    )
    val shadowModifier = rememberCrispShadowModifier(backgroundColor, shadowEnabled, shadowElevation, shadowShapeRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
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
            }, contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(vertical = 2.dp),
            fontFamily = AppFonts.keyFontFamily
        )
    }
}
