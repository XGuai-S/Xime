package com.kingzcheung.xime.ai.ime

import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.PipelineMode
import com.kingzcheung.xime.ai.data.SttProtocol
import com.kingzcheung.xime.ai.data.VoiceIntentMode
import com.kingzcheung.xime.ai.network.asr.LiveTranscript

/**
 * Lightweight abstraction over [InputConnection] enabling deterministic pure-JVM unit tests
 * for [StreamingComposer] without Android framework instrumentation.
 */
interface ComposerEditorBridge {
    fun beginBatchEdit(): Boolean
    fun endBatchEdit(): Boolean
    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean
    fun finishComposingText(): Boolean
    fun setComposingRegion(start: Int, end: Int): Boolean
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    fun getTextBeforeCursor(n: Int, flags: Int): CharSequence?
}

/**
 * Production [ComposerEditorBridge] backed by the active Android IME [InputConnection].
 */
class InputConnectionEditorBridge(
    private val connectionProvider: () -> InputConnection?
) : ComposerEditorBridge {
    override fun beginBatchEdit(): Boolean =
        runCatching { connectionProvider()?.beginBatchEdit() ?: false }.getOrDefault(false)

    override fun endBatchEdit(): Boolean =
        runCatching { connectionProvider()?.endBatchEdit() ?: false }.getOrDefault(false)

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
        runCatching { connectionProvider()?.setComposingText(text, newCursorPosition) ?: false }.getOrDefault(false)

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
        runCatching { connectionProvider()?.commitText(text, newCursorPosition) ?: false }.getOrDefault(false)

    override fun finishComposingText(): Boolean =
        runCatching { connectionProvider()?.finishComposingText() ?: false }.getOrDefault(false)

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        runCatching { connectionProvider()?.setComposingRegion(start, end) ?: false }.getOrDefault(false)

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        runCatching { connectionProvider()?.deleteSurroundingText(beforeLength, afterLength) ?: false }.getOrDefault(false)

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        runCatching { connectionProvider()?.getTextBeforeCursor(n, flags) }.getOrNull()
}

/**
 * Outcome returned when [StreamingComposer] completes a voice turn (either SMART direct or Dual-Stage polished).
 */
data class ComposerFinalizeOutcome(
    val appliedToEditor: Boolean,
    val wasFrozen: Boolean,
    val rawWrittenText: String,
    val finalCommittedText: String,
    val undoSnapshot: UndoSnapshot?
)

/**
 * State machine that streams real-time ASR recognition directly into the host editor's
 * [InputConnection] (`Idle` -> `Live` -> `Frozen` -> `Finishing` -> `Polishing` -> `Done`).
 *
 * Implements Scheme 3.3 & 3.4:
 * 1. Mutable `interim` stays inside `setComposingText(interim, 1)` so self-corrections like `"你"` -> `"泥土"`
 *    replace in-place; newly finalized clauses call `commitText(newFinalDelta, 1)`.
 * 2. Throttled to at most 15 updates/sec (`66ms` minimum interval), skipping identical content,
 *    wrapping every update in `beginBatchEdit()` / `endBatchEdit()`, while flushing finalized advances
 *    and `finish` immediately without throttling.
 * 3. Tracks `anchor` and `totalWrittenLength`; if `onUpdateSelection` detects the user moved the cursor
 *    away from `anchor + totalWrittenLength`, immediately calls `finishComposingText()` and enters `Frozen`.
 * 4. On mic release in Dual-Stage mode, verifies `getTextBeforeCursor(writtenText.length, 0) == writtenText`
 *    before replacing with the LLM-polished text once via `setComposingRegion` + `commitText`
 *    (falling back to `deleteSurroundingText` + `commitText`). If verification fails, never overwrites.
 *    Saves `UndoSnapshot(replacedOriginalText = rawWrittenText, insertedTextLength = polishedText.length)`
 *    so 1-tap Undo restores the unpolished raw transcript.
 * 5. In SMART direct mode (Gemini Live SMART), transitions `Finishing -> Done` with zero post-release jump.
 */
class StreamingComposer(
    private val editorBridge: ComposerEditorBridge,
    private val minUpdateIntervalMs: Long = MIN_UPDATE_INTERVAL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    enum class State {
        Idle,
        Live,
        Frozen,
        Finishing,
        Polishing,
        Done
    }

    var state: State = State.Idle
        private set

    var anchor: Int = 0
        private set

    var isSmartDirectMode: Boolean = false
        private set

    var committedFinalText: String = ""
        private set

    var currentInterimText: String = ""
        private set

    var writtenBeforeFreeze: String = ""
        private set

    var wasFrozenDuringSession: Boolean = false
        private set

    private var latestObservedTranscript: LiveTranscript = LiveTranscript()
    private var pendingThrottledTranscript: LiveTranscript? = null
    private var lastFlushAtMs: Long = -10_000L
    private var isInBatchEdit: Boolean = false
    private var cursorMovedDuringPolish: Boolean = false
    private var frozenRemainderCommitted: Boolean = false

    /**
     * Set when the host editor silently dropped our composing span (e.g. mobile QQ re-sets its text
     * on every change) while the stale interim text is still physically in the buffer. The next write
     * must delete that stale interim explicitly, otherwise `setComposingText` / `commitText` would
     * insert a second copy next to it ("落字重复两遍").
     */
    var hostDroppedComposing: Boolean = false
        private set

    /** Text right before [anchor] captured at session start; used to rule out ambiguous self-heals. */
    private var anchorPrefix: String = ""

    val totalWrittenLength: Int
        get() = committedFinalText.length + currentInterimText.length

    val expectedCursorPosition: Int
        get() = anchor + totalWrittenLength

    val writtenRawText: String
        get() = committedFinalText + currentInterimText

    val isActive: Boolean
        get() = state == State.Live || state == State.Frozen || state == State.Finishing || state == State.Polishing

    val uiStatus: LiveWriteUiStatus
        get() = when (state) {
            State.Live, State.Finishing, State.Polishing -> {
                if (wasFrozenDuringSession) LiveWriteUiStatus.FROZEN else LiveWriteUiStatus.WRITING
            }
            State.Frozen -> LiveWriteUiStatus.FROZEN
            State.Idle, State.Done -> LiveWriteUiStatus.INACTIVE
        }

    /**
     * Transitions from `Idle` -> `Live` when the microphone is pressed and live-write conditions are met.
     */
    fun startSession(
        anchorCursor: Int,
        smartDirectMode: Boolean = false
    ) {
        anchor = anchorCursor.coerceAtLeast(0)
        isSmartDirectMode = smartDirectMode
        committedFinalText = ""
        currentInterimText = ""
        writtenBeforeFreeze = ""
        wasFrozenDuringSession = false
        latestObservedTranscript = LiveTranscript()
        pendingThrottledTranscript = null
        lastFlushAtMs = -10_000L
        isInBatchEdit = false
        cursorMovedDuringPolish = false
        frozenRemainderCommitted = false
        hostDroppedComposing = false
        anchorPrefix = runCatching {
            editorBridge.getTextBeforeCursor(ANCHOR_PREFIX_WINDOW, 0)?.toString()
        }.getOrNull().orEmpty()
        state = State.Live
    }

    /**
     * Processes an incoming [LiveTranscript] while in `State.Live`.
     * Returns `true` if changes were flushed to [editorBridge], `false` if skipped or throttled.
     */
    fun onTranscriptUpdate(
        transcript: LiveTranscript,
        forceFlush: Boolean = false
    ): Boolean {
        latestObservedTranscript = transcript
        if (state != State.Live) {
            return false
        }

        val targetFinal = transcript.finalized
        val targetInterim = transcript.interim

        // Skip if identical to what is already displayed in the editor
        if (targetFinal == committedFinalText && targetInterim == currentInterimText) {
            pendingThrottledTranscript = null
            return false
        }

        val hasNewFinalized = targetFinal != committedFinalText
        val now = clockMs()
        val elapsedSinceLastFlush = now - lastFlushAtMs

        if (!forceFlush && !hasNewFinalized && elapsedSinceLastFlush < minUpdateIntervalMs) {
            pendingThrottledTranscript = transcript
            return false
        }

        pendingThrottledTranscript = null
        flushToEditor(targetFinal = targetFinal, targetInterim = targetInterim, nowMs = now)
        return true
    }

    /**
     * Removes the stale interim text that the host editor solidified on its own (composing span lost).
     * Verifies the characters before the cursor first so user text is never deleted.
     * Must be called inside a batch edit. Returns true if the stale interim was removed.
     */
    private fun purgeStaleInterimIfHostDroppedComposing(): Boolean {
        if (!hostDroppedComposing) return false
        hostDroppedComposing = false
        val stale = currentInterimText
        if (stale.isEmpty()) return false
        val replaceLen = resolveDraftReplaceLength(stale)
        if (replaceLen <= 0) return false
        editorBridge.finishComposingText()
        editorBridge.deleteSurroundingText(replaceLen, 0)
        currentInterimText = ""
        return true
    }

    private fun flushToEditor(
        targetFinal: String,
        targetInterim: String,
        nowMs: Long
    ) {
        isInBatchEdit = true
        editorBridge.beginBatchEdit()
        try {
            purgeStaleInterimIfHostDroppedComposing()
            val prevFinal = committedFinalText
            val prevInterim = currentInterimText
            var committedDelta = ""
            var composedInterim = ""
            val hasNewFinalDelta = targetFinal != committedFinalText

            if (hasNewFinalDelta) {
                // Explicitly flatten and remove the previous interim draft before committing finalized text.
                // This guarantees that even if the host editor (Feishu / Mobile QQ) dropped or split the
                // composing span mid-sentence (e.g. on spaces, commas, line wrap, or pause), or if the
                // finalized sentence removed filler words ("我靠,", "就是") and spaces, the raw interim
                // draft is completely replaced in-place and never left behind as a duplicate prefix.
                if (currentInterimText.isNotEmpty()) {
                    editorBridge.finishComposingText()
                    val interimReplaceLen = resolveDraftReplaceLength(currentInterimText)
                    if (interimReplaceLen > 0) {
                        editorBridge.deleteSurroundingText(interimReplaceLen, 0)
                    }
                    currentInterimText = ""
                }

                val commonLen = committedFinalText.commonPrefixWith(targetFinal).length
                val divergeTail = committedFinalText.substring(commonLen)
                if (divergeTail.isNotEmpty()) {
                    editorBridge.finishComposingText()
                    val divergeReplaceLen = resolveDraftReplaceLength(divergeTail)
                    if (divergeReplaceLen > 0) {
                        editorBridge.deleteSurroundingText(divergeReplaceLen, 0)
                    }
                }

                val delta = targetFinal.substring(commonLen)
                if (delta.isNotEmpty()) {
                    editorBridge.commitText(delta, 1)
                    committedDelta = delta
                }
                committedFinalText = targetFinal
            }

            if (targetInterim.isNotEmpty()) {
                if (targetInterim != currentInterimText || hasNewFinalDelta) {
                    editorBridge.setComposingText(targetInterim, 1)
                    composedInterim = targetInterim
                }
                currentInterimText = targetInterim
            } else {
                if (!hasNewFinalDelta && currentInterimText.isNotEmpty()) {
                    editorBridge.finishComposingText()
                    val interimReplaceLen = resolveDraftReplaceLength(currentInterimText)
                    if (interimReplaceLen > 0) {
                        editorBridge.deleteSurroundingText(interimReplaceLen, 0)
                    } else {
                        editorBridge.setComposingText("", 1)
                    }
                }
                currentInterimText = ""
            }

            healSilentComposingDrop(
                prevFinal = prevFinal,
                prevInterim = prevInterim,
                committedDelta = committedDelta,
                composedInterim = composedInterim,
                clearedInterim = targetInterim.isEmpty() && !hasNewFinalDelta
            )
            lastFlushAtMs = nowMs
        } finally {
            editorBridge.endBatchEdit()
            isInBatchEdit = false
        }
    }

    /**
     * Self-heal for hosts that drop the composing span without (or before) an `onUpdateSelection`
     * callback: if our write landed *after* the stale interim instead of replacing it, the buffer ends
     * with `prevInterim + newlyWritten`. Only acts when that tail is unambiguous, i.e. a correctly
     * replaced buffer (`prevFinal + newlyWritten`) could not end the same way.
     * Must be called inside a batch edit.
     */
    private fun healSilentComposingDrop(
        prevFinal: String,
        prevInterim: String,
        committedDelta: String,
        composedInterim: String,
        clearedInterim: Boolean
    ) {
        if (prevInterim.isEmpty()) return
        val newlyWritten = committedDelta + composedInterim
        if (newlyWritten.isNotEmpty()) {
            val duplicatedTail = prevInterim + newlyWritten
            if (isAmbiguousTail(anchorPrefix + prevFinal + newlyWritten, duplicatedTail)) return
            val tail = editorBridge.getTextBeforeCursor(duplicatedTail.length, 0)?.toString()
            if (tail != duplicatedTail) return
            editorBridge.finishComposingText()
            editorBridge.deleteSurroundingText(duplicatedTail.length, 0)
            if (committedDelta.isNotEmpty()) {
                editorBridge.commitText(committedDelta, 1)
            }
            if (composedInterim.isNotEmpty()) {
                editorBridge.setComposingText(composedInterim, 1)
            }
        } else if (clearedInterim) {
            if (isAmbiguousTail(anchorPrefix + prevFinal, prevInterim)) return
            val tail = editorBridge.getTextBeforeCursor(prevInterim.length, 0)?.toString()
            if (tail != prevInterim) return
            editorBridge.finishComposingText()
            editorBridge.deleteSurroundingText(prevInterim.length, 0)
        }
    }


    /**
     * Monitors selection / cursor callbacks from `InputMethodService.onUpdateSelection`.
     * If the cursor is moved away from [expectedCursorPosition] by the user or host app while `Live`,
     * immediately solidifies the composing region via `finishComposingText()` and transitions to `Frozen`.
     */
    fun onUpdateSelection(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int = CANDIDATES_UNKNOWN,
        candidatesEnd: Int = CANDIDATES_UNKNOWN
    ) {
        if (isInBatchEdit) return
        if (newSelStart < 0 || newSelEnd < 0) return

        when (state) {
            State.Live -> {
                val expected = expectedCursorPosition
                if (newSelStart == newSelEnd && newSelStart == expected) {
                    // Cursor is where we expect, but the host reports no composing span although
                    // we still hold interim text -> the host ended composition on its own.
                    if (candidatesStart == -1 && candidatesEnd == -1 && currentInterimText.isNotEmpty()) {
                        hostDroppedComposing = true
                    }
                    return
                }
                if (newSelStart != newSelEnd || newSelStart != expected) {
                    isInBatchEdit = true
                    try {
                        editorBridge.finishComposingText()
                    } finally {
                        isInBatchEdit = false
                    }
                    writtenBeforeFreeze = committedFinalText + currentInterimText
                    committedFinalText = writtenBeforeFreeze
                    currentInterimText = ""
                    wasFrozenDuringSession = true
                    pendingThrottledTranscript = null
                    state = State.Frozen
                }
            }
            State.Polishing -> {
                val expected = anchor + committedFinalText.length
                if (newSelStart != newSelEnd || newSelStart != expected) {
                    cursorMovedDuringPolish = true
                }
            }
            else -> Unit
        }
    }

    /**
     * Called when the user releases the microphone (or finishes hands-free recording).
     *
     * - Transitions `Live` / `Frozen` -> `Finishing`.
     * - If in `Live`, flushes any remaining un-written raw transcript and calls `finishComposingText()`.
     * - If [needsPolishing] is `false` (or [isSmartDirectMode] is `true`), transitions directly
     *   `Finishing -> Done` without replacing existing text (or commits the remaining suffix at cursor if `Frozen`).
     * - If [needsPolishing] is `true`, transitions `Finishing -> Polishing` and awaits [applyPolishedResult].
     */
    fun onRecordingFinished(
        finalRawTranscript: String = latestObservedTranscript.fullText,
        needsPolishing: Boolean = !isSmartDirectMode
    ): ComposerFinalizeOutcome? {
        if (state != State.Live && state != State.Frozen) {
            return null
        }

        val wasFrozen = (state == State.Frozen) || wasFrozenDuringSession
        state = State.Finishing

        if (!wasFrozen) {
            // Flush any pending throttled transcript or final tail delta and solidify composing region
            val targetRaw = finalRawTranscript.ifEmpty {
                (pendingThrottledTranscript ?: latestObservedTranscript).fullText
            }
            pendingThrottledTranscript = null

            isInBatchEdit = true
            editorBridge.beginBatchEdit()
            try {
                if (purgeStaleInterimIfHostDroppedComposing() && targetRaw.isEmpty()) {
                    // Nothing newer to write: re-insert the purged interim once as plain text.
                    val tail = (pendingThrottledTranscript ?: latestObservedTranscript).interim
                    if (tail.isNotEmpty()) {
                        editorBridge.commitText(tail, 1)
                        committedFinalText += tail
                    }
                }
                val currentFull = committedFinalText + currentInterimText
                // Flatten whatever is in the buffer first so later writes never rely on a composing
                // span that the host (e.g. mobile QQ) may already have dropped.
                editorBridge.finishComposingText()
                if (targetRaw.isNotEmpty() && targetRaw != currentFull) {
                    // Align the buffer to targetRaw by common prefix: delete only the diverging tail
                    // of what we wrote, then commit the new tail once (handles interim revisions and
                    // whitespace differences between streaming slices and the final transcript).
                    val bufferTail = editorBridge.getTextBeforeCursor(currentFull.length, 0)?.toString()
                    if (bufferTail == currentFull) {
                        val commonLen = currentFull.commonPrefixWith(targetRaw).length
                        val deleteLen = currentFull.length - commonLen
                        if (deleteLen > 0) {
                            editorBridge.deleteSurroundingText(deleteLen, 0)
                        }
                        val appendTail = targetRaw.substring(commonLen)
                        if (appendTail.isNotEmpty()) {
                            editorBridge.commitText(appendTail, 1)
                        }
                        committedFinalText = targetRaw
                    } else {
                        // Buffer no longer matches what we wrote: keep the editor untouched and
                        // track what is physically there so polish verification stays truthful.
                        committedFinalText = currentFull
                    }
                    currentInterimText = ""
                } else if (currentInterimText.isNotEmpty()) {
                    committedFinalText += currentInterimText
                    currentInterimText = ""
                }
                editorBridge.finishComposingText()
            } finally {
                editorBridge.endBatchEdit()
                isInBatchEdit = false
            }

            if (!needsPolishing) {
                state = State.Done
                val undo = ImeLogic.createUndoSnapshot(
                    committedText = committedFinalText,
                    replacedSelection = ""
                )
                return ComposerFinalizeOutcome(
                    appliedToEditor = true,
                    wasFrozen = false,
                    rawWrittenText = committedFinalText,
                    finalCommittedText = committedFinalText,
                    undoSnapshot = undo
                )
            } else {
                state = State.Polishing
                return null
            }
        } else {
            // Session was Frozen: do not touch historical anchor position
            if (!needsPolishing) {
                val remainder = extractRemainderAfterFreeze(
                    writtenPrefix = writtenBeforeFreeze,
                    finalText = finalRawTranscript,
                    rawTranscript = finalRawTranscript
                )
                if (remainder.isNotEmpty() && !frozenRemainderCommitted) {
                    isInBatchEdit = true
                    editorBridge.beginBatchEdit()
                    try {
                        editorBridge.commitText(remainder, 1)
                    } finally {
                        editorBridge.endBatchEdit()
                        isInBatchEdit = false
                    }
                    frozenRemainderCommitted = true
                }
                state = State.Done
                val undo = ImeLogic.createUndoSnapshot(
                    committedText = remainder,
                    replacedSelection = ""
                )
                return ComposerFinalizeOutcome(
                    appliedToEditor = remainder.isNotEmpty(),
                    wasFrozen = true,
                    rawWrittenText = writtenBeforeFreeze,
                    finalCommittedText = remainder,
                    undoSnapshot = undo
                )
            } else {
                state = State.Polishing
                return null
            }
        }
    }

    /**
     * Applies the Stage-2 LLM polished text in Dual-Stage mode (`Polishing -> Done`):
     * - If the session was `Frozen`, never overwrites the historical `[anchor, end]` range;
     *   commits only the remaining unwritten suffix at the current cursor.
     * - Otherwise verifies `getTextBeforeCursor(writtenText.length, 0) == writtenText`.
     * - If verified: replaces `[anchor, anchor + writtenText.length]` in one batch via
     *   `setComposingRegion(anchor, anchor + writtenText.length)` + `commitText(polishedText, 1)`
     *   (or `deleteSurroundingText(writtenText.length, 0)` + `commitText(polishedText, 1)` if `setComposingRegion` returns `false`),
     *   and returns `UndoSnapshot(replacedOriginalText = writtenText, insertedTextLength = polishedText.length)`.
     * - If verification fails: preserves the user's edited buffer without overwriting!
     */
    fun applyPolishedResult(
        polishedText: String,
        fullRawTranscript: String = latestObservedTranscript.fullText
    ): ComposerFinalizeOutcome {
        if (state == State.Live || state == State.Frozen) {
            onRecordingFinished(finalRawTranscript = fullRawTranscript, needsPolishing = true)
        }

        if (wasFrozenDuringSession) {
            val remainder = extractRemainderAfterFreeze(
                writtenPrefix = writtenBeforeFreeze,
                finalText = polishedText,
                rawTranscript = fullRawTranscript
            )
            if (remainder.isNotEmpty() && !frozenRemainderCommitted) {
                isInBatchEdit = true
                editorBridge.beginBatchEdit()
                try {
                    editorBridge.commitText(remainder, 1)
                } finally {
                    editorBridge.endBatchEdit()
                    isInBatchEdit = false
                }
                frozenRemainderCommitted = true
            }
            state = State.Done
            val undo = ImeLogic.createUndoSnapshot(
                committedText = remainder,
                replacedSelection = ""
            )
            return ComposerFinalizeOutcome(
                appliedToEditor = remainder.isNotEmpty(),
                wasFrozen = true,
                rawWrittenText = writtenBeforeFreeze,
                finalCommittedText = remainder,
                undoSnapshot = undo
            )
        }

        val writtenText = committedFinalText
        if (writtenText.isEmpty()) {
            if (polishedText.isNotEmpty()) {
                isInBatchEdit = true
                editorBridge.beginBatchEdit()
                try {
                    editorBridge.commitText(polishedText, 1)
                } finally {
                    editorBridge.endBatchEdit()
                    isInBatchEdit = false
                }
            }
            state = State.Done
            val undo = ImeLogic.createUndoSnapshot(
                committedText = polishedText,
                replacedSelection = ""
            )
            return ComposerFinalizeOutcome(
                appliedToEditor = polishedText.isNotEmpty(),
                wasFrozen = false,
                rawWrittenText = "",
                finalCommittedText = polishedText,
                undoSnapshot = undo
            )
        }

        // Verify that the text immediately before the cursor still matches `writtenText`
        // (whitespace-tolerant: streaming drafts may carry spaces the host or ASR later normalized).
        val replaceLength = if (cursorMovedDuringPolish) -1 else resolveDraftReplaceLength(writtenText)
        val verified = replaceLength > 0
        val editorDraftSnapshot = if (verified) {
            editorBridge.getTextBeforeCursor(replaceLength, 0)?.toString()
        } else {
            null
        }

        if (!verified) {
            // Verification failed: user edited or moved cursor -> NEVER overwrite!
            state = State.Done
            return ComposerFinalizeOutcome(
                appliedToEditor = false,
                wasFrozen = false,
                rawWrittenText = writtenText,
                finalCommittedText = writtenText,
                undoSnapshot = null
            )
        }

        isInBatchEdit = true
        editorBridge.beginBatchEdit()
        try {
            // Delete-then-commit relative to the verified cursor is honored by far more host editors
            // (e.g. mobile QQ) than setComposingRegion, which some hosts acknowledge but silently ignore,
            // causing the polished text to be appended after the raw draft ("重复两遍").
            editorBridge.finishComposingText()
            editorBridge.deleteSurroundingText(replaceLength, 0)
            editorBridge.commitText(polishedText, 1)
        } finally {
            editorBridge.endBatchEdit()
            isInBatchEdit = false
        }
        repairDuplicatedDraftIfHostIgnoredDeletion(
            rawDraft = editorDraftSnapshot ?: writtenText,
            polished = polishedText
        )

        state = State.Done
        val undo = UndoSnapshot(
            replacedOriginalText = writtenText,
            insertedTextLength = polishedText.length
        )
        return ComposerFinalizeOutcome(
            appliedToEditor = true,
            wasFrozen = false,
            rawWrittenText = writtenText,
            finalCommittedText = polishedText,
            undoSnapshot = undo
        )
    }

    /**
     * True when a correctly written buffer ([honoredBuffer]) could itself end with [suspectTail],
     * or when we cannot see far enough before the anchor to rule that out.
     */
    private fun isAmbiguousTail(honoredBuffer: String, suspectTail: String): Boolean {
        if (honoredBuffer.endsWith(suspectTail)) return true
        val prefixTruncated = anchorPrefix.length >= ANCHOR_PREFIX_WINDOW
        return prefixTruncated && honoredBuffer.length < suspectTail.length
    }

    /**
     * Returns how many characters before the cursor form our raw draft, or -1 if the user changed it.
     * Exact match first; otherwise accepts a suffix whose non-whitespace content equals the draft's.
     */
    private fun resolveDraftReplaceLength(writtenText: String): Int {
        val exact = editorBridge.getTextBeforeCursor(writtenText.length, 0)?.toString()
        if (exact == writtenText) return writtenText.length
        val target = writtenText.filterNot { it.isWhitespace() }
        if (target.isEmpty()) return -1
        val window = editorBridge.getTextBeforeCursor(writtenText.length + DRAFT_MATCH_SLACK, 0)
            ?.toString() ?: return -1
        for (len in 1..window.length) {
            val suffix = window.substring(window.length - len)
            val stripped = suffix.filterNot { it.isWhitespace() }
            if (stripped.length > target.length) break
            if (stripped == target && !suffix.first().isWhitespace()) return len
        }
        return -1
    }

    /**
     * Post-write safety net: if the host ignored the deletion and the buffer now ends with
     * `rawDraft + polished`, remove both and commit the polished text exactly once.
     */
    private fun repairDuplicatedDraftIfHostIgnoredDeletion(rawDraft: String, polished: String) {
        if (rawDraft.isEmpty() || polished.isEmpty()) return
        val duplicated = rawDraft + polished
        val tail = editorBridge.getTextBeforeCursor(duplicated.length, 0)?.toString() ?: return
        if (tail != duplicated) return
        isInBatchEdit = true
        editorBridge.beginBatchEdit()
        try {
            editorBridge.deleteSurroundingText(duplicated.length, 0)
            editorBridge.commitText(polished, 1)
        } finally {
            editorBridge.endBatchEdit()
            isInBatchEdit = false
        }
    }

    /**
     * Rolls back or solidifies any active live-write session when the user cancels recording.
     */
    fun cancelSession(rollbackWrittenText: Boolean = true) {
        if (state == State.Idle || state == State.Done) {
            state = State.Idle
            return
        }
        isInBatchEdit = true
        editorBridge.beginBatchEdit()
        try {
            if (rollbackWrittenText && !wasFrozenDuringSession) {
                if (currentInterimText.isNotEmpty()) {
                    editorBridge.setComposingText("", 1)
                    currentInterimText = ""
                }
                if (committedFinalText.isNotEmpty()) {
                    val before = editorBridge.getTextBeforeCursor(committedFinalText.length, 0)?.toString()
                    if (before == committedFinalText) {
                        editorBridge.deleteSurroundingText(committedFinalText.length, 0)
                    }
                    committedFinalText = ""
                }
            } else {
                editorBridge.finishComposingText()
            }
        } finally {
            editorBridge.endBatchEdit()
            isInBatchEdit = false
        }
        state = State.Idle
    }

    fun reset() {
        committedFinalText = ""
        currentInterimText = ""
        writtenBeforeFreeze = ""
        wasFrozenDuringSession = false
        pendingThrottledTranscript = null
        cursorMovedDuringPolish = false
        frozenRemainderCommitted = false
        state = State.Idle
    }

    companion object {
        /** Maximum 15 UI flushes per second (~66.6ms minimum interval). */
        const val MIN_UPDATE_INTERVAL_MS: Long = 66L

        /** Characters captured before the anchor at session start for self-heal disambiguation. */
        private const val ANCHOR_PREFIX_WINDOW: Int = 64

        /** Extra characters read when matching a whitespace-normalized draft before the cursor. */
        private const val DRAFT_MATCH_SLACK: Int = 32

        /** Sentinel for callers that cannot provide `onUpdateSelection` candidate bounds. */
        const val CANDIDATES_UNKNOWN: Int = -2

        /**
         * Built-in terminal / code editor packages where standard Android `setComposingText` /
         * `setComposingRegion` replacement either duplicates keystrokes or corrupts terminal buffers.
         */
        val LIVE_WRITE_BLACKLIST_PACKAGES: Set<String> = setOf(
            "com.termux",
            "com.termux.nvim",
            "com.termux.x11",
            "org.connectbot",
            "com.sonelli.juicessh",
            "jackpal.androidterm",
            "com.offsec.nethunter.kex"
        )

        /**
         * Evaluates all 6 enabling conditions for real-time live writing into the host editor (Scheme 3.4):
         * 1. [AppSettings.liveWriteToEditor] is `true`.
         * 2. [intentMode] is [VoiceIntentMode.DICTATE] (Speak-to-Edit, Help-me-Write, and Translate do not live-write raw speech).
         * 3. [effectiveSelectedText] is blank (no active text selection).
         * 4. Uses a streaming ASR session in [PipelineMode.DUAL_STAGE] (`hasStreamingSession == true` and protocol is not batch HTTP).
         * 5. Host app [targetPackage] is not in [LIVE_WRITE_BLACKLIST_PACKAGES].
         * 6. Not invoked from the floating bubble accessibility channel ([isFloatingBubbleChannel] == `false`).
         */
        fun shouldEnableLiveWrite(
            settings: AppSettings,
            intentMode: VoiceIntentMode,
            effectiveSelectedText: String,
            hasStreamingSession: Boolean,
            targetPackage: String,
            isFloatingBubbleChannel: Boolean = false
        ): Boolean {
            if (!settings.liveWriteToEditor) return false
            if (isFloatingBubbleChannel) return false
            if (intentMode != VoiceIntentMode.DICTATE) return false
            if (effectiveSelectedText.isNotBlank()) return false
            if (settings.pipelineMode != PipelineMode.DUAL_STAGE) return false
            if (!hasStreamingSession) return false
            if (settings.sttConfig.protocol == SttProtocol.OPENAI_HTTP) return false
            val cleanPkg = targetPackage.trim()
            if (cleanPkg.isNotEmpty() && cleanPkg in LIVE_WRITE_BLACKLIST_PACKAGES) return false
            return true
        }

        /**
         * Returns `true` when Gemini Live SMART streaming dictation is active without a separate Stage-2 LLM,
         * meaning the streamed transcript is already polished and requires zero post-release replacement.
         */
        fun isSmartDirectDictation(
            settings: AppSettings,
            intentMode: VoiceIntentMode
        ): Boolean {
            return settings.pipelineMode == PipelineMode.DUAL_STAGE &&
                settings.sttConfig.protocol == SttProtocol.GEMINI_LIVE_WS &&
                !settings.llmConfig.enabled &&
                intentMode == VoiceIntentMode.DICTATE
        }

        /**
         * Computes the unwritten remainder suffix when a session was interrupted into `Frozen`
         * after having already written [writtenPrefix] at the original anchor.
         */
        fun extractRemainderAfterFreeze(
            writtenPrefix: String,
            finalText: String,
            rawTranscript: String = finalText
        ): String {
            if (writtenPrefix.isEmpty()) return finalText
            if (finalText.isEmpty() || finalText == writtenPrefix) return ""
            if (finalText.startsWith(writtenPrefix)) {
                var cutIdx = writtenPrefix.length
                while (cutIdx < finalText.length && finalText[cutIdx] in "，。？！；、,.?! ") {
                    cutIdx += 1
                }
                return finalText.substring(cutIdx)
            }
            // Match ignoring punctuation differences between raw writtenPrefix and polished finalText
            val normPrefix = writtenPrefix.filter { it.isLetterOrDigit() }
            if (normPrefix.isNotEmpty()) {
                var matchedNormCount = 0
                var cutIdx = 0
                while (cutIdx < finalText.length && matchedNormCount < normPrefix.length) {
                    val ch = finalText[cutIdx]
                    if (ch.isLetterOrDigit()) {
                        if (ch.lowercaseChar() == normPrefix[matchedNormCount].lowercaseChar()) {
                            matchedNormCount += 1
                        } else {
                            break
                        }
                    }
                    cutIdx += 1
                }
                if (matchedNormCount == normPrefix.length) {
                    // Also skip one trailing punctuation if it immediately belonged to the frozen clause
                    while (cutIdx < finalText.length && finalText[cutIdx] in "，。？！；、,.?! ") {
                        cutIdx += 1
                    }
                    return finalText.substring(cutIdx)
                }
            }
            if (rawTranscript.startsWith(writtenPrefix) && rawTranscript.length > writtenPrefix.length) {
                return rawTranscript.substring(writtenPrefix.length)
            }
            return finalText
        }
    }
}
