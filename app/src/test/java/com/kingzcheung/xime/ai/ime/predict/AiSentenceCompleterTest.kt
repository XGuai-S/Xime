package com.kingzcheung.xime.ai.ime.predict

import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.ToneStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AiSentenceCompleterTest {
    private val settings = AppSettings(aiCompletionEnabled = true)
    private val style = ToneStyle("shared", "Default", "", "", "Use casual language")

    @Test
    fun `same scope returns cached completion synchronously`() = runTest {
        var calls = 0
        val completer = AiSentenceCompleter(this, 0, executor = SentenceCompletionExecutor { _, _, _, _ ->
            calls++
            Result.success("suggestion")
        })
        val results = mutableListOf<String>()
        completer.scheduleCompletion("context", settings, style, "app.one", onResult = results::add)!!.join()
        val hit = completer.scheduleCompletion("context  ", settings, style.copy(), "app.one", onResult = results::add)
        assertNull(hit)
        assertEquals(listOf("suggestion", "suggestion"), results)
        assertEquals(1, calls)
    }

    @Test
    fun `app changes discard the previous scope including on switch back`() = runTest {
        var calls = 0
        val completer = AiSentenceCompleter(this, 0, executor = SentenceCompletionExecutor { _, _, _, app ->
            calls++
            Result.success(app)
        })
        val results = mutableListOf<String>()
        for (app in listOf("app.one", "app.two", "app.one")) {
            completer.scheduleCompletion("context", settings, style, app, onResult = results::add)!!.join()
        }
        assertEquals(listOf("app.one", "app.two", "app.one"), results)
        assertEquals(3, calls)
    }

    @Test
    fun `equal settings copy and changed style with same id both miss`() = runTest {
        var calls = 0
        val completer = AiSentenceCompleter(this, 0, executor = SentenceCompletionExecutor { _, _, _, _ ->
            Result.success("suggestion ${++calls}")
        })
        val results = mutableListOf<String>()
        val copiedSettings = settings.copy()
        val revisedStyle = style.copy(promptInstruction = "Use formal language")
        completer.scheduleCompletion("context", settings, style, "app.one", onResult = results::add)!!.join()
        completer.scheduleCompletion("context", copiedSettings, style, "app.one", onResult = results::add)!!.join()
        completer.scheduleCompletion("context", copiedSettings, revisedStyle, "app.one", onResult = results::add)!!.join()
        assertEquals(listOf("suggestion 1", "suggestion 2", "suggestion 3"), results)
    }

    @Test
    fun `cancelled executor returning late cannot replace current cache`() = runTest {
        val oldStarted = CompletableDeferred<Unit>()
        val finishOld = CompletableDeferred<Unit>()
        val completer = AiSentenceCompleter(this, 0, executor = SentenceCompletionExecutor { _, _, _, app ->
            if (app == "app.one") {
                withContext(NonCancellable) {
                    oldStarted.complete(Unit)
                    finishOld.await()
                }
                Result.success("old suggestion")
            } else {
                Result.success("new suggestion")
            }
        })
        val results = mutableListOf<String>()
        val old = completer.scheduleCompletion("context", settings, style, "app.one", onResult = results::add)!!
        try {
            oldStarted.await()
            completer.scheduleCompletion("context", settings, style, "app.two", onResult = results::add)!!.join()
        } finally {
            finishOld.complete(Unit)
        }
        old.join()
        assertNull(completer.scheduleCompletion("context", settings, style, "app.two", onResult = results::add))
        assertEquals(listOf("new suggestion", "new suggestion"), results)
    }

    @Test
    fun `both incognito flags block cache delivery and execution`() = runTest {
        var calls = 0
        val completer = AiSentenceCompleter(this, 0, executor = SentenceCompletionExecutor { _, _, _, _ ->
            calls++
            Result.success("suggestion")
        })
        val results = mutableListOf<String>()
        completer.scheduleCompletion("context", settings, style, "app.one", onResult = results::add)!!.join()
        assertNull(completer.scheduleCompletion("context", settings, style, "app.one", true, results::add))
        assertNull(completer.scheduleCompletion("context", settings.copy(manualIncognitoMode = true), style, "app.one", onResult = results::add))
        assertEquals(listOf("suggestion"), results)
        assertEquals(1, calls)
        assertEquals(0, completer.cacheSize())
    }

    @Test
    fun `clearing cache invalidates an in flight request without cancelling it`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        val completer = AiSentenceCompleter(this, 0, executor = SentenceCompletionExecutor { _, _, _, _ ->
            started.complete(Unit)
            finish.await()
            Result.success("suggestion")
        })
        val pending = completer.scheduleCompletion("context", settings, style, "app.one", onResult = results::add)!!
        try {
            started.await()
            completer.clearCache()
        } finally {
            finish.complete(Unit)
        }
        pending.join()
        assertEquals(0, completer.cacheSize())
        assertEquals(emptyList<String>(), results)
    }

    @Test
    fun `default LRU retains only 32 entries and honors recent reads`() = runTest {
        val completer = AiSentenceCompleter(this, 0, executor = SentenceCompletionExecutor { _, _, _, _ ->
            Result.success("suggestion")
        })
        fun schedule(index: Int) = completer.scheduleCompletion("context $index", settings, style, "app.one") {}
        for (index in 0 until 32) schedule(index)!!.join()
        assertNull(schedule(0))
        schedule(32)!!.join()
        assertEquals(32, completer.cacheSize())
        assertNull(schedule(0))
        val evicted = schedule(1)
        assertNotNull(evicted)
        evicted!!.join()
    }
}
