package com.kingzcheung.xime.speech

import android.content.Context
import android.os.Handler
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SpeechRecognitionManagerStartTest {
    private class Fixture(expectedPosts: Int = 1) {
        val backend = mock(AsrBackend::class.java)
        val handler = mock(Handler::class.java)
        val entered = CountDownLatch(1)
        val finishLoad = CountDownLatch(1)
        val posted = CountDownLatch(expectedPosts)
        val callbacks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val manager: SpeechRecognitionManager

        init {
            whenever(backend.initialize()).thenAnswer {
                entered.countDown()
                check(finishLoad.await(5, TimeUnit.SECONDS)) { "test did not release preload" }
                true
            }
            whenever(handler.post(any<Runnable>())).thenAnswer {
                callbacks.add(it.getArgument(0))
                posted.countDown()
                true
            }
            manager = SpeechRecognitionManager(mock(Context::class.java), handler) { backend }
        }

        fun beginBlockedLoad() {
            manager.startRecognition()
            assertTrue("backend must be inside initialize", entered.await(5, TimeUnit.SECONDS))
        }

        fun assertReturnsWithoutFinishingLoad(action: () -> Unit) {
            val caller = Executors.newSingleThreadExecutor()
            try {
                caller.submit { action() }.get(1, TimeUnit.SECONDS)
                assertEquals("preload should still be blocked", 1L, finishLoad.count)
            } finally {
                caller.shutdownNow()
            }
        }

        fun cancelled(): Boolean = manager.javaClass.getDeclaredField("loadingCancelled").run {
            isAccessible = true
            getBoolean(manager)
        }
    }

    @Test
    fun `duplicate start returns while initialize is blocked and reuses one backend`() {
        val f = Fixture()
        try {
            f.beginBlockedLoad()
            f.assertReturnsWithoutFinishingLoad { repeat(3) { f.manager.startRecognition() } }
            assertFalse(f.cancelled())
            verify(f.backend, times(1)).initialize()
        } finally {
            f.finishLoad.countDown()
            assertTrue(f.posted.await(5, TimeUnit.SECONDS))
        }
        verify(f.backend, never()).start()
    }

    @Test
    fun `cancel then retry during preload restores request without another load`() {
        val f = Fixture()
        try {
            f.beginBlockedLoad()
            f.manager.cancelRecognition()
            assertTrue(f.cancelled())
            f.assertReturnsWithoutFinishingLoad { f.manager.startRecognition() }
            assertFalse("retry must restore recording intent", f.cancelled())
            verify(f.backend, times(1)).initialize()
        } finally {
            f.finishLoad.countDown()
            assertTrue(f.posted.await(5, TimeUnit.SECONDS))
        }
        verify(f.backend, never()).start()
    }

    @Test
    fun `cancel after completion was queued still prevents recording`() {
        assertQueuedCancellation { it.cancelRecognition() }
    }

    @Test
    fun `stop after completion was queued still prevents recording`() {
        assertQueuedCancellation { it.stopRecognition() }
    }

    private fun assertQueuedCancellation(cancel: (SpeechRecognitionManager) -> Unit) {
        val f = Fixture()
        f.beginBlockedLoad()
        f.finishLoad.countDown()
        assertTrue(f.posted.await(5, TimeUnit.SECONDS))
        cancel(f.manager)
        assertTrue(f.cancelled())
        while (true) (f.callbacks.poll() ?: break).run()
        assertEquals(RecognitionState.IDLE, f.manager.getState())
        verify(f.backend, never()).start()
    }

    @Test
    fun `release during load rejects publication and releases candidate`() {
        val f = Fixture()
        f.beginBlockedLoad()
        f.manager.release()
        f.finishLoad.countDown()
        assertTrue(f.posted.await(5, TimeUnit.SECONDS))
        while (true) (f.callbacks.poll() ?: break).run()
        verify(f.backend, timeout(5_000)).release()
        verify(f.backend, never()).start()
        val backendField = f.manager.javaClass.getDeclaredField("backend").apply { isAccessible = true }
        assertNull(backendField.get(f.manager))
        assertFalse(f.manager.preload())
    }

    @Test
    fun `concurrent preload shares initialized backend`() {
        val f = Fixture()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Boolean> { f.manager.preload() }
            assertTrue(f.entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Boolean> { f.manager.preload() }
            f.finishLoad.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertTrue(second.get(5, TimeUnit.SECONDS))
            verify(f.backend, times(1)).initialize()
        } finally {
            f.finishLoad.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `cancel without retry keeps completed load from recording`() {
        val f = Fixture(expectedPosts = 2)
        try {
            f.beginBlockedLoad()
            f.manager.cancelRecognition()
            assertTrue(f.cancelled())
        } finally {
            f.finishLoad.countDown()
            assertTrue(f.posted.await(5, TimeUnit.SECONDS))
        }
        f.callbacks.forEach { it.run() }
        assertEquals(RecognitionState.IDLE, f.manager.getState())
        verify(f.backend, never()).start()
    }
}
