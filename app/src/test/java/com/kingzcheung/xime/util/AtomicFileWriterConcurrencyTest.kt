package com.kingzcheung.xime.util

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 同一目标并发写：临时文件名固定为 `<name>.tmp`，无锁时多个线程交错写同一个 tmp，
 * rename 上去的是混杂内容。最终文件必须恰好是某一次完整写入。
 */
class AtomicFileWriterConcurrencyTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("atomic-writer-concurrency").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `concurrent writes to same file never interleave`() {
        val target = File(dir, "auto_word.dict.yaml")
        val threads = 8
        val rounds = 40
        // 每个线程写一份可辨识、足够大的内容，交错写入会产生不属于任何一份的文本
        val payloads = (0 until threads).map { t -> ("w$t\t" + "字".repeat(2000) + "\n").repeat(20) }
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        try {
            repeat(threads) { t ->
                pool.execute {
                    start.await()
                    repeat(rounds) {
                        if (!AtomicFileWriter.write(target, payloads[t])) failures.incrementAndGet()
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertTrue("并发写不应失败: ${failures.get()}", failures.get() == 0)
        val result = target.readText()
        assertTrue("最终内容必须是某一次完整写入", result in payloads)
        assertFalse(File(dir, "${target.name}.tmp").exists())
    }
}
