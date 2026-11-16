package com.kingzcheung.xime.ai.panels

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.DefaultPresets
import com.kingzcheung.xime.ai.data.VoiceIntentMode
import com.kingzcheung.xime.ai.ime.ImeLogic
import com.kingzcheung.xime.ai.ime.ImeUiState
import com.kingzcheung.xime.ai.ime.KeyboardTabMode
import com.kingzcheung.xime.ai.ime.LiveWriteUiStatus
import com.kingzcheung.xime.ai.ime.LocalKeyboardMetrics
import com.kingzcheung.xime.ai.ime.VoicePhase
import com.kingzcheung.xime.ai.ime.chrome.BottomCornerUtilityIcons
import com.kingzcheung.xime.ai.ime.chrome.CircleFloatingKey
import com.kingzcheung.xime.ai.ime.chrome.RepeatingCircleBackspaceKey
import com.kingzcheung.xime.ai.ime.chrome.SymmetricalSevenBarWaveform
import com.kingzcheung.xime.ai.ui.components.TypeFreeIcons
import com.kingzcheung.xime.ai.ui.theme.LocalTypeFreeKeyboardPalette
import com.kingzcheung.xime.ai.ui.theme.TypeFreeEmerald
import com.kingzcheung.xime.ai.ui.theme.TypeFreeRose

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceMainPanel(
    uiState: ImeUiState,
    settings: AppSettings,
    amplitudeProvider: () -> Float = { 0f },
    amplitudes: List<Float> = emptyList(),
    elapsedMs: Long,
    didPauseMedia: Boolean,
    onSwipeToTab: (KeyboardTabMode) -> Unit,
    onVoicePenClick: () -> Unit,
    onOpenJevCopilot: () -> Unit = {},
    onMicPressDown: () -> Unit,
    onMicPressUp: () -> Unit,
    onEnterArcTranslate: (Boolean) -> Unit,
    onUpdateArcTranslate: (String, Boolean) -> Unit,
    onReleaseArcTranslate: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onTogglePunctuationStrip: () -> Unit,
    onCommitText: (String) -> Unit,
    onInsertPairedSymbol: (String, String) -> Unit,
    onBackspace: () -> Unit,
    onEnterOrNewline: () -> Unit,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val s = metrics.elementScale
    val density = LocalDensity.current
    val phase = uiState.voicePhase
    val isRecording = phase is VoicePhase.Recording
    val isProcessing = phase is VoicePhase.Processing

    val swipeThresholdPx = with(density) { 52.dp.toPx() }
    val arcTriggerPx = with(density) { ImeLogic.ARC_TRANSLATE_TRIGGER_DP.dp.toPx() }
    val arcStepPx = with(density) { ImeLogic.ARC_WHEEL_STEP_DP.dp.toPx() }
    val arcCancelPx = with(density) { ImeLogic.ARC_CANCEL_THRESHOLD_DP.dp.toPx() }

    val currentPhaseState by rememberUpdatedState(phase)
    val currentSettingsState by rememberUpdatedState(settings)
    val currentOnSwipeToTab by rememberUpdatedState(onSwipeToTab)
    val currentOnMicDown by rememberUpdatedState(onMicPressDown)
    val currentOnMicUp by rememberUpdatedState(onMicPressUp)
    val currentOnEnterArc by rememberUpdatedState(onEnterArcTranslate)
    val currentOnUpdateArc by rememberUpdatedState(onUpdateArcTranslate)
    val currentOnReleaseArc by rememberUpdatedState(onReleaseArcTranslate)
    val currentOnFinishHandsFree by rememberUpdatedState(onFinishHandsFree)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Horizontal swipe anywhere on the Voice canvas switches VOICE <-> QWERTY
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val start = down.position
                    var last = start
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        if (!change.pressed) break
                    }
                    if (currentPhaseState !is VoicePhase.Recording &&
                        currentPhaseState !is VoicePhase.Processing
                    ) {
                        val next = ImeLogic.resolveNextTabByHorizontalSwipe(
                            currentTab = KeyboardTabMode.VOICE,
                            dragOffsetX = last.x - start.x,
                            dragOffsetY = last.y - start.y,
                            thresholdPx = swipeThresholdPx
                        )
                        if (next != null) {
                            currentOnSwipeToTab(next)
                        }
                    }
                }
            }
    ) {
        // 1. Top Subtitle (`Tap to speak · 轻点说话 / 上滑快译` or Toast / Timer / LiveWrite status)
        val activeTranslateLabel = uiState.arcSelectedLanguage.ifBlank { settings.activeTranslateTarget }
        val isTranslateRecording = phase is VoicePhase.Recording &&
            uiState.activeIntentMode == VoiceIntentMode.TRANSLATE &&
            activeTranslateLabel.isNotBlank()
        val topSubtitle = when (phase) {
            is VoicePhase.SuccessToast -> {
                ImeLogic.formatSuccessToastSubtitle(phase.latencyMs)
            }
            is VoicePhase.Recording -> {
                val timerText = ImeLogic.formatElapsedTimer(elapsedMs)
                val livePreview = uiState.streamingPartialPreview.trim()
                val recBadges = buildString {
                    if (didPauseMedia) append("外放已静音 · ")
                    if (settings.whisperModeEnabled) append("轻声 · ")
                }
                when {
                    uiState.liveWriteStatus == LiveWriteUiStatus.FROZEN ->
                        "已暂停写入 · 松手后在光标处上屏"
                    uiState.liveWriteStatus == LiveWriteUiStatus.WRITING ->
                        "$timerText · 正在写入输入框"
                    isTranslateRecording && livePreview.isNotEmpty() ->
                        "$activeTranslateLabel · ${livePreview.takeLast(18)}"
                    isTranslateRecording ->
                        "${recBadges}正在聆听 → $activeTranslateLabel · $timerText"
                    livePreview.isNotEmpty() ->
                        "$recBadges$timerText · ${livePreview.takeLast(20)}"
                    else ->
                        "$recBadges$timerText"
                }
            }
            is VoicePhase.Processing -> phase.stageText
            else -> if (settings.whisperModeEnabled) {
                "轻声耳语高敏模式 · 靠近麦克风轻声说"
            } else {
                "Tap to speak · 轻点说话 / 上滑快译"
            }
        }

        val isTopOverlayExpanded = uiState.isStylePickerExpanded || phase is VoicePhase.Error
        if (!isTopOverlayExpanded) {
            Text(
                text = topSubtitle,
                color = when {
                    phase is VoicePhase.SuccessToast -> TypeFreeEmerald
                    isTranslateRecording -> TypeFreeEmerald
                    phase is VoicePhase.Recording && uiState.liveWriteStatus == LiveWriteUiStatus.WRITING -> TypeFreeEmerald
                    else -> palette.keyTextMuted
                },
                fontSize = 12.5.sp,
                fontWeight = if (phase is VoicePhase.Recording || phase is VoicePhase.SuccessToast) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Normal
                },
                fontFamily = if (phase is VoicePhase.Recording) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = (4f * s).dp)
            )
        }

        // 2. Stationary Gesture Anchor for Center Primary Mic Capsule <-> 84.dp Recording Circle
        val micButtonWidth by animateDpAsState(
            targetValue = ((if (isRecording || isProcessing) 84f else 142f) * s).dp,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "micWidth"
        )
        val micButtonHeight by animateDpAsState(
            targetValue = ((if (isRecording || isProcessing) 84f else 56f) * s).dp,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "micHeight"
        )
        val micCornerRadius by animateDpAsState(
            targetValue = ((if (isRecording || isProcessing) 42f else 28f) * s).dp,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "micCorner"
        )

        val infiniteTransition = rememberInfiniteTransition(label = "haloPulse")
        val haloScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 920, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "haloScale"
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = (16f * s).dp)
                .size(width = (160f * s).dp, height = (96f * s).dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        val initialPhase = currentPhaseState
                        if (initialPhase is VoicePhase.Processing) {
                            return@awaitEachGesture
                        }

                        val startedFromHandsFree =
                            initialPhase is VoicePhase.Recording && initialPhase.isHandsFree
                        if (!startedFromHandsFree) {
                            currentOnMicDown()
                        }

                        val startPos = down.position
                        var inArcMode = false
                        val targets = currentSettingsState.translationTargets.ifEmpty {
                            DefaultPresets.defaultTranslationTargets()
                        }
                        val initialTargetIdx = targets.indexOf(currentSettingsState.activeTranslateTarget)
                            .let { if (it >= 0) it else 0 }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pointerChange = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break

                            val dx = pointerChange.position.x - startPos.x
                            val dy = pointerChange.position.y - startPos.y

                            if (!inArcMode && ImeLogic.shouldEnterArcTranslate(dy, arcTriggerPx)) {
                                inArcMode = true
                                currentOnEnterArc(startedFromHandsFree)
                            }

                            if (inArcMode) {
                                val selectedIdx = ImeLogic.computeArcWheelIndex(
                                    initialIndex = initialTargetIdx,
                                    targetCount = targets.size,
                                    dragOffsetX = dx,
                                    stepPx = arcStepPx
                                )
                                val cancelHovered = ImeLogic.isInArcCancelZone(dy, arcCancelPx)
                                currentOnUpdateArc(targets[selectedIdx], cancelHovered)
                            }

                            if (event.changes.all { !it.pressed }) {
                                event.changes.forEach { it.consume() }
                                break
                            }
                        }

                        when {
                            inArcMode -> currentOnReleaseArc()
                            startedFromHandsFree -> currentOnFinishHandsFree()
                            else -> currentOnMicUp()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Outer breathing halo when recording
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size((88f * s).dp)
                        .scale(haloScale)
                        .clip(CircleShape)
                        .background(palette.pulseHaloColor.copy(alpha = 0.65f))
                )
            }

            // Morphing Primary Pill / 84.dp Charcoal Circle
            Box(
                modifier = Modifier
                    .size(width = micButtonWidth, height = micButtonHeight)
                    .clip(RoundedCornerShape(micCornerRadius))
                    .background(palette.primaryMicBrush),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isRecording -> {
                        SymmetricalSevenBarWaveform(
                            amplitudeProvider = amplitudeProvider,
                            amplitudes = amplitudes,
                            color = palette.onPrimaryMic,
                            modifier = Modifier.size(width = (44f * s).dp, height = (32f * s).dp)
                        )
                    }
                    isProcessing -> {
                        CircularProgressIndicator(
                            color = palette.onPrimaryMic,
                            strokeWidth = 2.4.dp,
                            modifier = Modifier.size((26f * s).dp)
                        )
                    }
                    else -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Mic,
                                contentDescription = "轻点说话",
                                tint = palette.onPrimaryMic,
                                modifier = Modifier.size((26f * s).dp)
                            )
                            if (settings.whisperModeEnabled) {
                                Icon(
                                    imageVector = TypeFreeIcons.WhisperWave,
                                    contentDescription = "轻声模式已开启",
                                    tint = TypeFreeEmerald,
                                    modifier = Modifier.size((15f * s).dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Recording / Processing Bottom Status & Cancel Action OR Idle TypeFree Key Cluster
        if (isRecording || isProcessing) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (28f * s).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (phase is VoicePhase.Recording && phase.isHandsFree) {
                    val finishLabel = if (uiState.activeIntentMode == VoiceIntentMode.TRANSLATE) {
                        "✓ 说完点此直译"
                    } else {
                        "✓ 说完轻点完成"
                    }
                    Box(
                        modifier = Modifier
                            .height((34f * s).dp)
                            .clip(RoundedCornerShape((17f * s).dp))
                            .background(palette.primaryMicSolid)
                            .clickable(onClick = onFinishHandsFree)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = finishLabel,
                            color = palette.onPrimaryMic,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        text = when {
                            phase is VoicePhase.Recording ->
                                "松手完成 · 上滑选语言快译"
                            else -> "正在处理语音..."
                        },
                        color = palette.keyTextMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                CircleFloatingKey(
                    size = (34f * s).dp,
                    onClick = onCancelRecording
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Close,
                        contentDescription = "取消录音",
                        tint = palette.keyTextPrimary,
                        modifier = Modifier.size((16f * s).dp)
                    )
                }
            }
        } else {
            // Optional Quick Punctuation Floating Strip (toggled by `@` circle key)
            if (uiState.isPunctuationStripOpen) {
                FloatingQuickPunctuationStrip(
                    onCommitText = onCommitText,
                    onInsertPairedSymbol = onInsertPairedSymbol,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = (76f * s).dp, end = (52f * s).dp)
                )
            }

            // Bottom-Left Single Circular White Key (`44.dp`, `VoicePen` — top bar already hosts `[✦ 军师]`)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = (26f * s).dp)
            ) {
                CircleFloatingKey(
                    size = (44f * s).dp,
                    highlighted = uiState.hasIgnoredSelection,
                    onClick = onVoicePenClick
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.VoicePen,
                        contentDescription = "帮我写 / 语音编辑",
                        tint = if (uiState.hasIgnoredSelection) palette.onPrimaryMic else palette.keyTextPrimary,
                        modifier = Modifier.size((19f * s).dp)
                    )
                }
            }

            // Bottom-Center Stadium White Key (`104.dp × 42.dp`, adaptive Enter/Action label)
            val enterInfo = uiState.enterActionInfo
            val enterInteraction = remember { MutableInteractionSource() }
            val enterPressed by enterInteraction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (26f * s).dp)
                    .size(width = (104f * s).dp, height = (42f * s).dp)
                    .scale(if (enterPressed) 0.95f else 1f)
                    .clip(RoundedCornerShape((21f * s).dp))
                    .background(palette.keyCapBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape((21f * s).dp))
                    .clickable(
                        interactionSource = enterInteraction,
                        indication = null,
                        onClick = onEnterOrNewline
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = enterInfo.label,
                    color = palette.keyTextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            // Right Vertically Stacked Dual Circular White Keys (`42.dp` Backspace + `42.dp` `@`)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = (26f * s).dp),
                verticalArrangement = Arrangement.spacedBy((6f * s).dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Upper Circle: Repeating Backspace (`42.dp`)
                RepeatingCircleBackspaceKey(
                    size = (42f * s).dp,
                    onBackspace = onBackspace
                )

                // Lower Circle: `@` Quick Punctuation Toggle (`42.dp`)
                CircleFloatingKey(
                    size = (42f * s).dp,
                    highlighted = uiState.isPunctuationStripOpen,
                    onClick = onTogglePunctuationStrip
                ) {
                    Text(
                        text = "@",
                        color = if (uiState.isPunctuationStripOpen) {
                            palette.onPrimaryMic
                        } else {
                            palette.keyTextPrimary
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 4. Bottom Corner Utility Row (`ChevronDown` left, `KeyboardGrid` right)
        BottomCornerUtilityIcons(
            onHideKeyboard = onHideKeyboard,
            onSwitchKeyboard = onSwitchKeyboard,
            onShowImePicker = onShowImePicker,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun FloatingQuickPunctuationStrip(
    onCommitText: (String) -> Unit,
    onInsertPairedSymbol: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(palette.cardElevatedBg)
            .border(1.dp, palette.keyBorder, RoundedCornerShape(19.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ImeLogic.quickPunctuationItems.forEach { sym ->
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.keyboardCanvas.copy(alpha = 0.65f))
                    .clickable {
                        if (sym == "“”") {
                            onInsertPairedSymbol("“", "”")
                        } else {
                            onCommitText(sym)
                        }
                    }
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = sym,
                    color = palette.keyTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun ArcTranslateFullPanel(
    targets: List<String>,
    selectedLanguage: String,
    startedFromHandsFree: Boolean,
    isCancelHovered: Boolean,
    amplitudeProvider: () -> Float = { 0f },
    amplitudes: List<Float> = emptyList(),
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val selectedIdx = targets.indexOf(selectedLanguage).let { if (it >= 0) it else 0 }
    val slots = ImeLogic.resolveArcWheelSlots(targets, selectedIdx)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = (4f * s).dp)
            ) {
                SymmetricalSevenBarWaveform(
                    amplitudeProvider = amplitudeProvider,
                    amplitudes = amplitudes,
                    color = palette.keyTextPrimary,
                    modifier = Modifier.size(width = (22f * s).dp, height = (16f * s).dp)
                )
                Text(
                    text = if (startedFromHandsFree) {
                        "Release to translate · 松手立即直译"
                    } else {
                        "松手选定此语言 · 随后开口说话"
                    },
                    color = palette.keyTextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Center 3-Capsule Curved Language Wheel (`-10°` | `0°` | `+10°`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = (6f * s).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ArcSideLanguageCapsule(
                    language = slots.leftLanguage,
                    rotationDeg = -10f,
                    modifier = Modifier.weight(0.92f)
                )

                val centerScale = if (isCancelHovered) 0.95f else 1.04f
                Box(
                    modifier = Modifier
                        .weight(1.24f)
                        .height((58f * s).dp)
                        .graphicsLayer {
                            scaleX = centerScale
                            scaleY = centerScale
                        }
                        .clip(RoundedCornerShape((29f * s).dp))
                        .background(
                            if (isCancelHovered) palette.keyCapSecondaryBg else palette.primaryMicSolid
                        )
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = slots.centerLanguage,
                        color = if (isCancelHovered) palette.keyTextMuted else palette.onPrimaryMic,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                ArcSideLanguageCapsule(
                    language = slots.rightLanguage,
                    rotationDeg = 10f,
                    modifier = Modifier.weight(0.92f)
                )
            }

            // Bottom Curved Cancel Zone (`Cancel / 取消`)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((82f * s).dp)
                    .padding(bottom = (26f * s).dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = (44f * s).dp,
                            topEnd = (44f * s).dp,
                            bottomStart = (18f * s).dp,
                            bottomEnd = (18f * s).dp
                        )
                    )
                    .background(
                        if (isCancelHovered) {
                            TypeFreeRose.copy(alpha = 0.16f)
                        } else {
                            palette.keyCapSecondaryBg.copy(alpha = 0.65f)
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = if (isCancelHovered) {
                            TypeFreeRose.copy(alpha = 0.55f)
                        } else {
                            palette.keyBorder
                        },
                        shape = RoundedCornerShape(
                            topStart = (44f * s).dp,
                            topEnd = (44f * s).dp,
                            bottomStart = (18f * s).dp,
                            bottomEnd = (18f * s).dp
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isCancelHovered) "松手取消翻译 · Cancel" else "取消 · Cancel",
                    color = if (isCancelHovered) TypeFreeRose else palette.keyTextMuted,
                    fontSize = 13.5.sp,
                    fontWeight = if (isCancelHovered) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }

        BottomCornerUtilityIcons(
            onHideKeyboard = onHideKeyboard,
            onSwitchKeyboard = onSwitchKeyboard,
            onShowImePicker = onSwitchKeyboard,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ArcSideLanguageCapsule(
    language: String?,
    rotationDeg: Float,
    modifier: Modifier = Modifier
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    if (language.isNullOrBlank()) {
        Spacer(modifier = modifier.height((50f * s).dp))
        return
    }
    Box(
        modifier = modifier
            .height((50f * s).dp)
            .graphicsLayer {
                rotationZ = rotationDeg
                translationY = (10f * s).dp.toPx()
            }
            .clip(RoundedCornerShape((25f * s).dp))
            .background(palette.keyCapSecondaryBg.copy(alpha = 0.85f))
            .border(1.dp, palette.keyBorder, RoundedCornerShape((25f * s).dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = language,
            color = palette.keyTextPrimary.copy(alpha = 0.82f),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 15.sp,
            overflow = TextOverflow.Ellipsis
        )
    }
}
