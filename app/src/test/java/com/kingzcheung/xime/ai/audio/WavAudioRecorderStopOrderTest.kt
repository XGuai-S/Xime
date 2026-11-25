package com.kingzcheung.xime.ai.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WavAudioRecorderStopOrderTest {
    @Test
    fun `stop capture runs before joining a blocking read job`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val recordingJob = launch(Dispatchers.IO) {
            try {
                readStarted.countDown()
                releaseRead.await()
            } finally {
                events += "read-finished"
            }
        }

        try {
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            withTimeout(1_000L) {
                stopCaptureThenJoin(
                    stopCapture = {
                        events += "stop"
                        releaseRead.countDown()
                    },
                    joinJob = {
                        events += "join"
                        recordingJob.cancelAndJoin()
                    },
                )
            }
            assertEquals(listOf("stop", "join", "read-finished"), events)
        } finally {
            releaseRead.countDown()
            recordingJob.cancelAndJoin()
        }
    }
}
