package com.kingzcheung.xime.ai.ime.chrome

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ai.ime.ImeLogic
import com.kingzcheung.xime.ai.ime.LocalKeyboardMetrics
import com.kingzcheung.xime.ai.ime.VoicePhase
import com.kingzcheung.xime.ai.ui.components.TypeFreeIcons
import com.kingzcheung.xime.ai.ui.theme.LocalTypeFreeKeyboardPalette
import com.kingzcheung.xime.ai.ui.theme.TypeFreeAmber
import com.kingzcheung.xime.ai.ui.theme.TypeFreeEmerald
import com.kingzcheung.xime.ai.ui.theme.TypeFreeRose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * 只在需要反馈时才创建无限动画时钟。
 *
 * 空闲面板若常驻 rememberInfiniteTransition，即使动画值没有被使用，Compose 仍会
 * 持续推进时钟并触发重绘；这里 inactive 时直接返回静态值，不创建动画节点。
 */
@Composable
internal fun rememberActivePulse(
    active: Boolean,
    label: String,
    initialValue: Float = 1f,
    targetValue: Float,
    durationMillis: Int,
): Float {
    if (!active) return initialValue
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = label,
    ).value
}

@Composable
fun SubPanelHeaderBridgeBar(
    isFlypyChineseMode: Boolean,
    inputSchemeShortBadge: String = "鹤",
    hasFreshClipboard: Boolean,
    onSwitchToQwerty: () -> Unit,
    onOpenClipboard: () -> Unit,
    closeDescription: String,
    onClose: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1-Tap Jump to Built-in QWERTY (`拼/鹤 打字` / `EN 打字`) to type directly over selection
        Box(
            modifier = Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(palette.keyCapBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(13.dp))
                .clickable(onClick = onSwitchToQwerty)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isFlypyChineseMode) "$inputSchemeShortBadge 打字" else "EN 打字",
                style = androidx.compose.ui.text.TextStyle(
                    color = palette.keyTextPrimary,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1,
                softWrap = false
            )
        }

        // 1-Tap Jump to Built-in Clipboard to paste over selection
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(palette.keyCapBg)
                .border(1.dp, palette.keyBorder, CircleShape)
                .clickable(onClick = onOpenClipboard),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = TypeFreeIcons.Clipboard,
                contentDescription = "剪贴板",
                tint = palette.keyTextPrimary,
                modifier = Modifier.size(13.5.dp)
            )
            if (hasFreshClipboard) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.5.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(TypeFreeEmerald)
                )
            }
        }

        // Dismiss Sub-Panel (`×`)
        CircleFloatingKey(
            size = 26.dp,
            onClick = onClose
        ) {
            Icon(
                imageVector = TypeFreeIcons.Close,
                contentDescription = closeDescription,
                tint = palette.keyTextPrimary,
                modifier = Modifier.size(13.5.dp)
            )
        }
    }
}

/**
 * Monochrome `28.dp` segmented mode switcher `[ 帮写 | ✦ 军师 ]` for top-left of
 * `HelpMeWritePanel` and `JevCopilotPanel`.
 */
@Composable
fun SubPanelModeSwitchTrack(
    isJevActive: Boolean,
    jevEnabled: Boolean,
    onSelectHelpMeWrite: () -> Unit,
    onSelectJevCopilot: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    if (!jevEnabled) {
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.primaryMicSolid)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.5.dp)
        ) {
            Icon(
                imageVector = TypeFreeIcons.VoicePen,
                contentDescription = "帮写",
                tint = palette.onPrimaryMic,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = "帮写",
                color = palette.onPrimaryMic,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.capsuleTrackBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(14.dp))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Segment 1: 帮写
        val writeActive = !isJevActive
        Row(
            modifier = Modifier
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (writeActive) palette.primaryMicSolid else Color.Transparent)
                .clickable(onClick = onSelectHelpMeWrite)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = TypeFreeIcons.VoicePen,
                contentDescription = "帮写",
                tint = if (writeActive) palette.onPrimaryMic else palette.keyTextMuted,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = "帮写",
                color = if (writeActive) palette.onPrimaryMic else palette.keyTextMuted,
                fontSize = 10.5.sp,
                fontWeight = if (writeActive) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1
            )
        }

        // Segment 2: ✦ 军师
        Row(
            modifier = Modifier
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isJevActive) palette.primaryMicSolid else Color.Transparent)
                .clickable(onClick = onSelectJevCopilot)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = TypeFreeIcons.CopilotStar,
                contentDescription = "军师",
                tint = if (isJevActive) palette.onPrimaryMic else palette.keyTextMuted,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = "军师",
                color = if (isJevActive) palette.onPrimaryMic else palette.keyTextMuted,
                fontSize = 10.5.sp,
                fontWeight = if (isJevActive) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun SubPanelVoiceTriggerRow(
    phase: VoicePhase,
    amplitudeProvider: () -> Float = { 0f },
    amplitudes: List<Float> = emptyList(),
    elapsedMs: Long,
    idleTitle: String,
    idleSubtitle: String,
    recordingTitle: String,
    directActionLabel: String? = null,
    onDirectActionClick: () -> Unit = {},
    onStartRecording: () -> Unit,
    onReleaseRecording: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val isRecording = phase is VoicePhase.Recording
    val isProcessing = phase is VoicePhase.Processing

    val currentPhaseState by rememberUpdatedState(phase)
    val currentOnStart by rememberUpdatedState(onStartRecording)
    val currentOnRelease by rememberUpdatedState(onReleaseRecording)
    val currentOnFinish by rememberUpdatedState(onFinishHandsFree)

    val haloScale = rememberActivePulse(
        active = isRecording,
        label = "subPanelPulse",
        initialValue = 1.0f,
        targetValue = 1.16f,
        durationMillis = 1000,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Pulsing Halo + Circular Dark Voice Button
        Box(
            modifier = Modifier
                .size((64f * s).dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val initial = currentPhaseState
                        if (initial is VoicePhase.Processing) return@awaitEachGesture

                        val startedFromHandsFree =
                            initial is VoicePhase.Recording && initial.isHandsFree
                        if (!startedFromHandsFree) {
                            currentOnStart()
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) {
                                event.changes.forEach { it.consume() }
                                break
                            }
                        }

                        if (startedFromHandsFree) {
                            currentOnFinish()
                        } else {
                            currentOnRelease()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size((58f * s).dp)
                    .scale(if (isRecording) haloScale else 1.03f)
                    .clip(CircleShape)
                    .background(palette.pulseHaloColor.copy(alpha = if (isRecording) 0.72f else 0.45f))
            )

            Box(
                modifier = Modifier
                    .size((46f * s).dp)
                    .clip(CircleShape)
                    .background(palette.primaryMicBrush),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isRecording -> {
                        SymmetricalSevenBarWaveform(
                            amplitudeProvider = amplitudeProvider,
                            amplitudes = amplitudes,
                            color = palette.onPrimaryMic,
                            modifier = Modifier.size(width = (26f * s).dp, height = (20f * s).dp)
                        )
                    }
                    isProcessing -> {
                        CircularProgressIndicator(
                            color = palette.onPrimaryMic,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size((20f * s).dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = TypeFreeIcons.VoiceWaveform,
                            contentDescription = idleTitle,
                            tint = palette.onPrimaryMic,
                            modifier = Modifier.size((22f * s).dp)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = when {
                    isRecording -> "$recordingTitle · ${ImeLogic.formatElapsedTimer(elapsedMs)}"
                    phase is VoicePhase.Processing -> phase.stageText
                    else -> idleTitle
                },
                color = palette.keyTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    phase is VoicePhase.Recording && phase.isHandsFree ->
                        "Tap again to finish · 轻点完成"
                    isRecording -> "松手立即执行 · 轻点保持免提"
                    isProcessing -> "请稍候，正在生成..."
                    else -> idleSubtitle
                },
                color = palette.keyTextMuted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isRecording || isProcessing) {
            CircleFloatingKey(
                size = (32f * s).dp,
                onClick = onCancelRecording
            ) {
                Icon(
                    imageVector = TypeFreeIcons.Close,
                    contentDescription = "取消",
                    tint = palette.keyTextPrimary,
                    modifier = Modifier.size((15f * s).dp)
                )
            }
        } else if (!directActionLabel.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .height((34f * s).dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(TypeFreeEmerald)
                    .clickable(onClick = onDirectActionClick)
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = directActionLabel,
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 7-Bar symmetrical waveform reading [amplitudeProvider] lazily inside [Canvas] DrawScope
 * so high-frequency microphone amplitude updates do not trigger parent Composable recompositions.
 */
@Composable
fun SymmetricalSevenBarWaveform(
    amplitudeProvider: () -> Float = { 0f },
    amplitudes: List<Float> = emptyList(),
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sevenBarWave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    // 7 symmetrical envelope weights (`· | ||| | ·`)
    val envelope = remember { floatArrayOf(0.18f, 0.48f, 0.78f, 1.0f, 0.78f, 0.48f, 0.18f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val barCount = 7
        val barWidth = (w / 13.5f).coerceAtLeast(2.2f)
        val totalBarsWidth = barWidth * barCount
        val gap = ((w - totalBarsWidth) / (barCount - 1)).coerceAtLeast(1.5f)
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

        val currentAmp = amplitudeProvider().coerceIn(0f, 1f)
        val liveEnergy = if (currentAmp > 0f) {
            currentAmp.coerceIn(0.08f, 1.0f)
        } else {
            amplitudes.takeLast(4)
                .let { tail -> if (tail.isEmpty()) 0.12f else tail.average().toFloat() }
                .coerceIn(0.08f, 1.0f)
        }

        for (i in 0 until barCount) {
            val mirrorIdx = if (i <= 3) i else 6 - i
            val sampleAmp = amplitudes.getOrElse((amplitudes.size - 1 - mirrorIdx).coerceAtLeast(0)) {
                liveEnergy
            }.coerceIn(0.08f, 1.0f)

            val breathing = 0.16f * ((sin(wavePhase + mirrorIdx * 0.9f) + 1f) * 0.5f)
            val combined = (0.24f + sampleAmp * 0.76f + breathing).coerceIn(0.16f, 1.0f)
            val targetRatio = (envelope[i] * combined).coerceIn(0.14f, 0.98f)

            val barHeight = (h * targetRatio).coerceAtLeast(barWidth)
            val x = i * (barWidth + gap)
            val y = (h - barHeight) / 2f

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = corner
            )
        }
    }
}

@Composable
fun CircleFloatingKey(
    size: Dp,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bg = when {
        highlighted -> palette.primaryMicSolid
        isPressed -> palette.keyCapSecondaryBg
        else -> palette.keyCapBg
    }

    Box(
        modifier = Modifier
            .size(size)
            .scale(if (isPressed) 0.93f else 1f)
            .clip(CircleShape)
            .background(bg)
            .border(
                width = 1.dp,
                color = if (highlighted) Color.Transparent else palette.keyBorder,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun RepeatingCircleBackspaceKey(
    size: Dp,
    onBackspace: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val currentOnBackspace by rememberUpdatedState(onBackspace)
    var isHolding by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size)
            .scale(if (isHolding) 0.93f else 1f)
            .clip(CircleShape)
            .background(if (isHolding) palette.keyCapSecondaryBg else palette.keyCapBg)
            .border(1.dp, palette.keyBorder, CircleShape)
            .pointerInput(Unit) {
                coroutineScope {
                    val outerScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isHolding = true
                        currentOnBackspace()
                        val repeatJob = outerScope.launch {
                            delay(360L)
                            while (isActive) {
                                currentOnBackspace()
                                delay(50L)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.all { !it.pressed }) {
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                            }
                        } finally {
                            isHolding = false
                            repeatJob.cancel()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = TypeFreeIcons.Backspace,
            contentDescription = "退格删除",
            tint = palette.keyTextPrimary,
            modifier = Modifier.size(19.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomCornerUtilityIcons(
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Bottom-Left: Dismiss Keyboard (`TypeFreeIcons.ChevronDown` -> `requestHideSelf(0)`)
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 26.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onHideKeyboard),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                imageVector = TypeFreeIcons.ChevronDown,
                contentDescription = "收起键盘",
                tint = palette.keyTextPrimary.copy(alpha = 0.78f),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(18.dp)
            )
        }

        // Bottom-Right: Switch IME / System IME Picker (`TypeFreeIcons.KeyboardGrid`)
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 26.dp)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = onSwitchKeyboard,
                    onLongClick = onShowImePicker
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                imageVector = TypeFreeIcons.KeyboardGrid,
                contentDescription = "切换输入法",
                tint = palette.keyTextPrimary.copy(alpha = 0.78f),
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(18.dp)
            )
        }
    }
}

@Composable
fun ErrorContextBanner(
    error: VoicePhase.Error,
    onRetry: () -> Unit,
    onOpenApiSetup: () -> Unit,
    onOpenPermissionSetup: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (palette.isDark) Color(0xFF2A141D) else Color(0xFFFFF1F2)
            )
            .border(1.dp, TypeFreeRose.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "⚠ ${error.message}",
            color = if (palette.isDark) Color(0xFFFECDD3) else Color(0xFF9F1239),
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (error.needsMicPermission) {
            BannerActionChip(
                label = "去授权",
                color = TypeFreeAmber,
                onClick = onOpenPermissionSetup
            )
        }
        if (error.needsApiSetup) {
            BannerActionChip(
                label = "配置 API",
                color = palette.primaryMicSolid,
                textColor = palette.onPrimaryMic,
                onClick = onOpenApiSetup
            )
        }
        if (error.canRetryAudio) {
            BannerActionChip(
                label = "重试",
                color = TypeFreeEmerald,
                onClick = onRetry
            )
        }

        Icon(
            imageVector = TypeFreeIcons.Close,
            contentDescription = "关闭提示",
            tint = palette.keyTextMuted,
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(2.dp)
        )
    }
}

@Composable
private fun BannerActionChip(
    label: String,
    color: Color,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
