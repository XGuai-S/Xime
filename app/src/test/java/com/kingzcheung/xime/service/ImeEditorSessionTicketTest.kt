package com.kingzcheung.xime.service

import com.kingzcheung.xime.RimeNativeSupport
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.mutableStateOf
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.RimeEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 展开候选选词/删除的编辑器会话绑定回归。
 *
 * 这些操作走同步 JNI，协程取消打不断已开始的引擎调用，因此必须在阻塞返回后
 * 复核编辑器身份；否则切框后旧结果会写进新输入框或记到新框编码上。
 */
class ImeEditorSessionTicketTest {
    /**
     * mock<RimeEngine>() 需要实例化该类，其 init 会 loadLibrary("rime_jni")。
     * 仓库只提供 ARM64 Linux/Android 的 .so，本机加载不了时跳过该类的全部用例
     * （Linux CI 与真机 androidTest 上正常执行，覆盖不降低）。
     */
    @org.junit.Before
    fun requireRimeNative() = RimeNativeSupport.assumeAvailable()
    /** 任务内 `withContext(Dispatchers.Main)` 需要可用主调度器（单测环境默认没有）。 */
    @get:org.junit.Rule
    val coroutineRule = com.kingzcheung.xime.CoroutineTestRule()

    private class Fixture : AutoCloseable {
        val service = mock<XimeInputMethodService>()
        val engine = mock<RimeEngine>()
        val editor = EditorKeyGeneration()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val queue = createKeyJobQueue()
        val state = mutableStateOf(
            CandidateState(
                candidates = listOf("旧词"),
                expandedCandidates = listOf(RimeCandidate("旧词", "")),
                inputText = "old",
            )
        )
        val router = ImeKeyRouter(service)
        var connection: InputConnection? = mock()

        init {
            whenever(service.serviceScope).thenReturn(scope)
            whenever(service.keyProcessingDispatcher).thenReturn(dispatcher)
            whenever(service.keyJobs).thenReturn(queue)
            whenever(service.candidateState).thenReturn(state)
            whenever(service.uiState).thenReturn(mutableStateOf(InputUIState()))
            whenever(service.rimeEngine).thenReturn(engine)
            whenever(service.captureEditorKeyGeneration()).thenAnswer { editor.capture() }
            whenever(service.editorBoundKeyJob()).thenAnswer { editor.job() }
            whenever(service.isEditorKeyGenerationCurrent(any())).thenAnswer { editor.isCurrent(it.getArgument(0)) }
            whenever(service.currentInputConnection).thenAnswer { connection }
            whenever(engine.getCandidates()).thenReturn(arrayOf("旧词"))
        }

        override fun close() {
            queue.cancel()
            editor.dispose()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `queued global selection after editor switch never mutates engine or commits`() {
        Fixture().use { f ->
            f.router.selectCandidateGlobal(0)
            val queued = f.queue.tryReceive().getOrThrow()
            f.editor.invalidate()
            queued.start()
            runBlocking { queued.join() }
            verify(f.engine, never()).selectCandidateByGlobalIndex(any())
            verify(f.engine, never()).commit()
            verify(f.service, never()).commitText(any())
        }
    }

    @Test
    fun `global selection invalidated while engine is blocked drops the commit`() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            whenever(f.engine.selectCandidateByGlobalIndex(any())).thenAnswer {
                entered.countDown()
                assertTrue(resume.await(5, TimeUnit.SECONDS))
                true
            }
            whenever(f.engine.commit()).thenReturn("旧词")
            f.router.selectCandidateGlobal(0)
            val queued = f.queue.tryReceive().getOrThrow()
            try {
                queued.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.editor.invalidate()
                resume.countDown()
                runBlocking { queued.join() }
                verify(f.service, never()).commitText(any())
                verify(f.service, never()).markCandidateSelection(any(), anyOrNull())
            } finally {
                resume.countDown()
            }
        }
    }

    @Test
    fun `global deletion invalidated while engine is blocked skips blacklist`() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            whenever(f.engine.deleteCandidateByGlobalIndex(any())).thenAnswer {
                entered.countDown()
                assertTrue(resume.await(5, TimeUnit.SECONDS))
                true
            }
            f.router.deleteCandidateGlobal(0)
            val queued = f.queue.tryReceive().getOrThrow()
            try {
                queued.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.editor.invalidate()
                f.state.value = f.state.value.copy(inputText = "new")
                resume.countDown()
                runBlocking { queued.join() }
                verify(f.service, never()).addWordBlacklist(any(), any())
            } finally {
                resume.countDown()
            }
        }
    }

    @Test
    fun `ordinary deletion blacklists the code captured at enqueue time`() {
        Fixture().use { f ->
            whenever(f.engine.deleteCandidateOnCurrentPage(any())).thenReturn(true)
            f.router.deleteCandidate(0)
            val queued = f.queue.tryReceive().getOrThrow()
            // 即使入队后候选状态被刷新，也必须写入入队时的文本与编码
            f.state.value = f.state.value.copy(
                candidates = listOf("新词"),
                expandedCandidates = listOf(RimeCandidate("新词", "")),
                inputText = "new",
            )
            queued.start()
            runBlocking { queued.join() }
            verify(f.service).addWordBlacklist("旧词", "old")
        }
    }

    @Test
    fun `deletion blocked in engine before switch still blacklists the old editor`() {
        Fixture().use { f ->
            whenever(f.engine.deleteCandidateOnCurrentPage(any())).thenReturn(true)
            f.router.deleteCandidate(0)
            val queued = f.queue.tryReceive().getOrThrow()
            queued.start()
            runBlocking { queued.join() }
            verify(f.service).addWordBlacklist("旧词", "old")
            verify(f.service).updateUI()
        }
    }

    @Test
    fun `session ticket treats a replaced input connection as a new editor`() {
        Fixture().use { f ->
            val ticket = ImeKeyRouter.EditorSessionTicket(f.editor.capture(), f.connection)
            assertTrue(f.router.isEditorSessionCurrent(ticket))
            f.connection = mock()
            assertFalse(f.router.isEditorSessionCurrent(ticket))
        }
    }

    @Test
    fun `session ticket expires with the editor generation`() {
        Fixture().use { f ->
            val ticket = ImeKeyRouter.EditorSessionTicket(f.editor.capture(), f.connection)
            f.editor.invalidate()
            assertFalse(f.router.isEditorSessionCurrent(ticket))
        }
    }

    @Test
    fun `queued job whose connection changed before start does not run`() {
        Fixture().use { f ->
            f.router.deleteCandidate(0)
            val queued = f.queue.tryReceive().getOrThrow()
            f.connection = mock()
            queued.start()
            runBlocking { queued.join() }
            verify(f.engine, never()).deleteCandidateOnCurrentPage(any())
            verify(f.service, never()).addWordBlacklist(any(), any())
        }
    }
}
