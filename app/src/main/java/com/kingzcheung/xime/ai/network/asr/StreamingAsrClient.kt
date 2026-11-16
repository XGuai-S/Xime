package com.kingzcheung.xime.ai.network.asr

import com.kingzcheung.xime.ai.audio.WavAudioRecorder
import com.kingzcheung.xime.ai.data.SttConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Live bidirectional WebSocket speech recognition session.
 * Accepts 16kHz 16-bit mono little-endian PCM chunks while the user speaks,
 * and returns the finalized transcript within ~60-150ms after mic release.
 */
interface StreamingAsrSession {
    /** Real-time incremental recognition preview emitted while speaking. */
    val partialText: StateFlow<String>

    /**
     * Structured real-time transcript separating immutable [LiveTranscript.finalized] sentences
     * from the mutable [LiveTranscript.interim] tail.
     * Default implementation derives from [partialText] by treating all text as `interim`.
     */
    val transcript: StateFlow<LiveTranscript>
        get() = DerivedInterimTranscriptStateFlow(partialText)

    /** Non-blocking push of a 16kHz 16-bit mono PCM slice (typically 200ms = 6400 bytes). */
    fun sendPcmFrame(pcm16Le: ByteArray)

    /** Clears buffered/accumulated prefix if user resets hands-free timer before speaking. */
    fun resetPrefix() {}

    /** Sends stream-end marker and suspends briefly for the server's final recognition packet. */
    suspend fun finishAndAwaitFinal(timeoutMs: Long = 2400L): Result<String>

    /** Immediately aborts the WebSocket session without waiting for results. */
    fun cancel()
}

private class DerivedInterimTranscriptStateFlow(
    private val source: StateFlow<String>
) : StateFlow<LiveTranscript> {
    override val replayCache: List<LiveTranscript>
        get() = listOf(value)

    override val value: LiveTranscript
        get() {
            val text = source.value
            return LiveTranscript(
                finalized = "",
                interim = text,
                revision = if (text.isEmpty()) 0L else 1L
            )
        }

    override suspend fun collect(collector: FlowCollector<LiveTranscript>): Nothing {
        var rev = 0L
        var lastText: String? = null
        source.collect { text ->
            if (text != lastText) {
                if (lastText != null || text.isNotEmpty()) {
                    rev += 1L
                }
                lastText = text
            }
            collector.emit(LiveTranscript(finalized = "", interim = text, revision = rev))
        }
    }
}

/**
 * Pluggable real-time WebSocket ASR provider contract.
 */
interface StreamingAsrClient {

    fun openSession(
        config: SttConfig,
        hotwords: List<String> = emptyList()
    ): StreamingAsrSession

    /**
     * Optional connection/handshake pre-warmer (e.g. Gemini Live `BidiGenerateContent` setup)
     * invoked when the IME voice panel or Playground becomes visible so 1-2s short utterances
     * experience zero cold WebSocket handshake delay.
     */
    fun prewarmSession(
        config: SttConfig,
        hotwords: List<String> = emptyList()
    ) = Unit

    /** Releases any idle pre-warmed WebSocket session immediately. */
    fun evictPrewarmSession() = Unit

    /**
     * Replays a saved local WAV file through the streaming ASR protocol.
     * Used for History audio retry, error retry, or fallback recovery.
     */
    suspend fun transcribeWavFile(
        wavFile: File,
        config: SttConfig,
        hotwords: List<String> = emptyList(),
        timeoutMs: Long = 3_200L
    ): String = withContext(Dispatchers.IO) {
        val rawPcm = WavAudioRecorder.extractPcmFromWav(wavFile)
        if (rawPcm.isEmpty()) {
            throw IOException("本地音频文件为空，无法识别")
        }
        val normalizedPcm = WavAudioRecorder.normalizePcm16Le(rawPcm)
        val session = openSession(config, hotwords)
        try {
            val chunkSize = WavAudioRecorder.STREAM_CHUNK_BYTES
            var offset = 0
            while (offset < normalizedPcm.size) {
                val end = minOf(offset + chunkSize, normalizedPcm.size)
                session.sendPcmFrame(normalizedPcm.copyOfRange(offset, end))
                offset = end
            }
            session.finishAndAwaitFinal(timeoutMs = timeoutMs).getOrThrow()
        } catch (e: Throwable) {
            session.cancel()
            throw if (e is IOException) e else IOException(e.message ?: "流式语音识别失败", e)
        }
    }
}
