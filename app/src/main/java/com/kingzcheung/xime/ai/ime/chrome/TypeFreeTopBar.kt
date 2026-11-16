package com.kingzcheung.xime.ai.ime.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.ToneStyle
import com.kingzcheung.xime.ai.ime.ImeLogic
import com.kingzcheung.xime.ai.ime.ImeUiState
import com.kingzcheung.xime.ai.ime.KeyboardTabMode
import com.kingzcheung.xime.ai.ime.LocalKeyboardMetrics
import com.kingzcheung.xime.ai.ime.VoicePhase
import com.kingzcheung.xime.ai.ime.clipboard.ClipboardSmartExtractor
import com.kingzcheung.xime.ai.ime.clipboard.SmartClipKind
import com.kingzcheung.xime.ai.ui.components.TypeFreeIcons
import com.kingzcheung.xime.ai.ui.theme.LocalTypeFreeKeyboardPalette
import com.kingzcheung.xime.ai.ui.theme.TypeFreeEmerald
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class TopBarLeftSlotState(
    val showUndoButton: Boolean,
    val showClipboardQuickPaste: Boolean,
    val showVoicePenPill: Boolean,
    val voicePenLabel: String,
    val showJevCopilotPill: Boolean = false,
    val showClipboardButton: Boolean = true,
    val showStyleBadge: Boolean = false,
    val showQwertyHoldVoiceCapsule: Boolean = false
)

/**
 * Pure policy for TypeFree v3.4.0 Top Bar slot priority & intent convergence:
 * 1. When [hasUndoSnapshot] is true, [TopBarUndoIconButton] is ALWAYS kept visible so a fresh
 *    clipboard banner never swallows the 1-tap Undo safety net.
 * 2. A permanent 26.dp [TopBarClipboardIconButton] (`[📋]`) is always exposed when not composing
 *    for 1-tap clipboard access, with a green notification dot when [hasFreshClipboardBanner] is true.
 * 3. The AI Tone Style badge (`💼 职场▾`) is ONLY shown in [KeyboardTabMode.VOICE] (hidden in QWERTY).
 * 4. In [KeyboardTabMode.QWERTY] idle state, the right cluster converges to `[✦ 军师]` + `[✍️ 帮写/改写]`
 *    + dark primary `[🎤 语音]` capsule (tap `< 220ms` switches to Voice panel; hold `>= 220ms` dictates in-place).
 */
object TopBarSlotPolicy {
    fun resolveLeftSlots(
        activeTab: KeyboardTabMode,
        isComposing: Boolean,
        hasUndoSnapshot: Boolean,
        hasFreshClipboardBanner: Boolean,
        hasActiveSelection: Boolean,
        jevCopilotEnabled: Boolean = true
    ): TopBarLeftSlotState {
        if (isComposing) {
            return TopBarLeftSlotState(
                showUndoButton = false,
                showClipboardQuickPaste = false,
                showVoicePenPill = false,
                voicePenLabel = if (hasActiveSelection) "改写" else "帮写",
                showJevCopilotPill = false,
                showClipboardButton = false,
                showStyleBadge = false,
                showQwertyHoldVoiceCapsule = false
            )
        }
        val showVoicePen = activeTab == KeyboardTabMode.QWERTY ||
            (activeTab != KeyboardTabMode.VOICE && hasActiveSelection)
        val showJevPill = jevCopilotEnabled &&
            (activeTab == KeyboardTabMode.QWERTY || activeTab == KeyboardTabMode.VOICE)
        return TopBarLeftSlotState(
            showUndoButton = hasUndoSnapshot,
            showClipboardQuickPaste = hasFreshClipboardBanner,
            showVoicePenPill = showVoicePen,
            voicePenLabel = if (hasActiveSelection) "改写" else "帮写",
            showJevCopilotPill = showJevPill,
            showClipboardButton = true,
            showStyleBadge = activeTab == KeyboardTabMode.VOICE,
            showQwertyHoldVoiceCapsule = activeTab == KeyboardTabMode.QWERTY
        )
    }

    fun shouldShowTopBarAskAiPill(
        settings: AppSettings,
        activeTab: KeyboardTabMode,
        isComposing: Boolean,
        isToolPanelOpen: Boolean,
        isClipboardOpen: Boolean,
        currentInputType: Int,
        currentEditorText: String
    ): Boolean {
        if (!settings.equalEqualAskAiEnabled) return false
        if (activeTab != KeyboardTabMode.QWERTY || isComposing || isToolPanelOpen || isClipboardOpen) return false
        if (ImeLogic.isPasswordEditorInputType(currentInputType)) return false
        val inputClass = currentInputType and android.text.InputType.TYPE_MASK_CLASS
        if (inputClass == android.text.InputType.TYPE_CLASS_NUMBER ||
            inputClass == android.text.InputType.TYPE_CLASS_PHONE ||
            inputClass == android.text.InputType.TYPE_CLASS_DATETIME
        ) {
            return false
        }
        return currentEditorText.trim().length >= 2
    }
}

@Composable
fun TopBarToolPanelButton(
    isOpen: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val bgColor = if (isOpen) palette.primaryMicSolid else palette.keyCapBg
    val borderColor = if (isOpen) Color.Transparent else palette.keyBorder
    val iconTint = if (isOpen) palette.onPrimaryMic else palette.keyTextPrimary

    Box(
        modifier = Modifier
            .requiredSize(28.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = TypeFreeIcons.LogoMark,
            contentDescription = if (isOpen) "收起工具面板" else "展开工具面板",
            tint = iconTint,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
fun TypeFreeTopBar(
    uiState: ImeUiState,
    settings: AppSettings,
    activeStyle: ToneStyle,
    isAutoMatched: Boolean,
    onToggleToolPanel: () -> Unit,
    onSelectTabMode: (KeyboardTabMode) -> Unit,
    onOpenHelpMeWrite: () -> Unit,
    onToggleFlypyLanguageMode: () -> Unit,
    onToggleStylePicker: () -> Unit,
    onPerformUndo: () -> Unit,
    onPasteClipboardText: (String) -> Unit,
    onToggleManualIncognito: () -> Unit = {},
    onOpenJevCopilot: () -> Unit = {},
    onOpenAskAiCard: () -> Unit = {},
    onToggleClipboard: () -> Unit = {},
    onStartVoiceDictation: () -> Unit = {},
    onStopVoiceDictation: () -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val metrics = LocalKeyboardMetrics.current
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 42.dp.toPx() }
    val currentTab by rememberUpdatedState(uiState.tabMode)
    val currentOnSelectTab by rememberUpdatedState(onSelectTabMode)
    val freshClip = if (settings.clipboardEnabled && settings.clipboardQuickPasteSuggestion) {
        uiState.freshClipboardBannerItem
    } else {
        null
    }

    val slotState = remember(
        uiState.tabMode,
        uiState.isFlypyComposing,
        uiState.undoSnapshot,
        freshClip,
        uiState.rawSelectedText,
        settings.jevCopilotEnabled
    ) {
        TopBarSlotPolicy.resolveLeftSlots(
            activeTab = uiState.tabMode,
            isComposing = uiState.isFlypyComposing,
            hasUndoSnapshot = uiState.undoSnapshot != null,
            hasFreshClipboardBanner = freshClip != null,
            hasActiveSelection = uiState.rawSelectedText.isNotBlank(),
            jevCopilotEnabled = settings.jevCopilotEnabled
        )
    }

    val isQwertyRightCluster = slotState.showQwertyHoldVoiceCapsule && !uiState.isToolPanelOpen
    val isInPlaceQwertyVoiceActive = uiState.tabMode == KeyboardTabMode.QWERTY &&
        (uiState.voicePhase is VoicePhase.Recording || uiState.voicePhase is VoicePhase.Processing)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.topBarHeight)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var last = start
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        if (!change.pressed) break
                    }
                    val next = ImeLogic.resolveNextTabByHorizontalSwipe(
                        currentTab = currentTab,
                        dragOffsetX = last.x - start.x,
                        dragOffsetY = last.y - start.y,
                        thresholdPx = swipeThresholdPx
                    )
                    if (next != null) {
                        currentOnSelectTab(next)
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left Cluster: [⊞] + [↶] + [📋] + Smart Paste / Status / Style Badge (VOICE only)
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TopBarToolPanelButton(
                isOpen = uiState.isToolPanelOpen,
                onClick = onToggleToolPanel
            )

            // 1. Always-visible Undo button when undoSnapshot != null (never swallowed by freshClip!)
            if (slotState.showUndoButton) {
                TopBarUndoIconButton(onClick = onPerformUndo)
            }

            // 2. Permanent 1-Tap 26.dp Clipboard Button ([📋]) with green badge dot on fresh clip
            if (slotState.showClipboardButton) {
                TopBarClipboardIconButton(
                    isOpen = uiState.isClipboardOpen,
                    hasFreshClip = freshClip != null,
                    onClick = onToggleClipboard
                )
            }

            // In VOICE / TRANSLATE / ToolPanel mode, render [✦ 军师] and [✍️ 帮写/改写] on the left
            if (!isQwertyRightCluster && slotState.showJevCopilotPill) {
                TopBarJevCopilotPill(onClick = onOpenJevCopilot)
            }

            if (!isQwertyRightCluster && slotState.showVoicePenPill) {
                TopBarVoicePenPill(
                    label = slotState.voicePenLabel,
                    isHighlighted = uiState.shouldShowTopBarSpeakToEditPill,
                    onClick = onOpenHelpMeWrite
                )
            }

            if (uiState.isIncognitoMode) {
                Row(
                    modifier = Modifier
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TypeFreeEmerald.copy(alpha = 0.16f))
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onToggleManualIncognito)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val incTint = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                    Icon(
                        imageVector = TypeFreeIcons.IncognitoMask,
                        contentDescription = "无痕模式",
                        tint = incTint,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "无痕",
                        color = incTint,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            val learnedTerm = uiState.autoLearnedToastTerm
            val quickToast = uiState.quickActionToast
            if (isInPlaceQwertyVoiceActive) {
                val statusLabel = if (uiState.voicePhase is VoicePhase.Processing) {
                    "🎙 正在转写上屏…"
                } else {
                    "🎙 聆听中 · 松手上屏"
                }
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TypeFreeEmerald.copy(alpha = 0.16f))
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusLabel,
                        color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (!learnedTerm.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TypeFreeEmerald.copy(alpha = 0.15f))
                        .border(1.dp, TypeFreeEmerald.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "✓ 已自动收入词典「$learnedTerm」",
                        color = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (!quickToast.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.capsuleSelectedBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = quickToast,
                        color = palette.keyTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (slotState.showClipboardQuickPaste && freshClip != null) {
                val smart = remember(freshClip.text) {
                    ClipboardSmartExtractor.extractPrimary(freshClip.text)
                }
                val hasDistinctSmartEntity = smart != null && smart.extractedValue != freshClip.text.trim()
                val clipTint = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                if (hasDistinctSmartEntity && smart != null) {
                    val smartIcon: ImageVector = when (smart.kind) {
                        SmartClipKind.OTP_CODE -> TypeFreeIcons.HashCode
                        SmartClipKind.PICKUP_CODE -> TypeFreeIcons.PackageBox
                        else -> TypeFreeIcons.Clipboard
                    }
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .height(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(TypeFreeEmerald.copy(alpha = 0.15f))
                                .border(1.dp, TypeFreeEmerald.copy(alpha = 0.48f), RoundedCornerShape(13.dp))
                                .clickable { onPasteClipboardText(smart.extractedValue) }
                                .padding(horizontal = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = smartIcon,
                                contentDescription = null,
                                tint = clipTint,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = smart.extractedValue,
                                color = clipTint,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier
                                .height(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, RoundedCornerShape(13.dp))
                                .clickable { onPasteClipboardText(freshClip.text) }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Clipboard,
                                contentDescription = null,
                                tint = palette.keyTextPrimary,
                                modifier = Modifier.size(10.5.dp)
                            )
                            Text(
                                text = "全文",
                                color = palette.keyTextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    val previewText = ClipboardSmartExtractor.formatSingleLinePreview(freshClip.text, 7)
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .height(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(TypeFreeEmerald.copy(alpha = 0.15f))
                            .border(1.dp, TypeFreeEmerald.copy(alpha = 0.48f), RoundedCornerShape(13.dp))
                            .clickable { onPasteClipboardText(freshClip.text) }
                            .padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.Clipboard,
                            contentDescription = null,
                            tint = clipTint,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "粘贴「$previewText」",
                            color = clipTint,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else if (slotState.showStyleBadge) {
                // Compact Clickable Scene Style Badge (ONLY in VOICE mode, hidden in QWERTY!)
                val badgeLabel = ImeLogic.buildCompactStyleBadgeText(activeStyle, isAutoMatched)
                val chevron = if (uiState.isStylePickerExpanded) "▴" else "▾"
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (uiState.isStylePickerExpanded) palette.capsuleSelectedBg else palette.keyCapBg
                        )
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(13.dp))
                        .clickable(onClick = onToggleStylePicker)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        color = palette.keyTextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = chevron,
                        color = palette.keyTextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Right Cluster:
        // - In QWERTY mode: [✦ 军师] + [✍️ 帮写/改写] + Dark Primary [🎤 语音] (tap <220ms switches to VOICE; hold >=220ms dictates in-place)
        // - In VOICE / TRANSLATE mode: Compact 2-Segment Track [鹤 打字 | 文A]
        if (isQwertyRightCluster) {
            val showAskAi = TopBarSlotPolicy.shouldShowTopBarAskAiPill(
                settings = settings,
                activeTab = uiState.tabMode,
                isComposing = uiState.isFlypyComposing,
                isToolPanelOpen = uiState.isToolPanelOpen,
                isClipboardOpen = uiState.isClipboardOpen,
                currentInputType = uiState.currentInputType,
                currentEditorText = uiState.currentEditorText
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (showAskAi) {
                    TopBarAskAiPill(onClick = onOpenAskAiCard)
                }
                if (slotState.showJevCopilotPill) {
                    TopBarJevCopilotPill(onClick = onOpenJevCopilot)
                }
                if (slotState.showVoicePenPill) {
                    TopBarVoicePenPill(
                        label = slotState.voicePenLabel,
                        isHighlighted = uiState.shouldShowTopBarSpeakToEditPill,
                        onClick = onOpenHelpMeWrite
                    )
                }
                QwertyTopBarHoldVoiceCapsule(
                    isRecordingInPlace = uiState.voicePhase is VoicePhase.Recording,
                    onTapSwitchToVoice = { onSelectTabMode(KeyboardTabMode.VOICE) },
                    onStartVoiceDictation = onStartVoiceDictation,
                    onStopVoiceDictation = onStopVoiceDictation
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(palette.capsuleTrackBg)
                    .border(1.dp, palette.keyBorder, RoundedCornerShape(15.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (uiState.tabMode != KeyboardTabMode.VOICE) {
                    ModeSegmentItem(
                        selected = false,
                        width = 30.dp,
                        onClick = { onSelectTabMode(KeyboardTabMode.VOICE) }
                    ) { contentColor ->
                        Icon(
                            imageVector = TypeFreeIcons.VoiceWaveform,
                            contentDescription = "语音模式",
                            tint = contentColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                val schemeBadge = if (uiState.isFlypyChineseMode) {
                    uiState.activeInputScheme.shortBadge
                } else {
                    "EN"
                }
                ModeSegmentItem(
                    selected = uiState.tabMode == KeyboardTabMode.QWERTY && !uiState.isToolPanelOpen,
                    width = 52.dp,
                    onClick = {
                        if (uiState.tabMode == KeyboardTabMode.QWERTY && !uiState.isToolPanelOpen) {
                            onToggleFlypyLanguageMode()
                        } else {
                            onSelectTabMode(KeyboardTabMode.QWERTY)
                        }
                    }
                ) { contentColor ->
                    Text(
                        text = "$schemeBadge 打字",
                        color = contentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                ModeSegmentItem(
                    selected = uiState.tabMode == KeyboardTabMode.TRANSLATE && !uiState.isToolPanelOpen,
                    width = 34.dp,
                    onClick = { onSelectTabMode(KeyboardTabMode.TRANSLATE) }
                ) { contentColor ->
                    Text(
                        text = "文A",
                        color = contentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBarVoicePenPill(
    label: String,
    isHighlighted: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isHighlighted) palette.primaryMicSolid else palette.keyCapBg)
            .border(
                width = 1.dp,
                color = if (isHighlighted) Color.Transparent else palette.keyBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = TypeFreeIcons.VoicePen,
            contentDescription = label,
            tint = if (isHighlighted) palette.onPrimaryMic else palette.keyTextPrimary,
            modifier = Modifier.size(11.5.dp)
        )
        Text(
            text = label,
            color = if (isHighlighted) palette.onPrimaryMic else palette.keyTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

private val AskAiPillIndigo = Color(0xFF6366F1)

@Composable
fun TopBarAskAiPill(
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val fgColor = if (palette.isDark) Color(0xFF818CF8) else Color(0xFF4F46E5)
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AskAiPillIndigo.copy(alpha = 0.14f))
            .border(
                width = 1.dp,
                color = AskAiPillIndigo.copy(alpha = 0.50f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = "🔍",
            fontSize = 10.sp
        )
        Text(
            text = "问AI",
            color = fgColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun TopBarJevCopilotPill(
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.keyCapBg)
            .border(
                width = 1.dp,
                color = palette.keyBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = TypeFreeIcons.CopilotStar,
            contentDescription = "军师",
            tint = palette.keyTextPrimary,
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = "军师",
            color = palette.keyTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun QwertyTopBarHoldVoiceCapsule(
    isRecordingInPlace: Boolean,
    onTapSwitchToVoice: () -> Unit,
    onStartVoiceDictation: () -> Unit,
    onStopVoiceDictation: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTapSwitchToVoice)
    val currentOnStart by rememberUpdatedState(onStartVoiceDictation)
    val currentOnStop by rememberUpdatedState(onStopVoiceDictation)
    var isHoldingMic by remember { mutableStateOf(false) }

    val activeHighlight = isHoldingMic || isRecordingInPlace
    val bgColor = if (activeHighlight) TypeFreeEmerald else palette.primaryMicSolid
    val fgColor = if (activeHighlight) Color.White else palette.onPrimaryMic

    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var holdTriggered = false
                    val holdJob: Job = scope.launch {
                        delay(ImeLogic.TOP_BAR_VOICE_HOLD_MS)
                        holdTriggered = true
                        isHoldingMic = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        currentOnStart()
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }
                    } finally {
                        holdJob.cancel()
                        isHoldingMic = false
                    }
                    if (holdTriggered) {
                        currentOnStop()
                    } else {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        currentOnTap()
                    }
                }
            }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = TypeFreeIcons.Mic,
            contentDescription = "轻点切语音 / 按住说上屏",
            tint = fgColor,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = if (activeHighlight) "松手上屏" else "语音",
            color = fgColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
fun TopBarClipboardIconButton(
    isOpen: Boolean,
    hasFreshClip: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val bgColor = if (isOpen) palette.primaryMicSolid else palette.keyCapBg
    val borderColor = if (isOpen) Color.Transparent else palette.keyBorder
    val iconTint = if (isOpen) palette.onPrimaryMic else palette.keyTextPrimary

    Box(
        modifier = Modifier.requiredSize(26.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .requiredSize(26.dp)
                .clip(CircleShape)
                .background(bgColor)
                .border(1.dp, borderColor, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = TypeFreeIcons.Clipboard,
                contentDescription = "剪贴板",
                tint = iconTint,
                modifier = Modifier.size(13.dp)
            )
        }
        if (hasFreshClip && !isOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 1.dp, y = (-1).dp)
                    .requiredSize(6.dp)
                    .clip(CircleShape)
                    .background(TypeFreeEmerald)
            )
        }
    }
}

@Composable
fun TopBarUndoIconButton(
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    Box(
        modifier = Modifier
            .requiredSize(26.dp)
            .clip(CircleShape)
            .background(palette.keyCapBg)
            .border(1.dp, palette.keyBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = TypeFreeIcons.Undo,
            contentDescription = "撤销",
            tint = palette.keyTextPrimary,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
private fun ModeSegmentItem(
    selected: Boolean,
    width: androidx.compose.ui.unit.Dp = 33.dp,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val contentColor = if (selected) palette.keyTextPrimary else palette.keyTextMuted

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) palette.capsuleSelectedBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(contentColor)
    }
}

@Composable
fun InlineStyleSelectorStrip(
    settings: AppSettings,
    onSelectAutoStyle: () -> Unit,
    onSelectManualStyle: (ToneStyle) -> Unit,
    onOpenStyleRules: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val isAutoSelected = settings.selectedManualStyleId == "auto"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StyleChoicePill(
            text = "自动场景感知",
            leadingIcon = TypeFreeIcons.Sparkles,
            selected = isAutoSelected,
            onClick = onSelectAutoStyle
        )

        settings.styles.forEach { style ->
            val selected = !isAutoSelected && settings.selectedManualStyleId == style.id
            StyleChoicePill(
                text = "${style.emoji} ${style.name}",
                selected = selected,
                onClick = { onSelectManualStyle(style) }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.keyCapBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenStyleRules)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "管理风格 ›",
                color = palette.keyTextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StyleChoicePill(
    text: String,
    leadingIcon: ImageVector? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val fgColor = if (selected) palette.onPrimaryMic else palette.keyTextPrimary
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) palette.primaryMicSolid else palette.keyCapBg)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else palette.keyBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fgColor,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            text = text,
            color = fgColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
