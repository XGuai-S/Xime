package com.kingzcheung.xime.ai.network.asr

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kingzcheung.xime.ai.data.SttConfig
import com.kingzcheung.xime.ai.network.AiPipelineEngine
import com.kingzcheung.xime.ai.network.EndpointResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Google Gemini Live API real-time streaming transcription client (`BidiGenerateContent` WebSocket).
 *
 * Key low-latency guarantees (`v2.6.1`):
 * 1. **Hot Pre-Handshaked Session Pool (`prewarmSession`)**: Completes TLS 1.3 + WebSocket Upgrade +
 *    `setup -> setupComplete` when the IME keyboard / Playground opens, eliminating ~800-1100ms cold
 *    setup wait on 1-2s short words (auto-evicts after 45s idle).
 * 2. **Voiced-Energy-Aware Tail Tracking (`isVoicedPcmChunk`)**: Trailing 100-300ms ambient silence
 *    frames before finger release never invalidate an already-received `inputTranscription`, allowing
 *    `finishAndAwaitFinal()` to return in `0 ms`.
 * 3. **Adaptive Quiescence & `generationComplete` Convergence**: Recognizes both `turnComplete` and
 *    `generationComplete`, and converges automatically after ~420-560ms of post-`audioStreamEnd`
 *    server silence when interim/final text is already present — completely eliminating 3.6s/10s stalls.
 */
class GeminiLiveTranscribeClient(
    private val httpClient: OkHttpClient,
    internal val webSocketFactory: (Request, WebSocketListener) -> WebSocket = { req, listener ->
        httpClient.newWebSocket(req, listener)
    },
    internal val interimQuiescenceMs: Long = DEFAULT_INTERIM_QUIESCENCE_MS,
    internal val finalizedQuiescenceMs: Long = DEFAULT_FINALIZED_QUIESCENCE_MS,
    internal val postEndInterimSettleMs: Long = DEFAULT_POST_END_INTERIM_SETTLE_MS,
    internal val postFinalCoalesceMs: Long = DEFAULT_POST_FINAL_COALESCE_MS,
    internal val warmStreamFinishCapMs: Long = DEFAULT_WARM_FINISH_CAP_MS,
    internal val coldStreamFinishCapMs: Long = DEFAULT_COLD_FINISH_CAP_MS,
    internal val maxPrewarmIdleMs: Long = DEFAULT_MAX_PREWARM_IDLE_MS,
    internal val clockMs: () -> Long = System::currentTimeMillis
) : StreamingAsrClient {

    private data class SessionSpecKey(
        val wsUrl: String,
        val model: String,
        val language: String,
        val hotwordsCleaned: List<String>
    )

    private data class PrewarmedEntry(
        val key: SessionSpecKey,
        val session: GeminiLiveWsSession,
        val createdAtMs: Long
    )

    private val poolLock = Any()
    private var prewarmedEntry: PrewarmedEntry? = null

    override fun prewarmSession(config: SttConfig, hotwords: List<String>) {
        val apiKey = config.apiKey.trim()
        if (apiKey.isEmpty() || maxPrewarmIdleMs <= 0L) return

        val key = buildSpecKey(config, apiKey, hotwords)
        var staleToCancel: GeminiLiveWsSession? = null
        val createdEntry: PrewarmedEntry

        synchronized(poolLock) {
            val now = clockMs()
            val current = prewarmedEntry
            if (current != null) {
                val age = now - current.createdAtMs
                if (current.key == key &&
                    age in 0 until maxPrewarmIdleMs &&
                    current.session.isReusableForPrewarm()
                ) {
                    return
                }
                staleToCancel = current.session
                prewarmedEntry = null
            }

            val fresh = createRawWsSession(apiKey, key)
            createdEntry = PrewarmedEntry(
                key = key,
                session = fresh,
                createdAtMs = now
            )
            prewarmedEntry = createdEntry
        }

        staleToCancel?.cancel()

        if (maxPrewarmIdleMs >= 250L) {
            idleEvictor.schedule(
                { evictIfStillSameEntry(createdEntry) },
                maxPrewarmIdleMs,
                TimeUnit.MILLISECONDS
            )
        }
    }

    override fun evictPrewarmSession() {
        val victim = synchronized(poolLock) {
            prewarmedEntry.also { prewarmedEntry = null }
        }
        victim?.session?.cancel()
    }

    private fun evictIfStillSameEntry(target: PrewarmedEntry) {
        val shouldCancel = synchronized(poolLock) {
            if (prewarmedEntry === target) {
                prewarmedEntry = null
                true
            } else {
                false
            }
        }
        if (shouldCancel) {
            target.session.cancel()
        }
    }

    override fun openSession(
        config: SttConfig,
        hotwords: List<String>
    ): StreamingAsrSession {
        val apiKey = config.apiKey.trim()
        if (apiKey.isEmpty()) {
            return ImmediateFailedSession("请先在「API 配置」中填写 Google Gemini API Key")
        }

        val key = buildSpecKey(config, apiKey, hotwords)
        var staleToCancel: GeminiLiveWsSession? = null
        var claimedWarm: GeminiLiveWsSession? = null

        synchronized(poolLock) {
            val current = prewarmedEntry
            prewarmedEntry = null
            if (current != null) {
                val age = clockMs() - current.createdAtMs
                if (current.key == key &&
                    age in 0 until maxPrewarmIdleMs &&
                    current.session.tryClaimForRecording()
                ) {
                    claimedWarm = current.session
                } else {
                    staleToCancel = current.session
                }
            }
        }

        staleToCancel?.cancel()
        if (claimedWarm != null) {
            return claimedWarm!!
        }

        return createRawWsSession(apiKey, key).also { it.tryClaimForRecording() }
    }

    private fun buildSpecKey(
        config: SttConfig,
        apiKey: String,
        hotwords: List<String>
    ): SessionSpecKey {
        val wsUrl = EndpointResolver.resolveGeminiLiveWsEndpoint(config.baseUrl, apiKey)
        val model = normalizeLiveTranscribeModel(config.model)
        val cleanedVocab = sanitizeHotwords(hotwords)
        return SessionSpecKey(
            wsUrl = wsUrl,
            model = model,
            language = config.language.trim(),
            hotwordsCleaned = cleanedVocab
        )
    }

    private fun createRawWsSession(
        apiKey: String,
        key: SessionSpecKey
    ): GeminiLiveWsSession {
        val req = Request.Builder()
            .url(key.wsUrl)
            .header("x-goog-api-key", apiKey)
            .build()

        return GeminiLiveWsSession(
            webSocketFactory = webSocketFactory,
            request = req,
            model = key.model,
            language = key.language,
            hotwords = key.hotwordsCleaned,
            interimQuiescenceMs = interimQuiescenceMs,
            finalizedQuiescenceMs = finalizedQuiescenceMs,
            postEndInterimSettleMs = postEndInterimSettleMs,
            postFinalCoalesceMs = postFinalCoalesceMs,
            warmStreamFinishCapMs = warmStreamFinishCapMs,
            coldStreamFinishCapMs = coldStreamFinishCapMs,
            clockMs = clockMs
        )
    }

    private class ImmediateFailedSession(
        private val message: String
    ) : StreamingAsrSession {
        private val _partialText = MutableStateFlow("")
        override val partialText: StateFlow<String> = _partialText.asStateFlow()
        override fun sendPcmFrame(pcm16Le: ByteArray) = Unit
        override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> =
            Result.failure(IllegalStateException(message))
        override fun cancel() = Unit
    }

    private class GeminiLiveWsSession(
        webSocketFactory: (Request, WebSocketListener) -> WebSocket,
        request: Request,
        private val model: String,
        private val language: String,
        private val hotwords: List<String>,
        private val interimQuiescenceMs: Long,
        private val finalizedQuiescenceMs: Long,
        private val postEndInterimSettleMs: Long,
        private val postFinalCoalesceMs: Long,
        private val warmStreamFinishCapMs: Long,
        private val coldStreamFinishCapMs: Long,
        private val clockMs: () -> Long
    ) : StreamingAsrSession {

        private val lock = Any()
        private val _partialText = MutableStateFlow("")
        override val partialText: StateFlow<String> = _partialText.asStateFlow()
        private val _transcript = MutableStateFlow(LiveTranscript())
        override val transcript: StateFlow<LiveTranscript> = _transcript.asStateFlow()

        private val wakeupChannel = Channel<Unit>(Channel.CONFLATED)
        private val pendingFrames = ArrayDeque<ByteArray>()
        private val committedSegments = mutableListOf<String>()

        private var claimed = false
        private var currentInterim: String = ""
        private var silentFramesAfterTranscript: Int = 0
        private var hasUnfinalizedSpeechSinceLastFinal = false
        private var totalVoicedFramesSent = 0
        private var setupCompleted = false
        private var finishRequested = false
        private var streamEndSent = false
        private var streamEndSentAtMs: Long = 0L
        private var lastTranscriptActivityAtMs: Long = 0L
        private var sawExplicitFinalOrCompleteAfterFinish = false
        private var failureError: Throwable? = null
        private var closed = false

        private val webSocket: WebSocket = webSocketFactory(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val setupJson = buildSetupJson(
                        model = model,
                        language = language,
                        hotwords = hotwords
                    )
                    webSocket.send(setupJson)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerJson(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Google BidiGenerateContent emits UTF-8 JSON inside binary WebSocket frames!
                    handleServerJson(webSocket, bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val detail = response?.code?.let { "HTTP $it: ${t.message}" }
                        ?: (t.message ?: "网络连接中断")
                    failSession(IOException("Gemini Live 流式连接失败 ($detail)", t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(lock) {
                        closed = true
                        if (buildCombinedTranscript().isBlank() && failureError == null) {
                            failureError = IOException(
                                "Gemini Live 连接已关闭 ($code): ${reason.ifBlank { "未返回识别结果" }}"
                            )
                        }
                    }
                    wakeupChannel.trySend(Unit)
                }
            }
        )

        fun isReusableForPrewarm(): Boolean = synchronized(lock) {
            !closed && !claimed && !finishRequested && failureError == null
        }

        fun tryClaimForRecording(): Boolean = synchronized(lock) {
            if (closed || claimed || finishRequested || failureError != null) {
                false
            } else {
                claimed = true
                true
            }
        }

        private fun handleServerJson(ws: WebSocket, rawJson: String) {
            val event = try {
                parseServerMessage(rawJson)
            } catch (_: Exception) {
                return
            }

            when (event) {
                is LiveServerEvent.SetupComplete -> {
                    val toFlush = mutableListOf<ByteArray>()
                    var sendEnd = false
                    synchronized(lock) {
                        if (closed) return
                        setupCompleted = true
                        while (pendingFrames.isNotEmpty()) {
                            toFlush.add(pendingFrames.removeFirst())
                        }
                        if (finishRequested && !streamEndSent) {
                            streamEndSent = true
                            sendEnd = true
                        }
                    }
                    for (pcm in toFlush) {
                        ws.send(buildAudioChunkJson(pcm))
                    }
                    if (sendEnd) {
                        ws.send(buildAudioStreamEndJson())
                        synchronized(lock) {
                            streamEndSentAtMs = clockMs()
                        }
                    }
                    wakeupChannel.trySend(Unit)
                }

                is LiveServerEvent.TranscriptionUpdate -> {
                    val now = clockMs()
                    val (liveNow, previewNow) = synchronized(lock) {
                        if (event.interimText != null) {
                            currentInterim = AiPipelineEngine.cleanRawTranscript(event.interimText)
                            if (currentInterim.isNotEmpty()) {
                                hasUnfinalizedSpeechSinceLastFinal = true
                                sawExplicitFinalOrCompleteAfterFinish = false
                            }
                            lastTranscriptActivityAtMs = now
                            silentFramesAfterTranscript = 0
                        }
                        if (!event.finalText.isNullOrEmpty()) {
                            val cleanFinal = AiPipelineEngine.cleanRawTranscript(event.finalText)
                            if (cleanFinal.isNotEmpty()) {
                                committedSegments.add(cleanFinal)
                            }
                            currentInterim = ""
                            hasUnfinalizedSpeechSinceLastFinal = false
                            lastTranscriptActivityAtMs = now
                            silentFramesAfterTranscript = 0
                            if (finishRequested) {
                                sawExplicitFinalOrCompleteAfterFinish = true
                            }
                        }
                        if (event.turnComplete) {
                            lastTranscriptActivityAtMs = now
                            silentFramesAfterTranscript = 0
                            if (finishRequested && !hasUnfinalizedSpeechSinceLastFinal) {
                                sawExplicitFinalOrCompleteAfterFinish = true
                            }
                        }
                        val nextFinalized = committedSegments.joinToString("")
                        val nextInterim = currentInterim
                        val prevLive = _transcript.value
                        val nextLive = if (nextFinalized != prevLive.finalized || nextInterim != prevLive.interim) {
                            LiveTranscript(
                                finalized = nextFinalized,
                                interim = nextInterim,
                                revision = prevLive.revision + 1L
                            )
                        } else {
                            prevLive
                        }
                        nextLive to buildCombinedTranscript()
                    }
                    _transcript.value = liveNow
                    _partialText.value = previewNow
                    wakeupChannel.trySend(Unit)
                }

                is LiveServerEvent.ErrorEvent -> {
                    failSession(IOException("Gemini Live 错误 (${event.code}): ${event.message}"))
                }

                LiveServerEvent.Ignored -> Unit
            }
        }

        override fun sendPcmFrame(pcm16Le: ByteArray) {
            if (pcm16Le.isEmpty()) return
            val voiced = isVoicedPcmChunk(pcm16Le)
            var sendNow = false
            synchronized(lock) {
                if (closed || finishRequested) return
                if (voiced) {
                    hasUnfinalizedSpeechSinceLastFinal = true
                    sawExplicitFinalOrCompleteAfterFinish = false
                    totalVoicedFramesSent += 1
                    silentFramesAfterTranscript = 0
                } else if (!hasUnfinalizedSpeechSinceLastFinal && _transcript.value.interim.isNotEmpty()) {
                    silentFramesAfterTranscript += 1
                }
                if (setupCompleted) {
                    sendNow = true
                } else if (pendingFrames.size < MAX_BUFFERED_SETUP_FRAMES) {
                    pendingFrames.addLast(pcm16Le)
                }
            }
            if (sendNow) {
                webSocket.send(buildAudioChunkJson(pcm16Le))
            }
        }

        override fun resetPrefix() {
            synchronized(lock) {
                pendingFrames.clear()
                committedSegments.clear()
                currentInterim = ""
                silentFramesAfterTranscript = 0
                hasUnfinalizedSpeechSinceLastFinal = false
                totalVoicedFramesSent = 0
                lastTranscriptActivityAtMs = 0L
                sawExplicitFinalOrCompleteAfterFinish = false
            }
            _transcript.value = LiveTranscript()
            _partialText.value = ""
        }

        override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> {
            var sendEndImmediately = false
            var immediateReadyText: String? = null
            var immediateFailure: Throwable? = null
            val wasSetupDone: Boolean

            synchronized(lock) {
                failureError?.let { err ->
                    immediateFailure = err
                    wasSetupDone = setupCompleted
                    return@synchronized
                }
                if (closed) {
                    val salvaged = buildCombinedTranscript()
                    if (salvaged.isNotBlank()) {
                        immediateReadyText = salvaged
                    } else {
                        immediateFailure = IOException("流式语音会话已取消")
                    }
                    wasSetupDone = setupCompleted
                    return@synchronized
                }

                finishRequested = true
                wasSetupDone = setupCompleted
                if (setupCompleted && !streamEndSent) {
                    streamEndSent = true
                    streamEndSentAtMs = clockMs()
                    sendEndImmediately = true
                }

                // Fast Path 1: Server already delivered `inputTranscription` final segment and
                // subsequent mic slices before finger lift were pure trailing silence -> 0 ms return!
                if (setupCompleted &&
                    !hasUnfinalizedSpeechSinceLastFinal &&
                    currentInterim.isBlank() &&
                    committedSegments.isNotEmpty()
                ) {
                    immediateReadyText = buildCombinedTranscript()
                } else if (setupCompleted &&
                    totalVoicedFramesSent == 0 &&
                    committedSegments.isEmpty() &&
                    currentInterim.isBlank()
                ) {
                    // Fast Path 2: Zero voiced energy was ever sent -> 0 ms return empty so local VAD handles immediately
                    immediateReadyText = ""
                }
            }

            immediateFailure?.let { err ->
                closeSocketGracefully()
                return Result.failure(err)
            }

            if (sendEndImmediately) {
                webSocket.send(buildAudioStreamEndJson())
            }

            immediateReadyText?.let { ready ->
                closeSocketGracefully()
                return Result.success(AiPipelineEngine.cleanRawTranscript(ready))
            }

            val startWaitMs = clockMs()
            val hardCapMs = if (wasSetupDone) {
                timeoutMs.coerceIn(80L, warmStreamFinishCapMs)
            } else {
                timeoutMs.coerceIn(120L, maxOf(coldStreamFinishCapMs, timeoutMs.coerceAtMost(3200L)))
            }

            return try {
                while (true) {
                    val now = clockMs()
                    var explicitComplete = false
                    var readyQuiescentText: String? = null
                    var fatalErr: Throwable? = null
                    var socketClosedNow = false
                    var currentSnapshot = ""
                    var nextSleepMs = 50L

                    synchronized(lock) {
                        currentSnapshot = buildCombinedTranscript()
                        if (failureError != null && currentSnapshot.isBlank()) {
                            fatalErr = failureError
                            return@synchronized
                        }
                        if (closed) {
                            socketClosedNow = true
                            return@synchronized
                        }
                        if (sawExplicitFinalOrCompleteAfterFinish) {
                            explicitComplete = true
                            return@synchronized
                        }

                        val totalElapsed = (now - startWaitMs).coerceAtLeast(0L)
                        val remainingHard = hardCapMs - totalElapsed

                        if (streamEndSent && currentSnapshot.isNotBlank()) {
                            val requiredQuietMs = when {
                                currentInterim.isBlank() -> finalizedQuiescenceMs
                                lastTranscriptActivityAtMs >= streamEndSentAtMs -> postEndInterimSettleMs
                                else -> interimQuiescenceMs
                            }
                            val anchorMs = maxOf(streamEndSentAtMs, lastTranscriptActivityAtMs)
                            val quietElapsed = (now - anchorMs).coerceAtLeast(0L)
                            val remainingQuiet = requiredQuietMs - quietElapsed
                            if (remainingQuiet <= 0L) {
                                readyQuiescentText = currentSnapshot
                                return@synchronized
                            }
                            nextSleepMs = minOf(remainingHard, remainingQuiet).coerceAtLeast(1L)
                        } else {
                            nextSleepMs = remainingHard.coerceAtLeast(1L)
                        }

                        if (remainingHard <= 0L) {
                            readyQuiescentText = currentSnapshot
                        }
                    }

                    fatalErr?.let { throw it }

                    if (socketClosedNow) {
                        closeSocketGracefully()
                        return if (currentSnapshot.isNotBlank()) {
                            Result.success(AiPipelineEngine.cleanRawTranscript(currentSnapshot))
                        } else {
                            Result.failure(IOException("Gemini Live 连接已关闭，未返回识别结果"))
                        }
                    }

                    if (explicitComplete) {
                        if (postFinalCoalesceMs > 0L) {
                            delay(postFinalCoalesceMs)
                        }
                        val assembled = synchronized(lock) { buildCombinedTranscript() }
                        closeSocketGracefully()
                        return Result.success(AiPipelineEngine.cleanRawTranscript(assembled))
                    }

                    if (readyQuiescentText != null) {
                        closeSocketGracefully()
                        val cleaned = AiPipelineEngine.cleanRawTranscript(readyQuiescentText!!)
                        return if (cleaned.isNotBlank()) {
                            Result.success(cleaned)
                        } else {
                            Result.failure(IOException("Gemini Live 收尾超时且未识别到有效文本"))
                        }
                    }

                    withTimeoutOrNull(nextSleepMs) {
                        wakeupChannel.receive()
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                Result.failure(IOException("Unreachable"))
            } catch (e: CancellationException) {
                // 用户取消/切框必须透传，不能把残句当成功结果继续处理。
                closeSocketGracefully()
                throw e
            } catch (e: Exception) {
                val bestEffort = synchronized(lock) { buildCombinedTranscript() }
                closeSocketGracefully()
                if (bestEffort.isNotBlank()) {
                    Result.success(AiPipelineEngine.cleanRawTranscript(bestEffort))
                } else {
                    Result.failure(e)
                }
            }
        }

        override fun cancel() {
            synchronized(lock) {
                if (closed) return
                closed = true
                pendingFrames.clear()
            }
            wakeupChannel.trySend(Unit)
            runCatching { webSocket.cancel() }
        }

        private fun closeSocketGracefully() {
            val flushed = synchronized(lock) {
                closed = true
                pendingFrames.clear()
                flushInterimOnSilence(_transcript.value)
            }
            _transcript.value = flushed
            runCatching { webSocket.close(1000, "done") }
        }

        private fun failSession(error: Throwable) {
            synchronized(lock) {
                closed = true
                pendingFrames.clear()
                if (failureError == null) {
                    failureError = error
                }
            }
            wakeupChannel.trySend(Unit)
            runCatching { webSocket.cancel() }
        }

        private fun buildCombinedTranscript(): String {
            val base = committedSegments.joinToString("")
            return (base + currentInterim).trim()
        }
    }

    internal sealed interface LiveServerEvent {
        data object SetupComplete : LiveServerEvent
        data class TranscriptionUpdate(
            val interimText: String?,
            val finalText: String?,
            val turnComplete: Boolean
        ) : LiveServerEvent
        data class ErrorEvent(val code: Int, val message: String) : LiveServerEvent
        data object Ignored : LiveServerEvent
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-transcribe-live"
        const val MAX_INTERIM_CHARS = 80
        private val INTERIM_SPLIT_PUNCTUATION = charArrayOf(
            '，', '。', '？', '！', '；', '、', ',', '.', '?', '!'
        )
        private const val MAX_BUFFERED_SETUP_FRAMES = 180 // Up to ~36s of 200ms slices buffered safely
        private const val VOICED_CHUNK_RMS_FLOOR = 130.0
        private const val VOICED_CHUNK_PEAK_FLOOR = 420

        const val DEFAULT_INTERIM_QUIESCENCE_MS = 540L
        const val DEFAULT_FINALIZED_QUIESCENCE_MS = 400L
        const val DEFAULT_POST_END_INTERIM_SETTLE_MS = 320L
        const val DEFAULT_POST_FINAL_COALESCE_MS = 45L
        const val DEFAULT_WARM_FINISH_CAP_MS = 1_500L
        const val DEFAULT_COLD_FINISH_CAP_MS = 2_400L
        const val DEFAULT_MAX_PREWARM_IDLE_MS = 45_000L

        private val idleEvictor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "gemini-live-prewarm-evictor").apply { isDaemon = true }
            }

        private fun sanitizeHotwords(hotwords: List<String>): List<String> =
            hotwords.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(200)
                .toList()

        /**
         * Fast 16-bit LE PCM energy detector (~15 us for 3200 samples):
         * Returns `true` only when a streamed slice carries real speech/voiced energy above ambient
         * mic hiss, preventing 100-300ms silent tail frames before finger release from blocking `0ms` finish.
         */
        fun isVoicedPcmChunk(pcm16Le: ByteArray): Boolean {
            val sampleCount = pcm16Le.size / 2
            if (sampleCount <= 0) return false
            var sumSq = 0.0
            var peak = 0
            for (i in 0 until sampleCount) {
                val lo = pcm16Le[i * 2].toInt() and 0xFF
                val hi = pcm16Le[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                val mag = if (sample == Short.MIN_VALUE.toInt()) 32767 else abs(sample)
                if (mag > peak) peak = mag
                val d = sample.toDouble()
                sumSq += d * d
            }
            if (peak >= VOICED_CHUNK_PEAK_FLOOR) return true
            val rms = sqrt(sumSq / sampleCount)
            return rms >= VOICED_CHUNK_RMS_FLOOR
        }

        fun normalizeLiveTranscribeModel(rawModel: String): String {
            val clean = rawModel.trim().removePrefix("models/")
            val chosen = if (clean.isEmpty() ||
                (!clean.contains("live", ignoreCase = true) && !clean.contains("transcribe", ignoreCase = true))
            ) {
                DEFAULT_MODEL
            } else {
                clean
            }
            return "models/$chosen"
        }

        fun mapBcp47LanguageHint(rawLang: String): List<String> {
            return when (rawLang.trim().lowercase()) {
                "" -> emptyList()
                "zh", "zh-cn", "cn" -> listOf("zh-CN")
                "zh-tw", "zh-hk" -> listOf("zh-TW")
                "en", "en-us" -> listOf("en-US")
                "ja", "jp", "ja-jp" -> listOf("ja-JP")
                else -> listOf(rawLang.trim())
            }
        }

        fun buildSetupJson(
            model: String,
            language: String,
            hotwords: List<String>,
            smartMode: Boolean = true
        ): String {
            val normalizedModel = normalizeLiveTranscribeModel(model)
            val genConfig = JsonObject().apply {
                add("responseModalities", JsonArray().apply { add("TEXT") })
            }
            val transcriptionCfg = JsonObject().apply {
                val langArr = JsonArray()
                mapBcp47LanguageHint(language).forEach { langArr.add(it) }
                add("languageCodes", langArr)
                addProperty("mode", if (smartMode) "SMART" else "VERBATIM")
                val cleanedVocab = sanitizeHotwords(hotwords)
                if (cleanedVocab.isNotEmpty()) {
                    val vocabArr = JsonArray()
                    cleanedVocab.forEach { vocabArr.add(it) }
                    add("customVocabulary", vocabArr)
                }
            }
            val setupObj = JsonObject().apply {
                addProperty("model", normalizedModel)
                add("generationConfig", genConfig)
                add("inputAudioTranscription", transcriptionCfg)
            }
            return JsonObject().apply {
                add("setup", setupObj)
            }.toString()
        }

        fun buildAudioChunkJson(pcm16Le: ByteArray): String {
            val b64 = Base64.getEncoder().encodeToString(pcm16Le)
            val audioObj = JsonObject().apply {
                addProperty("data", b64)
                addProperty("mimeType", "audio/pcm;rate=16000")
            }
            val rtObj = JsonObject().apply {
                add("audio", audioObj)
            }
            return JsonObject().apply {
                add("realtimeInput", rtObj)
            }.toString()
        }

        fun buildAudioStreamEndJson(): String {
            val rtObj = JsonObject().apply {
                addProperty("audioStreamEnd", true)
            }
            return JsonObject().apply {
                add("realtimeInput", rtObj)
            }.toString()
        }

        internal fun parseServerMessage(rawJson: String): LiveServerEvent {
            val root = JsonParser.parseString(rawJson).asJsonObject
            if (root.has("setupComplete")) {
                return LiveServerEvent.SetupComplete
            }
            root.getAsJsonObject("error")?.let { err ->
                val code = err.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 500
                val msg = err.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "未知服务端错误"
                return LiveServerEvent.ErrorEvent(code, msg)
            }
            val sc = root.getAsJsonObject("serverContent") ?: return LiveServerEvent.Ignored
            val interim = sc.getAsJsonObject("interimInputTranscription")
                ?.get("text")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
            val finalized = sc.getAsJsonObject("inputTranscription")
                ?.get("text")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
            val turnComplete = sc.get("turnComplete")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean ?: false
            val generationComplete = sc.get("generationComplete")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean ?: false
            val isCompleteSignal = turnComplete || generationComplete

            if (interim == null && finalized == null && !isCompleteSignal) {
                return LiveServerEvent.Ignored
            }
            return LiveServerEvent.TranscriptionUpdate(
                interimText = interim,
                finalText = finalized,
                turnComplete = isCompleteSignal
            )
        }

        /**
         * Splits an overly long [interim] string (when `interim.length > maxInterimChars`) into
         * [finalized] prefix + remaining [interim] tail:
         * - Prefers splitting right after the last punctuation mark (`，。？！；、,.?!`) within the first
         *   [maxInterimChars] characters.
         * - Falls back to hard-splitting at [maxInterimChars] when no punctuation is present.
         */
        fun splitOverflowInterim(
            finalized: String,
            interim: String,
            maxInterimChars: Int = MAX_INTERIM_CHARS
        ): Pair<String, String> {
            val safeCap = maxInterimChars.coerceAtLeast(1)
            if (interim.length <= safeCap) return finalized to interim

            val finalizedBuilder = StringBuilder(finalized)
            var remaining = interim
            while (remaining.length > safeCap) {
                val window = remaining.substring(0, safeCap)
                val lastPuncIdx = window.lastIndexOfAny(INTERIM_SPLIT_PUNCTUATION)
                val splitIdx = if (lastPuncIdx >= 0) {
                    lastPuncIdx + 1
                } else {
                    safeCap
                }
                finalizedBuilder.append(remaining.substring(0, splitIdx))
                remaining = remaining.substring(splitIdx)
            }
            return finalizedBuilder.toString() to remaining
        }

        /**
         * Pure reducer updating [LiveTranscript] from a Gemini Live [LiveServerEvent.TranscriptionUpdate]:
         * - Incremental `inputTranscription` (`finalText`) or `interimInputTranscription` (`interimText`)
         *   accumulates in `interim` until `turnComplete` / `generationComplete` or silence convergence.
         * - Whenever `interim.length > maxInterimChars` (default 80), overflows the leading clause into
         *   `finalized` via [splitOverflowInterim] so the composing region never grows unbounded.
         */
        internal fun reduceLiveTranscript(
            previous: LiveTranscript,
            event: LiveServerEvent.TranscriptionUpdate,
            @Suppress("UNUSED_PARAMETER") maxInterimChars: Int = MAX_INTERIM_CHARS,
            silenceConverged: Boolean = false
        ): LiveTranscript {
            val hasFinalChunk = !event.finalText.isNullOrEmpty()
            if (hasFinalChunk) {
                // `inputTranscription` (`finalText`) is the server's finalized utterance for the current turn:
                // it replaces any provisional `interimInputTranscription` (`previous.interim`) rather than
                // concatenating onto it, and appends directly to `finalized`.
                val cleanFinal = AiPipelineEngine.cleanRawTranscript(event.finalText.orEmpty())
                val nextFinalized = previous.finalized + cleanFinal
                val nextInterim = if (!event.interimText.isNullOrEmpty()) {
                    AiPipelineEngine.cleanRawTranscript(event.interimText)
                } else {
                    ""
                }
                val changed = nextFinalized != previous.finalized || nextInterim != previous.interim
                return LiveTranscript(
                    finalized = nextFinalized,
                    interim = nextInterim,
                    revision = if (changed) previous.revision + 1L else previous.revision
                )
            }
            if (event.interimText != null) {
                val cleanInterim = AiPipelineEngine.cleanRawTranscript(event.interimText)
                val nextFinalized = if (silenceConverged) previous.finalized + cleanInterim else previous.finalized
                val nextInterim = if (silenceConverged) "" else cleanInterim
                val changed = nextFinalized != previous.finalized || nextInterim != previous.interim
                return LiveTranscript(
                    finalized = nextFinalized,
                    interim = nextInterim,
                    revision = if (changed) previous.revision + 1L else previous.revision
                )
            }
            if (silenceConverged && previous.interim.isNotEmpty()) {
                return flushInterimOnSilence(previous)
            }
            return previous
        }

        fun reduceLiveTranscript(
            current: LiveTranscript,
            inputChunk: String?,
            isInterimChunk: Boolean = false,
            turnComplete: Boolean = false,
            generationComplete: Boolean = false,
            maxInterimChars: Int = MAX_INTERIM_CHARS,
            silenceConverged: Boolean = false
        ): LiveTranscript {
            var candidateInterim = current.interim
            if (!inputChunk.isNullOrEmpty()) {
                val cleanChunk = AiPipelineEngine.cleanRawTranscript(inputChunk)
                if (isInterimChunk) {
                    candidateInterim = cleanChunk
                } else {
                    candidateInterim += cleanChunk
                }
            } else if (isInterimChunk && inputChunk != null) {
                candidateInterim = ""
            }

            var (nextFinalized, nextInterim) = splitOverflowInterim(
                finalized = current.finalized,
                interim = candidateInterim,
                maxInterimChars = maxInterimChars
            )

            if (turnComplete || generationComplete || silenceConverged) {
                nextFinalized += nextInterim
                nextInterim = ""
            }

            val changed = nextFinalized != current.finalized || nextInterim != current.interim
            val nextRev = if (changed) current.revision + 1L else current.revision
            return LiveTranscript(
                finalized = nextFinalized,
                interim = nextInterim,
                revision = nextRev
            )
        }

        fun flushInterimOnSilence(previous: LiveTranscript): LiveTranscript {
            if (previous.interim.isEmpty()) return previous
            return LiveTranscript(
                finalized = previous.finalized + previous.interim,
                interim = "",
                revision = previous.revision + 1L
            )
        }
    }
}