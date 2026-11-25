package com.kingzcheung.xime.association

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingUnloadWaitTest {
    @Test
    fun `no pending unload returns immediately without timeout callback`() = runTest {
        var timeouts = 0
        val result = awaitPendingUnload(null, 3_000L) { timeouts++ }
        assertTrue(result)
        assertEquals(0, timeouts)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `already finished unload returns true without waiting`() = runTest {
        var timeouts = 0
        val job = Job().also { it.complete() }
        val result = awaitPendingUnload(job, 3_000L) { timeouts++ }
        assertTrue(result)
        assertEquals(0, timeouts)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `unload that finishes within the limit is waited for in full`() = runTest {
        var timeouts = 0
        var unloadDone = false
        val job = launch {
            delay(1_000L)
            unloadDone = true
        }
        val result = awaitPendingUnload(job, 3_000L) { timeouts++ }
        assertTrue(result)
        // 关键：返回时卸载必须已经结束，否则晚到的卸载会卸掉新模型
        assertTrue(unloadDone)
        assertEquals(0, timeouts)
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun `unload that exceeds the limit times out once and does not block further`() = runTest {
        var timeouts = 0
        val gate = CompletableDeferred<Unit>()
        val job = launch { gate.await() }
        val result = awaitPendingUnload(job, 3_000L) { timeouts++ }
        assertFalse(result)
        assertEquals(1, timeouts)
        assertEquals(3_000L, currentTime)
        // 超时只放弃等待，不取消卸载任务本身
        assertTrue(job.isActive)
        gate.complete(Unit)
    }
}
