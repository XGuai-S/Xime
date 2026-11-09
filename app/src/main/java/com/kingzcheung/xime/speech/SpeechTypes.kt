package com.kingzcheung.xime.speech

enum class RecognitionState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR
}

data class AudioConfig(
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val encoding: String = "pcm16"
)

data class SpeechResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float = 1.0f
)