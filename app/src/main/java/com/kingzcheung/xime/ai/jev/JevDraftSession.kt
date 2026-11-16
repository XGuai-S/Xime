package com.kingzcheung.xime.ai.jev

import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.ai.privacy.RedactionSession

/**
 * Abstraction over an editor target (`InputConnection` on Android, or an in-memory buffer in JVM
 * unit tests) for `JevDraftSession`.
 *
 * Notice that this interface intentionally has NO editor-action or key-event dispatch methods,
 * physically guaranteeing at the type level that `✦ Jev 军师` can NEVER auto-send a message.
 */
interface JevDraftEditorTarget {
    fun beginBatchEdit(): Boolean
    fun endBatchEdit(): Boolean
    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean
    fun finishComposingText(): Boolean
    fun getTextBeforeCursor(maxChars: Int): String
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
}

/**
 * Adapter wrapping an Android [InputConnection] as a [JevDraftEditorTarget].
 */
class InputConnectionDraftTarget(
    private val ic: InputConnection
) : JevDraftEditorTarget {
    override fun beginBatchEdit(): Boolean = runCatching { ic.beginBatchEdit() }.getOrDefault(false)
    override fun endBatchEdit(): Boolean = runCatching { ic.endBatchEdit() }.getOrDefault(false)
    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
        runCatching { ic.setComposingText(text, newCursorPosition) }.getOrDefault(false)
    override fun finishComposingText(): Boolean =
        runCatching { ic.finishComposingText() }.getOrDefault(false)
    override fun getTextBeforeCursor(maxChars: Int): String =
        runCatching { ic.getTextBeforeCursor(maxChars, 0)?.toString().orEmpty() }.getOrDefault("")
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        runCatching { ic.deleteSurroundingText(beforeLength, afterLength) }.getOrDefault(false)
}

/**
 * Pure JVM-testable fake editor target simulating both standard composing-aware `EditText`
 * and quirky host `EditText`s that auto-commit `setComposingText` without maintaining a composing span.
 */
class InMemoryDraftEditorTarget(
    initialPrefixText: String = "",
    private val simulateAutoCommitQuirk: Boolean = false
) : JevDraftEditorTarget {
    private val committedPrefix = StringBuilder(initialPrefixText)
    var composingText: String = ""
        private set

    val fullEditorText: String
        get() = committedPrefix.toString() + composingText

    val bufferText: String
        get() = fullEditorText

    val composingStart: Int
        get() = if (composingText.isEmpty()) -1 else committedPrefix.length

    override fun beginBatchEdit(): Boolean = true
    override fun endBatchEdit(): Boolean = true

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        val str = text.toString()
        if (simulateAutoCommitQuirk) {
            committedPrefix.append(str)
            composingText = ""
        } else {
            composingText = str
        }
        return true
    }

    override fun finishComposingText(): Boolean {
        if (composingText.isNotEmpty()) {
            committedPrefix.append(composingText)
            composingText = ""
        }
        return true
    }

    override fun getTextBeforeCursor(maxChars: Int): String {
        val full = fullEditorText
        return if (full.length <= maxChars) full else full.takeLast(maxChars)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength <= 0) return true
        // Standard InputConnection deletes before the composing region
        val deleteCount = beforeLength.coerceAtMost(committedPrefix.length)
        if (deleteCount > 0) {
            committedPrefix.delete(committedPrefix.length - deleteCount, committedPrefix.length)
        }
        return true
    }
}

/**
 * Stateful in-place draft replacement controller for `✦ Jev 军师`.
 *
 * Guarantees:
 * 1. Clicking card A -> card B -> card C (even 10+ times) always replaces the previous Jev draft
 *    in-place without stacking duplicate sentences, while preserving any text the user had already
 *    typed before opening Jev.
 * 2. Restores only reversible contact placeholders (`[手机号_1]`, `[邮箱_1]`, `[地址_1]`, `[取件码_1]`)
 *    via [RedactionSession.restoreSafePlaceholders] and NEVER restores high-risk credentials.
 * 3. On panel exit (e.g. tapping `[鹤 打字]` or `[×]`), commits the active composing draft via
 *    [commitAndFinishOnExit] so the user can fine-tune words in the host input field and manually
 *    press the host app's Send button.
 */
class JevDraftSession {

    var selectedIndex: Int = -1
        private set

    var activeInjectedDraft: String = ""
        private set

    val hasActiveDraft: Boolean
        get() = activeInjectedDraft.isNotEmpty()

    val currentDraftText: String
        get() = activeInjectedDraft

    val selectedCardIndex: Int
        get() = selectedIndex

    /**
     * Previews/injects [rawStrategyText] into [target], replacing any previously injected Jev draft
     * in this session without stacking.
     *
     * @return The safe (contact-restored, credential-redacted) draft text injected into the editor.
     */
    fun applyStrategyCard(
        target: JevDraftEditorTarget,
        cardIndex: Int,
        rawStrategyText: String,
        redactionSession: RedactionSession? = null
    ): String {
        val safeDraft = (redactionSession?.restoreSafePlaceholders(rawStrategyText) ?: rawStrategyText)
            .trim()
        if (safeDraft.isEmpty()) return ""

        target.beginBatchEdit()
        try {
            val prevDraft = activeInjectedDraft
            if (prevDraft.isNotEmpty()) {
                // 1. Clear standard composing span first (no-op if host auto-committed)
                target.setComposingText("", 1)
                // 2. If a quirky host editor had auto-committed prevDraft into plain text, remove that tail
                val tail = target.getTextBeforeCursor(prevDraft.length + 8)
                if (tail.endsWith(prevDraft)) {
                    target.deleteSurroundingText(prevDraft.length, 0)
                }
            }
            target.setComposingText(safeDraft, 1)
            activeInjectedDraft = safeDraft
            selectedIndex = cardIndex
        } finally {
            target.endBatchEdit()
        }
        return safeDraft
    }

    /**
     * Finalizes the active composing draft when leaving `✦ Jev 军师` (e.g. switching to `[鹤 打字]`
     * or closing the panel) and returns the committed text for `UndoSnapshot` registration.
     */
    fun commitAndFinishOnExit(target: JevDraftEditorTarget?): String {
        val committed = activeInjectedDraft
        if (committed.isNotEmpty() && target != null) {
            target.beginBatchEdit()
            try {
                target.finishComposingText()
            } finally {
                target.endBatchEdit()
            }
        }
        activeInjectedDraft = ""
        selectedIndex = -1
        return committed
    }

    /**
     * Resets session tracking when starting a new generation or clearing state.
     */
    fun resetSessionOnly() {
        activeInjectedDraft = ""
        selectedIndex = -1
    }
}
