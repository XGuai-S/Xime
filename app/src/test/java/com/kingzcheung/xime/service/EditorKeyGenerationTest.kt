package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorKeyGenerationTest {
    @Test
    fun `queued key route checks captured generation and lifecycle invalidates it`() {
        val route = RepoFiles.mainKotlinSources.single { it.name == "ImeKeyRouter.kt" }.readText()
        val service = RepoFiles.mainKotlinSources.single { it.name == "XimeInputMethodService.kt" }.readText()
        assertTrue(route.contains("val editorKeyGeneration = service.captureEditorKeyGeneration()"))
        assertTrue(route.contains("service.keyProcessingDispatcher + editorJob"))
        assertTrue(route.contains("job.invokeOnCompletion"))
        assertTrue(route.contains("generation == service.deleteGeneration"))
        assertTrue(service.contains("editorKeyGeneration.invalidate()"))
        assertTrue(service.contains("EditorKeyGeneration(serviceScope.coroutineContext[Job])"))
        // 候选点选已改为「点击瞬间快照」：入队时固定候选文本与 actions，
        // 避免连打时任务排在按键之后、index 落到新一批候选上（点 A 上屏 B）。
        assertTrue(route.contains("postEditorRimeJob {\n                selectCandidateAsync(index, snapshot = pick)"))
        assertTrue(route.contains("internal fun postEditorRimeJob"))
        assertTrue(route.contains("private suspend fun refreshAfterKey("))
        assertTrue(route.contains("if (!service.isEditorKeyGenerationCurrent(editorGeneration)) return@trySend"))
        assertTrue(route.contains("internal fun pageDown() {\n        postEditorRimeJob"))
        assertTrue(service.substringAfter("override fun onStartInput(").contains("invalidateEditorKeyGeneration()"))
        assertTrue(service.substringAfter("override fun onFinishInput()").contains("invalidateEditorKeyGeneration()"))
    }

    @Test
    fun `invalidating editor generation cancels queued and active editor jobs but permits next generation`() = runTest {
        val generation = EditorKeyGeneration(coroutineContext[kotlinx.coroutines.Job])
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var staleCompleted = false
            val oldScope = kotlinx.coroutines.CoroutineScope(coroutineContext + generation.job())
            val queued = oldScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                staleCompleted = true
            }
            val oldJob = oldScope.launch {
                started.complete(Unit)
                release.await()
                staleCompleted = true
            }
            runCurrent()
            assertTrue(started.isCompleted)
            generation.invalidate()
            runCurrent()
            assertTrue(oldJob.isCancelled)
            assertTrue(queued.isCancelled)
            assertFalse(staleCompleted)

            var newCompleted = false
            val next = kotlinx.coroutines.CoroutineScope(coroutineContext + generation.job()).launch {
                newCompleted = true
            }
            runCurrent()
            assertTrue(next.isCompleted)
            assertTrue(newCompleted)
            release.complete(Unit)
        } finally {
            generation.dispose()
        }
    }

    @Test
    fun `invalidating editor generation drops queued work without invalidating later work`() {
        val generation = EditorKeyGeneration()
        val queuedForA = generation.capture()
        assertTrue(generation.isCurrent(queuedForA))

        generation.invalidate()
        assertFalse(generation.isCurrent(queuedForA))

        val queuedForB = generation.capture()
        assertTrue(generation.isCurrent(queuedForB))
        assertFalse(generation.isCurrent(queuedForA))
    }
}
