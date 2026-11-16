package com.kingzcheung.xime.ai.network.asr

import com.kingzcheung.xime.ai.audio.WavAudioRecorder
import com.kingzcheung.xime.ai.data.SttProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Structured real-time speech recognition state separating immutable finalized sentences
 * from the still-mutable interim tail.
 */
data class LiveTranscript(
    val finalized: String = "",   // 已确定、不会再变的句子
    val interim: String = "",     // 仍可能被改写的尾巴
    val revision: Long = 0L
) {
    val fullText: String
        get() = finalized + interim
}

/**
 * Per-provider single WebSocket session duration limits and rotation thresholds (Scheme 3.5).
 *
 * Technical verification summary:
 * - Aliyun Bailian DashScope (`paraformer-realtime-v2` / `fun-asr-realtime`):
 *   Continuous interactive WebSocket stream limit is 10 minutes (600,000 ms).
 * - Volcengine Doubao SeedASR (`sauc/bigmodel_async` v3):
 *   Continuous streaming WebSocket session duration limit is 10 minutes (600,000 ms).
 * - Google Gemini Live (`BidiGenerateContent` audio-only WebSocket):
 *   Official upstream single WebSocket session limit is 15 minutes (900,000 ms).
 */
data class SessionDurationLimit(
    val maxSessionDurationMs: Long,
    val softRotateThresholdMs: Long = (maxSessionDurationMs * 85L) / 100L,
    val hardRotateThresholdMs: Long = (maxSessionDurationMs * 95L) / 100L
)

object AsrSessionLimits {
    const val ALIYUN_DASHSCOPE_MAX_MS: Long = 10L * 60_000L   // 10 minutes
    const val VOLCENGINE_SAUC_MAX_MS: Long = 10L * 60_000L    // 10 minutes
    const val GEMINI_LIVE_WS_MAX_MS: Long = 15L * 60_000L     // 15 minutes

    val PROVIDER_LIMITS: Map<SttProtocol, SessionDurationLimit> = mapOf(
        SttProtocol.ALIYUN_DASHSCOPE_WS to SessionDurationLimit(maxSessionDurationMs = ALIYUN_DASHSCOPE_MAX_MS),
        SttProtocol.VOLCENGINE_SAUC_WS to SessionDurationLimit(maxSessionDurationMs = VOLCENGINE_SAUC_MAX_MS),
        SttProtocol.GEMINI_LIVE_WS to SessionDurationLimit(maxSessionDurationMs = GEMINI_LIVE_WS_MAX_MS)
    )

    fun forProtocol(protocol: SttProtocol): SessionDurationLimit {
        return PROVIDER_LIMITS[protocol] ?: SessionDurationLimit(maxSessionDurationMs = ALIYUN_DASHSCOPE_MAX_MS)
    }
}

/**
 * Thread-safe circular PCM audio buffer retaining the most recent [capacityBytes]
 * (default 2 seconds at 16kHz 16-bit mono = `16000 * 2 * 2 = 64,000` bytes).
 *
 * Used during long-dictation session rotation to replay the last 2 seconds of audio
 * into the newly opened WebSocket session when rotation occurs before a clean utterance boundary.
 */
class PcmRingBuffer(
    val capacityBytes: Int = DEFAULT_CAPACITY_BYTES
) {
    init {
        require(capacityBytes > 0) { "capacityBytes must be positive" }
    }

    private val lock = Any()
    private val buffer = ByteArray(capacityBytes)
    private var writePos: Int = 0
    private var storedBytes: Int = 0

    val size: Int
        get() = synchronized(lock) { storedBytes }

    fun write(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        synchronized(lock) {
            if (pcmBytes.size >= capacityBytes) {
                val startOffset = pcmBytes.size - capacityBytes
                System.arraycopy(pcmBytes, startOffset, buffer, 0, capacityBytes)
                writePos = 0
                storedBytes = capacityBytes
                return
            }

            val firstCopy = minOf(pcmBytes.size, capacityBytes - writePos)
            System.arraycopy(pcmBytes, 0, buffer, writePos, firstCopy)
            val secondCopy = pcmBytes.size - firstCopy
            if (secondCopy > 0) {
                System.arraycopy(pcmBytes, firstCopy, buffer, 0, secondCopy)
            }
            writePos = (writePos + pcmBytes.size) % capacityBytes
            storedBytes = minOf(capacityBytes, storedBytes + pcmBytes.size)
        }
    }

    fun snapshot(): ByteArray {
        synchronized(lock) {
            if (storedBytes == 0) return ByteArray(0)
            val out = ByteArray(storedBytes)
            val startPos = (writePos - storedBytes + capacityBytes) % capacityBytes
            val firstCopy = minOf(storedBytes, capacityBytes - startPos)
            System.arraycopy(buffer, startPos, out, 0, firstCopy)
            val secondCopy = storedBytes - firstCopy
            if (secondCopy > 0) {
                System.arraycopy(buffer, 0, out, firstCopy, secondCopy)
            }
            return out
        }
    }

    fun snapshotChunks(chunkSizeBytes: Int = WavAudioRecorder.STREAM_CHUNK_BYTES): List<ByteArray> {
        val all = snapshot()
        if (all.isEmpty()) return emptyList()
        val safeChunk = chunkSizeBytes.coerceAtLeast(1)
        val chunks = ArrayList<ByteArray>((all.size + safeChunk - 1) / safeChunk)
        var offset = 0
        while (offset < all.size) {
            val end = minOf(offset + safeChunk, all.size)
            chunks.add(all.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    fun clear() {
        synchronized(lock) {
            writePos = 0
            storedBytes = 0
        }
    }

    companion object {
        /** 16kHz * 2 bytes/sample * 2 seconds = 64,000 bytes. */
        const val DEFAULT_CAPACITY_BYTES: Int = WavAudioRecorder.SAMPLE_RATE * 2 * 2
    }
}

/**
 * Long-dictation session wrapper that transparently rotates the underlying [StreamingAsrSession]
 * when approaching the provider's single-session duration limit:
 * - At >= 85% duration ([SessionDurationLimit.softRotateThresholdMs]): rotates on the next
 *   clean finalized sentence boundary (`finalized` advanced and `interim` is empty).
 * - At >= 95% duration ([SessionDurationLimit.hardRotateThresholdMs]): forces rotation and
 *   replays the last 2 seconds from [PcmRingBuffer] into the new session so mid-sentence words
 *   are not dropped across the WebSocket boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RotatingStreamingAsrSession(
    private val sessionFactory: () -> StreamingAsrSession,
    private val limit: SessionDurationLimit,
    ringBufferCapacityBytes: Int = PcmRingBuffer.DEFAULT_CAPACITY_BYTES,
    private val clockMs: () -> Long = System::currentTimeMillis
) : StreamingAsrSession {

    private val lock = Any()
    internal val ringBuffer = PcmRingBuffer(ringBufferCapacityBytes)

    private var currentDelegate: StreamingAsrSession = sessionFactory()
    private val activeDelegateFlow = MutableStateFlow(currentDelegate)
    private var segmentOpenedAtMs: Long = clockMs()

    private var accumulatedFinalizedPrefix: String = ""
    private var lastObservedDelegateFinalized: String = ""
    private var combinedRevision: Long = 0L
    private var rotationCountInternal: Int = 0
    private var cancelled: Boolean = false

    private val _combinedTranscript = MutableStateFlow(LiveTranscript())
    private val _combinedPartialText = MutableStateFlow("")

    val rotationCount: Int
        get() = synchronized(lock) { rotationCountInternal }

    override val transcript: StateFlow<LiveTranscript> = object : StateFlow<LiveTranscript> {
        override val replayCache: List<LiveTranscript>
            get() = listOf(value)

        override val value: LiveTranscript
            get() {
                syncFromDelegate()
                return _combinedTranscript.value
            }

        override suspend fun collect(collector: FlowCollector<LiveTranscript>): Nothing {
            activeDelegateFlow
                .flatMapLatest { delegate -> delegate.transcript }
                .collect {
                    val updated = syncFromDelegate()
                    collector.emit(updated)
                }
            error("StateFlow collection never completes normally")
        }
    }

    override val partialText: StateFlow<String> = object : StateFlow<String> {
        override val replayCache: List<String>
            get() = listOf(value)

        override val value: String
            get() {
                syncFromDelegate()
                return _combinedPartialText.value
            }

        override suspend fun collect(collector: FlowCollector<String>): Nothing {
            activeDelegateFlow
                .flatMapLatest { delegate -> delegate.partialText }
                .collect {
                    val updated = syncFromDelegate()
                    collector.emit(updated.fullText)
                }
            error("StateFlow collection never completes normally")
        }
    }

    fun syncFromDelegate(): LiveTranscript {
        var oldToCancel: StreamingAsrSession? = null
        val snapshot: LiveTranscript
        synchronized(lock) {
            if (cancelled) return _combinedTranscript.value
            val delegateSnap = currentDelegate.transcript.value
            val delegatePartial = currentDelegate.partialText.value
            val effectiveFinal = delegateSnap.finalized
            val effectiveInterim = if (effectiveFinal.isEmpty() && delegateSnap.interim.isEmpty() && delegatePartial.isNotEmpty()) {
                delegatePartial
            } else {
                delegateSnap.interim
            }

            val finalizedAdvanced = effectiveFinal.length > lastObservedDelegateFinalized.length
            if (finalizedAdvanced && effectiveInterim.isEmpty()) {
                // Clean utterance boundary reached; old audio prior to this finalized boundary no longer needs replay
                ringBuffer.clear()
            }
            lastObservedDelegateFinalized = effectiveFinal

            val elapsed = (clockMs() - segmentOpenedAtMs).coerceAtLeast(0L)
            if (elapsed >= limit.softRotateThresholdMs &&
                effectiveFinal.isNotEmpty() &&
                effectiveInterim.isEmpty()
            ) {
                oldToCancel = rotateLocked(
                    carryOverFinalized = effectiveFinal,
                    replayAudio = false
                )
            }

            val currentSnap = currentDelegate.transcript.value
            val currentPartial = currentDelegate.partialText.value
            val curFinal = currentSnap.finalized
            val curInterim = if (curFinal.isEmpty() && currentSnap.interim.isEmpty() && currentPartial.isNotEmpty()) {
                currentPartial
            } else {
                currentSnap.interim
            }

            val mergedFinalized = accumulatedFinalizedPrefix + curFinal
            val prev = _combinedTranscript.value
            if (mergedFinalized != prev.finalized || curInterim != prev.interim) {
                combinedRevision += 1L
                val next = LiveTranscript(
                    finalized = mergedFinalized,
                    interim = curInterim,
                    revision = combinedRevision
                )
                _combinedTranscript.value = next
                _combinedPartialText.value = next.fullText
            }
            snapshot = _combinedTranscript.value
        }
        oldToCancel?.cancel()
        return snapshot
    }

    override fun sendPcmFrame(pcm16Le: ByteArray) {
        if (pcm16Le.isEmpty()) return
        syncFromDelegate()

        var oldToCancel: StreamingAsrSession? = null
        var replayChunks: List<ByteArray> = emptyList()
        val delegateToUse: StreamingAsrSession

        synchronized(lock) {
            if (cancelled) return
            val elapsed = (clockMs() - segmentOpenedAtMs).coerceAtLeast(0L)
            val delegateSnap = currentDelegate.transcript.value

            if (elapsed >= limit.softRotateThresholdMs &&
                delegateSnap.finalized.isNotEmpty() &&
                delegateSnap.interim.isEmpty()
            ) {
                oldToCancel = rotateLocked(
                    carryOverFinalized = delegateSnap.finalized,
                    replayAudio = false
                )
            } else if (elapsed >= limit.hardRotateThresholdMs) {
                replayChunks = ringBuffer.snapshotChunks()
                oldToCancel = rotateLocked(
                    carryOverFinalized = delegateSnap.finalized,
                    replayAudio = true
                )
            }

            ringBuffer.write(pcm16Le)
            delegateToUse = currentDelegate
        }

        oldToCancel?.cancel()
        for (chunk in replayChunks) {
            delegateToUse.sendPcmFrame(chunk)
        }
        delegateToUse.sendPcmFrame(pcm16Le)
        syncFromDelegate()
    }

    private fun rotateLocked(
        carryOverFinalized: String,
        replayAudio: Boolean
    ): StreamingAsrSession {
        val old = currentDelegate
        accumulatedFinalizedPrefix += carryOverFinalized
        lastObservedDelegateFinalized = ""
        if (!replayAudio) {
            ringBuffer.clear()
        }
        val fresh = sessionFactory()
        currentDelegate = fresh
        activeDelegateFlow.value = fresh
        segmentOpenedAtMs = clockMs()
        rotationCountInternal += 1
        return old
    }

    override fun resetPrefix() {
        val delegate: StreamingAsrSession
        synchronized(lock) {
            ringBuffer.clear()
            accumulatedFinalizedPrefix = ""
            lastObservedDelegateFinalized = ""
            combinedRevision = 0L
            _combinedTranscript.value = LiveTranscript()
            _combinedPartialText.value = ""
            delegate = currentDelegate
        }
        delegate.resetPrefix()
    }

    override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> {
        val (delegate, prefixSnapshot) = synchronized(lock) {
            currentDelegate to accumulatedFinalizedPrefix
        }
        val res = delegate.finishAndAwaitFinal(timeoutMs)
        return res.fold(
            onSuccess = { tailText ->
                val combined = (prefixSnapshot + tailText).trim()
                synchronized(lock) {
                    if (combined != _combinedTranscript.value.fullText || _combinedTranscript.value.interim.isNotEmpty()) {
                        combinedRevision += 1L
                        _combinedTranscript.value = LiveTranscript(
                            finalized = combined,
                            interim = "",
                            revision = combinedRevision
                        )
                        _combinedPartialText.value = combined
                    }
                }
                Result.success(combined)
            },
            onFailure = { err ->
                if (prefixSnapshot.isNotBlank()) {
                    Result.success(prefixSnapshot.trim())
                } else {
                    Result.failure(err)
                }
            }
        )
    }

    override fun cancel() {
        val delegate = synchronized(lock) {
            cancelled = true
            ringBuffer.clear()
            currentDelegate
        }
        delegate.cancel()
    }
}
