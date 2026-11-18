package com.kingzcheung.xime.ai.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import com.kingzcheung.xime.util.FileLogger
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.kingzcheung.xime.ai.data.HistoryRetention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class RecordedAudioResult(
    val wavFile: File,
    val durationMs: Long,
    val hasValidSpeech: Boolean
)

class WavAudioRecorder(private val context: Context) {

    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var activeAudioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var startTimeMs: Long = 0L

    @Volatile
    private var ignoreAudioUntilMs: Long = 0L

    @Volatile
    private var activeWhisperMode: Boolean = false

    private val _amplitudes = MutableStateFlow(List(BAR_COUNT) { 0.08f })
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _didPauseExternalMedia = MutableStateFlow(false)
    val didPauseExternalMedia: StateFlow<Boolean> = _didPauseExternalMedia.asStateFlow()

    private val sessionLock = Any()
    private var activeDiskFile: RandomAccessFile? = null
    private var totalPcmBytesWritten: Int = 0

    @Volatile
    private var speechFrameCount: Int = 0

    @Volatile
    private var currentStrongStreak: Int = 0

    @Volatile
    private var maxStrongStreak: Int = 0

    @Volatile
    private var observedGlobalPeak: Int = 0

    @Volatile
    private var prefixClearedCallback: (() -> Unit)? = null

    @Volatile
    private var resetRequested: Boolean = false

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasDetectedSpeech(): Boolean {
        return isLikelyHumanSpeech(speechFrameCount, maxStrongStreak)
    }

    private fun requestTransientAudioFocusIfNeeded(pauseMediaPlayback: Boolean) {
        abandonTransientAudioFocus()
        if (!pauseMediaPlayback) return
        val am = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull() ?: return

        val wasMusicActive = runCatching { am.isMusicActive }.getOrDefault(false)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { /* transient dictation capture */ }
            .build()

        val res = runCatching { am.requestAudioFocus(req) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            activeAudioFocusRequest = req
            _didPauseExternalMedia.value = wasMusicActive
        } else {
            _didPauseExternalMedia.value = false
        }
    }

    private fun abandonTransientAudioFocus() {
        val req = activeAudioFocusRequest
        activeAudioFocusRequest = null
        _didPauseExternalMedia.value = false
        if (req != null) {
            runCatching {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.abandonAudioFocusRequest(req)
            }
        }
    }

    /**
     * Clears initial swipe/touch friction & haptic motor frames when locking from the
     * Swipe-Up Arc Translate wheel into Hands-Free Translate recording, resetting timer to 00:00.
     */
    fun clearSilencePrefix(skipHapticMs: Long = 120L) {
        synchronized(sessionLock) {
            try {
                activeDiskFile?.let { raf ->
                    raf.setLength(WAV_HEADER_BYTES.toLong())
                    raf.seek(WAV_HEADER_BYTES.toLong())
                }
            } catch (_: Exception) {
            }
            totalPcmBytesWritten = 0
            resetRequested = true
        }
        val now = System.currentTimeMillis()
        speechFrameCount = 0
        currentStrongStreak = 0
        maxStrongStreak = 0
        observedGlobalPeak = 0
        startTimeMs = now
        ignoreAudioUntilMs = now + skipHapticMs.coerceAtLeast(0L)
        _elapsedMs.value = 0L
        _amplitudes.value = List(BAR_COUNT) { 0.08f }
        prefixClearedCallback?.invoke()
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        onStreamChunk: ((ByteArray) -> Unit)? = null,
        onSilencePrefixCleared: (() -> Unit)? = null,
        pauseMediaPlayback: Boolean = false,
        whisperMode: Boolean = false
    ): Result<Unit> {
        if (!hasRecordPermission()) {
            return Result.failure(SecurityException("尚未授予麦克风录音权限"))
        }
        if (recordingJob?.isActive == true) {
            return Result.success(Unit)
        }
        // 清理上一轮遗留的 AudioRecord 实例。
        //
        // 为什么必须有这一步：上面的早退条件只看 job 是否活跃，而 job 可能已因
        // scope 取消、录音循环异常 break 等原因结束，但 audioRecord 从未 release()。
        // 那样新调用会直接 `audioRecord = record` 覆盖引用，旧实例永久泄漏且仍处于
        // RECORDING 状态——麦克风与音频焦点被抓着不放：其他应用录音失败、
        // 系统麦克风指示灯常亮、下次录音抢不到焦点录成静音。
        val staleRecord = audioRecord
        if (staleRecord != null) {
            try {
                if (staleRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    staleRecord.stop()
                }
            } catch (e: Exception) {
                FileLogger.w("WavAudioRecorder", "startRecording: 停止遗留 AudioRecord 失败", e)
            }
            try {
                staleRecord.release()
            } catch (e: Exception) {
                FileLogger.w("WavAudioRecorder", "startRecording: 释放遗留 AudioRecord 失败", e)
            }
            FileLogger.w("WavAudioRecorder", "startRecording: 已释放上一轮遗留的 AudioRecord")
        }
        audioRecord = null

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            return Result.failure(IllegalStateException("无法初始化系统音频缓冲区"))
        }

        val bufferSize = maxOf(minBufSize * 2, CHUNK_SAMPLES * 4)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            return Result.failure(e)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Result.failure(IllegalStateException("麦克风当前被其他应用占用或初始化失败"))
        }

        val outFile = getLastRecordingFile(context)
        val raf = try {
            outFile.parentFile?.mkdirs()
            RandomAccessFile(outFile, "rw").apply {
                setLength(0L)
                write(buildWavHeader(0, SAMPLE_RATE, 1, 16))
            }
        } catch (e: Exception) {
            record.release()
            return Result.failure(e)
        }

        synchronized(sessionLock) {
            activeDiskFile = raf
            totalPcmBytesWritten = 0
            resetRequested = false
        }

        audioRecord = record
        activeWhisperMode = whisperMode
        prefixClearedCallback = onSilencePrefixCleared
        speechFrameCount = 0
        currentStrongStreak = 0
        maxStrongStreak = 0
        observedGlobalPeak = 0

        val initNow = System.currentTimeMillis()
        startTimeMs = initNow
        ignoreAudioUntilMs = initNow + 60L
        _elapsedMs.value = 0L
        _amplitudes.value = List(BAR_COUNT) { 0.08f }

        requestTransientAudioFocusIfNeeded(pauseMediaPlayback)

        try {
            record.startRecording()
        } catch (e: Exception) {
            abandonTransientAudioFocus()
            synchronized(sessionLock) {
                runCatching { activeDiskFile?.close() }
                activeDiskFile = null
            }
            record.release()
            audioRecord = null
            return Result.failure(e)
        }

        val speechRmsFloor = if (whisperMode) WHISPER_SPEECH_RMS_THRESHOLD else SPEECH_RMS_THRESHOLD
        val strongRmsFloor = if (whisperMode) WHISPER_STRONG_SPEECH_RMS_THRESHOLD else STRONG_SPEECH_RMS_THRESHOLD
        val waveformDivisor = if (whisperMode) 950.0 else 2400.0

        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuf = ShortArray(CHUNK_SAMPLES)
            val byteBuf = ByteBuffer.allocate(CHUNK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
            val streamAccumulator = ByteArrayOutputStream(STREAM_CHUNK_BYTES * 2)
            val rollingBars = ArrayDeque<Float>(BAR_COUNT)
            repeat(BAR_COUNT) { rollingBars.addLast(0.08f) }

            try {
                while (isActive) {
                    val readCount = record.read(shortBuf, 0, shortBuf.size)
                    // read() 返回 0 或负数时**必须处理**，否则是紧循环：
                    // 负数代表设备错误（ERROR_INVALID_OPERATION=-3 / ERROR_DEAD_OBJECT=-6 /
                    // ERROR_BAD_VALUE=-2），常见于录音中被系统收回麦克风（来电、另一个应用
                    // 申请 MIC、Android 14+ 后台 FGS 抢占）。
                    // 旧实现只判 >0，负值/零值直接进入下一轮 → 单核 100% 占用并持续耗电，
                    // 同时波形与计时冻结，用户完全看不出出错。
                    if (readCount < 0) {
                        com.kingzcheung.xime.util.FileLogger.e(
                            "WavAudioRecorder",
                            "AudioRecord.read 返回 $readCount（录音设备失效），中止本次录音"
                        )
                        break
                    }
                    if (readCount == 0) {
                        // 暂无数据（正常抖动）：让出线程，不占满 CPU
                        kotlinx.coroutines.delay(20)
                        continue
                    }
                    if (readCount > 0) {
                        val now = System.currentTimeMillis()
                        if (now < ignoreAudioUntilMs) {
                            _elapsedMs.value = (now - startTimeMs).coerceAtLeast(0L)
                            continue
                        }

                        byteBuf.clear()
                        var sumSquares = 0.0
                        var framePeak = 0
                        for (i in 0 until readCount) {
                            val sample = shortBuf[i]
                            byteBuf.putShort(sample)
                            val intSample = sample.toInt()
                            val mag = if (intSample == Short.MIN_VALUE.toInt()) 32767 else abs(intSample)
                            if (mag > framePeak) framePeak = mag
                            val d = intSample.toDouble()
                            sumSquares += d * d
                        }

                        val bytesRead = readCount * 2
                        var emitChunks: List<ByteArray>? = null

                        synchronized(sessionLock) {
                            if (resetRequested) {
                                streamAccumulator.reset()
                                resetRequested = false
                            }
                            activeDiskFile?.write(byteBuf.array(), 0, bytesRead)
                            totalPcmBytesWritten += bytesRead

                            if (onStreamChunk != null) {
                                streamAccumulator.write(byteBuf.array(), 0, bytesRead)
                                if (streamAccumulator.size() >= STREAM_CHUNK_BYTES) {
                                    val allBytes = streamAccumulator.toByteArray()
                                    streamAccumulator.reset()
                                    val chunks = ArrayList<ByteArray>(2)
                                    var offset = 0
                                    while (allBytes.size - offset >= STREAM_CHUNK_BYTES) {
                                        val rawSlice = allBytes.copyOfRange(offset, offset + STREAM_CHUNK_BYTES)
                                        chunks.add(if (whisperMode) boostWhisperPcm16Le(rawSlice) else rawSlice)
                                        offset += STREAM_CHUNK_BYTES
                                    }
                                    if (offset < allBytes.size) {
                                        streamAccumulator.write(allBytes, offset, allBytes.size - offset)
                                    }
                                    emitChunks = chunks
                                }
                            }
                        }

                        emitChunks?.forEach { chunk ->
                            runCatching { onStreamChunk?.invoke(chunk) }
                        }

                        val rms = sqrt(sumSquares / readCount)
                        val elapsedNow = now - startTimeMs
                        if (elapsedNow >= 110L) {
                            if (framePeak > observedGlobalPeak) {
                                observedGlobalPeak = framePeak
                            }
                            if (rms >= speechRmsFloor) {
                                speechFrameCount += 1
                            }
                            if (rms >= strongRmsFloor) {
                                currentStrongStreak += 1
                                if (currentStrongStreak > maxStrongStreak) {
                                    maxStrongStreak = currentStrongStreak
                                }
                            } else {
                                currentStrongStreak = 0
                            }
                        }

                        // Normalize speech level into [0.08f .. 1.0f] for crisp waveform display
                        val normalized = ((rms / waveformDivisor).toFloat()).coerceIn(0.08f, 1.0f)
                        if (rollingBars.size >= BAR_COUNT) {
                            rollingBars.removeFirst()
                        }
                        rollingBars.addLast(normalized)
                        _amplitudes.value = rollingBars.toList()
                        _elapsedMs.value = elapsedNow.coerceAtLeast(0L)
                    }
                }
            } finally {
                // Flush remaining <200ms tail PCM chunk right as recording stops so WebSocket ASR gets 100% of audio
                if (onStreamChunk != null) {
                    val tailBytes = synchronized(sessionLock) {
                        if (!resetRequested && streamAccumulator.size() > 0) {
                            streamAccumulator.toByteArray().also { streamAccumulator.reset() }
                        } else {
                            null
                        }
                    }
                    if (tailBytes != null && tailBytes.isNotEmpty()) {
                        val finalTail = if (whisperMode) boostWhisperPcm16Le(tailBytes) else tailBytes
                        runCatching { onStreamChunk.invoke(finalTail) }
                    }
                }
            }
        }

        return Result.success(Unit)
    }

    suspend fun stopRecording(): Result<RecordedAudioResult> = withContext(Dispatchers.IO) {
        val job = recordingJob
        recordingJob = null
        prefixClearedCallback = null
        val sessionWhisper = activeWhisperMode
        activeWhisperMode = false
        try {
            job?.cancelAndJoin()
        } catch (_: Exception) {
        }

        val record = audioRecord
        audioRecord = null
        try {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: Exception) {
        } finally {
            try {
                record?.release()
            } catch (_: Exception) {
            }
            // Immediately release transient AudioFocus so paused background music resumes right away
            abandonTransientAudioFocus()
        }

        _amplitudes.value = List(BAR_COUNT) { 0.08f }

        val duration = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
        val outFile = getLastRecordingFile(context)

        val pcmSize: Int
        synchronized(sessionLock) {
            pcmSize = totalPcmBytesWritten
            val raf = activeDiskFile
            activeDiskFile = null
            totalPcmBytesWritten = 0
            try {
                if (raf != null) {
                    if (pcmSize >= SAMPLE_RATE * 2 / 5 && duration >= 240L) {
                        raf.seek(0L)
                        raf.write(buildWavHeader(pcmSize, SAMPLE_RATE, 1, 16))
                    }
                    raf.close()
                }
            } catch (_: Exception) {
                runCatching { raf?.close() }
            }
        }

        if (pcmSize < SAMPLE_RATE * 2 / 5 || duration < 240L) {
            return@withContext Result.failure(IllegalArgumentException("录音时间太短，请说完后再点击完成"))
        }

        val hasSpeech = isLikelyHumanSpeech(speechFrameCount, maxStrongStreak)

        // Fast path (<1ms): if mic gain is already strong or pure silence, header was patched in-place!
        // Only run software gain boost if OEM VOICE_RECOGNITION source or Whisper mode produced a quiet voice track.
        val minBoostPeak = if (sessionWhisper) MIN_WHISPER_VOICE_PEAK else MIN_VOICE_PEAK_FOR_BOOST
        if (hasSpeech && observedGlobalPeak in minBoostPeak until TARGET_PEAK_AMPLITUDE) {
            runCatching {
                val rawPcm = extractPcmFromWav(outFile)
                if (rawPcm.isNotEmpty()) {
                    val boosted = normalizePcm16Le(
                        pcmBytes = rawPcm,
                        sampleRate = SAMPLE_RATE,
                        whisperMode = sessionWhisper
                    )
                    if (boosted !== rawPcm) {
                        writeWavFile(outFile, boosted, SAMPLE_RATE, 1, 16)
                    }
                }
            }
        }

        Result.success(
            RecordedAudioResult(
                wavFile = outFile,
                durationMs = duration,
                hasValidSpeech = hasSpeech
            )
        )
    }

    suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        val job = recordingJob
        recordingJob = null
        prefixClearedCallback = null
        activeWhisperMode = false
        try {
            job?.cancelAndJoin()
        } catch (_: Exception) {
        }
        val record = audioRecord
        audioRecord = null
        try {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: Exception) {
        } finally {
            try {
                record?.release()
            } catch (_: Exception) {
            }
            abandonTransientAudioFocus()
        }
        synchronized(sessionLock) {
            runCatching { activeDiskFile?.close() }
            activeDiskFile = null
            totalPcmBytesWritten = 0
        }
        _amplitudes.value = List(BAR_COUNT) { 0.08f }
        _elapsedMs.value = 0L
    }

    private fun writeWavFile(
        file: File,
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val header = buildWavHeader(pcmData.size, sampleRate, channels, bitsPerSample)
        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(pcmData)
            fos.flush()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val WAV_HEADER_BYTES = 44
        /** 200ms slice at 16kHz 16-bit mono = 3200 samples = 6400 bytes (ideal for Doubao, DashScope & Gemini Live WS). */
        const val STREAM_CHUNK_BYTES = 6400
        private const val CHUNK_SAMPLES = 720 // ~45ms per UI frame at 16kHz
        const val SPEECH_RMS_THRESHOLD = 155.0
        const val STRONG_SPEECH_RMS_THRESHOLD = 420.0
        const val WHISPER_SPEECH_RMS_THRESHOLD = 62.0
        const val WHISPER_STRONG_SPEECH_RMS_THRESHOLD = 105.0
        const val MIN_SPEECH_FRAMES = 3       // >= ~135ms voiced energy (filters 1-2 frame tap clicks)
        const val MIN_STRONG_STREAK_FRAMES = 2
        private const val MIN_VOICE_PEAK_FOR_BOOST = 220
        const val MIN_WHISPER_VOICE_PEAK = 80
        private const val TARGET_PEAK_AMPLITUDE = 18500
        private const val MAX_NORMALIZATION_GAIN = 6.0
        private const val MAX_WHISPER_NORMALIZATION_GAIN = 9.0
        private const val WHISPER_LIVE_GAIN = 3.8
        private const val SOFT_KNEE_START = 22000.0
        const val BAR_COUNT = 24

        fun getLastRecordingFile(context: Context): File {
            return File(context.cacheDir, "last_typefree_voice.wav")
        }

        /**
         * Copies [wavFile] into an isolated `<baseDir>/voice_history/voice_<timestamp>_<uuid8>.wav`
         * archive file so subsequent recordings into `last_typefree_voice.wav` (from either the IME
         * or the floating Bubble service) never overwrite historical audio playback files.
         */
        fun archiveWavForHistory(
            baseDir: File,
            wavFile: File,
            retention: HistoryRetention,
            maxFiles: Int = 50,
            nowMs: Long = System.currentTimeMillis()
        ): File {
            if (retention == HistoryRetention.NEVER || !wavFile.exists()) {
                return wavFile
            }
            return try {
                val dir = File(baseDir, "voice_history").apply { mkdirs() }
                val suffix = UUID.randomUUID().toString().take(8)
                val archived = File(dir, "voice_${nowMs}_${suffix}.wav")
                wavFile.copyTo(archived, overwrite = true)
                val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }
                    ?.sortedByDescending { it.lastModified() }
                    .orEmpty()
                if (files.size > maxFiles) {
                    files.drop(maxFiles).forEach { runCatching { it.delete() } }
                }
                archived
            } catch (_: Exception) {
                wavFile
            }
        }

        /**
         * Multi-frame speech VAD decision: filters single-frame mechanical button/screen clicks
         * while allowing soft whispers (>= 3 voiced frames, ~135ms) or crisp short words (>= 2 consecutive strong frames).
         */
        fun isLikelyHumanSpeech(speechFrameCount: Int, maxConsecutiveStrongFrames: Int): Boolean {
            return speechFrameCount >= MIN_SPEECH_FRAMES ||
                (speechFrameCount >= 2 && maxConsecutiveStrongFrames >= MIN_STRONG_STREAK_FRAMES)
        }

        /**
         * Pure helper for evaluating a sequence of 45ms frame RMS values in unit tests or offline WAV checks.
         */
        fun scanFramesForSpeech(rmsFrames: List<Double>, whisperMode: Boolean = false): Boolean {
            val speechFloor = if (whisperMode) WHISPER_SPEECH_RMS_THRESHOLD else SPEECH_RMS_THRESHOLD
            val strongFloor = if (whisperMode) WHISPER_STRONG_SPEECH_RMS_THRESHOLD else STRONG_SPEECH_RMS_THRESHOLD
            var voicedCount = 0
            var strongStreak = 0
            var bestStrongStreak = 0
            for (rms in rmsFrames) {
                if (rms >= speechFloor) {
                    voicedCount += 1
                }
                if (rms >= strongFloor) {
                    strongStreak += 1
                    if (strongStreak > bestStrongStreak) {
                        bestStrongStreak = strongStreak
                    }
                } else {
                    strongStreak = 0
                }
            }
            return isLikelyHumanSpeech(voicedCount, bestStrongStreak)
        }

        /**
         * Real-time soft-knee Whisper AGC booster for 200ms streaming WebSocket chunks:
         * - Noise gate below [MIN_WHISPER_VOICE_PEAK] (`80`) prevents amplifying silent mic floor hiss.
         * - Applies [WHISPER_LIVE_GAIN] (`3.8x`, ~`+11.6 dB`) with soft-knee compression above `22000`
         *   so sudden normal-volume words never clip harshly.
         */
        fun boostWhisperPcm16Le(pcmBytes: ByteArray): ByteArray {
            val sampleCount = pcmBytes.size / 2
            if (sampleCount <= 0) return pcmBytes

            var peak = 0
            for (i in 0 until sampleCount) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                val mag = if (sample == Short.MIN_VALUE.toInt()) 32767 else abs(sample)
                if (mag > peak) peak = mag
            }
            if (peak < MIN_WHISPER_VOICE_PEAK) {
                return pcmBytes
            }

            val out = ByteArray(sampleCount * 2)
            for (i in 0 until sampleCount) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                val rawScaled = sample * WHISPER_LIVE_GAIN
                val absRaw = abs(rawScaled)
                val compressedAbs = if (absRaw <= SOFT_KNEE_START) {
                    absRaw
                } else {
                    SOFT_KNEE_START + (absRaw - SOFT_KNEE_START) * 0.35
                }
                val signed = if (rawScaled < 0.0) -compressedAbs else compressedAbs
                val clamped = signed.roundToInt().coerceIn(-32767, 32767)
                out[i * 2] = (clamped and 0xFF).toByte()
                out[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
            }
            return out
        }

        fun buildWavHeader(
            dataSize: Int,
            sampleRate: Int = SAMPLE_RATE,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ): ByteArray {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = (channels * bitsPerSample / 8).toShort()
            val chunkSize = 36 + dataSize

            return ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(chunkSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // PCM Subchunk1Size
                putShort(1.toShort()) // AudioFormat = 1 (PCM)
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign)
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }.array()
        }

        /**
         * Extracts raw PCM payload from a RIFF WAVE file (supports standard 44-byte header and extended chunks).
         */
        fun extractPcmFromWav(wavFile: File): ByteArray {
            if (!wavFile.exists()) return ByteArray(0)
            val all = wavFile.readBytes()
            if (all.size <= WAV_HEADER_BYTES) return ByteArray(0)

            // Verify RIFF....WAVE signature; if raw/custom file in tests, strip 44 bytes safely
            val isRiffWave = all[0] == 'R'.code.toByte() &&
                all[1] == 'I'.code.toByte() &&
                all[2] == 'F'.code.toByte() &&
                all[3] == 'F'.code.toByte() &&
                all[8] == 'W'.code.toByte() &&
                all[9] == 'A'.code.toByte() &&
                all[10] == 'V'.code.toByte() &&
                all[11] == 'E'.code.toByte()

            if (!isRiffWave) {
                return all
            }

            var offset = 12
            val buf = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            while (offset + 8 <= all.size) {
                val id0 = all[offset].toInt().toChar()
                val id1 = all[offset + 1].toInt().toChar()
                val id2 = all[offset + 2].toInt().toChar()
                val id3 = all[offset + 3].toInt().toChar()
                val chunkSize = buf.getInt(offset + 4).coerceAtLeast(0)
                val dataStart = offset + 8
                if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
                    val end = (dataStart + chunkSize).coerceAtMost(all.size)
                    return if (end > dataStart) all.copyOfRange(dataStart, end) else ByteArray(0)
                }
                offset = dataStart + chunkSize + (chunkSize and 1)
            }
            return all.copyOfRange(WAV_HEADER_BYTES, all.size)
        }

        /**
         * Boosts quiet 16-bit little-endian PCM recordings (common on Android OEMs where
         * `AudioSource.VOICE_RECOGNITION` disables hardware AGC) up toward [TARGET_PEAK_AMPLITUDE]
         * with a capped gain ([MAX_NORMALIZATION_GAIN] or [MAX_WHISPER_NORMALIZATION_GAIN]) and soft sample clamping,
         * while leaving pure digital silence (`peak < minPeak`) untouched.
         */
        fun normalizePcm16Le(
            pcmBytes: ByteArray,
            sampleRate: Int = SAMPLE_RATE,
            whisperMode: Boolean = false
        ): ByteArray {
            val sampleCount = pcmBytes.size / 2
            if (sampleCount <= 0) return pcmBytes

            val minPeak = if (whisperMode) MIN_WHISPER_VOICE_PEAK else MIN_VOICE_PEAK_FOR_BOOST
            val maxGain = if (whisperMode) MAX_WHISPER_NORMALIZATION_GAIN else MAX_NORMALIZATION_GAIN

            val edgeMargin = if (sampleCount > sampleRate / 2) sampleRate / 10 else 0 // 100ms edge trim for peak scan
            var interiorPeak = 0
            var globalPeak = 0

            for (i in 0 until sampleCount) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                val mag = if (sample == Short.MIN_VALUE.toInt()) 32767 else abs(sample)
                if (mag > globalPeak) globalPeak = mag
                if (i >= edgeMargin && i < sampleCount - edgeMargin && mag > interiorPeak) {
                    interiorPeak = mag
                }
            }

            val refPeak = if (interiorPeak >= minPeak) interiorPeak else globalPeak
            if (refPeak < minPeak || refPeak >= TARGET_PEAK_AMPLITUDE) {
                return pcmBytes
            }

            val gain = (TARGET_PEAK_AMPLITUDE.toDouble() / refPeak.toDouble())
                .coerceIn(1.0, maxGain)
            if (gain <= 1.05) return pcmBytes

            val out = ByteArray(sampleCount * 2)
            for (i in 0 until sampleCount) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                val scaled = (sample * gain).roundToInt().coerceIn(-32767, 32767)
                out[i * 2] = (scaled and 0xFF).toByte()
                out[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
