package com.kingzcheung.xime.service

import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.keyboard.KeyActionContext
import com.kingzcheung.xime.keyboard.KeyActionRegistry
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.FloatingCardGeometry
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.util.FileLogger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 构建键盘回调集合（KeyboardCallbacks）。
 *
 * 所有回调直接操作服务层状态与方法；service 内部成员对同模块可见（internal）。
 * 仅当 [floatingMinY] 变化时重建；回调体内一律读 [XimeInputMethodService.uiState]
 * 现值——此前闭包捕获组合期的 state/effectiveScreenH，remember 只以 floatingMinY
 * 为 key，回调会拿着首组过期值工作（如悬浮拖动用了过期的屏幕高与模式开关）。
 */
@Composable
internal fun rememberImeKeyboardCallbacks(
    service: XimeInputMethodService,
    floatingMinY: Int,
): KeyboardCallbacks {
    val view = LocalView.current
    return remember(floatingMinY) {
        KeyboardCallbacks(
            onKeyPress = { key, isShifted ->
                service.keyRouter.handleKeyPress(key, isShifted)
            },
            onKeyPressDown = { key ->
                // 删除键按下：开一次删除会话快照（下滑撤回删除用），必须在首次退格
                // 派发之前（同一主线程回调内同步完成），否则首个被删字符漏记。
                // 会话结算见 XimeInputMethodService.finishDeleteSession：由撤回请求或
                // 其它按键触发，不在抬手上结算（抬手时退格 job 可能还在队列里没落盘）。
                if (key == "delete") service.beginDeleteSession()
                service.feedbackManager.performKeyPressDownEffect(key, view)
            },
            onKeyRelease = { key ->
                service.feedbackManager.hapticFeedback(view, keyUp = true)
            },
            onCandidateSelect = { index ->
                service.keyRouter.selectCandidate(index)
            },
            candidateCount = { service.candidateState.value.candidates.size },
            onCandidateDelete = { index ->
                service.keyRouter.deleteCandidate(index)
            },
            onAssociationSelect = { index -> handleAssociationSelect(service, view, index) },
            onClearAssociation = {
                // 清空英文联想候选，并结束英文输入态：pendingEnglish 清空后，
                // 后续刷新不再触发联想异步回填（否则"清了又冒出来"，见
                // ImeSessionController/updateUIWithResult 的在途回填校验）。
                service.candidateState.value = service.candidateState.value.copy(
                    associationCandidates = emptyList(),
                    pendingEnglishText = ""
                )
            },
            onToggleDarkMode = { service.toggleDarkMode() },
            onClipboard = {},
            onClipboardSelect = { text -> service.textCommit.selectClipboardItem(text) },
            onClipboardImageSelect = { item -> service.textCommit.selectClipboardImage(item) },
            onClipboardPullRemote = { service.clipboardSyncBridge?.pullOnce() },
            onCommitText = { text -> service.textCommit.commitClipboardText(text) },
            onDeleteText = { count -> service.textCommit.deleteClipboardChars(count) },
            onHandwritingAutoCommit = { newTail, expectedTail -> handleHandwritingAutoCommit(service, newTail, expectedTail) },
            onReplaceCommittedText = { expected, replacement -> handleReplaceCommittedText(service, expected, replacement) },
            onCapitalAmountDisplay = { expression, amount ->
                service.candidateState.value = service.candidateState.value.copy(
                    capitalAmountExpression = expression,
                    capitalAmountAmount = amount,
                )
            },
            onCandidateReorderSave = { code, ordered -> service.saveCandidateOrder(code, ordered) },
            onHandwritingFinalize = { service.finalizeHandwritingPrediction() },
            onQuickSend = {},
            onKeyboardResize = { handleKeyboardResize(service) },
            onReloadConfig = { service.schemaController.reloadConfig() },
            onSettings = { service.schemaController.openSettings() },
            onSwitchSchema = { schemaId -> service.schemaController.switchSchema(schemaId) },
            onToggleSchemaSwitch = { sw -> service.sessionController.toggleSchemaSwitch(sw) },
            onHideKeyboard = { service.hideKeyboard() },
            onSwitchKeyboard = { handleSwitchKeyboard(service) },
            onToolbarEditingAction = { action -> service.schemaController.handleToolbarEditingAction(action) },
            onCommitImage = { imagePath -> handleCommitImage(service, imagePath) },
            onVoiceModeChange = { enabled -> handleVoiceModeChange(service, enabled) },
            onVoiceStickyToggle = { handleVoiceStickyToggle(service) },
            onPageDown = { service.keyRouter.pageDown() },
            onPageUp = { service.keyRouter.pageUp() },
            onGlobalCandidateSelect = { globalIndex ->
                service.keyRouter.selectCandidateGlobal(globalIndex)
            },
            onGlobalCandidateDelete = { globalIndex ->
                service.keyRouter.deleteCandidateGlobal(globalIndex)
            },
            onRequestExpandedCandidates = { service.refreshExpandedCandidates() },
            onCursorMove = { direction -> handleCursorMove(service, direction) },
            onGestureAction = { action, value ->
                KeyActionRegistry.execute(action, KeyActionContext(service), value)
            },
            onUpdateToolbarButtons = { buttons -> handleUpdateToolbarButtons(service, buttons) },
            onOpenToolPanel = { pluginId -> service.openToolPanel(pluginId) },
            onToolPanelClose = { service.closeToolPanel() },
            onToolPanelItemClick = { item -> service.commitToolPanelItem(item.text) },
            onToolPanelAction = { actionId -> service.dispatchToolPanelAction(actionId) },
            onToolPanelFieldInput = { key, value -> service.onToolPanelFieldInput(key, value) },
            onToolPanelFocusChange = { focused -> handleToolPanelFocusChange(service, focused) },
            onKeyboardModeChange = { chineseMode -> handleKeyboardModeChange(service, chineseMode) },
            onDismissDeploying = { service.notifyDeploymentStatus(false, "") },
            onFloatingModeChange = { enabled -> service.schemaController.toggleFloatingMode(enabled, floatingMinY) },
            onFloatingKeyboardDrag = { dx, dy -> handleFloatingKeyboardDrag(service, floatingMinY, dx, dy) },
            onFloatingKeyboardDragEnd = { handleFloatingKeyboardDragEnd(service) },
            onT9ReplaceFullPinyin = { pinyin -> handleT9ReplaceFullPinyin(service, pinyin) },
            onT9RightCommitUndone = { count -> handleT9RightCommitUndone(service, count) },
            onT9RefreshComposition = { composition, injections ->
                // composition 由 T9 控制器在 flush 后一次取回并传入，
                // 避免在此再次 getComposition 造成重复 JNI 往返。
                service.mainHandler.post {
                    service.sessionController.applyComposition(composition, emptyList(), injections)
                }
            },
            onTCandidateTransform = { result ->
                // T9 后台线程（t9Dispatcher）调用：同步等插件至多 15ms，主线程零等待
                service.candidateTransform.transformForT9(result)
            },
            onT9SwitchAway = {
                service.keyRouter.postRimeJob {
                    service.sessionController.commitFirstCandidateAndClearT9()
                }
            },
            onCommitCandidateBeforeModeChange = { handleCommitCandidateBeforeModeChange(service) },
            onShowQuickSendForm = { handleShowQuickSendForm(service) },
            onQuickSendEditItem = { id, text, code -> handleQuickSendEditItem(service, id, text, code) },
            onHideQuickSendForm = { handleHideQuickSendForm(service) },
            onQuickSendFormFocusChange = { focused: Boolean -> handleQuickSendFormFocusChange(service, focused) },
            onQuickSendCodeFocusChange = { focused: Boolean -> handleQuickSendCodeFocusChange(service, focused) },
        )
    }
}

    /**
     * 联想候选上屏（rememberImeKeyboardCallbacks 的 onAssociationSelect 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleAssociationSelect(service: XimeInputMethodService, view: View, index: Int) {
        service.feedbackManager.performKeyPressEffect(view = view)
        val cs = service.candidateState.value
        val adjustedCandidates = if (cs.pendingEnglishText.isNotEmpty() && cs.englishReplaceSupported) {
            listOf(cs.pendingEnglishText) + cs.associationCandidates
        } else {
            cs.associationCandidates
        }
        if (index >= 0 && index < adjustedCandidates.size) {
            val text = adjustedCandidates[index]
            val pendingEnglish = cs.pendingEnglishText
            if (pendingEnglish.isNotEmpty()) {
                // 英文直接上屏模式：编码已逐字落盘，选中候选词时需回删屏上编码再提交候选词。
                // 第 0 项即当前已键入文本本身（上屏确认），无需替换。
                if (index == 0 && text == pendingEnglish) {
                    service.candidateState.value = service.candidateState.value.copy(
                        pendingEnglishText = "",
                        associationCandidates = emptyList()
                    )
                } else {
                    // 内部编辑器（快捷发送/工具面板）与宿主 InputConnection 统一走
                    // service 层重定向，避免编码回删/替换作用到错误的屏上文本。
                    var replaced = service.replaceBeforeCursor(pendingEnglish, text)
                    if (!replaced) {
                        // 光标位置与编码不对应（用户移动过光标）：放弃替换，
                        // 候选词降级为直接追加上屏，避免误删用户文本。
                        service.commitText(text)
                    }
                    FileLogger.d(
                        XimeInputMethodService.TAG,
                        "english candidate replace: replaced=$replaced, expected=${pendingEnglish.length} chars"
                    )
                    service.candidateState.value = service.candidateState.value.copy(
                        pendingEnglishText = "",
                        associationCandidates = emptyList()
                    )
                }
            } else {
                // 单次联想模式（仅中文模式——英文 commitText 不触发推理，
                // 否则抑制标志会悬挂并吞掉下次切回中文后的首轮推理）：
                // 联想候选上屏前先置抑制标志——commitText 触发的下一轮自动推理
                // 被跳过并清空联想候选（只推理一次）。
                // 连续联想模式：不抑制，commitText 自动推理新联想（一直上屏一直推理）。
                if (!service.uiState.value.isAsciiMode && SettingsPreferences.isSingleAssociationMode(service)) {
                    service.predictionManager.suppressNextPredictionOnce()
                }
                service.commitText(text)
                service.updateUI()
            }
        }
    }

    /**
     * 编码/编辑器光标移动（onCursorMove 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    internal fun handleCursorMove(service: XimeInputMethodService, direction: Int) {
        if (direction != 0) {
            if (service.candidateState.value.isComposing &&
                !isT9Schema(service.uiState.value.currentSchemaId)
            ) {
                // 组合态（仅全键盘）：滑动 = 移动编码编辑光标。光标由宿主维护、
                // 仅是插入/删除位置——librime caret 恒在编码末尾，候选始终针对
                // 整个编码转换；编辑操作（退格/字母）由路由层按此位置拦截处理。
                // 上屏（空闲态）后滑动走下方编辑器光标逻辑
                val input = service.rimeEngine.getInput()
                if (input.isNotEmpty()) {
                    val base = if (service.editingCaretPos < 0) input.length else service.editingCaretPos
                    val target = (base + direction).coerceIn(0, input.length)
                    service.editingCaretPos = if (target >= input.length) -1 else target
                    // 编码快照供 displayCaretOffset 失同步自愈比对
                    service.editingCaretInput = if (service.editingCaretPos >= 0) input else ""
                    service.updateUI()
                }
            } else {
                val ic = service.currentInputConnection
                if (ic != null) {
                    var movedBySelection = false
                    try {
                        val req = android.view.inputmethod.ExtractedTextRequest()
                        val extracted = ic.getExtractedText(req, 0)
                        if (extracted != null && extracted.selectionStart >= 0) {
                            val newPos = (extracted.selectionStart + direction)
                                .coerceIn(0, extracted.text?.length ?: 0)
                            ic.setSelection(newPos, newPos)
                            movedBySelection = true
                        }
                    } catch (_: Exception) {}
                    if (!movedBySelection) {
                        val keyCode = if (direction < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                        repeat(abs(direction)) {
                            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                        }
                    }
                }
            }
        }
    }

    /**
     * 悬浮键盘拖拽（onFloatingKeyboardDrag 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleFloatingKeyboardDrag(service: XimeInputMethodService, floatingMinY: Int, dx: Float, dy: Float) {
        val s = service.uiState.value
        val config = service.resources.configuration
        val halfMargin = FloatingCardGeometry.halfMarginDpForConfig(config.screenWidthDp, config.screenHeightDp)
        // dx/dy 为原始屏幕位移（+x 右、+y 下）：x 直接累加，y 与 offsetY
        // 方向相反（offsetY = 卡片离底边的距离，上拖 dy<0 → 卡片上移）
        val newX = (s.floatingOffsetX + dx).roundToInt().coerceIn(-halfMargin, halfMargin)
        // 卡高以实测（onCardPositioned）为唯一真源，首帧实测前走几何兜底
        val cardHeightDp = if (service.currentFloatingCardHeightDp > 0) {
            service.currentFloatingCardHeightDp
        } else {
            FloatingCardGeometry.fallbackCardHeightDp(
                SettingsPreferences.getKeyboardHeightDp(service, false)
                    .coerceAtMost((config.screenHeightDp * 8) / 10),
                s.keyboardBottomPaddingDp,
            )
        }
        // 悬浮卡片恒为竖屏形态：有效屏高 = 物理高 - 状态栏（与 setContent 一致）
        val screenH = if (s.isFloatingMode) {
            val metrics = service.resources.displayMetrics
            (metrics.heightPixels / metrics.density).roundToInt() -
                tryGetStatusBarHeightDp(service, service.window.window)
        } else {
            config.screenHeightDp
        }
        val rawY = (s.floatingOffsetY - dy).roundToInt()
        val newY = FloatingCardGeometry.clampOffsetY(
            rawY,
            minY = floatingMinY,
            screenHeightDp = screenH,
            cardHeightDp = cardHeightDp,
        )
        // 试图越过最低点（继续下拖）= 进入"松手切换非悬浮"提示态：边沿触发
        // 震动一次（不逐帧），同时点亮底部光效；拖离底部即退出提示态
        val atExitHint = rawY <= floatingMinY
        if (atExitHint != service.floatingExitHintState.value) {
            service.floatingExitHintState.value = atExitHint
            if (atExitHint) {
                service.feedbackManager.performVibration()
            }
        }
        service.uiState.value = s.copy(
            floatingOffsetX = newX,
            floatingOffsetY = newY,
        )
    }

    /**
     * 语音模式进入/退出（onVoiceModeChange 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleVoiceModeChange(service: XimeInputMethodService, enabled: Boolean) {
        if (!enabled) {
            // 长按抬手/常驻语音轻触空格或点按候选栏频谱：结束语音会话
            // （提交当前已识别文本并停止识别）。
            // 常驻语音下补一次震动反馈——轻触空格没有任何按键事件，
            // 不补就完全没有触感（与进入语音时的 performVibration 对称）。
            if (service.uiState.value.voiceSticky) {
                service.feedbackManager.performVibration()
            }
            service.endVoiceSession()
        } else if (!service.uiState.value.isVoiceMode) {
            service.uiState.value = service.uiState.value.copy(
                isVoiceMode = true,
                voiceSticky = false,
                voiceButtonState = VoiceButtonState(bottomActive = true),
                voiceRecognizedText = ""
            )
            service.keyboardViewModel.enterVoice()
            service.feedbackManager.performVibration()
            service.isTrackingVoiceButtons = true
            service.keyboardContainer.enableVoiceButtonTracking()
            service.voiceRecordingStarted = true
            // 立即提前启动麦克风录音（等 150ms 的话模型常驻时加载极快，
            // preStarted 可能还没创建好就被 startRecording 跳过，导致开头丢失）
            service.voiceRecognitionHandler.startDelayedPreStart(0)
            service.voiceRecognitionHandler.startRecognition()
        }
    }

    /**
     * 常驻语音开关（onVoiceStickyToggle 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleVoiceStickyToggle(service: XimeInputMethodService) {
        val state = service.uiState.value
        if (state.isVoiceMode && state.voiceSticky) {
            // 常驻语音中：点按空格/再次点击工具栏即结束
            service.endVoiceSession()
        } else if (!state.isVoiceMode) {
            // 进入常驻语音：保持正常键盘布局，候选栏显示频谱，空格键轻触结束
            service.uiState.value = service.uiState.value.copy(
                isVoiceMode = true,
                voiceSticky = true,
                voiceButtonState = VoiceButtonState(bottomActive = true),
                voiceRecognizedText = ""
            )
            service.voiceRecordingStarted = true
            service.voiceRecognitionHandler.startDelayedPreStart(0)
            service.voiceRecognitionHandler.startRecognition()
        }
    }

    /**
     * T9 整串替换拼音（onT9ReplaceFullPinyin 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleT9ReplaceFullPinyin(service: XimeInputMethodService, pinyin: String) {
        service.serviceScope.launch(service.keyProcessingDispatcher) {
            when {
                pinyin == T9InputController.CLEAR_COMPOSITION_ONLY -> {
                    service.rimeEngine.clearComposition()
                }
                pinyin == T9InputController.CLEAR_ALL -> {
                    service.t9PartialSegments.clear()
                    service.rimeEngine.setInput("")
                    service.rimeEngine.clearComposition()
                }
                pinyin.isEmpty() -> {
                    service.rimeEngine.clearComposition()
                }
                else -> {
                    service.rimeEngine.setInput(pinyin)
                }
            }
            val composition = service.rimeEngine.getComposition()
            withContext(Dispatchers.Main) {
                service.mainHandler.post { service.sessionController.applyComposition(composition) }
            }
        }
    }

    /**
     * T9 右提交撤销（onT9RightCommitUndone 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleT9RightCommitUndone(service: XimeInputMethodService, count: Int) {
        // 半提交文本在 composing 区域时无法用 deleteSurroundingText 删除，
        // 需通过 endComposingInputBox 清空，交由后续 applyComposition 重建。
        if (SettingsPreferences.isInputBoxComposing(service)) {
            service.endComposingInputBox()
        } else {
            service.deleteBeforeCursor(count)
        }
        // undo 联动：撤销 right commit 段时回滚用户词典调频。
        val undone = service.t9PartialSegments.removeLastOrNull()
        if (undone != null) {
            service.serviceScope.launch(service.keyProcessingDispatcher) {
                service.rimeEngine.t9Forget(undone.text, undone.pinyin)
            }
        }
    }

    /**
     * 切模式前提交候选（onCommitCandidateBeforeModeChange 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleCommitCandidateBeforeModeChange(service: XimeInputMethodService) {
        val cs = service.candidateState.value
        if (cs.pendingEnglishText.isNotEmpty()) {
            // 英文直接上屏模式：编码字符已逐字落盘，切模式只需结束本轮输入（清状态），
            // 不可再 commitText 否则会重复输出整个词。
            service.candidateState.value = cs.copy(
                pendingEnglishText = "",
                associationCandidates = emptyList()
            )
        } else if (!isT9Schema(service.uiState.value.currentSchemaId)
            && cs.isComposing) {
            if (cs.candidates.isNotEmpty()) {
                if (service.rimeEngine.selectCandidate(0)) {
                    val text = service.rimeEngine.commit()
                    if (text.isNotEmpty()) service.commitText(text)
                }
            } else if (cs.preeditText.isNotEmpty()) {
                // 提交原始键入串而非 preeditText：后者现为带回显分隔符的展示串（如 ni'hao），
                // 直接上屏会混入分隔符。
                service.commitText(cs.inputText)
                service.rimeEngine.clearComposition()
            }
        }
    }

    /**
     * 打开快捷发送表单（onShowQuickSendForm 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleShowQuickSendForm(service: XimeInputMethodService) {
        service.closeToolPanel()
        // 从 Overlay 页面（剪贴板面板）打开表单时必须退出 Overlay：
        // Overlay 渲染为全屏 clickable Box（吞点击），若不退出，表单会被
        // 盖在其下——可见但关闭按钮等点击全部失效（时灵时不灵的根源：
        // 从主键盘打开则无叠加）。
        service.keyboardViewModel.closeOverlay()
        val current = service.uiState.value
        service.uiState.value = current.copy(
            showQuickSendForm = true,
            quickSendFormFocused = true,
            quickSendCodeFocused = false,
            quickSendEditingItemId = null,
            quickSendEditingItemText = "",
            quickSendEditingItemCode = "",
            enterKeyText = "确定",
        )
        // 撑高由 SideEffect 驱动：uiState 变化 → Compose 内容高度变化 →
        // updateHeight 改容器物理高度 → relayout → onComputeInsets 自动重算。
    }

    /**
     * 编辑快捷发送条目（onQuickSendEditItem 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleQuickSendEditItem(service: XimeInputMethodService, id: Long?, text: String, code: String) {
        // 同 onShowQuickSendForm：编辑入口在剪贴板面板内，先退出 Overlay 防表单被盖
        service.keyboardViewModel.closeOverlay()
        service.uiState.value = service.uiState.value.copy(
            showQuickSendForm = true,
            quickSendFormFocused = true,
            quickSendCodeFocused = false,
            quickSendEditingItemId = id,
            quickSendEditingItemText = text,
            quickSendEditingItemCode = code,
            enterKeyText = "确定",
        )
        QuickSendFormEditTextHolder.editText?.let { et ->
            et.setText(text)
            et.setSelection(text.length)
        }
    }

    /**
     * 收起快捷发送表单（onHideQuickSendForm 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleHideQuickSendForm(service: XimeInputMethodService) {
        android.util.Log.d("QuickSendForm", "onHideQuickSendForm invoked")
        service.uiState.value = service.uiState.value.copy(
            showQuickSendForm = false,
            quickSendFormFocused = false,
            quickSendCodeFocused = false,
            quickSendEditingItemId = null,
            quickSendEditingItemText = "",
            quickSendEditingItemCode = "",
            enterKeyText = "发送",
        )
        QuickSendFormEditTextHolder.editText = null
        QuickSendFormCodeEditTextHolder.editText = null
        service.keyboardViewModel.showOverlay(OverlayRoute.Clipboard(1))
        // insets 恢复由 SideEffect 驱动：表单收起 → 容器物理高度还原 →
        // relayout → onComputeInsets 自动重算，无需强制触发。
    }

    /**
     * 大写金额模式的整串重写（onReplaceCommittedText 回调体）。
     *
     * 与手写叠写的区别：手写是「尾部替换」，这里 [expected] 为整串大写金额，
     * 且为空时表示首次上屏。走 [XimeInputMethodService.commitTextSilently] 而非
     * 全量 commitText：金额逐键重写，每按一次都触发联想推理既浪费又会闪烁候选栏。
     */
    private fun handleReplaceCommittedText(
        service: XimeInputMethodService,
        expected: String,
        replacement: String,
    ): Boolean {
        if (expected.isEmpty()) {
            if (replacement.isNotEmpty()) service.commitTextSilently(replacement)
            return true
        }
        // 光标前文本不一致（用户移动过光标/退格过）时不上屏，调用方重置累积状态后重新开始
        return service.replaceBeforeCursor(expected, replacement)
    }

    /**
     * 手写预测自动上屏（onHandwritingAutoCommit 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleHandwritingAutoCommit(service: XimeInputMethodService, newTail: String, expectedTail: String): Boolean {
        return if (expectedTail.isEmpty()) {
            service.commitTextSilently(newTail)
            true
        } else {
            // 光标前文本与手写尾部不对应（用户移动过光标/退格过）时不上屏，
            // 调用方重置手写尾部状态后以追加模式重建，避免误删/重复上屏
            service.replaceBeforeCursor(expectedTail, newTail)
        }
    }

    /**
     * 进入键盘高度调整预览（onKeyboardResize 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleKeyboardResize(service: XimeInputMethodService) {
        val config = service.resources.configuration
        val isLandscape = config.screenWidthDp > config.screenHeightDp
        service.uiState.value = service.uiState.value.copy(
            showKeyboardResize = true,
            resizePreviewHeightDp = SettingsPreferences.getKeyboardHeightDp(service, isLandscape),
            resizePreviewMarginStartDp = SettingsPreferences.getKeyboardMarginStartDp(service),
            resizePreviewMarginEndDp = SettingsPreferences.getKeyboardMarginEndDp(service),
        )
    }

    /**
     * 弹出输入法选择器（onSwitchKeyboard 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleSwitchKeyboard(service: XimeInputMethodService) {
        // 输入法侧同样要包住：部分 ROM 在键盘窗口内调用会抛异常，未捕获即闪退。
        runCatching {
            val imm = service.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            @Suppress("DEPRECATION")
            imm?.showInputMethodPicker()
        }
    }

    /**
     * 上屏图片，失败则回退到剪贴板（onCommitImage 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleCommitImage(service: XimeInputMethodService, imagePath: String) {
        val success = service.textCommit.commitImage(imagePath)
        if (!success) {
            android.widget.Toast.makeText(
                service,
                "发送失败，已复制到剪贴板",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            service.clipboardManager.copyImageToSystemClipboard(imagePath)
        }
    }

    /**
     * 持久化并应用工具栏按钮（onUpdateToolbarButtons 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleUpdateToolbarButtons(service: XimeInputMethodService, buttons: List<String>) {
        SettingsPreferences.setToolbarButtons(service, buttons)
        service.uiState.value = service.uiState.value.copy(toolbarButtons = buttons)
    }

    /**
     * 工具面板输入焦点变化（onToolPanelFocusChange 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleToolPanelFocusChange(service: XimeInputMethodService, focused: Boolean) {
        service.uiState.value = service.uiState.value.copy(
            toolPanelInputFocused = focused,
        )
    }

    /**
     * 中英模式切换（onKeyboardModeChange 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleKeyboardModeChange(service: XimeInputMethodService, chineseMode: Boolean) {
        if (service.isChineseMode != chineseMode) {
            service.isChineseMode = chineseMode
            if (!chineseMode) {
                service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
            }
        }
    }

    /**
     * 悬浮键盘拖拽结束（onFloatingKeyboardDragEnd 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleFloatingKeyboardDragEnd(service: XimeInputMethodService) {
        if (service.floatingExitHintState.value) {
            // 底部松手：切换为非悬浮（停靠），位置语义与菜单开关同一条出口
            service.floatingExitHintState.value = false
            service.schemaController.toggleFloatingMode(false)
        } else {
            val s = service.uiState.value
            val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
            SettingsPreferences.setFloatingOffsetX(service, s.floatingOffsetX, isLandscape)
            SettingsPreferences.setFloatingOffsetY(service, s.floatingOffsetY, isLandscape)
        }
    }

    /**
     * 快捷发送表单焦点变化（onQuickSendFormFocusChange 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleQuickSendFormFocusChange(service: XimeInputMethodService, focused: Boolean) {
        // 聚焦时回车键为"确定"；失焦不改文案——表单仍显示，回车保持"确定"
        // 语义（提交并关闭，由 handleKeyPress 的 showQuickSendForm 分支保证）。
        // 关闭表单时由 onHideQuickSendForm / onWindowHidden 统一还原"发送"。
        val s = service.uiState.value
        service.uiState.value = if (focused) {
            // 文本框抢回焦点：编码焦点清除，按键输入回到文本框
            s.copy(quickSendFormFocused = true, quickSendCodeFocused = false, enterKeyText = "确定")
        } else {
            s.copy(quickSendFormFocused = false, quickSendCodeFocused = false)
        }
    }

    /**
     * 快捷发送编码框焦点变化（onQuickSendCodeFocusChange 回调体）。
     *
     * 正文自原回调逐字搬出，仅整体缩进减 8 空格。
     */
    private fun handleQuickSendCodeFocusChange(service: XimeInputMethodService, focused: Boolean) {
        val s = service.uiState.value
        service.uiState.value = if (focused) {
            // 编码框获得焦点：同样视为表单聚焦（回车"确定"/按键路由生效）
            s.copy(quickSendFormFocused = true, quickSendCodeFocused = true, enterKeyText = "确定")
        } else {
            s.copy(quickSendCodeFocused = false)
        }
    }
