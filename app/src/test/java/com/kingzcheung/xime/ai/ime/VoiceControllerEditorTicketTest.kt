package com.kingzcheung.xime.ai.ime

import android.view.inputmethod.InputConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock

/**
 * AI 帮写的编辑器会话绑定回归。
 *
 * 帮写请求在应用级 scope 上做分钟级网络往返，期间用户可能切到别的输入框。
 * 若回填时再取 `currentInputConnection()`，成稿会整框写进新输入框——
 * 把用户正在写的内容整段删掉重写。这里把「请求发起时的输入框」钉住。
 */
class VoiceControllerEditorTicketTest {
    private fun controllerWith(
        connection: InputConnection?,
    ): Pair<VoiceController, (InputConnection?) -> Unit> {
        var current: InputConnection? = connection
        val host = mock<ImeHostCallbacks>()
        org.mockito.kotlin.whenever(host.currentInputConnection()).thenAnswer { current }
        val controller = VoiceController(
            host = host,
            audioRecorderProvider = { error("测试不构造录音器") },
            onExternalCommitToEngine = {},
            onResetComposing = {},
        )
        return controller to { next -> current = next }
    }

    @Test
    fun `ticket captures the connection that was current when the request started`() {
        val connection = mock<InputConnection>()
        val (controller, _) = controllerWith(connection)
        val ticket = controller.captureEditorTicket()
        assertSame(connection, ticket.connection)
        assertTrue(controller.isEditorTicketCurrent(ticket))
    }

    @Test
    fun `switching editor invalidates the ticket`() {
        val (controller, setConnection) = controllerWith(mock())
        val oldTicket = controller.captureEditorTicket()
        assertTrue(controller.isEditorTicketCurrent(oldTicket))
        // 用户切到另一个输入框：系统把当前连接换成新对象
        setConnection(mock())
        assertFalse(
            "输入框切换后旧票必须失效（否则成稿会写进新框）",
            controller.isEditorTicketCurrent(oldTicket),
        )
    }

    @Test
    fun `stale ticket drops the full editor replacement instead of writing the new box`() {
        val oldConnection = mock<InputConnection>()
        val newConnection = mock<InputConnection>()
        val (controller, setConnection) = controllerWith(oldConnection)
        val ticket = controller.captureEditorTicket()
        // 请求在网络往返期间用户切了输入框
        setConnection(newConnection)

        controller.replaceFullEditorTextSafely("AI 成稿", originalText = "原文", editorTicket = ticket)

        // 关键：旧框和新框都不得被写入——结果整体丢弃
        org.mockito.kotlin.verifyNoInteractions(oldConnection)
        org.mockito.kotlin.verifyNoInteractions(newConnection)
    }

    @Test
    fun `current ticket still writes through the captured connection`() {
        val connection = mock<InputConnection>()
        org.mockito.kotlin.whenever(connection.getExtractedText(any(), any()))
            .thenReturn(null)
        org.mockito.kotlin.whenever(connection.getTextBeforeCursor(any(), any()))
            .thenReturn(null)
        org.mockito.kotlin.whenever(connection.getTextAfterCursor(any(), any()))
            .thenReturn(null)
        val (controller, _) = controllerWith(connection)
        val ticket = controller.captureEditorTicket()

        controller.replaceFullEditorTextSafely("AI 成稿", originalText = "原文", editorTicket = ticket)

        org.mockito.kotlin.verify(connection).beginBatchEdit()
        org.mockito.kotlin.verify(connection).commitText("AI 成稿", 1)
        org.mockito.kotlin.verify(connection).endBatchEdit()
    }

    @Test
    fun `ticket without connection is never current`() {
        val (controller, _) = controllerWith(null)
        val ticket = controller.captureEditorTicket()
        assertFalse("没有输入连接时不得认为票有效", controller.isEditorTicketCurrent(ticket))
    }

    @Test
    fun `dispose cancels pending jobs and clears help-write base`() {
        val (controller, _) = controllerWith(mock())
        // 不应抛异常，且可重复调用（服务销毁路径会直接调）
        controller.dispose()
        controller.dispose()
    }

    @Test
    fun `resetHelpWriteBase keeps the controller usable`() {
        val (controller, _) = controllerWith(mock())
        controller.resetHelpWriteBase()
        controller.cancelPendingHelpWrite()
        assertTrue(controller.isEditorTicketCurrent(controller.captureEditorTicket()))
    }


    @Test
    fun `dispose releases the microphone instead of only cancelling jobs`() {
        // 缺陷：recorder 是 by lazy 单例，dispose 只取消 Job 不会释放 AudioRecord，
        // 服务销毁时若正在录音，麦克风与音频焦点会被一直占用。
        val source = com.kingzcheung.xime.RepoFiles.mainKotlinSources
            .single { it.name == "VoiceController.kt" }
            .readText()
            .replace("\r\n", "\n")
        val dispose = source.substringAfter("fun dispose() {")
            .substringBefore("fun runHelpWriteDraftTransform")
        assertTrue(
            "dispose 必须停止并释放录音器",
            dispose.contains("recorder.cancelRecording()"),
        )
        assertTrue(
            "本方法是服务销毁路径的同步收尾，必须等录音真正停下",
            dispose.contains("runBlocking"),
        )
        assertTrue(
            "必须有超时兜底，避免销毁流程被录音线程卡死",
            dispose.contains("withTimeoutOrNull"),
        )
    }

    @Test
    fun `dispose still tears down every background job`() {
        val source = com.kingzcheung.xime.RepoFiles.mainKotlinSources
            .single { it.name == "VoiceController.kt" }
            .readText()
            .replace("\r\n", "\n")
        val dispose = source.substringAfter("fun dispose() {")
            .substringBefore("fun runHelpWriteDraftTransform")
        listOf(
            "toastDismissJob?.cancel()",
            "handsFreeSilenceJob?.cancel()",
            "streamingPreviewJob?.cancel()",
            "chatScreenshotJob?.cancel()",
            "jevInferenceJob?.cancel()",
            "askAiJob?.cancel()",
            "activePipelineJob?.cancel()",
            "abortActiveStreamingSession()",
        ).forEach { expected ->
            assertTrue("dispose 必须清理 $expected", dispose.contains(expected))
        }
    }
}
