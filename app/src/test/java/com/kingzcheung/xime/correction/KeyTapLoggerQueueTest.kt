package com.kingzcheung.xime.correction

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTapLoggerQueueTest {
    @Test
    fun `bounded queue rejects diagnostics instead of growing without limit`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = AtomicInteger()
        val dropped = AtomicInteger()
        val executor = createBoundedKeyTapExecutor(queueCapacity = 2) { dropped.incrementAndGet() }

        try {
            executor.execute {
                started.countDown()
                release.await()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            executor.execute { completed.incrementAndGet() }
            executor.execute { completed.incrementAndGet() }
            executor.execute { completed.incrementAndGet() }

            assertEquals(2, executor.queue.size)
            assertEquals(1, dropped.get())
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(2, completed.get())
    }
}
