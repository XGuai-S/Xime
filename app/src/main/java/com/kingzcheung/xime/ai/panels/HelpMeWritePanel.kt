package com.kingzcheung.xime.ai.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.ime.HelpWritePromptPreset
import com.kingzcheung.xime.ai.ime.ImeLogic
import com.kingzcheung.xime.ai.ime.ImeUiAction
import com.kingzcheung.xime.ai.ime.ImeUiState
import com.kingzcheung.xime.ai.ime.LocalKeyboardMetrics
import com.kingzcheung.xime.ai.ime.VoicePhase
import com.kingzcheung.xime.ai.ime.chrome.BottomCornerUtilityIcons
import com.kingzcheung.xime.ai.ime.chrome.ErrorContextBanner
import com.kingzcheung.xime.ai.ime.chrome.SubPanelHeaderBridgeBar
import com.kingzcheung.xime.ai.ime.chrome.SubPanelModeSwitchTrack
import com.kingzcheung.xime.ai.ime.chrome.SubPanelVoiceTriggerRow
import com.kingzcheung.xime.ai.ui.AppDestinations
import com.kingzcheung.xime.ai.ui.components.TypeFreeIcons
import com.kingzcheung.xime.ai.ui.theme.LocalTypeFreeKeyboardPalette
import com.kingzcheung.xime.ai.ui.theme.TypeFreeEmerald

/**
 * Smart filter for `HelpMeWritePanel` presets:
 * Retains backward-compatible signatures for existing tests (V310UiArchitectureAndFilterTest)
 * while providing v3.5 pure creative genre catalog and draft transform presets.
 */
object HelpWriteChipFilter {
    private val IM_CHAT_PACKAGES = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.tencent.wework",
        "com.ss.android.lark",
        "com.larksuite.suite",
        "com.alibaba.android.rimet",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "tw.nekomimi.nekogram",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.discord",
        "com.Slack",
        "jp.naver.line.android",
        "com.facebook.orca"
    )

    private val LONG_FORM_PRESET_IDS = setOf(
        "weekly_recap",
        "meeting_invite"
    )

    val SOCIAL_CONTENT_PACKAGES: Set<String> = setOf(
        "com.xingin.xhs",            // 小红书
        "com.ss.android.ugc.aweme",   // 抖音
        "com.dianping.v1",           // 大众点评
        "com.sina.weibo",            // 微博
        "tv.danmaku.bili",           // B站
        "com.zhihu.android",         // 知乎
        "com.smile.gifmaker",        // 快手
        "com.taobao.idlefish"        // 闲鱼
    )

    val shortRewritePresets: List<Pair<String, String>> = listOf(
        "✨ 润色得体" to "将这段话润色得更加自然得体、通顺清晰",
        "💼 职场专业" to "改为专业、干练的职场沟通表达",
        "🤝 委婉高情商" to "用委婉、高情商、顾及对方感受的语气改写",
        "✂️ 精简短句" to "精简冗余字词，保留核心意思"
    )

    val shortDraftPresets: List<Pair<String, String>> = listOf(
        "💬 礼貌回复" to "根据我说的要点，起草一段得体礼貌的回复",
        "🙏 委婉拒绝" to "根据我说的理由，写一段真诚委婉的谢绝回复",
        "⏰ 协调时间" to "起草一段简洁礼貌的沟通，询问并协调双方时间"
    )

    val longFormOfficePresets: List<Pair<String, String>> = listOf(
        "📝 工作简报" to "根据我口述的要点，整理成结构清晰的工作简报/周报要点",
        "✉️ 正式邮件" to "起草一封格式规范、语气得体的商务/工作邮件",
        "📌 要点提炼" to "将核心内容提炼为条理清晰的分点结论",
        "📋 整理待办" to "提取关键行动项，整理为清晰的待办清单"
    )

    // v3.5: 7大主动创作体裁
    val creativeGenres: List<HelpWritePromptPreset> = listOf(
        HelpWritePromptPreset(
            id = "genre_seed_note",
            label = "🌱 种草笔记",
            instruction = "根据要点撰写真诚有网感、分段清晰、重点突出的种草分享文案，自然不硬广"
        ),
        HelpWritePromptPreset(
            id = "genre_moments",
            label = "✨ 朋友圈文案",
            instruction = "根据要点撰写自然有呼吸感、不矫情不爹味的朋友圈/动态短文案"
        ),
        HelpWritePromptPreset(
            id = "genre_top_comment",
            label = "💬 高赞神评",
            instruction = "根据要点撰写幽默机智、有网感共鸣的评论互动短句"
        ),
        HelpWritePromptPreset(
            id = "genre_group_announcement",
            label = "📢 群通知/公告",
            instruction = "根据要点整理成重点清晰、时间事项一目了然的群通知或公告"
        ),
        HelpWritePromptPreset(
            id = "genre_leave_request",
            label = "🙏 请假/求助",
            instruction = "根据事由撰写得体真诚、干脆不卑微的请假或求助说明"
        ),
        HelpWritePromptPreset(
            id = "genre_weekly_recap",
            label = "📝 周报总结",
            instruction = "根据要点提炼为条理清晰、突出成果与下一步计划的工作简报/周报"
        ),
        HelpWritePromptPreset(
            id = "genre_business_email",
            label = "✉️ 商务邮件",
            instruction = "起草格式规范、语气专业得体的商务/工作邮件正文"
        )
    )

    // v3.5: 5大免开口一键成稿动作芯片
    val draftTransformPresets: List<HelpWritePromptPreset> = listOf(
        HelpWritePromptPreset(
            id = "transform_expand_note",
            label = "🌱 扩成种草文 ⚡",
            instruction = "以输入框草稿为核心要点，扩写成一篇真诚有网感、分段清晰、重点突出的小红书/朋友圈种草文案，自然不硬广"
        ),
        HelpWritePromptPreset(
            id = "transform_polish",
            label = "✨ 润色得体",
            instruction = "保持输入框草稿原意，润色得更加自然得体、通顺清晰，修正错别字与语病并优化排版"
        ),
        HelpWritePromptPreset(
            id = "transform_formal",
            label = "👔 职场专业",
            instruction = "将输入框草稿改写为专业、干练、符合职场沟通规范的正式表达"
        ),
        HelpWritePromptPreset(
            id = "transform_concise",
            label = "✂️ 精简短句",
            instruction = "精简输入框草稿中的冗余字词，提炼为短平快、表意准确的短句"
        ),
        HelpWritePromptPreset(
            id = "transform_catchy_title",
            label = "🔥 生成吸睛标题",
            instruction = "根据输入框草稿内容，生成 3 到 5 个极具吸引力、有点击欲望的吸睛标题供选择"
        )
    )

    fun isSocialContentPackage(targetPackage: String): Boolean {
        val pkg = targetPackage.trim().lowercase()
        if (pkg.isEmpty()) return false
        if (pkg in SOCIAL_CONTENT_PACKAGES) return true
        return pkg.contains("xhs") || pkg.contains("weibo") || pkg.contains("dianping") ||
            pkg.contains("zhihu") || pkg.contains("bili") || pkg.contains("aweme") ||
            pkg.contains("idlefish")
    }

    fun isImOrOfficePackage(targetPackage: String): Boolean {
        val pkg = targetPackage.trim().lowercase()
        if (pkg.isEmpty()) return false
        if (pkg in IM_CHAT_PACKAGES) return true
        return pkg.contains("wechat") || pkg.contains("mobileqq") || pkg.contains("telegram") ||
            pkg.contains("whatsapp") || pkg.contains("lark") || pkg.contains("feishu") ||
            pkg.contains("dingtalk") || pkg.contains("wework")
    }

    fun resolveCreativeGenres(targetPackage: String): List<HelpWritePromptPreset> {
        return when {
            isSocialContentPackage(targetPackage) -> {
                val topIds = setOf("genre_seed_note", "genre_top_comment")
                creativeGenres.filter { it.id in topIds } + creativeGenres.filterNot { it.id in topIds }
            }
            isImOrOfficePackage(targetPackage) -> {
                val topIds = setOf("genre_moments", "genre_group_announcement")
                creativeGenres.filter { it.id in topIds } + creativeGenres.filterNot { it.id in topIds }
            }
            else -> creativeGenres
        }
    }

    fun shouldShowLongFormPresets(targetPackage: String, chatContextActive: Boolean): Boolean {
        if (chatContextActive) return false
        val pkg = targetPackage.trim()
        if (pkg.isEmpty()) return true
        if (pkg in IM_CHAT_PACKAGES) return false
        val lower = pkg.lowercase()
        return !(lower.contains("wechat") ||
            lower.contains("mobileqq") ||
            lower.contains("telegram") ||
            lower.contains("whatsapp") ||
            lower.contains("lark") ||
            lower.contains("feishu") ||
            lower.contains("dingtalk"))
    }

    fun filterPresets(
        presets: List<HelpWritePromptPreset>,
        targetPackage: String,
        chatContextActive: Boolean
    ): List<HelpWritePromptPreset> {
        if (shouldShowLongFormPresets(targetPackage, chatContextActive)) {
            return presets
        }
        return presets.filterNot { preset ->
            preset.id in LONG_FORM_PRESET_IDS ||
                preset.label.contains("工作简报") ||
                preset.label.contains("邮件")
        }
    }

    fun resolveVisiblePresets(
        hasSelection: Boolean,
        targetPackage: String,
        chatContextActive: Boolean
    ): List<Pair<String, String>> {
        val base = if (hasSelection) shortRewritePresets else shortDraftPresets
        return if (shouldShowLongFormPresets(targetPackage, chatContextActive)) {
            base + longFormOfficePresets
        } else {
            base
        }
    }
}

@Composable
fun HelpMeWritePanel(
    uiState: ImeUiState,
    settings: AppSettings,
    amplitudeProvider: () -> Float = { 0f },
    amplitudes: List<Float> = emptyList(),
    elapsedMs: Long,
    onClose: () -> Unit,
    onOpenJevCopilot: () -> Unit = {},
    onAcceptChatContextDisclosure: () -> Unit = {},
    onDeclineChatContextDisclosure: () -> Unit = {},
    onToggleIgnoreChatContext: () -> Unit = {},
    onSwitchToQwerty: () -> Unit,
    onOpenClipboard: () -> Unit,
    onPresetClick: (HelpWritePromptPreset) -> Unit,
    onDirectGenerate: () -> Unit = {},
    onStartRecording: () -> Unit,
    onReleaseRecording: () -> Unit,
    onFinishHandsFree: () -> Unit,
    onCancelRecording: () -> Unit,
    onOpenHostApp: (Int) -> Unit,
    onRetryLastAudio: () -> Unit,
    onDismissError: () -> Unit,
    onHideKeyboard: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowImePicker: () -> Unit,
    onAction: (ImeUiAction) -> Unit = {}
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val phase = uiState.voicePhase
    val isBusy = phase is VoicePhase.Recording || phase is VoicePhase.Processing
    val editorDraft = uiState.currentEditorText.trim()
    val hasDraft = editorDraft.isNotBlank()
    val isSocialContent = HelpWriteChipFilter.isSocialContentPackage(uiState.targetPackage)
    val selectedPreset = uiState.selectedHelpWritePreset
    val genres = remember(uiState.targetPackage) {
        HelpWriteChipFilter.resolveCreativeGenres(uiState.targetPackage)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = (28f * s).dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Top-Left `Help me write · 帮我写` + 微标 + Top-Right Bridge
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
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    SubPanelModeSwitchTrack(
                        isJevActive = false,
                        jevEnabled = settings.jevCopilotEnabled,
                        onSelectHelpMeWrite = {},
                        onSelectJevCopilot = onOpenJevCopilot
                    )

                    if (hasDraft) {
                        // 当有草稿时显示翡翠绿微标「已读草稿 · N字」
                        Row(
                            modifier = Modifier
                                .height(22.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(TypeFreeEmerald.copy(alpha = 0.14f))
                                .border(1.dp, TypeFreeEmerald.copy(alpha = 0.48f), RoundedCornerShape(11.dp))
                                .padding(horizontal = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val badgeFg = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                            Icon(
                                imageVector = TypeFreeIcons.VoicePen,
                                contentDescription = null,
                                tint = badgeFg,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "已读草稿 · ${editorDraft.length}字",
                                color = badgeFg,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else if (isSocialContent) {
                        // 空框且命中内容社区包时显示微标「创作优先」
                        Row(
                            modifier = Modifier
                                .height(22.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(TypeFreeEmerald.copy(alpha = 0.14f))
                                .border(1.dp, TypeFreeEmerald.copy(alpha = 0.48f), RoundedCornerShape(11.dp))
                                .padding(horizontal = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val badgeFg = if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                            Text(
                                text = "创作优先",
                                color = badgeFg,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    } else if (!settings.jevCopilotEnabled) {
                        Text(
                            text = "Help me write · 帮我写",
                            color = palette.keyTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                SubPanelHeaderBridgeBar(
                    isFlypyChineseMode = uiState.isFlypyChineseMode,
                    inputSchemeShortBadge = uiState.activeInputScheme.shortBadge,
                    hasFreshClipboard = uiState.freshClipboardBannerItem != null,
                    onSwitchToQwerty = onSwitchToQwerty,
                    onOpenClipboard = onOpenClipboard,
                    closeDescription = "关闭帮我写",
                    onClose = onClose
                )
            }

            // Middle Content: Error / Mode B (有草稿一键成稿) / Mode A (空框主动创作)
            if (phase is VoicePhase.Error) {
                ErrorContextBanner(
                    error = phase,
                    onRetry = onRetryLastAudio,
                    onOpenApiSetup = { onOpenHostApp(AppDestinations.TAB_API) },
                    onOpenPermissionSetup = { onOpenHostApp(AppDestinations.TAB_HOME) },
                    onDismiss = onDismissError
                )
            } else if (hasDraft) {
                // 模式 B — 输入框有草稿静音态：紧凑草稿回显条 + 免开口一键成稿动作芯片
                Column(verticalArrangement = Arrangement.spacedBy((6f * s).dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.cardElevatedBg)
                            .border(1.dp, TypeFreeEmerald.copy(alpha = 0.38f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = (5f * s).dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = TypeFreeIcons.VoicePen,
                            contentDescription = null,
                            tint = TypeFreeEmerald,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "📝 输入框草稿：「${ImeLogic.formatSelectionPreview(editorDraft, 28)}」",
                            color = palette.keyTextPrimary,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 免开口一键成稿动作芯片
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HelpWriteChipFilter.draftTransformPresets.forEach { preset ->
                            val isExpand = preset.id == "transform_expand_note"
                            val chipBg = if (isExpand) TypeFreeEmerald else palette.cardElevatedBg
                            val chipBorder = if (isExpand) TypeFreeEmerald else palette.keyBorder
                            val chipFg = if (isExpand) Color.White else palette.keyTextPrimary
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(chipBg)
                                    .border(1.dp, chipBorder, RoundedCornerShape(14.dp))
                                    .clickable(enabled = !isBusy) {
                                        onAction(ImeUiAction.RunHelpWriteDraftTransform(preset))
                                    }
                                    .padding(horizontal = 11.dp, vertical = (6f * s).dp)
                            ) {
                                Text(
                                    text = preset.label,
                                    color = if (isBusy) palette.keyTextMuted else chipFg,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isExpand) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            } else {
                // 模式 A — 空框主动创作态：纯粹创作体裁芯片
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = if (selectedPreset != null) {
                            "已选「${selectedPreset.label}」· 按住下方麦克风说出要点即可成稿"
                        } else {
                            "选择体裁直接口述要点，或先回键盘敲几个关键词："
                        },
                        color = if (selectedPreset != null) {
                            if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                        } else {
                            palette.keyTextMuted
                        },
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        genres.forEach { preset ->
                            val isSelected = selectedPreset?.id == preset.id
                            val isRecommend = isSocialContent && preset.id == "genre_seed_note" && selectedPreset == null
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        when {
                                            isSelected -> TypeFreeEmerald
                                            isRecommend -> TypeFreeEmerald.copy(alpha = 0.12f)
                                            else -> palette.cardElevatedBg
                                        }
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = when {
                                            isSelected -> TypeFreeEmerald
                                            isRecommend -> TypeFreeEmerald.copy(alpha = 0.5f)
                                            else -> palette.keyBorder
                                        },
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable(enabled = !isBusy) { onPresetClick(preset) }
                                    .padding(horizontal = 11.dp, vertical = (6f * s).dp)
                            ) {
                                Text(
                                    text = if (isSelected) "✓ ${preset.label}" else preset.label,
                                    color = when {
                                        isBusy -> palette.keyTextMuted
                                        isSelected -> Color.White
                                        isRecommend -> if (palette.isDark) Color(0xFF6EE7B7) else Color(0xFF047857)
                                        else -> palette.keyTextPrimary
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected || isRecommend) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Bottom-Left Pulsing Circle Button + Prompt Description + Optional `直接生成`
            SubPanelVoiceTriggerRow(
                phase = phase,
                amplitudeProvider = amplitudeProvider,
                amplitudes = amplitudes,
                elapsedMs = elapsedMs,
                idleTitle = when {
                    hasDraft -> "按住口述修改要求 · 原地扩写/改写草稿"
                    selectedPreset != null -> "已选「${selectedPreset.label}」· 按住说出要点即可成稿"
                    else -> "Describe what to write · 按住口述你想写的内容"
                },
                idleSubtitle = when {
                    hasDraft -> "例如：改得更热情点 / 加上具体感谢的话，松手成稿"
                    selectedPreset != null -> "已选「${selectedPreset.label}」· 点其他胶囊立即按新要求重写，或按住说话补充要点"
                    else -> "口述你的核心要点，松手一键生成自然成稿"
                },
                recordingTitle = when {
                    hasDraft -> "正在聆听草稿修改要求…"
                    selectedPreset != null -> "已选「${selectedPreset.label}」· 正在聆听要点"
                    else -> "Describe what to write · 正在聆听需求"
                },
                // 「直接生成」按钮已移除：点任意胶囊即按该要求立即生成
                directActionLabel = null,
                onDirectActionClick = {},
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
