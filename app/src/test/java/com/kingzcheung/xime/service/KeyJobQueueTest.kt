package com.kingzcheung.xime.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyJobQueueTest {
    @Test
    fun `one suspended job leaves exactly 64 waiting slots and rejects the 66th job`() = runTest {
        val queue = createKeyJobQueue()
        val releaseFirst = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val completed = mutableListOf<Int>()
        val consumer = backgroundScope.launch { consumeKeyJobs(queue) { throw it } }
        try {
            val first = backgroundScope.launch(start = CoroutineStart.LAZY) {
                started.add(0)
                releaseFirst.await()
                completed.add(0)
            }
            assertTrue(tryEnqueueKeyJob(queue, first))
            runCurrent()
            assertEquals(listOf(0), started)

            var otherWorkRan = false
            backgroundScope.launch { otherWorkRan = true }
            runCurrent()
            assertTrue("Waiting must suspend the consumer rather than block its dispatcher", otherWorkRan)

            val waiting = (1..64).map { index ->
                backgroundScope.launch(start = CoroutineStart.LAZY) {
                    started.add(index)
                    completed.add(index)
                }.also { job ->
                    assertTrue("Waiting job $index must fit", tryEnqueueKeyJob(queue, job))
                    // Give the consumer every opportunity to drain into an executor queue.
                    runCurrent()
                    assertEquals(listOf(0), started)
                }
            }
            val rejected = backgroundScope.launch(start = CoroutineStart.LAZY) { started.add(65) }
            assertFalse(tryEnqueueKeyJob(queue, rejected))
            assertTrue("Rejected LAZY children must not remain pending", rejected.isCancelled)
            assertTrue(rejected.isCompleted)
            assertTrue(completed.isEmpty())

            releaseFirst.complete(Unit)
            runCurrent()
            assertEquals((0..64).toList(), started)
            assertEquals((0..64).toList(), completed)
            assertTrue(waiting.all { it.isCompleted })
            queue.close()
            runCurrent()
            assertTrue(consumer.isCompleted)
        } finally {
            queue.cancel()
            consumer.cancel()
        }
    }

    @Test
    fun `suspension inside every job preserves complete FIFO operations`() = runTest {
        val queue = createKeyJobQueue(capacity = 3)
        val events = mutableListOf<String>()
        repeat(3) { index ->
            val job = backgroundScope.launch(start = CoroutineStart.LAZY) {
                events.add("start:$index")
                yield()
                events.add("end:$index")
            }
            assertTrue(tryEnqueueKeyJob(queue, job))
        }
        val consumer = backgroundScope.launch { consumeKeyJobs(queue) { throw it } }
        try {
            runCurrent()
            assertEquals(listOf("start:0", "end:0", "start:1", "end:1", "start:2", "end:2"), events)
            queue.close()
            runCurrent()
            assertTrue(consumer.isCompleted)
        } finally {
            queue.cancel()
            consumer.cancel()
        }
    }

    @Test
    fun `consumer cancellation cancels running and undelivered jobs`() = runTest {
        val queue = createKeyJobQueue(capacity = 2)
        val neverReleased = CompletableDeferred<Unit>()
        var firstCleaned = false
        var waitingStarts = 0
        val first = backgroundScope.launch(start = CoroutineStart.LAZY) {
            try {
                neverReleased.await()
            } finally {
                firstCleaned = true
            }
        }
        val consumer = backgroundScope.launch { consumeKeyJobs(queue) { throw it } }
        try {
            assertTrue(tryEnqueueKeyJob(queue, first))
            runCurrent()
            val waiting = List(2) {
                backgroundScope.launch(start = CoroutineStart.LAZY) { waitingStarts++ }
                    .also { assertTrue(tryEnqueueKeyJob(queue, it)) }
            }
            consumer.cancel()
            runCurrent()
            assertTrue(consumer.isCancelled)
            assertTrue(first.isCancelled)
            assertTrue(firstCleaned)
            assertTrue(waiting.all { it.isCancelled && it.isCompleted })
            assertEquals(0, waitingStarts)
        } finally {
            queue.cancel()
            consumer.cancel()
        }
    }

    @Test
    fun `cancelled queue cancels buffered LAZY jobs without starting them`() = runTest {
        val queue = createKeyJobQueue(capacity = 2)
        var starts = 0
        val waiting = List(2) {
            backgroundScope.launch(start = CoroutineStart.LAZY) { starts++ }
                .also { assertTrue(tryEnqueueKeyJob(queue, it)) }
        }
        queue.cancel()
        runCurrent()
        assertTrue(waiting.all { it.isCancelled && it.isCompleted })
        assertEquals(0, starts)
    }

    @Test
    fun `closed queue rejects and cancels a new LAZY job`() = runTest {
        val queue = createKeyJobQueue(capacity = 1)
        queue.close()
        var started = false
        val rejected = backgroundScope.launch(start = CoroutineStart.LAZY) { started = true }
        assertFalse(tryEnqueueKeyJob(queue, rejected))
        runCurrent()
        assertTrue(rejected.isCancelled)
        assertTrue(rejected.isCompleted)
        assertFalse(started)
    }

    @Test
    fun `drop tracker stays silent below threshold and reports the accumulated count once`() {
        var now = 1_000L
        val tracker = KeyJobDropTracker(noticeThreshold = 3, noticeCooldownMs = 5_000L) { now }
        assertEquals(null, tracker.onDropped())
        assertEquals(null, tracker.onDropped())
        assertEquals(3, tracker.onDropped())
        assertEquals(3L, tracker.totalDropped)
        // 刚提示过：新累计继续计，但冷却内不再打扰用户
        assertEquals(null, tracker.onDropped())
        assertEquals(null, tracker.onDropped())
        now += 5_000L
        assertEquals(3, tracker.onDropped())
        assertEquals(6L, tracker.totalDropped)
    }

    @Test
    fun `a cancelled head job does not prevent following work`() = runTest {
        val queue = createKeyJobQueue(capacity = 2)
        val cancelled = backgroundScope.launch(start = CoroutineStart.LAZY) { error("Must not start") }
        cancelled.cancel()
        var nextRan = false
        val next = backgroundScope.launch(start = CoroutineStart.LAZY) { nextRan = true }
        assertTrue(tryEnqueueKeyJob(queue, cancelled))
        assertTrue(tryEnqueueKeyJob(queue, next))
        val consumer = backgroundScope.launch { consumeKeyJobs(queue) { throw it } }
        try {
            runCurrent()
            assertTrue(nextRan)
            assertTrue(next.isCompleted)
            queue.close()
            runCurrent()
            assertTrue(consumer.isCompleted)
        } finally {
            queue.cancel()
            consumer.cancel()
        }
    }
}
