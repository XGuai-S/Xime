package com.kingzcheung.xime.ai.network.asr

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kingzcheung.xime.ai.audio.WavAudioRecorder
import com.kingzcheung.xime.ai.data.SttConfig
import com.kingzcheung.xime.ai.network.AiPipelineEngine
import com.kingzcheung.xime.ai.network.EndpointResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Volcengine Doubao Speech (火山引擎 · 豆包流式语音识别大模型 SAUC v3) WebSocket client.
 *
 * Default endpoint: `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`
 * Default resource ID: `volc.seedasr.sauc.duration` (SeedASR 2.0) / `volc.bigasr.sauc.duration` (1.0)
 *
 * Supports BOTH authentication styles:
 * 1. Speech Console `AppID` ([SttConfig.appId]) + `Access Token` ([SttConfig.apiKey])
 *    -> `X-Api-App-Key` + `X-Api-Access-Key`
 * 2. Unified v3 Speech `API Key` ([SttConfig.appId] left blank, [SttConfig.apiKey] filled)
 *    -> `X-Api-Key`
 */
class VolcengineSaucAsrClient(
    private val httpClient: OkHttpClient
) : StreamingAsrClient {

    override fun openSession(
        config: SttConfig,
        hotwords: List<String>
    ): StreamingAsrSession {
        val tokenOrKey = config.apiKey.trim()
        if (tokenOrKey.isEmpty()) {
            return DashScopeStreamingAsrClient.FailedImmediateSession(
                "请先在「API 配置」中填写火山豆包语音的 Access Token 或 API Key"
            )
        }

        val wsUrl = EndpointResolver.resolveVolcengineSaucWsEndpoint(config.baseUrl)
        val appId = config.appId.trim()
        val resourceId = config.resourceId.trim().ifEmpty { DEFAULT_RESOURCE_ID }
        val connectId = UUID.randomUUID().toString()

        val reqBuilder = Request.Builder()
            .url(wsUrl)
            .header("X-Api-Resource-Id", resourceId)
            .header("X-Api-Connect-Id", connectId)

        if (appId.isNotEmpty()) {
            reqBuilder.header("X-Api-App-Key", appId)
            reqBuilder.header("X-Api-Access-Key", tokenOrKey)
        } else {
            reqBuilder.header("X-Api-Key", tokenOrKey)
        }

        val initPayloadJson = buildFullRequestJson(
            uid = appId.ifEmpty { "typefree_android" },
            modelName = config.model.trim().ifEmpty { DEFAULT_MODEL_NAME },
            language = config.language.trim(),
            hotwords = hotwords
        )

        return VolcengineSaucSession(
            httpClient = httpClient,
            request = reqBuilder.build(),
            fullRequestJson = initPayloadJson
        )
    }

    private class VolcengineSaucSession(
        httpClient: OkHttpClient,
        request: Request,
        private val fullRequestJson: String
    ) : StreamingAsrSession {

        private val lock = Any()
        private val _partialText = MutableStateFlow("")
        override val partialText: StateFlow<String> = _partialText.asStateFlow()
        private val _transcript = MutableStateFlow(LiveTranscript())
        override val transcript: StateFlow<LiveTranscript> = _transcript.asStateFlow()
        private var transcriptRevision: Long = 0L

        private val finalDeferred = CompletableDeferred<String>()
        private val pendingFrames = ArrayDeque<ByteArray>()

        private var handshakeSent = false
        private var finishRequested = false
        private var lastPacketSent = false
        private var closed = false
        private var latestServerText: String = ""

        private val webSocket: WebSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val initFrame = encodeFullClientRequest(fullRequestJson)
                    webSocket.send(initFrame.toByteString(0, initFrame.size))

                    val queued = mutableListOf<ByteArray>()
                    var sendFinalNow = false
                    synchronized(lock) {
                        if (closed) return
                        handshakeSent = true
                        while (pendingFrames.isNotEmpty()) {
                            queued.add(pendingFrames.removeFirst())
                        }
                        if (finishRequested && !lastPacketSent) {
                            lastPacketSent = true
                            sendFinalNow = true
                        }
                    }

                    if (sendFinalNow) {
                        if (queued.isEmpty()) {
                            val eofFrame = encodeAudioChunk(ByteArray(0), isLast = true)
                            webSocket.send(eofFrame.toByteString(0, eofFrame.size))
                        } else {
                            for (i in 0 until queued.lastIndex) {
                                val frame = encodeAudioChunk(queued[i], isLast = false)
                                webSocket.send(frame.toByteString(0, frame.size))
                            }
                            val lastFrame = encodeAudioChunk(queued.last(), isLast = true)
                            webSocket.send(lastFrame.toByteString(0, lastFrame.size))
                        }
                    } else {
                        for (pcm in queued) {
                            val frame = encodeAudioChunk(pcm, isLast = false)
                            webSocket.send(frame.toByteString(0, frame.size))
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleBinaryMessage(webSocket, bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Fallback in case a proxy gateway forwards plain JSON error
                    runCatching {
                        val obj = JsonParser.parseString(text).asJsonObject
                        val msg = obj.get("message")?.asString ?: text.take(160)
                        failSession(IOException("豆包流式 ASR 响应异常: $msg"))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val logId = response?.header("X-Tt-Logid")?.let { " [LogId: $it]" }.orEmpty()
                    val statusInfo = response?.code?.let { "HTTP $it" } ?: (t.message ?: "连接中断")
                    failSession(IOException("豆包流式 ASR 连接失败 ($statusInfo)$logId", t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val snapshot = synchronized(lock) { latestServerText }
                    if (!finalDeferred.isCompleted) {
                        if (snapshot.isNotBlank()) {
                            finalDeferred.complete(AiPipelineEngine.cleanRawTranscript(snapshot))
                        } else {
                            finalDeferred.completeExceptionally(
                                IOException("豆包流式连接已关闭 ($code): ${reason.ifBlank { "未返回识别文本" }}")
                            )
                        }
                    }
                }
            }
        )

        private fun handleBinaryMessage(ws: WebSocket, rawBytes: ByteArray) {
            val decoded = try {
                decodeServerFrame(rawBytes)
            } catch (e: Exception) {
                failSession(IOException("解析豆包流式报文失败: ${e.message}", e))
                return
            }

            when (decoded) {
                is DecodedServerFrame.RecognitionResult -> {
                    val cleaned = AiPipelineEngine.cleanRawTranscript(decoded.text)
                    var nextTranscript: LiveTranscript? = null
                    val shouldFinish = synchronized(lock) {
                        if (cleaned.isNotEmpty() || decoded.isFinalPackage) {
                            latestServerText = cleaned
                        }
                        val prev = _transcript.value
                        if (decoded.finalizedText != prev.finalized || decoded.interimText != prev.interim) {
                            transcriptRevision += 1L
                            nextTranscript = LiveTranscript(
                                finalized = decoded.finalizedText,
                                interim = decoded.interimText,
                                revision = transcriptRevision
                            )
                        }
                        decoded.isFinalPackage || (lastPacketSent && decoded.definiteUtteranceEnded)
                    }
                    nextTranscript?.let { _transcript.value = it }
                    if (cleaned.isNotEmpty()) {
                        _partialText.value = cleaned
                    }
                    if (shouldFinish) {
                        val finalOut = synchronized(lock) {
                            closed = true
                            latestServerText
                        }
                        if (!finalDeferred.isCompleted) {
                            finalDeferred.complete(finalOut)
                        }
                        runCatching { ws.close(1000, "done") }
                    }
                }

                is DecodedServerFrame.ServerError -> {
                    failSession(
                        IOException("豆包流式 ASR 错误 (${decoded.code}): ${decoded.message}")
                    )
                    runCatching { ws.close(1000, "error") }
                }

                DecodedServerFrame.AckOrEmpty -> Unit
            }
        }

        private fun failSession(error: IOException) {
            synchronized(lock) {
                closed = true
                pendingFrames.clear()
            }
            if (!finalDeferred.isCompleted) {
                finalDeferred.completeExceptionally(error)
            }
        }

        override fun sendPcmFrame(pcm16Le: ByteArray) {
            if (pcm16Le.isEmpty()) return
            var sendNow = false
            synchronized(lock) {
                if (closed || finishRequested) return
                if (handshakeSent) {
                    sendNow = true
                } else {
                    while (pendingFrames.size >= 60) {
                        pendingFrames.removeFirst()
                    }
                    pendingFrames.addLast(pcm16Le)
                }
            }
            if (sendNow) {
                val frame = encodeAudioChunk(pcm16Le, isLast = false)
                webSocket.send(frame.toByteString(0, frame.size))
            }
        }

        override fun resetPrefix() {
            synchronized(lock) {
                if (!handshakeSent) {
                    pendingFrames.clear()
                }
                latestServerText = ""
                transcriptRevision = 0L
            }
            _transcript.value = LiveTranscript()
            _partialText.value = ""
        }

        override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> {
            var sendEofNow = false
            synchronized(lock) {
                if (!closed && !finishRequested) {
                    finishRequested = true
                    if (handshakeSent && !lastPacketSent) {
                        lastPacketSent = true
                        sendEofNow = true
                    }
                }
            }
            if (sendEofNow) {
                val eofFrame = encodeAudioChunk(ByteArray(0), isLast = true)
                webSocket.send(eofFrame.toByteString(0, eofFrame.size))
            }

            return try {
                val finalTranscript = withTimeout(timeoutMs) {
                    finalDeferred.await()
                }
                Result.success(finalTranscript)
            } catch (e: Throwable) {
                // Note: In `bigmodel_async`, if the trailing 200ms after user finished speaking was pure silence,
                // the server may not emit a new changed-text packet before timeout — salvage `latestServerText`!
                val salvaged = synchronized(lock) { latestServerText }.trim()
                cancel()
                if (salvaged.isNotEmpty() && e !is IOException) {
                    Result.success(salvaged)
                } else {
                    Result.failure(
                        if (e is IOException) e else IOException("豆包流式尾包等待超时，已自动切换兜底", e)
                    )
                }
            }
        }

        override fun cancel() {
            synchronized(lock) {
                closed = true
                pendingFrames.clear()
            }
            if (!finalDeferred.isCompleted) {
                finalDeferred.cancel()
            }
            runCatching { webSocket.cancel() }
        }
    }

    internal sealed interface DecodedServerFrame {
        data class RecognitionResult(
            val text: String,
            val isFinalPackage: Boolean,
            val definiteUtteranceEnded: Boolean = false,
            val finalizedText: String = "",
            val interimText: String = ""
        ) : DecodedServerFrame

        data class ServerError(
            val code: Int,
            val message: String
        ) : DecodedServerFrame

        data object AckOrEmpty : DecodedServerFrame
    }

    companion object {
        const val DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"
        const val LEGACY_RESOURCE_ID_V1 = "volc.bigasr.sauc.duration"
        const val DEFAULT_MODEL_NAME = "bigmodel"

        private const val PROTOCOL_VERSION = 0x01
        private const val HEADER_SIZE_UNITS = 0x01 // 1 * 4 bytes = 4 bytes

        // Message types (upper nibble of byte 1)
        const val MSG_CLIENT_FULL_REQUEST = 0x01
        const val MSG_CLIENT_AUDIO_ONLY = 0x02
        const val MSG_SERVER_FULL_RESPONSE = 0x09
        const val MSG_SERVER_ACK = 0x0B
        const val MSG_SERVER_ERROR = 0x0F

        // Message flags (lower nibble of byte 1)
        const val FLAG_NO_SEQUENCE = 0x00
        const val FLAG_POS_SEQUENCE = 0x01
        const val FLAG_NEG_SEQUENCE = 0x02
        const val FLAG_NEG_WITH_SEQUENCE = 0x03

        // Serialization (upper nibble of byte 2)
        const val SERIALIZATION_NONE = 0x00
        const val SERIALIZATION_JSON = 0x01

        // Compression (lower nibble of byte 2)
        const val COMPRESSION_NONE = 0x00
        const val COMPRESSION_GZIP = 0x01

        fun buildFullRequestJson(
            uid: String = "typefree_android",
            modelName: String = DEFAULT_MODEL_NAME,
            language: String = "",
            hotwords: List<String> = emptyList(),
            sampleRate: Int = WavAudioRecorder.SAMPLE_RATE
        ): String {
            val userObj = JsonObject().apply {
                addProperty("uid", uid)
            }
            val audioObj = JsonObject().apply {
                addProperty("format", "pcm")
                addProperty("codec", "raw")
                addProperty("rate", sampleRate)
                addProperty("bits", 16)
                addProperty("channels", 1)
                if (language.isNotBlank()) {
                    addProperty("language", language)
                }
            }
            val reqObj = JsonObject().apply {
                addProperty("model_name", modelName.ifBlank { DEFAULT_MODEL_NAME })
                addProperty("enable_itn", true)
                addProperty("enable_punc", true)
                addProperty("enable_ddc", false) // Let TypeFree's downstream LLM handle semantic self-corrections cleanly
                addProperty("show_utterances", true)
                addProperty("result_type", "full")

                if (hotwords.isNotEmpty()) {
                    val contextJson = JsonObject().apply {
                        val hwArray = JsonArray()
                        hotwords.take(50).forEach { word ->
                            if (word.isNotBlank()) {
                                hwArray.add(JsonObject().apply { addProperty("word", word.trim()) })
                            }
                        }
                        add("hotwords", hwArray)
                    }
                    add("corpus", JsonObject().apply {
                        addProperty("context", contextJson.toString())
                    })
                }
            }
            return JsonObject().apply {
                add("user", userObj)
                add("audio", audioObj)
                add("request", reqObj)
            }.toString()
        }

        fun buildHeader(
            messageType: Int,
            flags: Int,
            serialization: Int,
            compression: Int
        ): ByteArray {
            val b0 = ((PROTOCOL_VERSION and 0x0F) shl 4) or (HEADER_SIZE_UNITS and 0x0F)
            val b1 = ((messageType and 0x0F) shl 4) or (flags and 0x0F)
            val b2 = ((serialization and 0x0F) shl 4) or (compression and 0x0F)
            return byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte(), 0x00)
        }

        fun encodeFullClientRequest(jsonPayload: String): ByteArray {
            val header = buildHeader(
                messageType = MSG_CLIENT_FULL_REQUEST,
                flags = FLAG_NO_SEQUENCE,
                serialization = SERIALIZATION_JSON,
                compression = COMPRESSION_GZIP
            )
            val compressed = gzipCompress(jsonPayload.toByteArray(Charsets.UTF_8))
            return ByteBuffer.allocate(4 + 4 + compressed.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(header)
                .putInt(compressed.size)
                .put(compressed)
                .array()
        }

        fun encodeAudioChunk(pcmBytes: ByteArray, isLast: Boolean): ByteArray {
            val flags = if (isLast) FLAG_NEG_SEQUENCE else FLAG_NO_SEQUENCE
            val header = buildHeader(
                messageType = MSG_CLIENT_AUDIO_ONLY,
                flags = flags,
                serialization = SERIALIZATION_NONE,
                compression = COMPRESSION_GZIP
            )
            val compressed = gzipCompress(pcmBytes)
            return ByteBuffer.allocate(4 + 4 + compressed.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(header)
                .putInt(compressed.size)
                .put(compressed)
                .array()
        }

        internal fun decodeServerFrame(raw: ByteArray): DecodedServerFrame {
            if (raw.size < 4) return DecodedServerFrame.AckOrEmpty
            val headerSize = ((raw[0].toInt() and 0x0F) * 4).coerceAtLeast(4)
            if (raw.size < headerSize) return DecodedServerFrame.AckOrEmpty

            val messageType = (raw[1].toInt() ushr 4) and 0x0F
            val flags = raw[1].toInt() and 0x0F
            val compression = raw[2].toInt() and 0x0F

            val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            var offset = headerSize

            return when (messageType) {
                MSG_SERVER_FULL_RESPONSE -> {
                    val hasSequence = (flags and FLAG_POS_SEQUENCE) != 0
                    val isFinalPackage = (flags and FLAG_NEG_SEQUENCE) != 0
                    if (hasSequence) {
                        if (offset + 4 > raw.size) return DecodedServerFrame.AckOrEmpty
                        val seq = buf.getInt(offset)
                        offset += 4
                        // Negative sequence also signals final server packet
                        if (seq < 0 && !isFinalPackage) {
                            return parseRecognitionPayload(raw, buf, offset, compression, isFinal = true)
                        }
                    }
                    parseRecognitionPayload(raw, buf, offset, compression, isFinal = isFinalPackage)
                }

                MSG_SERVER_ERROR -> {
                    if (offset + 8 > raw.size) {
                        return DecodedServerFrame.ServerError(-1, "未知服务端错误")
                    }
                    val errCode = buf.getInt(offset)
                    val msgSize = buf.getInt(offset + 4).coerceAtLeast(0)
                    offset += 8
                    val end = (offset + msgSize).coerceAtMost(raw.size)
                    val rawMsgBytes = if (end > offset) raw.copyOfRange(offset, end) else ByteArray(0)
                    val decodedBytes = if (compression == COMPRESSION_GZIP && rawMsgBytes.isNotEmpty()) {
                        runCatching { gzipDecompress(rawMsgBytes) }.getOrDefault(rawMsgBytes)
                    } else {
                        rawMsgBytes
                    }
                    val msgText = decodedBytes.toString(Charsets.UTF_8).trim().ifEmpty { "语音服务端拒绝请求" }
                    DecodedServerFrame.ServerError(errCode, msgText)
                }

                else -> DecodedServerFrame.AckOrEmpty
            }
        }

        private fun parseRecognitionPayload(
            raw: ByteArray,
            buf: ByteBuffer,
            offset: Int,
            compression: Int,
            isFinal: Boolean
        ): DecodedServerFrame {
            if (offset + 4 > raw.size) {
                return DecodedServerFrame.RecognitionResult("", isFinalPackage = isFinal)
            }
            val payloadSize = buf.getInt(offset).coerceAtLeast(0)
            val start = offset + 4
            val end = (start + payloadSize).coerceAtMost(raw.size)
            if (end <= start) {
                return DecodedServerFrame.RecognitionResult("", isFinalPackage = isFinal)
            }
            val slice = raw.copyOfRange(start, end)
            val jsonBytes = if (compression == COMPRESSION_GZIP) {
                gzipDecompress(slice)
            } else {
                slice
            }
            val jsonStr = jsonBytes.toString(Charsets.UTF_8)
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val resultObj = root.getAsJsonObject("result")
            val directText = resultObj?.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val utterances = resultObj?.getAsJsonArray("utterances")
            val text = if (directText.isNotEmpty()) {
                directText
            } else {
                utterances?.joinToString("") { elem ->
                    elem.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                }.orEmpty()
            }

            val lastUtteranceDefinite = utterances?.takeIf { it.size() > 0 }?.let { arr ->
                val lastUtt = arr[arr.size() - 1].asJsonObject
                lastUtt.get("definite")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            } ?: false

            val live = parseLiveTranscriptFromResultJson(
                rawJson = jsonStr,
                previousRevision = 0L,
                isFinalPackage = isFinal
            )

            return DecodedServerFrame.RecognitionResult(
                text = text,
                isFinalPackage = isFinal,
                definiteUtteranceEnded = lastUtteranceDefinite,
                finalizedText = live.finalized,
                interimText = live.interim
            )
        }

        /**
         * Pure parser splitting Volcengine SeedASR `result.utterances[]` by `definite == true`:
         * - Utterances with `definite == true` are concatenated into [LiveTranscript.finalized].
         * - Trailing utterances with `definite == false` are placed into [LiveTranscript.interim].
         * - When `utterances` is absent/empty, falls back to placing `result.text` into `interim`
         *   (or `finalized` if [isFinalPackage] is true).
         */
        fun parseLiveTranscriptFromResultJson(
            rawJson: String,
            previousRevision: Long = 0L,
            isFinalPackage: Boolean = false
        ): LiveTranscript {
            val root = JsonParser.parseString(rawJson).asJsonObject
            val resultObj = root.getAsJsonObject("result") ?: root
            val directText = resultObj.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val utterances = resultObj.getAsJsonArray("utterances")

            val finalizedBuilder = StringBuilder()
            val interimBuilder = StringBuilder()

            if (utterances != null && utterances.size() > 0) {
                for (i in 0 until utterances.size()) {
                    val elem = utterances[i]
                    if (!elem.isJsonObject) continue
                    val uttObj = elem.asJsonObject
                    val uttText = AiPipelineEngine.cleanRawTranscript(
                        uttObj.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    )
                    if (uttText.isEmpty()) continue
                    val isDefinite = uttObj.get("definite")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                        ?.asBoolean ?: false
                    if (isDefinite || isFinalPackage) {
                        finalizedBuilder.append(uttText)
                    } else {
                        interimBuilder.append(uttText)
                    }
                }
            } else {
                val cleaned = AiPipelineEngine.cleanRawTranscript(directText)
                if (isFinalPackage) {
                    finalizedBuilder.append(cleaned)
                } else {
                    interimBuilder.append(cleaned)
                }
            }

            return LiveTranscript(
                finalized = finalizedBuilder.toString(),
                interim = interimBuilder.toString(),
                revision = previousRevision + 1L
            )
        }

        internal fun gzipCompress(input: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream(maxOf(64, input.size / 2))
            GZIPOutputStream(bos).use { gzip ->
                if (input.isNotEmpty()) {
                    gzip.write(input)
                }
            }
            return bos.toByteArray()
        }

        internal fun gzipDecompress(compressed: ByteArray): ByteArray {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gis ->
                return gis.readBytes()
            }
        }
    }
}
