package com.kingzcheung.xime.ai.ime

import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.ai.data.AppRepository
import com.kingzcheung.xime.ai.data.HistoryRetention
import com.kingzcheung.xime.ai.network.AiPipelineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * AI 语音链路对宿主输入法的最小依赖接口。
 *
 * 移植自 TypeFree 的 TypingController.kt（原文件把 26 键引擎、联想、标点快捷键
 * 与宿主回调混在一起；Xime 的键盘由 Rime 负责，只需这些回调）。
 *
 * 实现方：XimeAiHostBridge（把调用转发到 XimeInputMethodService）。
 */
interface ImeHostCallbacks {
    val scope: CoroutineScope
    val repository: AppRepository
    val aiEngine: AiPipelineEngine
    val stateFlow: MutableStateFlow<ImeUiState>
    /** 应用 Context（本地 ASR 等需要）。 */
    val appContext: android.content.Context

    fun currentInputConnection(): InputConnection?
    /** 当前编辑框的 EditorInfo（面板判断回车动作等用）。 */
    fun currentEditorInfo(): android.view.inputmethod.EditorInfo?
    fun performKeyFeedback(strong: Boolean = false)
    fun performHaptic(strong: Boolean = false)
    fun showQuickActionToast(message: String)

    fun commitDirectText(
        text: String,
        recordUndo: Boolean,
        markManualEdit: Boolean = false,
        playFeedback: Boolean = true
    )

    fun pushUndoSnapshot(snapshot: UndoSnapshot?)
    fun peekTopUndoSnapshot(): UndoSnapshot?
    fun recordManualEditTouch()
    fun tryAutoLearnFromRecentEdit()
    fun onDictationCommittedForAutoLearn(fullBufferAfterCommit: String)
    fun clearAutoLearnBaseline()

    fun readSelectedTextSafely(): String
    fun readSurroundingBeforeCursor(maxChars: Int): String
    fun readFullEditorTextSafely(): String
    fun readSystemClipboardText(): String
    fun syncSystemClipboardIfNeeded(allowFreshBanner: Boolean)
    fun copyTextToClipboard(text: String) {}

    fun archiveWavForHistory(wavFile: File, retention: HistoryRetention): File
    fun getLastRecordingWavFile(): File
    fun hasRetryWavFile(): Boolean
}
