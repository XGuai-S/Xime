package com.kingzcheung.xime.ai.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Hardware-accelerated PCM-to-M4A (AAC-LC 32kbps @ 16kHz mono) compressor using
 * standard Android platform APIs (minSdk 26, zero NDK/native binary bloat).
 *
 * Shrinks a 10-second 320 KB WAV recording down to ~40 KB (~8x smaller),
 * drastically cutting HTTP multipart upload time on weak cellular networks.
 */
object AacAudioCompressor {

    private const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 32_000
    private const val SAMPLE_RATE = WavAudioRecorder.SAMPLE_RATE
    private const val CHANNEL_COUNT = 1
    private const val TIMEOUT_US = 10_000L

    /** Skip compression for tiny sub-0.75s clips that already fit in 1-2 TCP segments. */
    const val MIN_WAV_BYTES_FOR_COMPRESSION = 24_000L

    /**
     * Compresses [wavFile] into an `.m4a` file in the same directory.
     * Safely returns `null` in JVM unit tests or on stripped devices lacking an AAC encoder,
     * allowing callers to fall back transparently to uploading [wavFile].
     */
    fun compressWavToM4aOrNull(wavFile: File): File? {
        if (!wavFile.exists() || wavFile.length() < MIN_WAV_BYTES_FOR_COMPRESSION) {
            return null
        }
        return try {
            val pcmBytes = WavAudioRecorder.extractPcmFromWav(wavFile)
            if (pcmBytes.isEmpty()) return null

            val outM4a = File(
                wavFile.parentFile ?: wavFile.absoluteFile.parentFile,
                wavFile.nameWithoutExtension + "_upload.m4a"
            )
            if (outM4a.exists()) {
                outM4a.delete()
            }

            encodePcm16LeToM4a(pcmBytes, outM4a)
            if (outM4a.exists() && outM4a.length() > 128L && outM4a.length() < wavFile.length()) {
                outM4a
            } else {
                outM4a.delete()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun encodePcm16LeToM4a(pcmBytes: ByteArray, outputFile: File) {
        val format = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }

        val codec = MediaCodec.createEncoderByType(MIME_AAC)
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()

            var inputOffset = 0
            var inputDone = false
            var outputDone = false
            val bytesPerSecond = SAMPLE_RATE * 2L

            while (!outputDone) {
                if (!inputDone) {
                    val inBufIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inBufIndex >= 0) {
                        val inBuf: ByteBuffer? = codec.getInputBuffer(inBufIndex)
                        if (inBuf != null) {
                            inBuf.clear()
                            val remaining = pcmBytes.size - inputOffset
                            if (remaining <= 0) {
                                val ptsUs = (inputOffset * 1_000_000L) / bytesPerSecond
                                codec.queueInputBuffer(
                                    inBufIndex,
                                    0,
                                    0,
                                    ptsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val toCopy = minOf(remaining, inBuf.remaining())
                                inBuf.put(pcmBytes, inputOffset, toCopy)
                                val ptsUs = (inputOffset * 1_000_000L) / bytesPerSecond
                                codec.queueInputBuffer(inBufIndex, 0, toCopy, ptsUs, 0)
                                inputOffset += toCopy
                            }
                        }
                    }
                }

                val outBufIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone) {
                            // Continue polling briefly until EOS arrives
                        }
                    }
                    outBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outBufIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outBufIndex)
                        val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isCodecConfig && bufferInfo.size > 0 && muxerStarted && outBuf != null) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outBufIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching {
                if (muxerStarted) muxer?.stop()
            }
            runCatching { muxer?.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}
