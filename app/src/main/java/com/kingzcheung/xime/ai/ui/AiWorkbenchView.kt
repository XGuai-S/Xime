package com.kingzcheung.xime.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ai.XimeAiApp
import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.VoiceIntentMode
import com.kingzcheung.xime.ai.ime.ImeUiState
import com.kingzcheung.xime.ai.ime.VoiceController
import com.kingzcheung.xime.ai.ime.VoicePhase
import com.kingzcheung.xime.ai.panels.HelpMeWritePanel
import com.kingzcheung.xime.ai.panels.JevCopilotPanel
import com.kingzcheung.xime.ai.panels.RealtimeTranslatePanel
import com.kingzcheung.xime.ai.panels.SpeakToEditPanel
import com.kingzcheung.xime.ai.panels.VoiceMainPanel
import com.kingzcheung.xime.ai.panels.AskAiCardPanel
import kotlinx.coroutines.delay

/**
 * AI 工作台容器：承载五大 AI 意图面板。
 *
 * 移植自 TypeFree 的语音面板体系。TypeFree 把这些面板挂在语音键盘页里
 * （`KeyboardTabMode.VOICE` 的子面板）；Xime 键盘由 Rime 主导，改为
 * **工具栏按钮 → Overlay 路由 → 本容器**，不改动现有键盘布局。
 *
 * 状态来源：VoiceController 通过 ImeHostCallbacks.stateFlow 驱动 uiState，
 * 本容器订阅同一份 stateFlow，录音/识别/润色各阶段的 UI 自动流转。
 */
@Composable
fun AiWorkbenchView(
    controller: VoiceController,
    uiStateFlow: kotlinx.coroutines.flow.StateFlow<ImeUiState>,
    onClose: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    itemBgColor: Color,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
) {
    val uiState by uiStateFlow.collectAsState()
    val repo = runCatching { XimeAiApp.repository }.getOrNull()
    val settingsFlow = repo?.settings
    val settings by (settingsFlow?.collectAsState()
        ?: remember { mutableStateOf<AppSettings?>(null) })

    val context = androidx.compose.ui.platform.LocalContext.current
    // 无麦克风权限时拦截所有录音入口，并提示去开启（避免点了没反应）
    val micGranted = AiPermissions.hasMicrophone(context)
    fun ensureMic(then: () -> Unit) {
        if (micGranted) then()
        else {
            android.widget.Toast.makeText(
                context, "需要麦克风权限才能使用语音（设置 → 智能 → AI 服务商）",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    var activeIntent by remember { mutableStateOf(uiState.activeIntentMode) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val subColor = textColor.copy(alpha = 0.65f)

    // 录音计时：仅 Recording 阶段走动
    LaunchedEffect(uiState.voicePhase) {
        if (uiState.voicePhase is VoicePhase.Recording) {
            elapsedMs = 0L
            while (true) {
                delay(100)
                elapsedMs += 100
            }
        } else elapsedMs = 0L
    }

    // 离开面板时确保停止录音，避免麦克风悬挂
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                if (uiState.voicePhase is VoicePhase.Recording) {
                    controller.cancelRecordingOrProcessing()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(bottom = bottomPaddingDp.dp)
    ) {
        // ---- 顶栏：关闭 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(itemBgColor)
                    .clickable {
                        runCatching { controller.closeVoiceSubPanel() }
                        onClose()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭 AI 工作台",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(itemBgColor)
                    .clickable { runCatching { controller.openAskAiCardPanel() } }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("🔍 问AI", color = accentColor, fontSize = 12.sp)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            // ---- 轻量工具条：只暴露 Xime 工作台真正具备能力的操作 ----
            val repoWb = remember { runCatching { XimeAiApp.repository }.getOrNull() }
            // 一键复制：把最近一次 AI 结果复制到剪贴板（工作台在 overlay 内，无法直接回滚宿主文本）
            WorkbenchToolChip("📋 复制", accentColor = accentColor, itemBgColor = itemBgColor, textColor = textColor) {
                runCatching {
                    val txt = uiState.currentEditorText
                    if (txt.isNotBlank()) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Xime AI", txt))
                    }
                }
            }
            // 无痕：阻断学习/历史/读屏
            val incognito = settings?.manualIncognitoMode == true
            WorkbenchToolChip(
                if (incognito) "🕶 无痕·开" else "🕶 无痕",
                active = incognito,
                accentColor = accentColor,
                itemBgColor = itemBgColor,
                textColor = textColor
            ) {
                runCatching {
                    repoWb?.updateSettings { it.copy(manualIncognitoMode = !it.manualIncognitoMode) }
                }
            }
            // 文风快捷：在"自动"和当前固定文风间切换
            val styles = settings?.styles.orEmpty()
            val manualId = settings?.selectedManualStyleId ?: "auto"
            val currentStyleName = styles.firstOrNull { it.id == manualId }?.let { it.emoji + it.name } ?: "自动"
            WorkbenchToolChip("🎨 $currentStyleName", accentColor = accentColor, itemBgColor = itemBgColor, textColor = textColor) {
                runCatching {
                    repoWb?.let { r ->
                        // 轮换：auto → 各文风 → auto
                        val ids = listOf("auto") + styles.map { it.id }
                        val idx = ids.indexOf(manualId).let { if (it < 0) 0 else it }
                        val next = ids[(idx + 1) % ids.size]
                        r.updateSettings { it.copy(selectedManualStyleId = next) }
                    }
                }
            }
        }

        // 意图标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VoiceIntentMode.entries.forEach { intent ->
                val selected = intent == activeIntent
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (selected) accentColor else itemBgColor)
                        .clickable { activeIntent = intent },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = intent.label
                            .replace("口述净写", "净写")
                            .replace("帮我写", "帮写")
                            .replace("语音编辑", "编辑")
                            .replace("实时快译", "快译")
                            .replace("Jev 军师", "军师"),
                        color = if (selected) Color.White else textColor,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }

        // ---- 面板内容 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 6.dp)
        ) {
            val st = settings
            if (st == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在初始化 AI 模块…", color = subColor, fontSize = 13.sp)
                }
            } else {
                if (uiState.voiceSubPanel == com.kingzcheung.xime.ai.ime.VoiceSubPanel.ASK_AI_CARD) {
                    AskAiCardPanel(
                        uiState = uiState,
                        onAction = { act -> runCatching { controller.handleImeUiAction(act) } },
                    )
                } else when (activeIntent) {
                    VoiceIntentMode.DICTATE -> VoiceMainPanel(
                        uiState = uiState,
                        settings = st,
                        elapsedMs = elapsedMs,
                        amplitudeProvider = { runCatching { controller.currentAmplitude() }.getOrDefault(0f) },
                        didPauseMedia = false,
                        onSwipeToTab = { tabMode ->
                            // 语音面板横滑：在 净写(DICTATE) 与 快译(TRANSLATE) 之间切换
                            activeIntent = when (tabMode) {
                                com.kingzcheung.xime.ai.ime.KeyboardTabMode.TRANSLATE ->
                                    VoiceIntentMode.TRANSLATE
                                else -> VoiceIntentMode.DICTATE
                            }
                        },
                        onVoicePenClick = { runCatching { controller.openOrRestoreVoicePenPanel() } },
                        onOpenJevCopilot = { activeIntent = VoiceIntentMode.JEV_COPILOT },
                        onMicPressDown = { ensureMic { runCatching { controller.handleMicPressDown(VoiceIntentMode.DICTATE) } } },
                        onMicPressUp = { runCatching { controller.handleMicPressUp() } },
                        onEnterArcTranslate = { runCatching { controller.enterArcTranslateMode(it) } },
                        onUpdateArcTranslate = { lang, cancel -> runCatching { controller.updateArcTranslateSelection(lang, cancel) } },
                        onReleaseArcTranslate = { runCatching { controller.releaseArcTranslate() } },
                        onFinishHandsFree = { runCatching { controller.finishHandsFreeRecording() } },
                        onCancelRecording = { runCatching { controller.cancelRecordingOrProcessing() } },
                        onTogglePunctuationStrip = { runCatching { controller.togglePunctuationStrip() } },
                        onCommitText = { text -> runCatching { controller.commitQuickText(text) } },
                        onInsertPairedSymbol = { open, close ->
                            runCatching { controller.commitQuickText(open + close) }
                        },
                        onBackspace = { runCatching { controller.quickBackspace() } },
                        onEnterOrNewline = { runCatching { controller.quickEnter() } },
                        onHideKeyboard = { onClose() },
                        onSwitchKeyboard = { onClose() },
                        onShowImePicker = { onClose() },
                    )

                    VoiceIntentMode.HELP_ME_WRITE -> HelpMeWritePanel(
                        uiState = uiState,
                        settings = st,
                        elapsedMs = elapsedMs,
                        amplitudeProvider = { runCatching { controller.currentAmplitude() }.getOrDefault(0f) },
                        // 关键：不传 onAction 时面板内所有 action（点草稿胶囊等）都会落进默认空实现，
                        // 表现为"点了没反应、日志也没有"。必须接到 controller。
                        onAction = { act -> runCatching { controller.handleImeUiAction(act) } },
                        onClose = { activeIntent = VoiceIntentMode.DICTATE },
                        onOpenJevCopilot = { activeIntent = VoiceIntentMode.JEV_COPILOT },
                        onAcceptChatContextDisclosure = { runCatching { controller.acceptChatContextDisclosure() } },
                        onDeclineChatContextDisclosure = { runCatching { controller.declineChatContextDisclosure() } },
                        onToggleIgnoreChatContext = { runCatching { controller.toggleIgnoreChatContextForTurn() } },
                        onSwitchToQwerty = { onClose() },
                        onOpenClipboard = { runCatching { onClose() } },
                        onPresetClick = { preset ->
                            // 点胶囊 = 立即按该要求生成（切换要求即用原文自动重新生成）
                            runCatching {
                                controller.handleImeUiAction(
                                    com.kingzcheung.xime.ai.ime.ImeUiAction.SelectHelpWritePreset(preset)
                                )
                                controller.runHelpWritePreset(preset)
                            }
                        },
                        onDirectGenerate = { runCatching { controller.runSelectedHelpWritePresetDirect() } },
                        onStartRecording = { ensureMic { runCatching { controller.handleMicPressDown(VoiceIntentMode.HELP_ME_WRITE) } } },
                        onReleaseRecording = { runCatching { controller.handleMicPressUp() } },
                        onFinishHandsFree = { runCatching { controller.finishHandsFreeRecording() } },
                        onCancelRecording = { runCatching { controller.cancelRecordingOrProcessing() } },
                        onOpenHostApp = { runCatching { controller.closeVoiceSubPanel() }; onClose() },
                        onRetryLastAudio = { runCatching { controller.retryLastAudio() } },
                        onDismissError = { runCatching { controller.dismissErrorBanner() } },
                        onHideKeyboard = { onClose() },
                        onSwitchKeyboard = { onClose() },
                        onShowImePicker = { onClose() },
                    )

                    VoiceIntentMode.SPEAK_TO_EDIT -> SpeakToEditPanel(
                        uiState = uiState,
                        elapsedMs = elapsedMs,
                        amplitudeProvider = { runCatching { controller.currentAmplitude() }.getOrDefault(0f) },
                        onDismissSelection = { activeIntent = VoiceIntentMode.DICTATE },
                        onSwitchToQwerty = { onClose() },
                        onOpenClipboard = { runCatching { onClose() } },
                        onQuickChipClick = { chip -> runCatching { controller.runQuickEditChip(chip) } },
                        onStartRecording = { ensureMic { runCatching { controller.handleMicPressDown(VoiceIntentMode.SPEAK_TO_EDIT) } } },
                        onReleaseRecording = { runCatching { controller.handleMicPressUp() } },
                        onFinishHandsFree = { runCatching { controller.finishHandsFreeRecording() } },
                        onCancelRecording = { runCatching { controller.cancelRecordingOrProcessing() } },
                        onOpenHostApp = { runCatching { controller.closeVoiceSubPanel() }; onClose() },
                        onRetryLastAudio = { runCatching { controller.retryLastAudio() } },
                        onDismissError = { runCatching { controller.dismissErrorBanner() } },
                        onHideKeyboard = { onClose() },
                        onSwitchKeyboard = { onClose() },
                        onShowImePicker = { onClose() },
                    )

                    VoiceIntentMode.TRANSLATE -> RealtimeTranslatePanel(
                        uiState = uiState,
                        settings = st,
                        elapsedMs = elapsedMs,
                        amplitudeProvider = { runCatching { controller.currentAmplitude() }.getOrDefault(0f) },
                        onSelectLanguage = { lang -> runCatching { controller.selectTranslateLanguage(lang) } },
                        onStartRecording = { ensureMic { runCatching { controller.handleMicPressDown(VoiceIntentMode.TRANSLATE) } } },
                        onReleaseRecording = { runCatching { controller.handleMicPressUp() } },
                        onFinishHandsFree = { runCatching { controller.finishHandsFreeRecording() } },
                        onCancelRecording = { runCatching { controller.cancelRecordingOrProcessing() } },
                        onBackspace = { runCatching { controller.quickBackspace() } },
                        onEnterOrNewline = { runCatching { controller.quickEnter() } },
                        onHideKeyboard = { onClose() },
                        onSwitchKeyboard = { onClose() },
                        onShowImePicker = { onClose() },
                    )

                    VoiceIntentMode.JEV_COPILOT -> JevCopilotPanel(
                        uiState = uiState,
                        settings = st,
                        onAction = { action ->
                            runCatching { controller.handleImeUiAction(action) }
                        },
                    )
                }
            }
        }
    }
}


/** 工作台顶栏的小胶囊按钮。 */
@Composable
private fun WorkbenchToolChip(
    label: String,
    active: Boolean = false,
    accentColor: Color = Color.Unspecified,
    itemBgColor: Color = Color.Unspecified,
    textColor: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) accentColor.copy(alpha = 0.25f) else itemBgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) accentColor else textColor,
            fontSize = 11.sp
        )
    }
}
