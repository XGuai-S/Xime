package com.kingzcheung.xime.ai.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ai.ime.ImeLogic
import com.kingzcheung.xime.ai.ime.ImeUiState
import com.kingzcheung.xime.ai.ime.LocalKeyboardMetrics
import com.kingzcheung.xime.ai.ime.QuickEditChip
import com.kingzcheung.xime.ai.ime.VoicePhase
import com.kingzcheung.xime.ai.ime.chrome.BottomCornerUtilityIcons
import com.kingzcheung.xime.ai.ime.chrome.ErrorContextBanner
import com.kingzcheung.xime.ai.ime.chrome.SubPanelHeaderBridgeBar
import com.kingzcheung.xime.ai.ime.chrome.SubPanelVoiceTriggerRow
import com.kingzcheung.xime.ai.ui.AppDestinations
import com.kingzcheung.xime.ai.ui.components.TypeFreeIcons
import com.kingzcheung.xime.ai.ui.theme.LocalTypeFreeKeyboardPalette

@Composable
fun SpeakToEditPanel(
    uiState: ImeUiState,
    amplitudeProvider: () -> Float = { 0f },
    amplitudes: List<Float> = emptyList(),
    elapsedMs: Long,
    onDismissSelection: () -> Unit,
    onSwitchToQwerty: () -> Unit,
    onOpenClipboard: () -> Unit,
    onQuickChipClick: (QuickEditChip) -> Unit,
    onStartRecording: () -> Unit,
    onReleaseRecording: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onOpenHostApp: (Int) -> Unit,
    onRetryLastAudio: () -> Unit,
    onDismissError: () -> Unit,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val phase = uiState.voicePhase
    val isBusy = phase is VoicePhase.Recording || phase is VoicePhase.Processing

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = (28f * s).dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Brand Logo + `TypeFree · 语音编辑` + Top-Right Direct QWERTY / Clipboard / `×`
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .requiredWidth(IntrinsicSize.Max)
                            .padding(vertical = 3.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.LogoMark,
                            contentDescription = null,
                            tint = palette.keyTextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "TypeFree",
                            color = palette.keyTextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "· 语音编辑",
                        color = palette.keyTextMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                SubPanelHeaderBridgeBar(
                    isFlypyChineseMode = uiState.isFlypyChineseMode,
                    inputSchemeShortBadge = uiState.activeInputScheme.shortBadge,
                    hasFreshClipboard = uiState.freshClipboardBannerItem != null,
                    onSwitchToQwerty = onSwitchToQwerty,
                    onOpenClipboard = onOpenClipboard,
                    closeDescription = "忽略选区切回普通口述",
                    onClose = onDismissSelection
                )
            }

            if (phase is VoicePhase.Error) {
                ErrorContextBanner(
                    error = phase,
                    onRetry = onRetryLastAudio,
                    onOpenApiSetup = { onOpenHostApp(AppDestinations.TAB_API) },
                    onOpenPermissionSetup = { onOpenHostApp(AppDestinations.TAB_HOME) },
                    onDismiss = onDismissError
                )
            } else {
                // Selected Text Preview Card + Quick Rewrite Chips (`ImeLogic.quickEditChips`)
                Column(verticalArrangement = Arrangement.spacedBy((6f * s).dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.cardElevatedBg)
                            .border(1.dp, palette.keyBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = (6f * s).dp)
                    ) {
                        Text(
                            text = ImeLogic.formatSelectionPreview(uiState.effectiveSelectedText, 64),
                            color = palette.keyTextPrimary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (palette.isDark) {
                                        Color(0xFF1E3A5F).copy(alpha = 0.55f)
                                    } else {
                                        Color(0xFFBFDBFE).copy(alpha = 0.65f)
                                    }
                                )
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ImeLogic.quickEditChips.forEach { chip ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.keyCapBg)
                                    .border(1.dp, palette.keyBorder, RoundedCornerShape(12.dp))
                                    .clickable(enabled = !isBusy) { onQuickChipClick(chip) }
                                    .padding(horizontal = 10.dp, vertical = (5f * s).dp)
                            ) {
                                Text(
                                    text = chip.label,
                                    color = if (isBusy) palette.keyTextMuted else palette.keyTextPrimary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Bottom-Left Pulsing Circle Button + `Speak to edit`
            SubPanelVoiceTriggerRow(
                phase = phase,
                amplitudeProvider = amplitudeProvider,
                amplitudes = amplitudes,
                elapsedMs = elapsedMs,
                idleTitle = "Speak to edit · 语音编辑",
                idleSubtitle = "轻点说出修改指令（如：改成正式语气 / 翻译成英文）",
                recordingTitle = "Speak to edit · 正在聆听修改指令",
                onStartRecording = onStartRecording,
                onReleaseRecording = onReleaseRecording,
                onFinishHandsFree = onFinishHandsFree,
                onCancelRecording = onCancelRecording
            )
        }

        BottomCornerUtilityIcons(
            onHideKeyboard = onHideKeyboard,
            onSwitchKeyboard = onSwitchKeyboard,
            onShowImePicker = onShowImePicker,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
