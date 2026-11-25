package com.kingzcheung.xime.service

import com.kingzcheung.xime.RimeNativeSupport
import androidx.compose.runtime.mutableStateOf
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.RimeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class ImeCandidateEditorIsolationTest {
    /**
     * mock<RimeEngine>() 需要实例化该类，其 init 会 loadLibrary("rime_jni")。
     * 仓库只提供 ARM64 Linux/Android 的 .so，本机加载不了时跳过该类的全部用例
     * （Linux CI 与真机 androidTest 上正常执行，覆盖不降低）。
     */
    @org.junit.Before
    fun requireRimeNative() = RimeNativeSupport.assumeAvailable()
    private class Fixture : AutoCloseable {
        val service = mock<XimeInputMethodService>()
        val engine = mock<RimeEngine>()
        val editor = EditorKeyGeneration()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val queue = createKeyJobQueue()
        val state = mutableStateOf(CandidateState(candidates = listOf("旧词"), expandedCandidates = listOf(RimeCandidate("旧词", "")), inputText = "old"))
        val router = ImeKeyRouter(service)
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
            whenever(engine.getCandidates()).thenReturn(arrayOf("旧词"))
        }
        override fun close() {
            queue.cancel()
            editor.dispose()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test fun `queued global selection is canceled on editor switch before engine mutation`() {
        Fixture().use { f ->
            f.router.selectCandidateGlobal(0)
            val queued = f.queue.tryReceive().getOrThrow()
            f.editor.invalidate()
            queued.start()
            kotlinx.coroutines.runBlocking { queued.join() }
            verify(f.engine, never()).selectCandidateByGlobalIndex(any())
            verify(f.service, never()).commitText(any())
        }
    }

    @Test fun `queued global deletion does not read new editor candidates`() {
        Fixture().use { f ->
            f.router.deleteCandidateGlobal(0)
            val queued = f.queue.tryReceive().getOrThrow()
            f.editor.invalidate()
            f.state.value = f.state.value.copy(expandedCandidates = listOf(RimeCandidate("新词", "")), inputText = "new")
            queued.start()
            kotlinx.coroutines.runBlocking { queued.join() }
            verify(f.engine, never()).deleteCandidateByGlobalIndex(any())
            verify(f.service, never()).addWordBlacklist(any(), any())
        }
    }

    @Test fun `ordinary deletion blocked in engine cannot blacklist a new editor code`() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            whenever(f.engine.deleteCandidateOnCurrentPage(any())).thenAnswer {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                true
            }
            f.router.deleteCandidate(0)
            val queued = f.queue.tryReceive().getOrThrow()
            try {
                queued.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.editor.invalidate()
                f.state.value = f.state.value.copy(inputText = "new")
                resume.countDown()
                kotlinx.coroutines.runBlocking { queued.join() }
                verify(f.service, never()).addWordBlacklist(any(), any())
            } finally { resume.countDown() }
        }
    }
}
