package com.kingzcheung.xime.ai

import android.app.Application
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.ai.audio.WavAudioRecorder
import com.kingzcheung.xime.ai.data.HistoryRetention
import com.kingzcheung.xime.ai.ime.ImeHostCallbacks
import com.kingzcheung.xime.ai.ime.ImeUiState
import com.kingzcheung.xime.ai.ime.UndoSnapshot
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * AI 语音链路 ↔ Xime 输入法服务的适配层。
 *
 * 职责：把 TypeFree 的 [ImeHostCallbacks] 契约翻译成 Xime 的能力。
 * XimeInputMethodService 只需构造一个实例并传给 VoiceController，不必了解 AI 内部细节。
 *
 * 设计要点：
 * - 所有 InputConnection 读取都包了 runCatching：宿主输入框可能随时失效（切应用、
 *   焦点丢失），AI 链路不能因此崩溃。
 * - 上屏走 Xime 现有的 commitText，保证与 Rime 的 composing 状态互不干扰。
 * - 撤销快照在 Xime 侧是简单的单槽（够 AI 改写/语音上屏的一步撤回），不引入完整 undo 栈。
 *
 * @param app      Application（取 Context 用）
 * @param bridge   宿主能力提供者——由 XimeInputMethodService 实现
 */
class XimeAiHostBridge(
    private val app: Application,
    private val bridge: HostBridge,
) : ImeHostCallbacks {

    private val TAG = "XimeAiHost"

    override val scope: CoroutineScope get() = XimeAiApp.scope
    override val repository get() = XimeAiApp.repository
    override val aiEngine get() = XimeAiApp.aiEngine

    /** AI 面板自己的 UI 状态（与 Xime 的 KeyboardUiState 解耦，避免污染主键盘）。 */
    override val stateFlow = MutableStateFlow(ImeUiState())
    override val appContext: android.content.Context get() = app

    // ---- 撤销：单槽快照（AI 改写/语音上屏后的一步撤回） ----
    private var lastUndo: UndoSnapshot? = null

    /** 宿主需要实现的最小能力集。 */
    interface HostBridge {
        fun inputConnection(): InputConnection?
        fun currentEditorInfo(): android.view.inputmethod.EditorInfo?
        fun commitText(text: String)
        fun performFeedback(strong: Boolean)
        fun toast(message: String)
        /** 当前编辑框是否为密码/敏感框（AI 链路据此禁用读屏与历史）。 */
        fun isSensitiveEditor(): Boolean
    }

    override fun currentInputConnection(): InputConnection? =
        runCatching { bridge.inputConnection() }.getOrNull()

    override fun currentEditorInfo(): android.view.inputmethod.EditorInfo? =
        runCatching { bridge.currentEditorInfo() }.getOrNull()

    override fun performKeyFeedback(strong: Boolean) {
        runCatching { bridge.performFeedback(strong) }
    }

    override fun performHaptic(strong: Boolean) {
        runCatching { bridge.performFeedback(strong) }
    }

    override fun showQuickActionToast(message: String) {
        runCatching { bridge.toast(message) }
    }

    override fun commitDirectText(
        text: String,
        recordUndo: Boolean,
        markManualEdit: Boolean,
        playFeedback: Boolean
    ) {
        if (text.isEmpty()) return
        // 记"上屏前原文 + 插入长度"，撤回时据此定位删除；原文留空表示纯插入
        if (recordUndo) {
            lastUndo = UndoSnapshot(replacedOriginalText = "", insertedTextLength = text.length)
        }
        runCatching {
            // 交给 Xime 的 commitText：它会处理 Rime 组合态收尾、词频学习、插件事件广播，
            // 直接写 InputConnection 会绕过这些。
            bridge.commitText(text)
        }.onFailure { FileLogger.e(TAG, "commitDirectText failed", it) }
        if (playFeedback) performKeyFeedback(false)
    }

    override fun pushUndoSnapshot(snapshot: UndoSnapshot?) {
        lastUndo = snapshot
    }

    override fun peekTopUndoSnapshot(): UndoSnapshot? = lastUndo

    override fun recordManualEditTouch() {
        // Xime 的人工编辑学习在 Rime 层处理，AI 侧不需要额外记账
    }

    override fun tryAutoLearnFromRecentEdit() {
        // 同上：TypeFree 用 Myers Diff 学词，Xime 交给造词链路
    }

    override fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String) {
        // Xime 的词频/造词在 commitText 内部已处理，此处不重复
    }

    override fun clearAutoLearnBaseline() {
    }

    override fun readSelectedTextSafely(): String =
        runCatching {
            val ic = currentInputConnection() ?: return ""
            ic.getSelectedText(0)?.toString().orEmpty()
        }.getOrDefault("")

    override fun readSurroundingBeforeCursor(maxChars: Int): String =
        runCatching {
            val ic = currentInputConnection() ?: return ""
            ic.getTextBeforeCursor(maxChars, 0)?.toString().orEmpty()
        }.getOrDefault("")

    override fun readFullEditorTextSafely(): String =
        runCatching {
            val ic = currentInputConnection() ?: return ""
            val before = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(2000, 0)?.toString().orEmpty()
            before + after
        }.getOrDefault("")

    override fun readSystemClipboardText(): String =
        // 胶囊会把上下文交给 AI 模型，敏感内容不得进入
        runCatching {
            com.kingzcheung.xime.clipboard.ClipboardManager.getInstance(app)
                .getCurrentClipboardText(allowSensitive = false)
        }
            .getOrNull().orEmpty()

    override fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean) {
        // Xime 的剪贴板监听常驻，无需此处同步
    }

    override fun copyTextToClipboard(text: String) {
        runCatching { com.kingzcheung.xime.clipboard.ClipboardManager.getInstance(app).copyToSystemClipboard(text) }
    }

    /**
     * 归档本次录音，返回**已归档**文件的路径（历史记录据此回放）。
     *
     * 旧实现 `= wavFile` 是空实现：直接把缓存里的 `last_typefree_voice.wav` 返回给历史记录，
     * 而该文件每次录音都会被 `setLength(0)` 覆盖 —— 于是历史里所有条目都指向同一份文件，
     * 点开任意一条播放的都是「最近一次」录音（串音）。录音器那边本来就有完整实现
     * （复制到 files/voice_history/voice_<ts>_<rand>.wav + 保留期清理），这里直接委托即可。
     */
    override fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File =
        runCatching {
            WavAudioRecorder.archiveWavForHistory(app.filesDir, wavFile, retention)
        }.getOrElse { wavFile }

    /**
     * 「上一次录音」文件 —— 必须与录音器实际写入的路径一致。
     *
     * 旧实现指向 `ai_last_recording.wav`，但全仓库没有任何地方写这个文件名（录音器写的是
     * `last_typefree_voice.wav`），导致 `hasRetryWavFile()` 恒为 false：
     * 错误态永远不提供「重试」，`retryLastAudio()` 恒走「未找到可重试的最近录音文件」。
     * 统一委托给录音器，避免两处各写一份文件名再次漂移。
     */
    override fun getLastRecordingWavFile(): File =
        WavAudioRecorder.getLastRecordingFile(app)

    /** 只有存在且大于 WAV 头（44 字节）才算有可重试的有效录音。 */
    override fun hasRetryWavFile(): Boolean =
        runCatching {
            val f = getLastRecordingWavFile()
            f.exists() && f.length() > 44L
        }.getOrDefault(false)
}
