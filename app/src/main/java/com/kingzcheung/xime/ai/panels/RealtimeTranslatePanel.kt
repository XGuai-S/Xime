package com.kingzcheung.xime.ai.panels

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.DefaultPresets
import com.kingzcheung.xime.ai.ime.ImeLogic
import com.kingzcheung.xime.ai.ime.ImeUiState
import com.kingzcheung.xime.ai.ime.LocalKeyboardMetrics
import com.kingzcheung.xime.ai.ime.VoicePhase
import com.kingzcheung.xime.ai.ime.chrome.BottomCornerUtilityIcons
import com.kingzcheung.xime.ai.ime.chrome.CircleFloatingKey
import com.kingzcheung.xime.ai.ime.chrome.RepeatingCircleBackspaceKey
import com.kingzcheung.xime.ai.ime.chrome.SymmetricalSevenBarWaveform
import com.kingzcheung.xime.ai.ui.components.TypeFreeIcons
import com.kingzcheung.xime.ai.ui.theme.LocalTypeFreeKeyboardPalette

@Composable
fun RealtimeTranslatePanel(
    uiState: ImeUiState,
    settings: AppSettings,
    amplitudeProvider: () -> Float = { 0f },
    amplitudes: List<Float> = emptyList(),
    elapsedMs: Long,
    onSelectLanguage: (String) -> Unit,
    onStartRecording: () -> Unit,
    onReleaseRecording: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onBackspace: () -> Unit,
    onEnterOrNewline: () -> Unit,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val phase = uiState.voicePhase
    val isRecording = phase is VoicePhase.Recording
    val isProcessing = phase is VoicePhase.Processing
    val targets = settings.translationTargets.ifEmpty { DefaultPresets.defaultTranslationTargets() }
    val activeLang = settings.activeTranslateTarget.ifBlank { targets.first() }

    val currentPhaseState by rememberUpdatedState(phase)
    val currentOnStart by rememberUpdatedState(onStartRecording)
    val currentOnRelease by rememberUpdatedState(onReleaseRecording)
    val currentOnFinish by rememberUpdatedState(onFinishHandsFree)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = (28f * s).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Target Language Capsule Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = TypeFreeIcons.Translate,
                    contentDescription = null,
                    tint = palette.keyTextMuted,
                    modifier = Modifier.size(16.dp)
                )
                targets.forEach { lang ->
                    val selected = lang == activeLang
                    Box(
                        modifier = Modifier
                            .height((30f * s).dp)
                            .clip(RoundedCornerShape((15f * s).dp))
                            .background(if (selected) palette.primaryMicSolid else palette.keyCapBg)
                            .border(
                                width = 1.dp,
                                color = if (selected) Color.Transparent else palette.keyBorder,
                                shape = RoundedCornerShape((15f * s).dp)
                            )
                            .clickable(enabled = !isRecording && !isProcessing) {
                                onSelectLanguage(lang)
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lang,
                            color = if (selected) palette.onPrimaryMic else palette.keyTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }

            // Center Translate Voice Button + Live Status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((6f * s).dp)
            ) {
                Text(
                    text = when {
                        isRecording -> "${ImeLogic.formatElapsedTimer(elapsedMs)} · 译为 $activeLang"
                        phase is VoicePhase.Processing -> phase.stageText
                        else -> "说任意语言，自动直译为 $activeLang"
                    },
                    color = palette.keyTextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                val btnWidth by animateDpAsState(
                    targetValue = ((if (isRecording || isProcessing) 76f else 142f) * s).dp,
                    animationSpec = tween(180),
                    label = "transBtnWidth"
                )
                val btnHeight by animateDpAsState(
                    targetValue = ((if (isRecording || isProcessing) 76f else 54f) * s).dp,
                    animationSpec = tween(180),
                    label = "transBtnHeight"
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isRecording || isProcessing) {
                        Spacer(modifier = Modifier.width((34f * s).dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(width = btnWidth, height = btnHeight)
                            .clip(RoundedCornerShape((38f * s).dp))
                            .background(palette.primaryMicBrush)
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
                        when {
                            isRecording -> {
                                SymmetricalSevenBarWaveform(
                                    amplitudeProvider = amplitudeProvider,
                                    amplitudes = amplitudes,
                                    color = palette.onPrimaryMic,
                                    modifier = Modifier.size(width = (38f * s).dp, height = (28f * s).dp)
                                )
                            }
                            isProcessing -> {
                                CircularProgressIndicator(
                                    color = palette.onPrimaryMic,
                                    strokeWidth = 2.2.dp,
                                    modifier = Modifier.size((24f * s).dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = TypeFreeIcons.Mic,
                                    contentDescription = "说话快译",
                                    tint = palette.onPrimaryMic,
                                    modifier = Modifier.size((25f * s).dp)
                                )
                            }
                        }
                    }

                    if (isRecording || isProcessing) {
                        CircleFloatingKey(
                            size = (34f * s).dp,
                            onClick = onCancelRecording
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Close,
                                contentDescription = "取消快译",
                                tint = palette.keyTextPrimary,
                                modifier = Modifier.size((15f * s).dp)
                            )
                        }
                    }
                }
            }

            // Compact Send + Backspace Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width((42f * s).dp))

                Box(
                    modifier = Modifier
                        .size(width = (104f * s).dp, height = (40f * s).dp)
                        .clip(RoundedCornerShape((20f * s).dp))
                        .background(palette.keyCapBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape((20f * s).dp))
                        .clickable(onClick = onEnterOrNewline),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.enterActionInfo.label,
                        color = palette.keyTextMuted,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                RepeatingCircleBackspaceKey(
                    size = (42f * s).dp,
                    onBackspace = onBackspace
                )
            }
        }

        BottomCornerUtilityIcons(
            onHideKeyboard = onHideKeyboard,
            onSwitchKeyboard = onSwitchKeyboard,
            onShowImePicker = onShowImePicker,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
