package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.RimeProcessResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ImeKeyRouterTransformedResultTest {
    private class Fixture {
        val generation = EditorKeyGeneration()
        val service = mock(XimeInputMethodService::class.java)
        val transform = mock(CandidateTransformCoordinator::class.java)
        val controller = mock(ImeSessionController::class.java)
        val events = Channel<suspend () -> Unit>(Channel.CONFLATED)
        val router = ImeKeyRouter(service)
        val result = RimeProcessResult(
            processed = true,
            committedText = "",
            inputText = "ni",
            preeditText = "ni",
            candidates = arrayOf(RimeCandidate("你", ""), RimeCandidate("拟", "")),
            isAsciiMode = false,
            hasNextPage = true,
            hasPrevPage = false,
        )

        init {
            whenever(service.isEditorKeyGenerationCurrent(any())).thenAnswer {
                generation.isCurrent(it.getArgument(0))
            }
            whenever(service.candidateTransform).thenReturn(transform)
            whenever(service.sessionController).thenReturn(controller)
            whenever(service.uiEventChannel).thenReturn(events)
        }

        fun send(captured: Long, afterUpdate: suspend () -> Unit = {}) {
            val method = ImeKeyRouter::class.java.declaredMethods.single { it.name == "sendTransformedResult" }
            method.isAccessible = true
            method.invoke(router, result, captured, afterUpdate)
        }
    }

    @Test
    fun `result from task invalidated while waiting for engine never enters transform`() {
        val f = Fixture()
        val captured = f.generation.capture()
        f.generation.invalidate()

        f.send(captured)

        verifyNoInteractions(f.transform, f.controller)
        assertTrue(f.events.tryReceive().isFailure)
    }

    @Test
    fun `editor switch during blocked plugin transform drops transformed result`() {
        assertSwitchDuringTransform(
            CandidateTransformResult(listOf(RimeCandidate("插件", "")), listOf(CandidateAction.plugin("插件")))
        )
    }

    @Test
    fun `editor switch during blocked plugin transform also drops engine fallback`() {
        assertSwitchDuringTransform(null)
    }

    private fun assertSwitchDuringTransform(transformed: CandidateTransformResult?) {
        val f = Fixture()
        val captured = f.generation.capture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var afterUpdated = false
        whenever(f.transform.transformFor(f.result)).thenAnswer {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "transform was not released" }
            transformed
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit { f.send(captured) { afterUpdated = true } }
            assertTrue("transform did not start", entered.await(5, TimeUnit.SECONDS))
            f.generation.invalidate()
            release.countDown()
            task.get(5, TimeUnit.SECONDS)

            assertTrue(f.events.tryReceive().isFailure)
            verifyNoInteractions(f.controller)
            assertFalse(afterUpdated)
        } finally {
            release.countDown()
            executor.shutdownNow()
            f.generation.dispose()
        }
    }

    @Test
    fun `editor switch after UI enqueue suppresses update and afterUpdate`() = runBlocking {
        val f = Fixture()
        var afterUpdated = false
        f.send(f.generation.capture()) { afterUpdated = true }
        val queued = f.events.tryReceive().getOrThrow()
        f.generation.invalidate()

        queued()

        verifyNoInteractions(f.controller)
        assertFalse(afterUpdated)

        f.send(f.generation.capture()) { afterUpdated = true }
        f.events.tryReceive().getOrThrow().invoke()
        verify(f.controller).updateUIWithResult(f.result, emptyList())
        assertTrue(afterUpdated)
    }

    @Test
    fun `current engine fallback preserves snapshot and runs callback after update`() = runBlocking {
        val f = Fixture()
        var afterUpdated = false
        f.send(f.generation.capture()) {
            verify(f.controller).updateUIWithResult(f.result, emptyList())
            afterUpdated = true
        }
        f.events.tryReceive().getOrThrow().invoke()

        assertTrue(afterUpdated)
        verify(f.controller).updateUIWithResult(f.result, emptyList())
    }

    @Test
    fun `current plugin result keeps correction promotion and parallel actions`() = runBlocking {
        val f = Fixture()
        val candidates = listOf(RimeCandidate("正常", ""), RimeCandidate("次选", ""), RimeCandidate("纠正", "纠错"))
        val actions = listOf(CandidateAction.engine(0), CandidateAction.plugin("次选"), CandidateAction.engine(1))
        whenever(f.transform.transformFor(f.result)).thenReturn(CandidateTransformResult(candidates, actions))

        f.send(f.generation.capture())
        f.events.tryReceive().getOrThrow().invoke()

        val displayed = argumentCaptor<RimeProcessResult>()
        val displayedActions = argumentCaptor<List<CandidateAction>>()
        verify(f.controller).updateUIWithResult(displayed.capture(), displayedActions.capture())
        assertEquals(listOf("正常", "纠正", "次选"), displayed.firstValue.candidates.map { it.text })
        assertEquals(listOf(actions[0], actions[2], actions[1]), displayedActions.firstValue)
        assertEquals(f.result.copy(candidates = displayed.firstValue.candidates), displayed.firstValue)
    }

    @Test
    fun `callers carry original task generation through blocking engine calls`() {
        val source = RepoFiles.mainKotlinSources.single { it.name == "ImeKeyRouter.kt" }.readText()
        val calls = source.lineSequence().filter {
            it.trimStart().startsWith("sendTransformedResult(")
        }.toList()
        assertEquals(13, calls.size)
        assertTrue(calls.all { it.contains(", editorGeneration)") })
        assertTrue(source.contains("handleMainKey(key, candState, state.isAsciiMode, isShifted, editorKeyGeneration)"))
        assertTrue(source.contains("handleEngineKey(key, candState, isAsciiMode, isShifted, pendingEnglish, editorGeneration)"))
        assertTrue(source.contains("mask, isShiftedChinese,\n                editorGeneration"))
        assertTrue(source.contains("handleWordSeparatorKey(candState, editorKeyGeneration)"))
        assertTrue(source.contains(") processDeleteKey(editorGeneration)"))
        assertTrue(source.contains("deleteRimeComposition(editorGeneration)"))
        for (name in listOf("handleToolPanelKey", "handleQuickSendFormKey")) {
            val body = source.substringAfter("private fun $name(").substringBefore("\n    /**")
            assertTrue(body.indexOf("val editorGeneration = service.captureEditorKeyGeneration()") < body.indexOf("postRimeJob {"))
        }
        for (name in listOf("handleMainKey", "handleEngineKey", "handleNormalEngineKey", "handleWordSeparatorKey", "deleteRimeComposition")) {
            val body = source.substringAfter("private suspend fun $name(").substringBefore("\n    /**")
            assertTrue(body.contains("editorGeneration: Long"))
            assertFalse(body.contains("captureEditorKeyGeneration()"))
        }
    }
}
