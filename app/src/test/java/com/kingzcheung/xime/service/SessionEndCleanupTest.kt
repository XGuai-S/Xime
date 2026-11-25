package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话结束/服务销毁时的资源释放契约。
 *
 * 真实缺陷：录音中切到其他应用 → 键盘收起 → `onFinishInput` 只调了
 * `cancelAiCompletion()`，**没停录音**。结果麦克风一直被占用、状态栏图标不消失，
 * 用户会以为输入法在偷听。
 */
class SessionEndCleanupTest {

    private val service by lazy {
        RepoFiles.mainKotlinSources.single { it.name == "XimeInputMethodService.kt" }.readText()
    }

    @Test
    fun `onFinishInput releases voice recording`() {
        val block = service.substringAfter("override fun onFinishInput()")
            .substringBefore("override fun onDestroy()")
        assertTrue(
            "onFinishInput 必须释放语音录音——否则用户切走应用后麦克风一直被占用",
            block.contains("releaseForSessionEnd") || block.contains("cancelRecordingOrProcessing"),
        )
    }

    @Test
    fun `onDestroy releases voice recording`() {
        val block = service.substringAfter("override fun onDestroy()")
            .substringBefore("override fun ")
        assertTrue(
            "onDestroy 必须释放语音录音——切换输入法会让系统销毁服务，孤儿线程会占着麦克风",
            block.contains("releaseForSessionEnd") || block.contains("cancelRecordingOrProcessing"),
        )
    }

    @Test
    fun `session end release is silent`() {
        val voice = RepoFiles.mainKotlinSources
            .single { it.name == "VoiceController.kt" }.readText()
        val fn = voice.substringAfter("fun releaseForSessionEnd()")
            .substringBefore("fun cancelRecordingOrProcessing()")
        assertTrue(
            "会话结束的清理不该触发按键反馈（用户切走应用时不该突然震动）",
            !fn.contains("performKeyFeedback"),
        )
    }
}
