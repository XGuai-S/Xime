package com.kingzcheung.xime.ai.audio

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 录音采集异常退出时的自清理与上层收尾接线回归。 */
class WavAudioRecorderCaptureExitTest {
    private val recorderSource by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/ai/audio/WavAudioRecorder.kt").readText()
    }
    private val controllerSource by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/ai/ime/VoiceController.kt").readText()
    }
    private val bubbleSource by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/ai/bubble/XimeBubbleService.kt").readText()
    }

    @Test
    fun `negative read is an abnormal capture exit while positive read is not`() {
        val reason = RecorderExitPolicy.readFailureReason(-3)
        assertNotNull(reason)
        assertTrue(reason!!.contains("-3"))
        assertNull(RecorderExitPolicy.readFailureReason(720))
    }

    @Test
    fun `self release runs on abnormal exit or when the job is no longer tracked`() {
        assertFalse(RecorderExitPolicy.shouldSelfRelease(false, false))
        assertTrue(RecorderExitPolicy.shouldSelfRelease(true, false))
        assertTrue(RecorderExitPolicy.shouldSelfRelease(false, true))
        assertTrue(RecorderExitPolicy.shouldSelfRelease(true, true))
    }

    @Test
    fun `recorder declares and invokes the capture failure callback`() {
        assertTrue(recorderSource.contains("onCaptureFailure: ((Throwable) -> Unit)? = null"))
        assertTrue(recorderSource.contains("RecorderExitPolicy.shouldSelfRelease("))
        assertTrue(recorderSource.contains("onCaptureFailure?.invoke("))
    }

    @Test
    fun `both recording callers wire the capture failure callback`() {
        assertTrue(controllerSource.contains("onCaptureFailure = {"))
        assertTrue(bubbleSource.contains("onCaptureFailure = {"))
    }
}
