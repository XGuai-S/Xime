package com.kingzcheung.xime.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黑名单串行落盘回归（H06）。
 *
 * 缺陷场景：连续删除甲、乙会各 launch 一个 IO 协程。若把「当时那份表」随协程带出，
 * 两个协程取得写盘权的顺序不保证与创建顺序一致——旧快照 `{甲}` 后写就覆盖了新快照
 * `{甲,乙}`，内存仍屏蔽乙，但重启后乙从磁盘复活。
 *
 * 修法：落盘在统一 IO 域内**重新读取当前内存表**再写。
 *
 * 生产实现已合并进 `XimeInputMethodService.saveWordBlacklist()`（不再单独一个
 * Saver 类——那只是 `wordTableIo.io { 读内存表 → 写 }` 的重复抽象）。
 * 这里用等价的局部实现验证同一组不变量，并通过源码契约钉住生产接线。
 */
class WordBlacklistSerialPersistTest {
    private fun liveTable(vararg words: String): Map<String, Map<String, Int>> =
        mapOf("code" to words.associateWith { 1 })

    /**
     * 与生产同构的最小实现：IO 域内重新读取当前内存表再写。
     * 这样测试验证的是**修法的核心不变量**，而不是某个具体类的形状。
     */
    private class SerialSaver(
        private val ioLock: ReentrantLock,
        private val liveLock: ReentrantLock,
        private val read: () -> Map<String, Map<String, Int>>,
        private val persist: (Map<String, Map<String, Int>>) -> Unit,
    ) {
        fun save() = ioLock.withLock {
            liveLock.lock()
            val latest = try { read() } finally { liveLock.unlock() }
            persist(latest)
        }
    }

    @Test
    fun `older snapshot written last cannot resurrect a removed word`() {
        // 复刻缺陷时序：
        //   1. 保存 A 拿到写盘域，persist 被卡住；
        //   2. 保存 B 启动并排队等写盘域；
        //   3. 内存加入乙；
        //   4. 放行 A：A 写 {甲}；B 随后**重新读内存**得到 {甲,乙}。
        // 旧实现里 B 在排队前就读好了 {甲}，两次都写 {甲} → 磁盘回退。
        val ioLock = ReentrantLock()
        val liveLock = ReentrantLock()
        var live = liveTable("甲")
        val writes = mutableListOf<Map<String, Map<String, Int>>>()
        val persistEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val saver = SerialSaver(ioLock, liveLock, { live }, { table ->
            writes += table
            if (writes.size == 1) {
                persistEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
        })
        val first = Thread { saver.save() }
        val second = Thread { saver.save() }
        try {
            first.start()
            assertTrue(persistEntered.await(5, TimeUnit.SECONDS))
            second.start()
            // 让 B 进入排队（拿不到 ioLock），再改内存
            Thread.sleep(100)
            liveLock.withLock { live = liveTable("甲", "乙") }
            releaseFirst.countDown()
            first.join(5_000)
            second.join(5_000)
            assertFalse("两次保存都必须结束", first.isAlive || second.isAlive)
            assertEquals(2, writes.size)
            assertEquals(
                "磁盘最终内容必须等于内存最终内容（旧快照后写会复活已删的词）：writes=$writes",
                live,
                writes.last(),
            )
        } finally {
            releaseFirst.countDown()
            first.interrupt()
            second.interrupt()
        }
    }

    @Test
    fun `concurrent saves are serialized and never lose the newest word`() {
        val ioLock = ReentrantLock()
        val liveLock = ReentrantLock()
        var live = emptyMap<String, Map<String, Int>>()
        val writes = mutableListOf<Map<String, Map<String, Int>>>()
        var active = 0
        var maxActive = 0
        val saver = SerialSaver(ioLock, liveLock, { live }, { table ->
            active++
            maxActive = maxOf(maxActive, active)
            Thread.sleep(20)
            writes += table
            active--
        })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val words = listOf("甲", "乙", "丙", "丁")
            words.forEach { word ->
                liveLock.withLock {
                    val code = live["code"] ?: emptyMap()
                    live = mapOf("code" to (code + (word to 1)))
                }
                executor.submit { saver.save() }
            }
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals("写盘必须串行", 1, maxActive)
            assertEquals(words.toSet(), writes.last()["code"]?.keys)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `live lock is not held while writing`() {
        val ioLock = ReentrantLock()
        val liveLock = ReentrantLock()
        val live = liveTable("甲")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saver = SerialSaver(ioLock, liveLock, { live }, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit { saver.save() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue("写盘期间不得持有输入锁（否则阻塞上屏计数与词库保存）", liveLock.tryLock())
            liveLock.unlock()
            release.countDown()
            task.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `save failure releases the writer lock for the next save`() {
        val ioLock = ReentrantLock()
        val liveLock = ReentrantLock()
        var attempts = 0
        val saver = SerialSaver(ioLock, liveLock, { liveTable("甲") }, {
            attempts++
            if (attempts == 1) error("disk full")
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val failed = executor.submit(java.util.concurrent.Callable {
                runCatching { saver.save() }.exceptionOrNull()
            })
            assertTrue("首次写盘必须真的失败", failed.get(5, TimeUnit.SECONDS) != null)
            val next = executor.submit(java.util.concurrent.Callable {
                runCatching { saver.save() }.exceptionOrNull()
            })
            assertTrue("失败后写盘域必须释放", next.get(5, TimeUnit.SECONDS) == null)
            assertEquals(2, attempts)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `production path reads the live table inside the io domain`() {
        // 契约：生产入口不得把快照随协程带出，必须在 IO 域内重新读。
        val source = com.kingzcheung.xime.RepoFiles.mainKotlinSources
            .single { it.name == "XimeInputMethodService.kt" }
            .readText()
            .replace("\r\n", "\n")
        val body = source.substringAfter("private fun saveWordBlacklist()")
            .substringBefore("/**")
        assertTrue("必须在 IO 域内落盘", body.contains("wordTableIo.io {"))
        assertTrue("必须在 IO 域内读取当前内存表", body.contains("wordBlacklist"))
        assertFalse(
            "不得再把快照作为参数带进协程",
            body.contains("WordBlacklistStore.save(this, snapshot)"),
        )
    }
}
