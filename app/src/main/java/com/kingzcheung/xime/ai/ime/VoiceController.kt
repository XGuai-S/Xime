package com.kingzcheung.xime.ai.ime

import android.view.inputmethod.ExtractedTextRequest
import com.kingzcheung.xime.ai.audio.WavAudioRecorder
import com.kingzcheung.xime.ai.bubble.CapturedChatContext
import com.kingzcheung.xime.ai.bubble.ChatScreenContextExtractor
import com.kingzcheung.xime.ai.bubble.XimeAccessibilityService
import com.kingzcheung.xime.ai.bubble.ViewportCaptureOutcome
import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.DefaultPresets
import com.kingzcheung.xime.ai.data.HistoryRecord
import com.kingzcheung.xime.ai.data.PipelineMode
import com.kingzcheung.xime.ai.data.VoiceIntentMode
import com.kingzcheung.xime.ai.jev.InputConnectionDraftTarget
import com.kingzcheung.xime.ai.jev.JevDomainProfile
import com.kingzcheung.xime.ai.jev.JevDraftSession
import com.kingzcheung.xime.ai.network.AiPipelineEngine
import com.kingzcheung.xime.ai.network.PipelineStage
import com.kingzcheung.xime.ai.network.asr.StreamingAsrSession
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Controller encapsulating microphone recording, streaming ASR preview / live write,
 * ArcTranslate wheel, Speak-to-Edit, Help-me-Write, `==` Ask AI, and voice pipeline execution.
 */
class VoiceController(
    private val host: ImeHostCallbacks,
    private val audioRecorderProvider: () -> WavAudioRecorder,
    private val onExternalCommitToEngine: (String) -> Unit,
    private val onResetComposing: () -> Unit,
    private val chatContextCaptureProvider: (
        expectedPackage: String,
        appName: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean
    ) -> CapturedChatContext = { pkg, app, s, inc, pwd, ign ->
        XimeAccessibilityService.captureChatContextIfAvailable(pkg, app, s, inc, pwd, ign, forJevCopilot = false)
    },
    private val chatScreenshotCaptureProvider: suspend (
        expectedPackage: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean
    ) -> Triple<String, Int, Int>? = { pkg, s, inc, pwd, ign ->
        XimeAccessibilityService.captureChatScreenshotIfAvailable(pkg, s, inc, pwd, ign, forJevCopilot = false)
    }
) {

    private var activePipelineJob: Job? = null
    private var chatScreenshotJob: Job? = null
    private var jevInferenceJob: Job? = null
    private val jevDraftSession = JevDraftSession()
    private var activeStreamingSession: StreamingAsrSession? = null
    private var streamingPreviewJob: Job? = null
    private var handsFreeSilenceJob: Job? = null
    private var toastDismissJob: Job? = null
    // 单一录音器实例：start/stop 必须用同一个实例，否则 start 的 recorder 永远不被 stop，
    // 会一直占用麦克风与音频焦点，导致下次录音被抢焦点、录到静音、识别不出。
    private val recorder: WavAudioRecorder by lazy { audioRecorderProvider() }

    private var activeComposer: StreamingComposer? = null
    /** 本次语音编辑是否使用了"无选区→整框回落"（决定上屏时插入还是整框替换）。 */
    private var speakToEditWholeText = false
    private var preSessionSurroundingContext: String = ""
    private var lastKnownSelectionStart: Int = 0
    private var lastKnownSelectionEnd: Int = 0

    @Volatile
    var micPressDownTimeMs: Long = 0L
        private set

    fun warmVoiceConnectionsInBackground() {
        val snap = host.repository.settings.value
        host.scope.launch {
            runCatching {
                host.aiEngine.prewarmLiveStreamingIfNeeded(snap)
                host.aiEngine.prewarmEndpoints(snap)
            }
        }
    }

    fun openOrRestoreVoicePenPanel() {
        // 打开工作台即后台预热本地流式（若已开启本地识别），让首次说话少丢开头
        runCatching { if (host.repository.settings.value.workbenchLocalSttEnabled) warmupLocalStreaming() }
        host.performKeyFeedback(strong = false)
        finalizeJevDraftAndClearMemory(clearContext = false)
        onResetComposing()
        val stateSnap = host.stateFlow.value
        val isPassword = ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        val editorDraft = if (isPassword) "" else host.readFullEditorTextSafely().trim()
        val isSocialPkg = com.kingzcheung.xime.ai.panels.HelpWriteChipFilter.isSocialContentPackage(stateSnap.targetPackage)
        val defaultPreset = if (isSocialPkg && editorDraft.isBlank()) {
            com.kingzcheung.xime.ai.panels.HelpWriteChipFilter.creativeGenres.firstOrNull { it.id == "genre_seed_note" }
        } else {
            null
        }

        host.stateFlow.update { prev ->
            if (prev.rawSelectedText.isNotBlank()) {
                prev.copy(
                    tabMode = KeyboardTabMode.VOICE,
                    ignoreSelectionForThisTurn = false,
                    voiceSubPanel = VoiceSubPanel.SPEAK_TO_EDIT,
                    activeIntentMode = VoiceIntentMode.SPEAK_TO_EDIT,
                    capturedChatContext = CapturedChatContext(),
                    ignoreChatContextForThisTurn = false,
                    showChatContextDisclosurePrompt = false,
                    currentEditorText = editorDraft,
                    jevCopilotResult = null,
                    selectedJevStrategyIndex = -1,
                    isJevLoading = false,
                    jevUserBottomLine = "",
                    jevExcludedReplies = emptyList(),
                    isFullScreenPeekCapturing = false,
                    isJevContextInspectorOpen = false,
                    selectedHelpWritePreset = null,
                    bigBangClipboardItem = null,
                    isClipboardOpen = false,
                    isToolPanelOpen = false,
                    isPunctuationStripOpen = false,
                    isStylePickerExpanded = false,
                    flypyComposingCode = "",
                    preeditDisplay = "",
                    flypyCandidates = emptyList(),
                    isFlypyCandidateSheetExpanded = false
                )
            } else {
                prev.copy(
                    tabMode = KeyboardTabMode.VOICE,
                    voiceSubPanel = VoiceSubPanel.HELP_ME_WRITE,
                    activeIntentMode = VoiceIntentMode.HELP_ME_WRITE,
                    capturedChatContext = CapturedChatContext(),
                    ignoreChatContextForThisTurn = false,
                    showChatContextDisclosurePrompt = false,
                    currentEditorText = editorDraft,
                    selectedHelpWritePreset = defaultPreset,
                    jevCopilotResult = null,
                    selectedJevStrategyIndex = -1,
                    isJevLoading = false,
                    jevUserBottomLine = "",
                    jevExcludedReplies = emptyList(),
                    isFullScreenPeekCapturing = false,
                    isJevContextInspectorOpen = false,
                    bigBangClipboardItem = null,
                    isClipboardOpen = false,
                    isToolPanelOpen = false,
                    isPunctuationStripOpen = false,
                    isStylePickerExpanded = false,
                    flypyComposingCode = "",
                    preeditDisplay = "",
                    flypyCandidates = emptyList(),
                    isFlypyCandidateSheetExpanded = false
                )
            }
        }
    }

    fun openJevCopilotPanel() {
        val settingsSnap = host.repository.settings.value
        if (!settingsSnap.jevCopilotEnabled) return
        host.performKeyFeedback(strong = false)
        finalizeJevDraftAndClearMemory(clearContext = true)
        onResetComposing()

        val stateSnap = host.stateFlow.value
        val isPassword = ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        val accessibilityConnected =
            XimeAccessibilityService.activeInstance != null || XimeAccessibilityService.debugSimulatedNodesForTest != null
        val shouldPromptDisclosure = accessibilityConnected &&
            ChatScreenContextExtractor.shouldPromptJevFirstTimeDisclosure(
                settings = settingsSnap,
                isIncognitoMode = stateSnap.isIncognitoMode,
                isPasswordInput = isPassword,
                targetInputPackage = stateSnap.targetPackage
            )
        val capturedContext = if (!shouldPromptDisclosure) {
            XimeAccessibilityService.captureChatContextIfAvailable(
                expectedPackage = stateSnap.targetPackage,
                appName = stateSnap.targetAppName,
                settings = settingsSnap,
                isIncognitoMode = stateSnap.isIncognitoMode,
                isPasswordInput = isPassword,
                ignoreChatContextForTurn = false,
                forJevCopilot = true
            )
        } else {
            CapturedChatContext(
                packageName = stateSnap.targetPackage,
                appName = stateSnap.targetAppName,
                domainProfile = JevDomainProfile.fromPackage(stateSnap.targetPackage)
            )
        }

        host.stateFlow.update { prev ->
            prev.copy(
                tabMode = KeyboardTabMode.VOICE,
                voiceSubPanel = VoiceSubPanel.JEV_COPILOT,
                activeIntentMode = VoiceIntentMode.JEV_COPILOT,
                capturedChatContext = capturedContext,
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = shouldPromptDisclosure,
                jevCopilotResult = null,
                selectedJevStrategyIndex = -1,
                isJevLoading = !shouldPromptDisclosure,
                isJevThinking = false,
                jevErrorMessage = null,
                jevUserBottomLine = "",
                jevExcludedReplies = emptyList(),
                isFullScreenPeekCapturing = false,
                isJevContextInspectorOpen = false,
                selectedHelpWritePreset = null,
                bigBangClipboardItem = null,
                isClipboardOpen = false,
                isToolPanelOpen = false,
                isPunctuationStripOpen = false,
                isStylePickerExpanded = false,
                flypyComposingCode = "",
                preeditDisplay = "",
                flypyCandidates = emptyList(),
                isFlypyCandidateSheetExpanded = false
            )
        }

        if (!shouldPromptDisclosure) {
            triggerJevCopilotInference(isRefresh = false)
        } else {
            warmVoiceConnectionsInBackground()
        }
    }

    fun selectJevStrategyCard(index: Int) {
        val stateSnap = host.stateFlow.value
        val option = stateSnap.jevCopilotResult?.options?.getOrNull(index) ?: return
        host.performKeyFeedback(strong = false)
        if (stateSnap.isFlypyComposing) {
            onResetComposing()
        }
        val ic = host.currentInputConnection()
        if (ic != null) {
            jevDraftSession.applyStrategyCard(
                target = InputConnectionDraftTarget(ic),
                cardIndex = index,
                rawStrategyText = option.replyText,
                redactionSession = stateSnap.capturedChatContext.redactionSession
            )
        }
        host.stateFlow.update {
            it.copy(
                selectedJevStrategyIndex = index,
                isJevContextInspectorOpen = false
            )
        }
    }

    fun toggleJevContextInspector() {
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            it.copy(isJevContextInspectorOpen = !it.isJevContextInspectorOpen)
        }
    }

    fun refreshJevStrategies() {
        val stateSnap = host.stateFlow.value
        if (stateSnap.isJevLoading) return
        host.performKeyFeedback(strong = false)
        val prevReplies = stateSnap.jevCopilotResult?.options?.map { it.replyText }.orEmpty()
        val updatedExcluded = (stateSnap.jevExcludedReplies + prevReplies)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeLast(6)

        val settingsSnap = host.repository.settings.value
        val isPassword = ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        val refreshedContext = if (!stateSnap.showChatContextDisclosurePrompt) {
            XimeAccessibilityService.captureChatContextIfAvailable(
                expectedPackage = stateSnap.targetPackage,
                appName = stateSnap.targetAppName,
                settings = settingsSnap,
                isIncognitoMode = stateSnap.isIncognitoMode,
                isPasswordInput = isPassword,
                ignoreChatContextForTurn = stateSnap.ignoreChatContextForThisTurn,
                forJevCopilot = true
            )
        } else {
            stateSnap.capturedChatContext
        }

        host.stateFlow.update {
            it.copy(
                capturedChatContext = refreshedContext,
                selectedJevStrategyIndex = -1,
                isJevLoading = true,
                isJevThinking = false,
                jevErrorMessage = null,
                isJevContextInspectorOpen = false,
                jevExcludedReplies = updatedExcluded
            )
        }
        triggerJevCopilotInference(isRefresh = true)
    }

    fun triggerFullScreenPeekCapture(onRequestComputeInsets: () -> Unit) {
        val stateSnap = host.stateFlow.value
        if (stateSnap.isFullScreenPeekCapturing || stateSnap.isJevLoading) return
        host.performKeyFeedback(strong = false)
        jevInferenceJob?.cancel()
        chatScreenshotJob?.cancel()
        host.stateFlow.update {
            it.copy(
                isJevContextInspectorOpen = false,
                isFullScreenPeekCapturing = true,
                isJevLoading = true,
                isJevThinking = false,
                jevErrorMessage = null
            )
        }

        if (XimeAccessibilityService.activeInstance != null) {
            warmVoiceConnectionsInBackground()
        }

        jevInferenceJob = host.scope.launch {
            try {
                host.stateFlow.update {
                    it.copy(
                        isJevContextInspectorOpen = false,
                        isFullScreenPeekCapturing = true,
                        isJevLoading = true,
                        jevErrorMessage = null
                    )
                }
                onRequestComputeInsets()
                delay(240L)

                val curr = host.stateFlow.value
                val settingsSnap = host.repository.settings.value
                val isPassword = ImeLogic.isPasswordEditorInputType(curr.currentInputType)
                val fullScreenCtx = XimeAccessibilityService.captureChatContextIfAvailable(
                    expectedPackage = curr.targetPackage,
                    appName = curr.targetAppName,
                    settings = settingsSnap,
                    isIncognitoMode = curr.isIncognitoMode,
                    isPasswordInput = isPassword,
                    ignoreChatContextForTurn = false,
                    forJevCopilot = true,
                    isFullScreenPeek = true
                )
                val fullScreenOutcome = if (
                    settingsSnap.chatContextSendScreenshot ||
                    ChatScreenContextExtractor.shouldTriggerOcrForCustomCanvas(fullScreenCtx)
                ) {
                    runCatching {
                        XimeAccessibilityService.captureChatViewportOutcomeIfAvailable(
                            expectedPackage = curr.targetPackage,
                            settings = settingsSnap,
                            isIncognitoMode = curr.isIncognitoMode,
                            isPasswordInput = isPassword,
                            ignoreChatContextForTurn = false,
                            forJevCopilot = true,
                            existingMessages = fullScreenCtx.formattedMessages,
                            redactionSession = fullScreenCtx.redactionSession,
                            isFullScreenPeek = true
                        )
                    }.getOrNull()
                } else {
                    null
                }
                val mergedCtx = if (fullScreenOutcome != null) {
                    fullScreenCtx.withViewportOutcome(
                        outcome = fullScreenOutcome,
                        fallbackPackage = curr.targetPackage,
                        fallbackAppName = curr.targetAppName
                    )
                } else {
                    fullScreenCtx
                }

                host.stateFlow.update {
                    it.copy(
                        isFullScreenPeekCapturing = false,
                        isJevContextInspectorOpen = false,
                        capturedChatContext = mergedCtx,
                        ignoreChatContextForThisTurn = false,
                        showChatContextDisclosurePrompt = false,
                        selectedJevStrategyIndex = -1
                    )
                }
                onRequestComputeInsets()
            } catch (e: CancellationException) {
                host.stateFlow.update { it.copy(isFullScreenPeekCapturing = false, isJevLoading = false) }
                onRequestComputeInsets()
                throw e
            } catch (_: Throwable) {
                host.stateFlow.update { it.copy(isFullScreenPeekCapturing = false) }
                onRequestComputeInsets()
            }
            executeJevCopilotInferenceSuspend(isRefresh = false, skipViewportRecapture = true)
        }
    }

    fun triggerJevCopilotInference(
        isRefresh: Boolean = false,
        overrideBottomLine: String? = null,
        wavFile: File? = null,
        pretranscribedSpeech: String? = null
    ) {
        jevInferenceJob?.cancel()

        jevInferenceJob = host.scope.launch {
            executeJevCopilotInferenceSuspend(
                isRefresh = isRefresh,
                overrideBottomLine = overrideBottomLine,
                wavFile = wavFile,
                pretranscribedSpeech = pretranscribedSpeech
            )
        }
    }

    private suspend fun executeJevCopilotInferenceSuspend(
        @Suppress("UNUSED_PARAMETER") isRefresh: Boolean = false,
        overrideBottomLine: String? = null,
        wavFile: File? = null,
        pretranscribedSpeech: String? = null,
        skipViewportRecapture: Boolean = false
    ) {
        val result: Result<Pair<com.kingzcheung.xime.ai.jev.JevCopilotResult, String>> = try {
            val settingsSnap = host.repository.settings.value
            val initialSnap = host.stateFlow.value
            val isPassword = ImeLogic.isPasswordEditorInputType(initialSnap.currentInputType)

            host.stateFlow.update {
                it.copy(
                    isJevLoading = true,
                    isJevThinking = false,
                    jevErrorMessage = null,
                    isJevContextInspectorOpen = false,
                    selectedJevStrategyIndex = -1
                )
            }

            val canUseVision = host.aiEngine.canJevUseVisionScreenshot(settingsSnap)
            val needsViewportCaptureOrOcr = !skipViewportRecapture &&
                !initialSnap.ignoreChatContextForThisTurn &&
                !initialSnap.showChatContextDisclosurePrompt &&
                (
                    (canUseVision && initialSnap.capturedChatContext.screenshotJpegBase64.isBlank()) ||
                        ChatScreenContextExtractor.shouldTriggerOcrForCustomCanvas(initialSnap.capturedChatContext)
                    )
            if (needsViewportCaptureOrOcr) {
                if (XimeAccessibilityService.activeInstance != null) {
                    warmVoiceConnectionsInBackground()
                }
                val capturedOutcome = runCatching {
                    XimeAccessibilityService.captureChatViewportOutcomeIfAvailable(
                        expectedPackage = initialSnap.targetPackage,
                        settings = settingsSnap,
                        isIncognitoMode = initialSnap.isIncognitoMode,
                        isPasswordInput = isPassword,
                        ignoreChatContextForTurn = initialSnap.ignoreChatContextForThisTurn,
                        forJevCopilot = true,
                        existingMessages = initialSnap.capturedChatContext.formattedMessages,
                        redactionSession = initialSnap.capturedChatContext.redactionSession
                    )
                }.getOrNull()
                // 捕获失败提示：不写死应用名（通用），且 6 秒内不重复弹，避免连点刷屏
                if (capturedOutcome == null) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastCaptureFailToastAt > 6000L) {
                        lastCaptureFailToastAt = now
                        val appLabel = initialSnap.targetAppName.ifBlank { "当前应用" }
                        host.showQuickActionToast("⚠ 读不到聊天画面：请让${appLabel}保持在前台")
                    }
                }
                FileLogger.d(
                    TAG,
                    "jev capture: outcome=${capturedOutcome != null} " +
                        "msgs=${capturedOutcome?.mergedMessages?.size ?: 0} " +
                        "shot=${!capturedOutcome?.screenshotJpegBase64.isNullOrBlank()} " +
                        "topology=${capturedOutcome?.topology?.roleMode?.name ?: "-"}"
                )
                if (capturedOutcome != null) {
                    host.stateFlow.update { curr ->
                        val base = curr.capturedChatContext
                        curr.copy(
                            capturedChatContext = base.withViewportOutcome(
                                outcome = capturedOutcome,
                                fallbackPackage = initialSnap.targetPackage,
                                fallbackAppName = initialSnap.targetAppName
                            )
                        )
                    }
                }
            }

            val latest = host.stateFlow.value
            val (activeStyle, _) = host.repository.resolveActiveStyle(latest.targetPackage, latest.currentInputType)
            val domainProfile = if (latest.capturedChatContext.packageName.isNotBlank()) {
                latest.capturedChatContext.domainProfile
            } else {
                JevDomainProfile.fromPackage(latest.targetPackage)
            }
            val effectiveBottomLine = overrideBottomLine ?: latest.jevUserBottomLine
            val effectiveTopology = if (latest.ignoreChatContextForThisTurn) {
                com.kingzcheung.xime.ai.jev.ChatTopology()
            } else {
                latest.capturedChatContext.topology
            }

            // 诊断：打印实际发给模型的消息条数与首字（不打印完整内容，避免隐私）
            runCatching {
                latest.effectiveChatContextMessages.take(6).forEachIndexed { i, m ->
                    FileLogger.d(TAG, "jev ctx[$i] len=${m.length} head=${m.take(12)}")
                }
            }
            FileLogger.d(
                TAG,
                "jev request: ctxMsgs=${latest.effectiveChatContextMessages.size} " +
                    "shot=${latest.effectiveChatScreenshotBase64.isNotBlank()} " +
                    "bottomLine=${effectiveBottomLine.isNotBlank()} " +
                    "topology=${effectiveTopology.roleMode.name} (group=${effectiveTopology.isGroupChat} others=${effectiveTopology.distinctOtherSenders.size})"
            )
            host.aiEngine.runJevCopilot(
                settings = settingsSnap,
                activeStyle = activeStyle,
                targetAppName = latest.targetAppName,
                domainProfile = domainProfile,
                chatContextMessages = latest.effectiveChatContextMessages,
                chatContextScreenshotBase64 = latest.effectiveChatScreenshotBase64,
                userBottomLine = effectiveBottomLine,
                excludedPreviousReplies = latest.jevExcludedReplies,
                redactionSession = latest.capturedChatContext.redactionSession,
                wavFile = wavFile,
                preTranscribedBottomLine = pretranscribedSpeech,
                chatTopology = effectiveTopology,
                targetPackageName = latest.capturedChatContext.packageName.ifBlank { latest.targetPackage },
                onThinkingStarted = {
                    host.stateFlow.update { curr ->
                        if (curr.isJevLoading && curr.jevCopilotResult == null) {
                            curr.copy(isJevThinking = true)
                        } else {
                            curr
                        }
                    }
                },
                onPartialResult = { partialResult ->
                    host.stateFlow.update { curr ->
                        if (!curr.isJevLoading) {
                            curr
                        } else {
                            val prevSelectedReply = curr.jevCopilotResult?.options
                                ?.getOrNull(curr.selectedJevStrategyIndex)
                                ?.replyText
                            val mappedIndex = if (!prevSelectedReply.isNullOrBlank()) {
                                partialResult.options.indexOfFirst { it.replyText == prevSelectedReply }
                            } else {
                                -1
                            }
                            curr.copy(
                                jevCopilotResult = partialResult,
                                isJevThinking = false,
                                jevErrorMessage = null,
                                selectedJevStrategyIndex = mappedIndex
                            )
                        }
                    }
                }
            )
        } catch (e: CancellationException) {
            host.stateFlow.update { it.copy(isJevLoading = false, isJevThinking = false) }
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            // Wall 4: Ephemeral screenshot bytes & audio file wiped immediately after request
            if (wavFile != null) {
                runCatching { wavFile.delete() }
            }
            host.stateFlow.update { curr ->
                if (curr.capturedChatContext.screenshotJpegBase64.isNotEmpty()) {
                    curr.copy(
                        capturedChatContext = curr.capturedChatContext.copy(
                            screenshotJpegBase64 = ""
                        )
                    )
                } else {
                    curr
                }
            }
        }

        result.fold(
            onSuccess = { (jevResult, resolvedBottomLine) ->
                host.performHaptic(strong = false)
                host.stateFlow.update { curr ->
                    val prevSelectedReply = curr.jevCopilotResult?.options
                        ?.getOrNull(curr.selectedJevStrategyIndex)
                        ?.replyText
                    val preservedSelectedIdx = if (!prevSelectedReply.isNullOrBlank()) {
                        jevResult.options.indexOfFirst { it.replyText == prevSelectedReply }
                    } else {
                        -1
                    }
                    curr.copy(
                        voicePhase = VoicePhase.Idle,
                        jevCopilotResult = jevResult,
                        selectedJevStrategyIndex = preservedSelectedIdx,
                        isJevLoading = false,
                        isJevThinking = false,
                        jevErrorMessage = null,
                        jevUserBottomLine = resolvedBottomLine
                    )
                }
            },
            onFailure = { err ->
                if (err is CancellationException) return
                val friendlyError = formatJevUserFacingError(err)
                host.performHaptic(strong = true)
                host.stateFlow.update { curr ->
                    curr.copy(
                        voicePhase = VoicePhase.Idle,
                        isJevLoading = false,
                        isJevThinking = false,
                        jevErrorMessage = friendlyError
                    )
                }
                host.showQuickActionToast("⚠ ${friendlyError.take(24)}")
            }
        )
    }

    fun finalizeJevDraftAndClearMemory(clearContext: Boolean = true) {
        jevInferenceJob?.cancel()
        jevInferenceJob = null
        if (jevDraftSession.hasActiveDraft) {
            val ic = host.currentInputConnection()
            val committed = if (ic != null) {
                jevDraftSession.commitAndFinishOnExit(InputConnectionDraftTarget(ic))
            } else {
                val draft = jevDraftSession.currentDraftText
                jevDraftSession.resetSessionOnly()
                draft
            }
            if (!committed.isNullOrEmpty()) {
                onExternalCommitToEngine(committed)
                val undo = ImeLogic.createUndoSnapshot(
                    committedText = committed,
                    replacedSelection = ""
                )
                host.pushUndoSnapshot(undo)
                host.stateFlow.update {
                    it.copy(undoSnapshot = host.peekTopUndoSnapshot())
                }
            }
        } else {
            jevDraftSession.resetSessionOnly()
        }
        host.stateFlow.update { prev ->
            prev.copy(
                jevCopilotResult = null,
                selectedJevStrategyIndex = -1,
                isJevLoading = false,
                jevErrorMessage = null,
                jevUserBottomLine = "",
                jevExcludedReplies = emptyList(),
                isFullScreenPeekCapturing = false,
                isJevContextInspectorOpen = false,
                capturedChatContext = if (clearContext) CapturedChatContext() else prev.capturedChatContext
            )
        }
    }

    private fun triggerChatViewportScreenshotCapture(
        expectedPackage: String,
        appName: String,
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean
    ) {
        chatScreenshotJob?.cancel()
        chatScreenshotJob = host.scope.launch {
            val baseSnap = host.stateFlow.value.capturedChatContext
            val outcome = runCatching {
                XimeAccessibilityService.captureChatViewportOutcomeIfAvailable(
                    expectedPackage = expectedPackage,
                    settings = settings,
                    isIncognitoMode = isIncognitoMode,
                    isPasswordInput = isPasswordInput,
                    ignoreChatContextForTurn = false,
                    forJevCopilot = false,
                    existingMessages = baseSnap.formattedMessages,
                    redactionSession = baseSnap.redactionSession
                )
            }.getOrNull() ?: runCatching {
                val legacyShot = chatScreenshotCaptureProvider(
                    expectedPackage,
                    settings,
                    isIncognitoMode,
                    isPasswordInput,
                    false
                )
                if (legacyShot != null && legacyShot.first.isNotBlank()) {
                    ViewportCaptureOutcome(
                        screenshotJpegBase64 = legacyShot.first,
                        width = legacyShot.second,
                        height = legacyShot.third,
                        mergedMessages = baseSnap.formattedMessages,
                        redactionSession = baseSnap.redactionSession
                    )
                } else {
                    null
                }
            }.getOrNull()

            if (outcome != null && (outcome.screenshotJpegBase64.isNotBlank() || outcome.mergedMessages.isNotEmpty())) {
                host.stateFlow.update { curr ->
                    if (curr.voiceSubPanel == VoiceSubPanel.HELP_ME_WRITE) {
                        val baseCtx = curr.capturedChatContext
                        curr.copy(
                            capturedChatContext = baseCtx.withViewportOutcome(
                                outcome = outcome,
                                fallbackPackage = expectedPackage,
                                fallbackAppName = appName
                            )
                        )
                    } else {
                        curr
                    }
                }
            }
        }
    }

    fun acceptChatContextDisclosure() {
        host.performKeyFeedback(strong = false)
        val updatedSettings = host.repository.settings.value.copy(
            chatContextReplyEnabled = true,
            chatContextDisclosureAccepted = true,
            chatContextDisclosureDismissed = false
        )
        host.repository.updateSettings {
            it.copy(
                chatContextReplyEnabled = true,
                chatContextDisclosureAccepted = true,
                chatContextDisclosureDismissed = false
            )
        }
        val stateSnap = host.stateFlow.value
        val isPassword = ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        val isJev = stateSnap.voiceSubPanel == VoiceSubPanel.JEV_COPILOT
        val captured = if (isJev) {
            XimeAccessibilityService.captureChatContextIfAvailable(
                expectedPackage = stateSnap.targetPackage,
                appName = stateSnap.targetAppName,
                settings = updatedSettings,
                isIncognitoMode = stateSnap.isIncognitoMode,
                isPasswordInput = isPassword,
                ignoreChatContextForTurn = false,
                forJevCopilot = true
            )
        } else {
            chatContextCaptureProvider(
                stateSnap.targetPackage,
                stateSnap.targetAppName,
                updatedSettings,
                stateSnap.isIncognitoMode,
                isPassword,
                false
            )
        }
        host.stateFlow.update {
            it.copy(
                capturedChatContext = captured,
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                isJevLoading = isJev,
                jevErrorMessage = if (isJev) null else it.jevErrorMessage
            )
        }
        if (isJev) {
            triggerJevCopilotInference(isRefresh = false)
        } else {
            triggerChatViewportScreenshotCapture(
                expectedPackage = stateSnap.targetPackage,
                appName = stateSnap.targetAppName,
                settings = updatedSettings,
                isIncognitoMode = stateSnap.isIncognitoMode,
                isPasswordInput = isPassword
            )
        }
    }

    fun declineChatContextDisclosure() {
        host.performKeyFeedback(strong = false)
        chatScreenshotJob?.cancel()
        host.repository.updateSettings {
            it.copy(
                chatContextReplyEnabled = false,
                chatContextDisclosureAccepted = false,
                chatContextDisclosureDismissed = true
            )
        }
        val isJev = host.stateFlow.value.voiceSubPanel == VoiceSubPanel.JEV_COPILOT
        host.stateFlow.update {
            it.copy(
                capturedChatContext = CapturedChatContext(
                    packageName = it.targetPackage,
                    appName = it.targetAppName,
                    domainProfile = JevDomainProfile.fromPackage(it.targetPackage)
                ),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                isJevLoading = isJev,
                jevErrorMessage = if (isJev) null else it.jevErrorMessage
            )
        }
        if (isJev) {
            triggerJevCopilotInference(isRefresh = false)
        }
    }

    fun toggleIgnoreChatContextForTurn() {
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            it.copy(ignoreChatContextForThisTurn = !it.ignoreChatContextForThisTurn)
        }
    }

    fun toggleHelpWritePreset(preset: HelpWritePromptPreset) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return
        host.performKeyFeedback(strong = false)
        host.stateFlow.update { curr ->
            val nextSelected = if (curr.selectedHelpWritePreset?.id == preset.id) null else preset
            curr.copy(selectedHelpWritePreset = nextSelected)
        }
    }

    fun runSelectedHelpWritePresetDirect() {
        val selected = host.stateFlow.value.selectedHelpWritePreset ?: return
        runHelpWritePreset(selected)
    }

    fun closeVoiceSubPanel() {
        host.performKeyFeedback(strong = false)
        chatScreenshotJob?.cancel()
        finalizeJevDraftAndClearMemory(clearContext = true)
        if (host.stateFlow.value.voicePhase is VoicePhase.Recording ||
            host.stateFlow.value.voicePhase is VoicePhase.Processing
        ) {
            cancelRecordingOrProcessing()
        }
        host.stateFlow.update {
            it.copy(
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE,
                capturedChatContext = CapturedChatContext(),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                isJevContextInspectorOpen = false,
                selectedHelpWritePreset = null
            )
        }
    }

    fun dismissSpeakToEditForTurn() {
        host.performKeyFeedback(strong = false)
        if (host.stateFlow.value.voicePhase is VoicePhase.Recording ||
            host.stateFlow.value.voicePhase is VoicePhase.Processing
        ) {
            cancelRecordingOrProcessing()
        }
        host.stateFlow.update {
            it.copy(
                ignoreSelectionForThisTurn = true,
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE
            )
        }
    }

    fun selectTranslateLanguage(language: String) {
        if (language.isBlank()) return
        host.performKeyFeedback(strong = false)
        host.repository.updateSettings {
            it.copy(activeTranslateTarget = language)
        }
        host.stateFlow.update {
            it.copy(arcSelectedLanguage = language)
        }
    }

    fun togglePunctuationStrip() {
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            it.copy(
                isPunctuationStripOpen = !it.isPunctuationStripOpen,
                isStylePickerExpanded = false
            )
        }
    }

    fun enterArcTranslateMode(startedFromHandsFree: Boolean) {
        val curr = host.stateFlow.value
        if (curr.voicePhase !is VoicePhase.Recording) return
        val settings = host.repository.settings.value
        val defaultLang = settings.activeTranslateTarget.ifBlank {
            settings.translationTargets.firstOrNull() ?: DefaultPresets.defaultTranslationTargets().first()
        }
        activeComposer?.cancelSession(rollbackWrittenText = true)
        activeComposer = null
        host.performHaptic(strong = false)
        host.stateFlow.update {
            it.copy(
                isArcTranslateActive = true,
                arcStartedFromHandsFree = startedFromHandsFree,
                isArcCancelHovered = false,
                arcSelectedLanguage = defaultLang,
                activeIntentMode = VoiceIntentMode.TRANSLATE,
                isPunctuationStripOpen = false,
                isStylePickerExpanded = false,
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
    }

    fun updateArcTranslateSelection(language: String, cancelHovered: Boolean) {
        val prev = host.stateFlow.value
        if (!prev.isArcTranslateActive) return
        val changed = prev.arcSelectedLanguage != language || prev.isArcCancelHovered != cancelHovered
        if (!changed) return

        host.performHaptic(strong = false)
        host.stateFlow.update {
            it.copy(
                arcSelectedLanguage = language,
                isArcCancelHovered = cancelHovered
            )
        }
    }

    fun releaseArcTranslate() {
        val snapshot = host.stateFlow.value
        micPressDownTimeMs = 0L
        if (!snapshot.isArcTranslateActive) {
            handleMicPressUp()
            return
        }

        if (snapshot.isArcCancelHovered) {
            cancelRecordingOrProcessing()
            return
        }

        val chosenLang = snapshot.arcSelectedLanguage.ifBlank {
            host.repository.settings.value.activeTranslateTarget.ifBlank { "English (US)" }
        }
        host.repository.updateSettings {
            it.copy(activeTranslateTarget = chosenLang)
        }

        val recorder = runCatching { recorder }.getOrNull()
        if (ImeLogic.shouldLockArcIntoHandsFree(snapshot.arcStartedFromHandsFree)) {
            host.performHaptic(strong = false)
            recorder?.clearSilencePrefix(skipHapticMs = 120L)
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Recording(isHandsFree = true),
                    isArcTranslateActive = false,
                    arcStartedFromHandsFree = false,
                    isArcCancelHovered = false,
                    arcSelectedLanguage = chosenLang,
                    activeIntentMode = VoiceIntentMode.TRANSLATE
                )
            }
            startHandsFreeSilenceMonitor()
        } else {
            host.performKeyFeedback(strong = true)
            host.stateFlow.update {
                it.copy(
                    isArcTranslateActive = false,
                    arcStartedFromHandsFree = false,
                    isArcCancelHovered = false,
                    arcSelectedLanguage = chosenLang,
                    activeIntentMode = VoiceIntentMode.TRANSLATE
                )
            }
            stopRecordingAndRunPipeline(
                intentOverride = VoiceIntentMode.TRANSLATE,
                translateLangOverride = chosenLang
            )
        }
    }

    fun handleMicPressDown(intentMode: VoiceIntentMode = VoiceIntentMode.DICTATE) {
        val currentPhase = host.stateFlow.value.voicePhase
        if (currentPhase is VoicePhase.Processing) return
        if (currentPhase is VoicePhase.Recording && currentPhase.isHandsFree) return

        host.tryAutoLearnFromRecentEdit()
        toastDismissJob?.cancel()

        val defaultTranslateLang = host.repository.settings.value.activeTranslateTarget
        host.stateFlow.update {
            it.copy(
                activeIntentMode = intentMode,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                arcSelectedLanguage = defaultTranslateLang,
                isPunctuationStripOpen = false,
                isStylePickerExpanded = false,
                isHistoryDrawerOpen = false
            )
        }

        val audioRecorder = recorder
        if (!audioRecorder.hasRecordPermission()) {
            micPressDownTimeMs = 0L
            host.performHaptic(strong = true)
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Error(
                        message = "尚未授予麦克风录音权限，请先授权后使用",
                        canRetryAudio = false,
                        needsApiSetup = false,
                        needsMicPermission = true
                    )
                )
            }
            return
        }

        // Fire tactile feedback BEFORE opening AudioRecord so speaker click / motor hum isn't recorded
        host.performHaptic(strong = true)

        abortActiveStreamingSession()
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        activeComposer?.cancelSession(rollbackWrittenText = false)
        activeComposer = null

        val settingsSnap = host.repository.settings.value
        val prevState = host.stateFlow.value
        val liveSelected = host.readSelectedTextSafely().ifBlank { prevState.rawSelectedText }
        val effectiveSelected = if (prevState.ignoreSelectionForThisTurn) "" else liveSelected
        val resolvedStartIntent = when {
            intentMode != VoiceIntentMode.DICTATE -> intentMode
            effectiveSelected.isNotBlank() -> VoiceIntentMode.SPEAK_TO_EDIT
            else -> VoiceIntentMode.DICTATE
        }
        preSessionSurroundingContext = host.readSurroundingBeforeCursor(80)

        // 流式会话：开"工作台本地识别"且本地模型就绪时用本地 Zipformer 流式，
        // 否则走云端流式（原行为）。
        // 本地流式：只在模型已预热常驻时启用（avoid 主线程阻塞绑定服务）。
        // 未预热/未就绪则退回云端流式，不影响原有功能。
        val streamSession: com.kingzcheung.xime.ai.network.asr.StreamingAsrSession? =
            if (settingsSnap.workbenchLocalSttEnabled && localStreamingReady) {
                com.kingzcheung.xime.ai.network.asr.LocalZipformerStreamingSession(host.appContext)
                    .also { runCatching { it.prepareInBackground() } }
            } else {
                host.aiEngine.openLiveStreamingSession(settingsSnap)
            }
        val enableLiveWrite = StreamingComposer.shouldEnableLiveWrite(
            settings = settingsSnap,
            intentMode = resolvedStartIntent,
            effectiveSelectedText = effectiveSelected,
            hasStreamingSession = streamSession != null,
            targetPackage = prevState.targetPackage,
            isFloatingBubbleChannel = false
        )

        val startRes = audioRecorder.startRecording(
            scope = host.scope,
            onStreamChunk = if (streamSession != null) {
                { pcmChunk -> streamSession.sendPcmFrame(pcmChunk) }
            } else {
                null
            },
            onSilencePrefixCleared = {
                streamSession?.resetPrefix()
                activeComposer?.cancelSession(rollbackWrittenText = true)
                host.stateFlow.update {
                    it.copy(
                        streamingPartialPreview = "",
                        liveWriteStatus = activeComposer?.uiStatus ?: LiveWriteUiStatus.INACTIVE
                    )
                }
            },
            pauseMediaPlayback = settingsSnap.pauseMediaWhileRecording,
            whisperMode = settingsSnap.whisperModeEnabled
        )
        startRes.fold(
            onSuccess = {
                micPressDownTimeMs = System.currentTimeMillis()
                activeStreamingSession = streamSession
                if (enableLiveWrite) {
                    onResetComposing()
                    val anchor = resolveCurrentCursorAnchor()
                    val composer = StreamingComposer(
                        editorBridge = InputConnectionEditorBridge { host.currentInputConnection() }
                    )
                    composer.startSession(
                        anchorCursor = anchor,
                        smartDirectMode = StreamingComposer.isSmartDirectDictation(settingsSnap, resolvedStartIntent)
                    )
                    activeComposer = composer
                } else {
                    activeComposer = null
                }

                if (streamSession != null) {
                    streamingPreviewJob = host.scope.launch {
                        streamSession.transcript.collect { liveTranscript ->
                            val composer = activeComposer
                            if (composer != null && composer.isActive) {
                                composer.onTranscriptUpdate(liveTranscript)
                                host.stateFlow.update {
                                    it.copy(
                                        streamingPartialPreview = liveTranscript.fullText,
                                        liveWriteStatus = composer.uiStatus
                                    )
                                }
                            } else {
                                host.stateFlow.update {
                                    it.copy(streamingPartialPreview = liveTranscript.fullText)
                                }
                            }
                        }
                    }
                }
                // Warm up LLM / HTTP STT TCP+TLS sockets during the first ~250ms of speaking
                host.scope.launch {
                    host.aiEngine.prewarmEndpoints(settingsSnap)
                }
                host.stateFlow.update {
                    it.copy(
                        voicePhase = VoicePhase.Recording(isHandsFree = false),
                        streamingPartialPreview = "",
                        liveWriteStatus = activeComposer?.uiStatus ?: LiveWriteUiStatus.INACTIVE
                    )
                }
            },
            onFailure = { err ->
                streamSession?.cancel()
                activeComposer = null
                micPressDownTimeMs = 0L
                host.performHaptic(strong = true)
                val errorPhase = ImeLogic.classifyErrorFlags(
                    errorMessage = err.message ?: "无法启动麦克风录音",
                    hasCachedAudio = host.hasRetryWavFile(),
                    hasMicPermission = audioRecorder.hasRecordPermission()
                )
                host.stateFlow.update {
                    it.copy(
                        voicePhase = errorPhase,
                        streamingPartialPreview = "",
                        liveWriteStatus = LiveWriteUiStatus.INACTIVE
                    )
                }
            }
        )
    }

    fun abortActiveStreamingSession() {
        streamingPreviewJob?.cancel()
        streamingPreviewJob = null
        activeStreamingSession?.cancel()
        activeStreamingSession = null
    }

    fun handleMicPressUp() {
        if (host.stateFlow.value.isArcTranslateActive) {
            releaseArcTranslate()
            return
        }

        val downTime = micPressDownTimeMs
        micPressDownTimeMs = 0L
        val phase = host.stateFlow.value.voicePhase
        if (downTime <= 0L || phase !is VoicePhase.Recording || phase.isHandsFree) {
            return
        }

        val heldDurationMs = System.currentTimeMillis() - downTime
        val isFromQwertyTopBar = host.stateFlow.value.tabMode == KeyboardTabMode.QWERTY
        if (isFromQwertyTopBar || ImeLogic.isPressAndHoldGesture(heldDurationMs)) {
            host.performKeyFeedback(strong = false)
            stopRecordingAndRunPipeline()
        } else {
            host.performHaptic(strong = false)
            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Recording(isHandsFree = true))
            }
            startHandsFreeSilenceMonitor()
        }
    }

    private fun startHandsFreeSilenceMonitor() {
        handsFreeSilenceJob?.cancel()
        val recorder = runCatching { recorder }.getOrNull() ?: return
        handsFreeSilenceJob = host.scope.launch {
            var lastVoiceActivityAtMs = System.currentTimeMillis()
            val ampCollectorJob = launch {
                recorder.amplitudes.collect { amps ->
                    if (!isSilentAmplitudeFrame(amps)) {
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                    }
                }
            }
            try {
                while (true) {
                    delay(200L)
                    val curr = host.stateFlow.value
                    val phase = curr.voicePhase
                    if (phase !is VoicePhase.Recording || !phase.isHandsFree) {
                        break
                    }
                    if (curr.isArcTranslateActive) {
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                        continue
                    }
                    if (!isSilentAmplitudeFrame(recorder.amplitudes.value)) {
                        lastVoiceActivityAtMs = System.currentTimeMillis()
                        continue
                    }
                    if (System.currentTimeMillis() - lastVoiceActivityAtMs >= HANDS_FREE_SILENCE_AUTO_STOP_MS) {
                        finishHandsFreeRecording()
                        break
                    }
                }
            } finally {
                ampCollectorJob.cancel()
            }
        }
    }

    private fun resolveCurrentCursorAnchor(): Int {
        val ic = host.currentInputConnection()
        if (ic != null) {
            val extracted = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
            if (extracted != null && extracted.selectionStart >= 0) {
                return extracted.selectionStart
            }
            val before = runCatching { ic.getTextBeforeCursor(4000, 0) }.getOrNull()
            if (before != null) {
                return before.length
            }
        }
        return lastKnownSelectionStart.coerceAtLeast(0)
    }

    fun finishHandsFreeRecording() {
        val snapshot = host.stateFlow.value
        if (snapshot.voicePhase !is VoicePhase.Recording) return
        host.performKeyFeedback(strong = true)
        if (snapshot.activeIntentMode == VoiceIntentMode.TRANSLATE) {
            val targetLang = snapshot.arcSelectedLanguage.ifBlank {
                host.repository.settings.value.activeTranslateTarget
            }
            stopRecordingAndRunPipeline(
                intentOverride = VoiceIntentMode.TRANSLATE,
                translateLangOverride = targetLang
            )
        } else {
            stopRecordingAndRunPipeline()
        }
    }

    /**
     * 会话结束/服务销毁时的静默清理：停录音、清状态，但**不**给按键反馈。
     *
     * 与 [cancelRecordingOrProcessing] 的区别只有「不震动/不发声」——
     * 用户切走应用时不该突然震一下，那是主动取消才有的反馈。
     */
    fun releaseForSessionEnd() {
        micPressDownTimeMs = 0L
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        abortActiveStreamingSession()
        activeComposer?.cancelSession(rollbackWrittenText = true)
        activeComposer = null
        activePipelineJob?.cancel()
        activePipelineJob = null
        val audioRecorder = runCatching { recorder }.getOrNull()
        if (audioRecorder != null) {
            host.scope.launch { runCatching { audioRecorder.cancelRecording() } }
        }
        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.Idle,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                streamingPartialPreview = "",
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
    }

    fun cancelRecordingOrProcessing() {
        host.performKeyFeedback(strong = false)
        micPressDownTimeMs = 0L
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        abortActiveStreamingSession()
        activeComposer?.cancelSession(rollbackWrittenText = true)
        activeComposer = null
        activePipelineJob?.cancel()
        activePipelineJob = null
        val audioRecorder = runCatching { recorder }.getOrNull()
        if (audioRecorder != null) {
            host.scope.launch {
                audioRecorder.cancelRecording()
            }
        }
        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.Idle,
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                streamingPartialPreview = "",
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
    }

    fun stopRecordingAndRunPipeline(
        intentOverride: VoiceIntentMode? = null,
        translateLangOverride: String? = null
    ) {
        val liveSession = activeStreamingSession
        activeStreamingSession = null
        streamingPreviewJob?.cancel()
        streamingPreviewJob = null
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null

        val audioRecorder = recorder
        activePipelineJob?.cancel()
        activePipelineJob = host.scope.launch {
            val stopResult = audioRecorder.stopRecording()
            stopResult.fold(
                onSuccess = { recorded ->
                    var preTranscribed: String? = null
                    var tailWaitMs = 0L

                    if (liveSession != null) {
                        host.stateFlow.update {
                            it.copy(
                                voicePhase = VoicePhase.Processing(stageText = "收尾识别中..."),
                                isArcTranslateActive = false,
                                arcStartedFromHandsFree = false,
                                isArcCancelHovered = false,
                                streamingPartialPreview = ""
                            )
                        }
                        val tailStart = System.currentTimeMillis()
                        val streamRes = liveSession.finishAndAwaitFinal()
                        tailWaitMs = (System.currentTimeMillis() - tailStart).coerceAtLeast(0L)

                        val streamErr = streamRes.exceptionOrNull()
                        if (streamErr is IllegalStateException) {
                            activeComposer?.cancelSession(rollbackWrittenText = true)
                            activeComposer = null
                            val errorPhase = ImeLogic.classifyErrorFlags(
                                errorMessage = streamErr.message ?: "请先完成语音 API 配置",
                                hasCachedAudio = recorded.wavFile.exists(),
                                hasMicPermission = audioRecorder.hasRecordPermission()
                            )
                            host.stateFlow.update {
                                it.copy(
                                    voicePhase = errorPhase,
                                    hasCachedAudioForRetry = errorPhase.canRetryAudio,
                                    liveWriteStatus = LiveWriteUiStatus.INACTIVE
                                )
                            }
                            return@launch
                        }

                        val candidate = streamRes.getOrNull()?.trim().orEmpty()
                        if (candidate.isNotEmpty() && !AiPipelineEngine.isHallucinatedSilence(candidate)) {
                            preTranscribed = candidate
                        }
                    }

                    if (preTranscribed == null && !recorded.hasValidSpeech) {
                        liveSession?.cancel()
                        activeComposer?.cancelSession(rollbackWrittenText = true)
                        activeComposer = null
                        val errorPhase = ImeLogic.classifyErrorFlags(
                            errorMessage = "未检测到清晰说话声，请靠近麦克风重试",
                            hasCachedAudio = false,
                            hasMicPermission = audioRecorder.hasRecordPermission()
                        )
                        host.stateFlow.update {
                            it.copy(
                                voicePhase = errorPhase,
                                isArcTranslateActive = false,
                                arcStartedFromHandsFree = false,
                                isArcCancelHovered = false,
                                streamingPartialPreview = "",
                                hasCachedAudioForRetry = false,
                                liveWriteStatus = LiveWriteUiStatus.INACTIVE
                            )
                        }
                        return@launch
                    }

                    executeVoicePipelineOnWav(
                        wavFile = recorded.wavFile,
                        durationMs = recorded.durationMs,
                        intentOverride = intentOverride,
                        translateLangOverride = translateLangOverride,
                        preTranscribedText = preTranscribed,
                        sttTailWaitMs = tailWaitMs
                    )
                },
                onFailure = { err ->
                    liveSession?.cancel()
                    activeComposer?.cancelSession(rollbackWrittenText = true)
                    activeComposer = null
                    if (err is CancellationException) return@launch
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "录音时间过短，请重试",
                        hasCachedAudio = host.hasRetryWavFile(),
                        hasMicPermission = audioRecorder.hasRecordPermission()
                    )
                    host.stateFlow.update {
                        it.copy(
                            voicePhase = errorPhase,
                            isArcTranslateActive = false,
                            arcStartedFromHandsFree = false,
                            isArcCancelHovered = false,
                            streamingPartialPreview = "",
                            hasCachedAudioForRetry = errorPhase.canRetryAudio,
                            liveWriteStatus = LiveWriteUiStatus.INACTIVE
                        )
                    }
                }
            )
        }
    }

    /** 供工作台面板显示录音波形（0..1）。未录音时返回 0。 */
    fun currentAmplitude(): Float =
        runCatching { recorder.amplitudes.value.lastOrNull() ?: 0f }.getOrDefault(0f)

    /** 波形序列（最近若干帧），用于绘制柱状波形。 */
    val amplitudeList: List<Float>
        get() = runCatching { recorder.amplitudes.value }.getOrDefault(emptyList())

    /** 本地流式引擎是否已预热就绪（由 warmupLocalStreaming 设置）。 */
    @Volatile private var localStreamingReady = false

    /** 预热本地流式识别（进入工作台/开始录音前调用，后台线程，不阻塞主线程）。 */
    fun warmupLocalStreaming() {
        host.scope.launch {
            try {
                val mm = com.kingzcheung.xime.speech.AsrModelManager(host.appContext)
                localStreamingReady = mm.isModelReady()
            } catch (_: Throwable) {
                localStreamingReady = false
            }
        }
    }

    // —— 本地离线识别（工作台可选）：复用 :asr 进程 + Zipformer int8 ——
    private fun readWavPcm(file: File): ByteArray? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 44) return null
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = (bytes[i + 4].toInt() and 0xff) or
                ((bytes[i + 5].toInt() and 0xff) shl 8) or
                ((bytes[i + 6].toInt() and 0xff) shl 16) or
                ((bytes[i + 7].toInt() and 0xff) shl 24)
            if (id == "data") {
                val start = i + 8
                val end = minOf(start + size, bytes.size)
                return if (end > start) bytes.copyOfRange(start, end) else null
            }
            if (size <= 0) break
            i += 8 + size
        }
        return bytes.copyOfRange(44, bytes.size)
    }

    private suspend fun transcribeLocally(wavFile: File): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val ctx = host.appContext
                val mm = com.kingzcheung.xime.speech.AsrModelManager(ctx)
                if (!mm.isModelReady()) return@withContext null
                val dir = mm.getSelectedModelDir()?.absolutePath ?: return@withContext null
                val pcm = readWavPcm(wavFile) ?: return@withContext null
                if (pcm.isEmpty()) return@withContext null
                val client = com.kingzcheung.xime.service.AsrInferenceClient(ctx)
                if (!client.ensureBound()) return@withContext null
                try {
                    val cb = object : com.kingzcheung.xime.service.AsrInferenceClient.AsrCallback {
                        override fun onPartialResult(text: String) {}
                        override fun onFinalResult(text: String) {}
                        override fun onError(message: String) {}
                    }
                    if (!client.startAsr(dir, cb)) return@withContext null
                    var off = 0
                    val chunk = 3200
                    while (off < pcm.size) {
                        val e = minOf(off + chunk, pcm.size)
                        client.pushAsrAudio(pcm.copyOfRange(off, e))
                        off = e
                    }
                    val text = client.stopAsr()
                    text.ifBlank { null }
                } finally {
                    runCatching { client.unbind() }
                }
            } catch (_: Throwable) {
                null
            }
        }

    private suspend fun executeVoicePipelineOnWav(
        wavFile: File,
        durationMs: Long,
        intentOverride: VoiceIntentMode? = null,
        translateLangOverride: String? = null,
        preTranscribedText: String? = null,
        sttTailWaitMs: Long = 0L
    ) {
        val settings = host.repository.settings.value
        val snapshot = host.stateFlow.value
        val targetPkg = snapshot.targetPackage
        val targetAppName = snapshot.targetAppName
        val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

        val liveSelection = host.readSelectedTextSafely().ifBlank { snapshot.rawSelectedText }
        var effectiveSelected = if (snapshot.ignoreSelectionForThisTurn) "" else liveSelection
        // 编辑面板里没有选区时，用输入框全文作为改写对象：
        // 用户打开「语音编辑」就是想改现有文字，无选区时回落到全文比静默变成普通口述更符合预期。
        if (effectiveSelected.isBlank() &&
            snapshot.activeIntentMode == VoiceIntentMode.SPEAK_TO_EDIT &&
            !snapshot.ignoreSelectionForThisTurn
        ) {
            val whole = runCatching { host.readFullEditorTextSafely().trim() }.getOrDefault("")
            if (whole.isNotBlank()) {
                effectiveSelected = whole
                speakToEditWholeText = true
                FileLogger.d(TAG, "speakToEdit: no selection, fallback to full editor text len=${whole.length}")
            }
        }

        val resolvedIntent = when {
            intentOverride != null -> intentOverride
            snapshot.voiceSubPanel == VoiceSubPanel.JEV_COPILOT ||
                snapshot.activeIntentMode == VoiceIntentMode.JEV_COPILOT -> VoiceIntentMode.JEV_COPILOT
            snapshot.activeIntentMode == VoiceIntentMode.TRANSLATE -> VoiceIntentMode.TRANSLATE
            snapshot.activeIntentMode == VoiceIntentMode.HELP_ME_WRITE -> VoiceIntentMode.HELP_ME_WRITE
            effectiveSelected.isNotBlank() -> VoiceIntentMode.SPEAK_TO_EDIT
            else -> VoiceIntentMode.DICTATE
        }
        if (resolvedIntent == VoiceIntentMode.JEV_COPILOT) {
            jevInferenceJob?.cancel()
            jevInferenceJob = null
            activeComposer?.cancelSession(rollbackWrittenText = true)
            activeComposer = null
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Idle,
                    isArcTranslateActive = false,
                    arcStartedFromHandsFree = false,
                    isArcCancelHovered = false,
                    streamingPartialPreview = "",
                    liveWriteStatus = LiveWriteUiStatus.INACTIVE,
                    isJevLoading = true,
                    jevErrorMessage = null,
                    selectedJevStrategyIndex = -1
                )
            }
            executeJevCopilotInferenceSuspend(
                isRefresh = false,
                wavFile = wavFile,
                pretranscribedSpeech = preTranscribedText
            )
            return
        }
        val isSpeakToEdit = resolvedIntent == VoiceIntentMode.SPEAK_TO_EDIT
        if (!isSpeakToEdit) speakToEditWholeText = false
        val targetTranslateLang = if (resolvedIntent == VoiceIntentMode.TRANSLATE) {
            (translateLangOverride ?: settings.activeTranslateTarget).ifBlank { "English (US)" }
        } else {
            ""
        }

        val composer = activeComposer
        val surroundingContext = if (composer != null && !composer.wasFrozenDuringSession) {
            preSessionSurroundingContext
        } else {
            host.readSurroundingBeforeCursor(80)
        }

        if (composer != null && composer.isActive) {
            if (resolvedIntent != VoiceIntentMode.DICTATE) {
                composer.cancelSession(rollbackWrittenText = true)
                activeComposer = null
            } else {
                val needsPolish = !StreamingComposer.isSmartDirectDictation(settings, resolvedIntent)
                composer.onRecordingFinished(
                    finalRawTranscript = preTranscribedText.orEmpty(),
                    needsPolishing = needsPolish
                )
            }
        }

        val initialStage = when {
            settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT -> PipelineStage.MULTIMODAL_PROCESSING
            !preTranscribedText.isNullOrBlank() -> PipelineStage.POLISHING_LLM
            else -> PipelineStage.TRANSCRIBING_STT
        }

        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.Processing(
                    stageText = ImeLogic.stageDisplayTextForIntent(
                        stage = initialStage,
                        intentMode = resolvedIntent,
                        targetLanguage = targetTranslateLang
                    )
                ),
                isArcTranslateActive = false,
                arcStartedFromHandsFree = false,
                isArcCancelHovered = false,
                streamingPartialPreview = "",
                hasCachedAudioForRetry = wavFile.exists(),
                liveWriteStatus = activeComposer?.uiStatus ?: LiveWriteUiStatus.INACTIVE
            )
        }

        val activeChatContext = if (resolvedIntent == VoiceIntentMode.HELP_ME_WRITE) {
            snapshot.effectiveChatContextMessages
        } else {
            emptyList()
        }
        val activeChatScreenshot = if (resolvedIntent == VoiceIntentMode.HELP_ME_WRITE) {
            snapshot.effectiveChatScreenshotBase64
        } else {
            ""
        }
        val activeAttitudeInstruction = if (resolvedIntent == VoiceIntentMode.HELP_ME_WRITE) {
            snapshot.selectedHelpWritePreset?.instruction.orEmpty()
        } else {
            ""
        }

        // 本地离线识别（可选）：工作台语音优先用本地 Zipformer int8，再按需云端 LLM 润色
        var effectivePreTranscribed = preTranscribedText
        var effectiveSettings = settings
        if (settings.workbenchLocalSttEnabled &&
            preTranscribedText.isNullOrBlank() &&
            settings.pipelineMode == PipelineMode.DUAL_STAGE &&
            resolvedIntent != VoiceIntentMode.JEV_COPILOT
        ) {
            val localText = transcribeLocally(wavFile)
            if (!localText.isNullOrBlank()) {
                effectivePreTranscribed = localText
                val baseUrl = settings.llmConfig.baseUrl
                val llmUsable = settings.llmConfig.enabled && (
                    settings.llmConfig.apiKey.isNotBlank() ||
                    baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost") ||
                    baseUrl.contains("192.168") || baseUrl.contains("10.")
                )
                if (!llmUsable && resolvedIntent == VoiceIntentMode.DICTATE) {
                    // 没配可用的 LLM → 直出（不润色），实现纯离线
                    effectiveSettings = settings.copy(llmConfig = settings.llmConfig.copy(enabled = false))
                }
            }
        }

        FileLogger.d(
            TAG,
            "pipeline: intent=$resolvedIntent local=${effectiveSettings.workbenchLocalSttEnabled} " +
                "preLen=${effectivePreTranscribed?.length ?: -1} llmEnabled=${effectiveSettings.llmConfig.enabled} " +
                "model=${effectiveSettings.llmConfig.model}"
        )

        val pipelineResult = host.aiEngine.processVoiceAudio(
            wavFile = wavFile,
            settings = effectiveSettings,
            activeStyle = activeStyle,
            targetAppName = targetAppName,
            selectedText = if (isSpeakToEdit) effectiveSelected else "",
            surroundingBeforeCursor = surroundingContext,
            intentMode = resolvedIntent,
            translateTargetLanguage = targetTranslateLang.ifBlank { settings.activeTranslateTarget },
            preTranscribedText = effectivePreTranscribed,
            sttTailWaitMs = sttTailWaitMs,
            chatContextMessages = activeChatContext,
            chatContextScreenshotBase64 = activeChatScreenshot,
            attitudeInstruction = activeAttitudeInstruction,
            editorDraft = if (resolvedIntent == VoiceIntentMode.HELP_ME_WRITE) snapshot.currentEditorText.trim() else "",
            onStageChanged = { stage ->
                host.stateFlow.update { curr ->
                    curr.copy(
                        voicePhase = VoicePhase.Processing(
                            stageText = ImeLogic.stageDisplayTextForIntent(
                                stage = stage,
                                intentMode = resolvedIntent,
                                targetLanguage = targetTranslateLang
                            )
                        )
                    )
                }
            }
        )

        pipelineResult.fold(
            onSuccess = { exec ->
                val finalOutput = exec.finalOutput
                val displayLatencyMs = ImeLogic.resolveSuccessToastElapsedMs(
                    pipelineLatencyMs = exec.latencyMs,
                    recordingDurationMs = durationMs
                )
                val activeComp = activeComposer
                activeComposer = null
                if (activeComp != null) {
                    finalizeLiveWriteOutput(
                        composer = activeComp,
                        finalText = finalOutput,
                        rawTranscript = exec.rawTranscript,
                        latencyMs = displayLatencyMs
                    )
                } else {
                    if (resolvedIntent == VoiceIntentMode.HELP_ME_WRITE && snapshot.currentEditorText.isNotBlank()) {
                        replaceFullEditorTextSafely(
                            finalOutput = finalOutput,
                            originalText = snapshot.currentEditorText
                        )
                        host.stateFlow.update {
                            it.copy(
                                voicePhase = VoicePhase.SuccessToast(
                                    preview = ImeLogic.formatSelectionPreview(finalOutput, 24),
                                    latencyMs = displayLatencyMs
                                ),
                                voiceSubPanel = VoiceSubPanel.NONE,
                                activeIntentMode = VoiceIntentMode.DICTATE,
                                currentEditorText = finalOutput,
                                selectedHelpWritePreset = null,
                                undoSnapshot = host.peekTopUndoSnapshot(),
                                rawSelectedText = "",
                                ignoreSelectionForThisTurn = false,
                                liveWriteStatus = LiveWriteUiStatus.INACTIVE
                            )
                        }
                        scheduleToastReset()
                    } else if (isSpeakToEdit && speakToEditWholeText) {
                        // 无选区整框改写：必须整框替换，否则会在光标处插入造成重复
                        replaceFullEditorTextSafely(finalOutput = finalOutput, originalText = effectiveSelected)
                    } else {
                        val overwritten = if (isSpeakToEdit) liveSelection else host.readSelectedTextSafely()
                        commitPipelineOutput(
                            finalText = finalOutput,
                            overwrittenSelection = overwritten,
                            latencyMs = displayLatencyMs
                        )
                    }
                }
                val isIncognito = host.stateFlow.value.isIncognitoMode || settings.manualIncognitoMode
                if (isIncognito) {
                    runCatching { wavFile.delete() }
                } else {
                    val historyAudioFile = host.archiveWavForHistory(wavFile, settings.historyRetention)
                    host.repository.addHistory(
                        HistoryRecord(
                            targetAppName = targetAppName,
                            targetPackage = targetPkg,
                            styleName = activeStyle.name,
                            isSpeakToEdit = isSpeakToEdit,
                            intentMode = resolvedIntent,
                            translateTargetLanguage = targetTranslateLang,
                            originalSelectedText = if (isSpeakToEdit) effectiveSelected else "",
                            rawTranscript = exec.rawTranscript,
                            polishedText = finalOutput,
                            durationMs = durationMs,
                            latencyMs = exec.latencyMs,
                            modeLabel = exec.modeSummary,
                            audioFilePath = historyAudioFile.absolutePath
                        )
                    )
                }
                if (host.repository.isImeWindowVisible.value) {
                    warmVoiceConnectionsInBackground()
                }
            },
            onFailure = { err ->
                activeComposer?.cancelSession(rollbackWrittenText = false)
                activeComposer = null
                if (err is CancellationException) return
                // 降级直出：净写模式下已有识别文本、但润色/网络失败（如 429 限流、
                // LLM 不可用）时，直接把原始识别结果上屏，而不是整体失败。
                val fallbackText = effectivePreTranscribed?.trim().orEmpty()
                FileLogger.w(
                    TAG,
                    "pipeline failed: intent=$resolvedIntent fallbackLen=${fallbackText.length} err=${err.message}"
                )
                if (resolvedIntent == VoiceIntentMode.DICTATE && fallbackText.isNotEmpty()) {
                    FileLogger.w(
                        TAG,
                        "LLM failed, fallback to raw transcript (len=${fallbackText.length}): ${err.message}"
                    )
                    runCatching {
                        host.commitDirectText(
                            text = fallbackText,
                            recordUndo = true,
                            markManualEdit = false,
                            playFeedback = false
                        )
                    }
                    host.stateFlow.update {
                        it.copy(
                            voicePhase = VoicePhase.SuccessToast("✓ 已上屏（未润色）", latencyMs = durationMs),
                            liveWriteStatus = LiveWriteUiStatus.INACTIVE
                        )
                    }
                    scheduleToastReset()
                    return
                }
                host.performHaptic(strong = true)
                val audioRecorder = runCatching { recorder }.getOrNull()
                val errorPhase = ImeLogic.classifyErrorFlags(
                    errorMessage = err.message ?: "语音处理失败，请检查网络或 API 配置",
                    hasCachedAudio = wavFile.exists(),
                    hasMicPermission = audioRecorder?.hasRecordPermission() == true
                )
                host.stateFlow.update {
                    it.copy(
                        voicePhase = errorPhase,
                        hasCachedAudioForRetry = errorPhase.canRetryAudio,
                        liveWriteStatus = LiveWriteUiStatus.INACTIVE
                    )
                }
            }
        )
    }

    fun runQuickEditChip(chip: QuickEditChip) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        var liveSelected = host.readSelectedTextSafely().ifBlank { host.stateFlow.value.effectiveSelectedText }
        var usedFullTextFallback = false
        if (liveSelected.isBlank()) {
            // 无选区时回落用输入框全文：用户在「语音编辑」里点润色/改正/翻译，
            // 直觉是处理框里的文字，不该要求先全选。（源码此处直接 return，
            // 按 Xime 使用习惯放宽，并记日志便于排查。）
            val whole = runCatching { host.readFullEditorTextSafely().trim() }.getOrDefault("")
            if (whole.isNotBlank()) {
                liveSelected = whole
                usedFullTextFallback = true
                FileLogger.d(TAG, "quickEditChip(${chip.label}): no selection, use full editor text len=${whole.length}")
            } else {
                host.showQuickActionToast("输入框里没有可处理的文字")
                return
            }
        }

        host.performKeyFeedback(strong = false)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "${chip.label}中..."))
            }

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = liveSelected,
                explicitInstruction = chip.instruction,
                targetAppName = targetAppName
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    if (usedFullTextFallback) {
                        // 无选区：整框替换（commitText 只会在光标处插入，会造成原文重复）
                        replaceFullEditorTextSafely(finalOutput = finalOutput, originalText = liveSelected)
                        host.stateFlow.update {
                            it.copy(
                                voicePhase = VoicePhase.SuccessToast(
                                    preview = ImeLogic.formatSelectionPreview(finalOutput, 24),
                                    latencyMs = exec.latencyMs
                                ),
                                undoSnapshot = host.peekTopUndoSnapshot(),
                                rawSelectedText = "",
                                ignoreSelectionForThisTurn = false
                            )
                        }
                        scheduleToastReset()
                    } else {
                        commitPipelineOutput(
                            finalText = finalOutput,
                            overwrittenSelection = liveSelected,
                            latencyMs = exec.latencyMs
                        )
                    }
                    if (!host.stateFlow.value.isIncognitoMode) {
                        host.repository.addHistory(
                            HistoryRecord(
                                targetAppName = targetAppName,
                                targetPackage = targetPkg,
                                styleName = activeStyle.name,
                                isSpeakToEdit = true,
                                intentMode = VoiceIntentMode.SPEAK_TO_EDIT,
                                translateTargetLanguage = "",
                                originalSelectedText = liveSelected,
                                rawTranscript = "[快捷改写] ${chip.label}",
                                polishedText = finalOutput,
                                durationMs = 0L,
                                latencyMs = exec.latencyMs,
                                modeLabel = exec.modeSummary,
                                audioFilePath = ""
                            )
                        )
                        // 成功反馈：明确告知已改写（否则只看到文字变了，不知道发生过什么）
                        host.showQuickActionToast("✓ 已${chip.label} · 可点 ↶ 撤回")
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val audioRecorder = runCatching { recorder }.getOrNull()
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "改写失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder?.hasRecordPermission() == true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                }
            )
        }
    }

    fun runHelpWritePreset(preset: HelpWritePromptPreset) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        host.performKeyFeedback(strong = false)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "正在起草：${preset.label}..."))
            }

            val activeChatContext = snapshot.effectiveChatContextMessages
            val activeChatScreenshot = snapshot.effectiveChatScreenshotBase64
            val draftInstruction = if (activeChatContext.isNotEmpty() || activeChatScreenshot.isNotBlank()) {
                preset.instruction
            } else {
                "请忽略<selected_text>中的占位文字，直接根据以下写作需求起草完整、自然得体、可直接发送的成稿正文（绝不输出任何寒暄或解释）：${preset.instruction}"
            }

            // 切换写作要求时用「原文」重写：优先选区，其次输入框全文；
            // 两者都空时才按胶囊要求从零起草。
            val sourceText = runCatching {
                val sel = host.readSelectedTextSafely().trim()
                if (sel.isNotEmpty()) sel else host.readFullEditorTextSafely().trim()
            }.getOrDefault("")
            val effectiveInstruction = if (sourceText.isNotBlank()) {
                "${preset.instruction}\n\n请基于以下原文，按上述要求重写（保持原意与关键信息，不要添加原文没有的事实）："
            } else {
                draftInstruction
            }

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = sourceText.ifBlank { preset.label },
                explicitInstruction = effectiveInstruction,
                targetAppName = targetAppName,
                chatContextMessages = activeChatContext,
                chatContextScreenshotBase64 = activeChatScreenshot
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    val existingSel = host.readSelectedTextSafely()
                    commitPipelineOutput(
                        finalText = finalOutput,
                        overwrittenSelection = existingSel,
                        latencyMs = exec.latencyMs
                    )
                    if (!host.stateFlow.value.isIncognitoMode) {
                        host.repository.addHistory(
                            HistoryRecord(
                                targetAppName = targetAppName,
                                targetPackage = targetPkg,
                                styleName = activeStyle.name,
                                isSpeakToEdit = false,
                                intentMode = VoiceIntentMode.HELP_ME_WRITE,
                                translateTargetLanguage = "",
                                originalSelectedText = "",
                                rawTranscript = "[帮我写灵感] ${preset.instruction}",
                                polishedText = finalOutput,
                                durationMs = 0L,
                                latencyMs = exec.latencyMs,
                                modeLabel = exec.modeSummary,
                                audioFilePath = ""
                            )
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val audioRecorder = runCatching { recorder }.getOrNull()
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "起草失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder?.hasRecordPermission() == true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                }
            )
        }
    }

    fun retryLastAudio() {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        val wavFile = host.getLastRecordingWavFile()
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            host.stateFlow.update {
                it.copy(
                    voicePhase = VoicePhase.Error(
                        message = "未找到可重试的最近录音文件，请重新说话",
                        canRetryAudio = false,
                        needsApiSetup = false,
                        needsMicPermission = false
                    ),
                    hasCachedAudioForRetry = false
                )
            }
            return
        }

        host.performKeyFeedback(strong = false)
        val estimatedDurationMs =
            ((wavFile.length() - 44L).coerceAtLeast(0L) * 1000L) / (WavAudioRecorder.SAMPLE_RATE * 2L)

        activePipelineJob?.cancel()
        activePipelineJob = host.scope.launch {
            executeVoicePipelineOnWav(wavFile, estimatedDurationMs)
        }
    }

    fun dismissErrorBanner() {
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            if (it.voicePhase is VoicePhase.Error) {
                it.copy(voicePhase = VoicePhase.Idle)
            } else {
                it
            }
        }
    }

    private fun finalizeLiveWriteOutput(
        composer: StreamingComposer,
        finalText: String,
        rawTranscript: String,
        latencyMs: Long
    ) {
        val outcome = if (composer.state == StreamingComposer.State.Done &&
            finalText == composer.writtenRawText
        ) {
            ComposerFinalizeOutcome(
                appliedToEditor = true,
                wasFrozen = composer.wasFrozenDuringSession,
                rawWrittenText = composer.writtenRawText,
                finalCommittedText = finalText,
                undoSnapshot = ImeLogic.createUndoSnapshot(
                    committedText = finalText,
                    replacedSelection = ""
                )
            )
        } else {
            composer.applyPolishedResult(
                polishedText = finalText,
                fullRawTranscript = rawTranscript
            )
        }

        if (host.stateFlow.value.capturedChatContext.hasContext) {
            host.clearAutoLearnBaseline()
        } else {
            val fullBufferAfterCommit = host.readFullEditorTextSafely().ifBlank { outcome.finalCommittedText }
            host.onDictationCommittedForAutoLearn(fullBufferAfterCommit)
        }

        host.performHaptic(strong = true)
        if (outcome.finalCommittedText.isNotEmpty()) {
            onExternalCommitToEngine(outcome.finalCommittedText)
        }
        host.pushUndoSnapshot(outcome.undoSnapshot)

        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.SuccessToast(
                    preview = ImeLogic.formatSelectionPreview(outcome.finalCommittedText.ifBlank { finalText }, 24),
                    latencyMs = latencyMs
                ),
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE,
                capturedChatContext = CapturedChatContext(),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                selectedHelpWritePreset = null,
                undoSnapshot = host.peekTopUndoSnapshot(),
                rawSelectedText = "",
                ignoreSelectionForThisTurn = false,
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
        scheduleToastReset()
    }

    fun commitPipelineOutput(
        finalText: String,
        overwrittenSelection: String,
        latencyMs: Long
    ) {
        val ic = host.currentInputConnection()
        if (ic != null) {
            ic.beginBatchEdit()
            ic.commitText(finalText, 1)
            ic.endBatchEdit()
        }

        if (host.stateFlow.value.capturedChatContext.hasContext) {
            host.clearAutoLearnBaseline()
        } else {
            val fullBufferAfterCommit = host.readFullEditorTextSafely().ifBlank { finalText }
            host.onDictationCommittedForAutoLearn(fullBufferAfterCommit)
        }

        host.performHaptic(strong = true)
        onExternalCommitToEngine(finalText)
        val undo = ImeLogic.createUndoSnapshot(
            committedText = finalText,
            replacedSelection = overwrittenSelection
        )
        host.pushUndoSnapshot(undo)

        host.stateFlow.update {
            it.copy(
                voicePhase = VoicePhase.SuccessToast(
                    preview = ImeLogic.formatSelectionPreview(finalText, 24),
                    latencyMs = latencyMs
                ),
                voiceSubPanel = VoiceSubPanel.NONE,
                activeIntentMode = VoiceIntentMode.DICTATE,
                capturedChatContext = CapturedChatContext(),
                ignoreChatContextForThisTurn = false,
                showChatContextDisclosurePrompt = false,
                currentEditorText = finalText,
                selectedHelpWritePreset = null,
                undoSnapshot = host.peekTopUndoSnapshot(),
                rawSelectedText = "",
                ignoreSelectionForThisTurn = false,
                liveWriteStatus = LiveWriteUiStatus.INACTIVE
            )
        }
        scheduleToastReset()
    }

    /**
     * 编辑器会话票：AI 请求发起瞬间固定输入连接。
     *
     * AI 请求走应用级 scope（分钟级网络调用），期间用户可能切到别的输入框。
     * 若回填时直接取 `currentInputConnection()`，整框替换会落进新框——
     * 把用户正在写的内容整个删掉重写。因此把入队时的连接记下来，回填前复核。
     */
    class EditorTicket internal constructor(val connection: android.view.inputmethod.InputConnection?)

    /** 捕获当前编辑器票。 */
    internal fun captureEditorTicket(): EditorTicket = EditorTicket(host.currentInputConnection())

    /** 票是否仍属于当前编辑器（连接对象未被替换）。 */
    internal fun isEditorTicketCurrent(ticket: EditorTicket): Boolean =
        ticket.connection != null && host.currentInputConnection() === ticket.connection

    fun replaceFullEditorTextSafely(
        finalOutput: String,
        originalText: String = "",
        editorTicket: EditorTicket? = null,
    ) {
        // 迟到结果保护：请求发起时的输入框已不是当前输入框时，丢弃整框替换。
        // 不传票（面板内的即时调用）沿用原行为，由调用点自行保证同会话。
        if (editorTicket != null && !isEditorTicketCurrent(editorTicket)) {
            FileLogger.w(TAG, "replaceFullEditorText dropped: editor switched during request")
            return
        }
        val ic = editorTicket?.connection ?: host.currentInputConnection()
        val currentFull = originalText.ifEmpty { host.readFullEditorTextSafely() }
        if (ic != null) {
            ic.beginBatchEdit()
            val extracted = runCatching {
                ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            }.getOrNull()
            if (extracted?.text != null) {
                ic.setSelection(0, extracted.text.length)
                ic.commitText(finalOutput, 1)
            } else {
                val beforeLen = runCatching {
                    ic.getTextBeforeCursor(4000, 0)?.length ?: currentFull.length
                }.getOrDefault(currentFull.length)
                val afterLen = runCatching {
                    ic.getTextAfterCursor(4000, 0)?.length ?: 0
                }.getOrDefault(0)
                ic.deleteSurroundingText(beforeLen, afterLen)
                ic.commitText(finalOutput, 1)
            }
            ic.endBatchEdit()
        }

        host.clearAutoLearnBaseline()
        host.performHaptic(strong = true)
        if (finalOutput.isNotEmpty()) {
            onExternalCommitToEngine(finalOutput)
        }
        val undo = ImeLogic.createUndoSnapshot(
            committedText = finalOutput,
            replacedSelection = currentFull
        )
        host.pushUndoSnapshot(undo)
    }

    /** 换输入框/关闭面板时清空"原文基准"，下次重新以当前内容为原文。 */
    fun resetHelpWriteBase() {
        helpWriteBaseDraft = null
        lastHelpWriteOutput = null
    }

    /**
     * 换输入框：取消仍在路上的 AI 帮写请求。
     *
     * 只清原文基准不够——请求已在网络往返，回来时仍会走整框替换。
     * 与之配套的 [replaceFullEditorTextSafely] 会复核编辑器票，这里再主动作废，
     * 避免旧请求的回填把新输入框的内容整段覆盖。
     */
    fun cancelPendingHelpWrite() {
        activePipelineJob?.cancel()
        activePipelineJob = null
        resetHelpWriteBase()
    }

    /**
     * 服务销毁：取消本控制器挂在应用级 scope 上的全部任务与流式会话。
     *
     * ⚠️ 必须一并停掉录音：`recorder` 是 `by lazy` 的单例，服务销毁时若正在录音，
     * 只取消协程 Job 不会释放 AudioRecord，麦克风与音频焦点会一直被占用
     * （用户表现为「语音说完后其他应用录不到音 / 麦克风被占」）。
     * `cancelRecording()` 内部会 stop + release AudioRecord 并放弃 audio focus。
     */
    fun dispose() {
        runCatching { toastDismissJob?.cancel() }
        runCatching { handsFreeSilenceJob?.cancel() }
        runCatching { streamingPreviewJob?.cancel() }
        runCatching { chatScreenshotJob?.cancel() }
        runCatching { jevInferenceJob?.cancel() }
        runCatching { askAiJob?.cancel() }
        runCatching { abortActiveStreamingSession() }
        runCatching { activeComposer?.cancelSession(rollbackWrittenText = false) }
        activeComposer = null
        activePipelineJob?.cancel()
        activePipelineJob = null
        toastDismissJob = null
        handsFreeSilenceJob = null
        streamingPreviewJob = null
        chatScreenshotJob = null
        jevInferenceJob = null
        askAiJob = null
        // 释放麦克风：必须放在最后，且用 runBlocking 等它真正停掉——
        // 本方法是服务销毁路径的同步收尾，没有后续时机再来补。
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                    recorder.cancelRecording()
                }
            }
        }
        resetHelpWriteBase()
    }

    fun runHelpWriteDraftTransform(preset: HelpWritePromptPreset) {
        val currPhase = host.stateFlow.value.voicePhase
        FileLogger.d(TAG, "draftTransform click: preset=${preset.id} phase=${currPhase::class.simpleName}")
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) {
            FileLogger.d(TAG, "draftTransform BLOCKED by busy phase")
            return
        }
        // 上一次成稿的 SuccessToast 不应阻止这次切换要求重新生成
        if (currPhase is VoicePhase.SuccessToast) {
            host.stateFlow.update { it.copy(voicePhase = VoicePhase.Idle) }
        }

        host.performKeyFeedback(strong = false)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        // 请求发起瞬间固定编辑器：网络往返期间切框后，成稿不能再整框写进新输入框。
        val helpWriteTicket = captureEditorTicket()
        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val isPassword = ImeLogic.isPasswordEditorInputType(snapshot.currentInputType)
            val liveDraft = if (isPassword) "" else host.readFullEditorTextSafely().trim().ifBlank { snapshot.currentEditorText.trim() }
            // 首次点击记住原文；之后切换要求都用这份原文重写（不在上次结果上叠加）
            val base = helpWriteBaseDraft
            val currentDraft = when {
                base.isNullOrBlank() -> liveDraft
                // 用户手动改过输入框（与上次结果不同）时，认为换了原文，重新取基准
                liveDraft.isNotBlank() && liveDraft != lastHelpWriteOutput -> liveDraft
                else -> base
            }
            if (currentDraft.isBlank()) {
                FileLogger.d(TAG, "draftTransform BLOCKED: empty draft")
                return@launch
            }
            if (helpWriteBaseDraft.isNullOrBlank() || run {
                    val b = helpWriteBaseDraft
                    b == null || (liveDraft.isNotBlank() && liveDraft != lastHelpWriteOutput)
                }
            ) {
                helpWriteBaseDraft = currentDraft
                FileLogger.d(TAG, "draftTransform base captured len=${currentDraft.length}")
            }

            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "正在${preset.label}..."))
            }

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = currentDraft,
                explicitInstruction = preset.instruction,
                targetAppName = targetAppName,
                chatContextMessages = emptyList(),
                chatContextScreenshotBase64 = ""
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    lastHelpWriteOutput = finalOutput
                    replaceFullEditorTextSafely(
                        finalOutput = finalOutput,
                        originalText = currentDraft,
                        editorTicket = helpWriteTicket,
                    )
                    host.stateFlow.update {
                        it.copy(
                            voicePhase = VoicePhase.SuccessToast(
                                preview = ImeLogic.formatSelectionPreview(finalOutput, 24),
                                latencyMs = exec.latencyMs
                            ),
                            // 保持在工作台帮写面板：方便连续切换要求重新生成
                            currentEditorText = finalOutput,
                            selectedHelpWritePreset = null,
                            undoSnapshot = host.peekTopUndoSnapshot(),
                            rawSelectedText = "",
                            ignoreSelectionForThisTurn = false,
                            liveWriteStatus = LiveWriteUiStatus.INACTIVE
                        )
                    }
                    scheduleToastReset()
                    host.showQuickActionToast("✓ 已一键成稿 · 可点 ↶ 撤回")
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "生成失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                }
            )
        }
    }

    /** 帮写"模式B"的原文基准：首次生成时记住，切换其他要求时都以它为素材重写，
     *  避免在已改写的结果上反复叠加。清空输入框/换输入框/关闭面板时重置。 */
    private var helpWriteBaseDraft: String? = null
    /** 上一次帮写成稿的结果，用于区分“用户改了原文”与“这是我们刚写进去的”。 */
    private var lastHelpWriteOutput: String? = null

    /** 读屏失败提示的节流时间戳，避免连续误报刷屏。 */
    @Volatile private var lastCaptureFailToastAt = 0L

    private var askAiJob: Job? = null

    fun openAskAiCardPanel() {
        host.performKeyFeedback(strong = false)
        onResetComposing()
        val stateSnap = host.stateFlow.value
        val isPassword = ImeLogic.isPasswordEditorInputType(stateSnap.currentInputType)
        val fullText = if (isPassword) "" else host.readFullEditorTextSafely().trim().ifBlank { stateSnap.currentEditorText.trim() }
        if (fullText.isBlank()) return

        host.stateFlow.update {
            it.copy(
                tabMode = KeyboardTabMode.VOICE,
                voiceSubPanel = VoiceSubPanel.ASK_AI_CARD,
                activeIntentMode = VoiceIntentMode.DICTATE,
                askAiQuery = fullText,
                askAiAnswer = "",
                isAskAiLoading = true,
                askAiErrorMessage = null,
                isClipboardOpen = false,
                isToolPanelOpen = false,
                selectedHelpWritePreset = null
            )
        }
        triggerAskAiInference(fullText)
    }

    fun triggerAskAiInference(query: String) {
        askAiJob?.cancel()
        askAiJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val result = host.aiEngine.runAskAiCardQuery(
                settings = settings,
                query = query,
                targetAppName = snapshot.targetAppName
            )
            result.fold(
                onSuccess = { exec ->
                    host.stateFlow.update {
                        it.copy(
                            isAskAiLoading = false,
                            askAiAnswer = exec.finalOutput,
                            askAiErrorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.stateFlow.update {
                        it.copy(
                            isAskAiLoading = false,
                            askAiErrorMessage = err.message ?: "解答失败，请检查网络或 API 配置"
                        )
                    }
                }
            )
        }
    }

    fun retryAskAiCard() {
        val q = host.stateFlow.value.askAiQuery
        if (q.isBlank()) return
        host.performKeyFeedback(strong = false)
        host.stateFlow.update {
            it.copy(
                isAskAiLoading = true,
                askAiErrorMessage = null
            )
        }
        triggerAskAiInference(q)
    }

    fun returnFromAskAiToQwerty() {
        host.performKeyFeedback(strong = false)
        askAiJob?.cancel()
        host.stateFlow.update {
            it.copy(
                tabMode = KeyboardTabMode.QWERTY,
                voiceSubPanel = VoiceSubPanel.NONE,
                isAskAiLoading = false,
                askAiErrorMessage = null
            )
        }
    }

    fun dismissAskAiAndClearEditor() {
        host.performKeyFeedback(strong = false)
        askAiJob?.cancel()
        val q = host.stateFlow.value.askAiQuery
        replaceFullEditorTextSafely(finalOutput = "", originalText = q)
        host.stateFlow.update {
            it.copy(
                tabMode = KeyboardTabMode.QWERTY,
                voiceSubPanel = VoiceSubPanel.NONE,
                currentEditorText = "",
                undoSnapshot = host.peekTopUndoSnapshot(),
                isAskAiLoading = false,
                askAiErrorMessage = null
            )
        }
        host.showQuickActionToast("✓ 已清空，可点 ↶ 撤回找回")
    }

    fun copyAskAiAnswer() {
        val answer = host.stateFlow.value.askAiAnswer
        if (answer.isBlank()) return
        host.performKeyFeedback(strong = false)
        host.copyTextToClipboard(answer)
        host.showQuickActionToast("✓ 已复制 AI 解答")
    }

    fun commitAskAiAnswerToEditor() {
        val answer = host.stateFlow.value.askAiAnswer
        if (answer.isBlank()) return
        host.performKeyFeedback(strong = false)
        askAiJob?.cancel()
        val q = host.stateFlow.value.askAiQuery
        replaceFullEditorTextSafely(finalOutput = answer, originalText = q)
        host.stateFlow.update {
            it.copy(
                tabMode = KeyboardTabMode.QWERTY,
                voiceSubPanel = VoiceSubPanel.NONE,
                currentEditorText = answer,
                undoSnapshot = host.peekTopUndoSnapshot(),
                isAskAiLoading = false,
                askAiErrorMessage = null
            )
        }
        host.showQuickActionToast("✓ 已填入输入框 · 可点 ↶ 撤回")
    }

    fun scheduleToastReset() {
        toastDismissJob?.cancel()
        toastDismissJob = host.scope.launch {
            delay(2200L)
            host.stateFlow.update { curr ->
                if (curr.voicePhase is VoicePhase.SuccessToast) {
                    curr.copy(voicePhase = VoicePhase.Idle)
                } else {
                    curr
                }
            }
        }
    }

    fun toggleWhisperMode() {
        host.performKeyFeedback(strong = true)
        val nextWhisper = !host.repository.settings.value.whisperModeEnabled
        host.repository.updateSettings { it.copy(whisperModeEnabled = nextWhisper) }
        host.showQuickActionToast(if (nextWhisper) "🤫 已开启轻声耳语高敏收音" else "🎙️ 已恢复标准收音灵敏度")
    }

    fun runEqualEqualAskAi(fullEditorText: String, query: String) {
        val currPhase = host.stateFlow.value.voicePhase
        if (currPhase is VoicePhase.Recording || currPhase is VoicePhase.Processing) return

        host.performKeyFeedback(strong = true)
        toastDismissJob?.cancel()
        activePipelineJob?.cancel()

        activePipelineJob = host.scope.launch {
            val settings = host.repository.settings.value
            val snapshot = host.stateFlow.value
            val targetPkg = snapshot.targetPackage
            val targetAppName = snapshot.targetAppName
            val (activeStyle, _) = host.repository.resolveActiveStyle(targetPkg, snapshot.currentInputType)

            host.stateFlow.update {
                it.copy(voicePhase = VoicePhase.Processing(stageText = "🤖 AI 思考解答中..."))
            }
            if (snapshot.tabMode != KeyboardTabMode.VOICE) {
                host.showQuickActionToast("🤖 AI 正在解答「${query.take(10)}」…")
            }

            val directAskInstruction =
                "用户在输入框通过 == 向你直接提问或下达写作指令。请忽略<selected_text>的选中改写语义，直接把<selected_text>当作用户的问题或指令本身，直接输出准确、简洁、立即可发送或使用的结果正文（严禁寒暄、严禁解释前缀、严禁复述问题）。"

            val transformResult = host.aiEngine.runTextTransform(
                settings = settings,
                activeStyle = activeStyle,
                rawOrSelectedText = query,
                explicitInstruction = directAskInstruction,
                targetAppName = targetAppName
            )

            transformResult.fold(
                onSuccess = { exec ->
                    val finalOutput = exec.finalOutput
                    val ic = host.currentInputConnection()
                    val currentFull = host.readFullEditorTextSafely().ifEmpty { fullEditorText }
                    if (ic != null) {
                        ic.beginBatchEdit()
                        val extracted = runCatching {
                            ic.getExtractedText(ExtractedTextRequest(), 0)
                        }.getOrNull()
                        if (extracted?.text != null) {
                            ic.setSelection(0, extracted.text.length)
                            ic.commitText(finalOutput, 1)
                        } else {
                            val beforeLen = runCatching {
                                ic.getTextBeforeCursor(4000, 0)?.length ?: currentFull.length
                            }.getOrDefault(currentFull.length)
                            val afterLen = runCatching {
                                ic.getTextAfterCursor(4000, 0)?.length ?: 0
                            }.getOrDefault(0)
                            ic.deleteSurroundingText(beforeLen, afterLen)
                            ic.commitText(finalOutput, 1)
                        }
                        ic.endBatchEdit()
                    }

                    host.clearAutoLearnBaseline()
                    host.performHaptic(strong = true)
                    onExternalCommitToEngine(finalOutput)

                    val undo = ImeLogic.createUndoSnapshot(
                        committedText = finalOutput,
                        replacedSelection = currentFull
                    )
                    host.pushUndoSnapshot(undo)

                    host.stateFlow.update {
                        it.copy(
                            voicePhase = VoicePhase.SuccessToast(
                                preview = ImeLogic.formatSelectionPreview(finalOutput, 24),
                                latencyMs = exec.latencyMs
                            ),
                            undoSnapshot = host.peekTopUndoSnapshot(),
                            rawSelectedText = "",
                            ignoreSelectionForThisTurn = false
                        )
                    }
                    scheduleToastReset()
                    if (host.stateFlow.value.tabMode != KeyboardTabMode.VOICE) {
                        host.showQuickActionToast("✓ AI 已填入答案 · 可点 ↶ 撤回")
                    }

                    if (!host.stateFlow.value.isIncognitoMode) {
                        host.repository.addHistory(
                            HistoryRecord(
                                targetAppName = targetAppName,
                                targetPackage = targetPkg,
                                styleName = activeStyle.name,
                                isSpeakToEdit = false,
                                intentMode = VoiceIntentMode.HELP_ME_WRITE,
                                translateTargetLanguage = "",
                                originalSelectedText = currentFull,
                                rawTranscript = "[== 问 AI] $query",
                                polishedText = finalOutput,
                                durationMs = 0L,
                                latencyMs = exec.latencyMs,
                                modeLabel = exec.modeSummary,
                                audioFilePath = ""
                            )
                        )
                    }
                },
                onFailure = { err ->
                    if (err is CancellationException) return@launch
                    host.performHaptic(strong = true)
                    val audioRecorder = runCatching { recorder }.getOrNull()
                    val errorPhase = ImeLogic.classifyErrorFlags(
                        errorMessage = err.message ?: "AI 回答失败，请检查网络或 API 配置",
                        hasCachedAudio = false,
                        hasMicPermission = audioRecorder?.hasRecordPermission() == true
                    )
                    host.stateFlow.update { it.copy(voicePhase = errorPhase) }
                    if (host.stateFlow.value.tabMode != KeyboardTabMode.VOICE) {
                        host.showQuickActionToast("⚠ ${(err.message ?: "AI 回答失败").take(22)}")
                    }
                }
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onSelectionUpdated(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        if (newSelStart >= 0 && newSelEnd >= 0) {
            lastKnownSelectionStart = newSelStart
            lastKnownSelectionEnd = newSelEnd
        }
        val composer = activeComposer ?: return
        if (!composer.isActive) return
        composer.onUpdateSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val nextUiStatus = composer.uiStatus
        if (host.stateFlow.value.liveWriteStatus != nextUiStatus) {
            host.stateFlow.update { it.copy(liveWriteStatus = nextUiStatus) }
        }
    }

    fun stopRecordingOnTabSwitchOrWindowHidden() {
        finalizeJevDraftAndClearMemory(clearContext = true)
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        activeComposer?.cancelSession(rollbackWrittenText = false)
        activeComposer = null
        if (host.stateFlow.value.voicePhase is VoicePhase.Recording) {
            micPressDownTimeMs = 0L
            abortActiveStreamingSession()
            val recorder = runCatching { recorder }.getOrNull()
            if (recorder != null) {
                host.scope.launch {
                    recorder.cancelRecording()
                }
            }
        }
        if (host.stateFlow.value.liveWriteStatus != LiveWriteUiStatus.INACTIVE) {
            host.stateFlow.update { it.copy(liveWriteStatus = LiveWriteUiStatus.INACTIVE) }
        }
    }

    fun onDestroy() {
        micPressDownTimeMs = 0L
        jevInferenceJob?.cancel()
        jevInferenceJob = null
        jevDraftSession.resetSessionOnly()
        handsFreeSilenceJob?.cancel()
        handsFreeSilenceJob = null
        chatScreenshotJob?.cancel()
        chatScreenshotJob = null
        activeComposer?.cancelSession(rollbackWrittenText = false)
        activeComposer = null
        abortActiveStreamingSession()
        host.aiEngine.evictLiveStreamingPrewarm()
        activePipelineJob?.cancel()
        toastDismissJob?.cancel()
    }

    companion object {
        private const val TAG = "VoiceController"
        const val HANDS_FREE_SILENCE_AUTO_STOP_MS: Long = 8_000L
        const val HANDS_FREE_SILENCE_AMPLITUDE_THRESHOLD: Float = 0.12f

        fun isSilentAmplitudeFrame(
            amplitudes: List<Float>,
            threshold: Float = HANDS_FREE_SILENCE_AMPLITUDE_THRESHOLD
        ): Boolean {
            return (amplitudes.lastOrNull() ?: 0.08f) <= threshold
        }

        fun formatJevUserFacingError(err: Throwable?): String {
            if (err == null) return "军师推演异常，请点「↻ 换一批」重试"
            val raw = err.message
                ?.substringBefore("\n--- 诊断日志 ---")
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                .orEmpty()
            val lower = raw.lowercase()
            return when {
                err is SocketTimeoutException ||
                    err is InterruptedIOException ||
                    lower.contains("timeout") ||
                    lower.contains("timed out") ||
                    raw.contains("超时") ->
                    "模型请求超时，请点「↻ 换一批」重试"
                err is UnknownHostException ||
                    err is ConnectException ||
                    lower.contains("unable to resolve host") ||
                    lower.contains("failed to connect") ||
                    lower.contains("network is unreachable") ||
                    lower.contains("connection reset") ||
                    lower.contains("software caused connection abort") ->
                    "网络连接异常，请检查网络后点「↻ 换一批」重试"
                raw.contains("API Key", ignoreCase = true) ||
                    lower.contains("unauthorized") ||
                    raw.contains("(401)") ||
                    raw.contains(" 401") ->
                    if (raw.contains("API Key", ignoreCase = true)) raw else "API Key 鉴权失败 (401)，请前往设置检查"
                raw.contains("(429)") ||
                    raw.contains(" 429") ||
                    lower.contains("rate limit") ||
                    lower.contains("quota") ||
                    raw.contains("限流") ->
                    "模型接口触发限流 (429)，请稍后点「↻ 换一批」重试"
                raw.contains("(500)") ||
                    raw.contains("(502)") ||
                    raw.contains("(503)") ||
                    raw.contains("(504)") ||
                    raw.contains("服务端错误") ->
                    "模型服务端繁忙，请点「↻ 换一批」重试"
                raw.isBlank() ->
                    "军师推演异常，请点「↻ 换一批」重试"
                raw.length > 48 ->
                    raw.take(46) + "…"
                else ->
                    raw
            }
        }
    }

    // ================= Xime 适配层（面板所需的原语） =================
    //
    // TypeFree 把这些操作放在 TypingController（26 键引擎 + 联想 + 标点快捷键）里；
    // Xime 键盘由 Rime 负责，因此这里只保留 AI 面板真正需要的几个原语，
    // 全部经 host 转发到 XimeInputMethodService，保证与 Rime 组合态/词频/插件不冲突。

    /** 面板快捷文本（标点、配对符号、翻译结果）直接上屏。 */
    fun commitQuickText(text: String) {
        if (text.isEmpty()) return
        runCatching {
            host.commitDirectText(text = text, recordUndo = false, playFeedback = false)
        }.onFailure { FileLogger.e(TAG, "commitQuickText failed", it) }
    }

    /** 面板退格：删一个码点（emoji 代理对整体删除）。 */
    fun quickBackspace() {
        runCatching {
            val ic = host.currentInputConnection() ?: return
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.isEmpty()) return
            val n = if (before.length >= 2 &&
                before[before.length - 2].isHighSurrogate() &&
                before[before.length - 1].isLowSurrogate()
            ) 2 else 1
            ic.deleteSurroundingText(n, 0)
        }.onFailure { FileLogger.e(TAG, "quickBackspace failed", it) }
    }

    /** 面板回车：优先触发宿主编辑器动作，无动作则上屏换行。 */
    fun quickEnter() {
        runCatching {
            val ic = host.currentInputConnection() ?: return
            val actionId = host.currentEditorInfo()
                ?.imeOptions
                ?.and(android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
                ?: 0
            if (actionId != 0 && actionId != android.view.inputmethod.EditorInfo.IME_ACTION_NONE) {
                ic.performEditorAction(actionId)
            } else {
                host.commitDirectText("\n", recordUndo = false, playFeedback = false)
            }
        }.onFailure { FileLogger.e(TAG, "quickEnter failed", it) }
    }

    /** 帮我写：直接执行当前选中的预设（免开口一键生成）。 */
    fun runHelpWritePresetDirect() {
        runCatching { runSelectedHelpWritePresetDirect() }
            .onFailure { FileLogger.e(TAG, "runHelpWritePresetDirect failed", it) }
    }

    /**
     * 面板动作分发（JevCopilotPanel 用）。
     * 未覆盖的动作只记日志，不抛异常——面板不能因某个动作没实现就崩。
     */
    fun handleImeUiAction(action: ImeUiAction) {
        runCatching {
            when (action) {
                is ImeUiAction.OpenJevCopilot -> openJevCopilotPanel()
                is ImeUiAction.CloseSubPanel -> closeVoiceSubPanel()
                is ImeUiAction.RefreshJevStrategies -> refreshJevStrategies()
                is ImeUiAction.SelectJevStrategyCard -> selectJevStrategyCard(action.index)
                is ImeUiAction.AcceptChatContextDisclosure -> acceptChatContextDisclosure()
                is ImeUiAction.DeclineChatContextDisclosure -> declineChatContextDisclosure()
                is ImeUiAction.DismissError -> dismissErrorBanner()
                is ImeUiAction.TriggerFullScreenPeekCapture -> triggerFullScreenPeekCapture {}

                is ImeUiAction.ToggleJevContextInspector -> toggleJevContextInspector()
                is ImeUiAction.TogglePunctuationStrip -> togglePunctuationStrip()
                is ImeUiAction.FinishHandsFree -> finishHandsFreeRecording()
                is ImeUiAction.CancelVoiceRecording -> cancelRecordingOrProcessing()
                is ImeUiAction.RetryLastAudio -> retryLastAudio()
                is ImeUiAction.MicPressDown -> handleMicPressDown(action.mode)
                is ImeUiAction.MicPressUp -> handleMicPressUp()
                is ImeUiAction.SelectTranslateLanguage -> selectTranslateLanguage(action.language)
                is ImeUiAction.ToggleWhisperMode -> toggleWhisperMode()
                is ImeUiAction.ToggleAiCompletion -> host.repository.updateSettings { it.copy(aiCompletionEnabled = !it.aiCompletionEnabled) }
                is ImeUiAction.ToggleManualIncognito -> host.repository.updateSettings { it.copy(manualIncognitoMode = !it.manualIncognitoMode) }
                is ImeUiAction.SelectAutoStyle -> host.repository.updateSettings { it.copy(selectedManualStyleId = "auto") }
                is ImeUiAction.SelectManualStyle -> host.repository.updateSettings { it.copy(selectedManualStyleId = action.style.id) }
                is ImeUiAction.ToggleStylePicker -> Unit
                is ImeUiAction.OpenAskAiCard -> openAskAiCardPanel()
                is ImeUiAction.ReturnFromAskAiToQwerty -> closeVoiceSubPanel()
                is ImeUiAction.RetryAskAiCard -> retryAskAiCard()
                is ImeUiAction.DismissAskAiAndClearEditor -> dismissAskAiAndClearEditor()
                is ImeUiAction.CopyAskAiAnswer -> copyAskAiAnswer()
                is ImeUiAction.OpenHelpMeWrite -> {
                    host.stateFlow.update { it.copy(activeIntentMode = VoiceIntentMode.HELP_ME_WRITE) }
                }
                is ImeUiAction.ToggleLiveWrite -> host.repository.updateSettings {
                    it.copy(liveWriteToEditor = !it.liveWriteToEditor)
                }
                is ImeUiAction.OpenStylePickerFromToolPanel, is ImeUiAction.ToggleStylePicker -> Unit
                is ImeUiAction.RunHelpWriteDraftTransform -> {
                    // 「模式B·输入框有草稿」那排胶囊：点一下立即按该要求改写草稿
                    runCatching { runHelpWriteDraftTransform(action.preset) }
                }
                is ImeUiAction.SelectHelpWritePreset -> {
                    host.stateFlow.update { it.copy(selectedHelpWritePreset = action.preset) }
                }
                is ImeUiAction.CycleInputScheme,
                is ImeUiAction.DirectGenerateHelpWrite -> {
                    runCatching { runSelectedHelpWritePresetDirect() }
                }
                is ImeUiAction.SelectTab -> {
                    // 军师/面板内的"切换键盘页"：在 overlay 里等于关闭工作台回到键盘
                    closeVoiceSubPanel()
                }
                is ImeUiAction.OpenClipboard,
                is ImeUiAction.CloseClipboard,
                is ImeUiAction.SelectKeyboardHeightLevel,
                is ImeUiAction.SelectKeyboardHeightMode,
                is ImeUiAction.SelectKeyboardLiftLevel,
                is ImeUiAction.ToggleToolPanel,
                is ImeUiAction.ToggleToolPanelHeight -> {
                    // 这些属于 Rime 主键盘的能力，工作台不接管：
                    // 记录一次日志即可，避免误以为面板按钮失效。
                    FileLogger.d(TAG, "delegated to Rime keyboard: ${'$'}{action::class.simpleName}")
                }                is ImeUiAction.CommitAskAiAnswerToEditor -> commitAskAiAnswerToEditor()
                else -> FileLogger.d(TAG, "unhandled ImeUiAction: " + action::class.simpleName)
            }
        }.onFailure { FileLogger.e(TAG, "handleImeUiAction failed", it) }
    }

}
