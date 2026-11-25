package com.kingzcheung.xime.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.*
import org.junit.Test

class WordTableSnapshotSaverTest {
    @Test
    fun `slow persistence does not hold input lock and newer counters are flushed`() {
        val lock = ReentrantLock()
        var live = mapOf("a" to mapOf("词" to 1))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val writes = mutableListOf<Map<String, Map<String, Int>>>()
        val saver = WordTableSnapshotSaver(lock, { live }, { snapshot ->
            if (writes.isEmpty()) {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
            writes += snapshot
            snapshot
        }, { base, persisted ->
            val changed = live != base
            live = UserDataMerge.mergeTable(base, live, persisted)
            changed
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit { saver.save() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue("input must not wait for IO", lock.tryLock())
            try { live = mapOf("a" to mapOf("词" to 2)) } finally { lock.unlock() }
            resume.countDown()
            result.get(5, TimeUnit.SECONDS)
            assertEquals(2, writes.last()["a"]?.get("词"))
            assertEquals(2, live["a"]?.get("词"))
            assertEquals(2, writes.size)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `local deletion during save is not resurrected`() {
        val lock = ReentrantLock()
        var live = mapOf("a" to mapOf("词" to 1))
        var writes = 0
        val saver = WordTableSnapshotSaver(lock, { live }, { snapshot ->
            if (writes++ == 0) lock.withLock { live = emptyMap() }
            snapshot
        }, { base, persisted ->
            val changed = live != base
            live = UserDataMerge.mergeTable(base, live, persisted)
            changed
        })
        saver.save()
        assertTrue(live.isEmpty())
        assertEquals(2, writes)
    }

    @Test
    fun `concurrent saves serialize persistence and snapshot after acquiring writer lock`() {
        val lock = ReentrantLock()
        var live = 1
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val writes = mutableListOf<Int>()
        val saver = WordTableSnapshotSaver(lock, { live }, { snapshot ->
            if (writes.isEmpty()) {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
            writes += snapshot
            snapshot
        }, { base, _ -> live != base })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { saver.save() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            lock.withLock { live = 2 }
            val second = executor.submit {
                secondStarted.countDown()
                saver.save()
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            resume.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(1, 2, 2), writes)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed persistence does not publish and releases writer lock for another thread`() {
        val lock = ReentrantLock()
        var live = 1
        var failWrite = true
        var publications = 0
        val saver = WordTableSnapshotSaver(lock, { live }, { snapshot ->
            if (failWrite) throw java.io.IOException("test write failure")
            snapshot
        }, { _, _ ->
            publications++
            false
        })
        try {
            saver.save()
            fail("save must propagate persistence failure")
        } catch (_: java.io.IOException) {
            assertEquals(0, publications)
        }
        lock.withLock { live = 2 }
        failWrite = false
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { saver.save() }.get(5, TimeUnit.SECONDS)
            assertEquals(1, publications)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `startup deduplication saves current tables through snapshot saver`() {
        val source = com.kingzcheung.xime.RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt",
        ).readText()
        val cleanup = source.substringAfter("if (cleanFixed !== rawFixed) {")
            .substringBefore("if (prunedTemp !== tempTable)")
        assertTrue(cleanup.contains("saveWordData()"))
        assertFalse(cleanup.contains("AutoWordStore.saveFixed("))
        assertFalse(cleanup.contains("wordTablesLock.lock()"))
    }

    @Test
    fun `external edit retains existing priority over concurrent local edit`() {
        val base = mapOf("a" to mapOf("词" to 1))
        val local = mapOf("a" to mapOf("词" to 2, "新" to 1))
        val persisted = mapOf("a" to mapOf("词" to 10))
        assertEquals(mapOf("a" to mapOf("词" to 10, "新" to 1)),
            UserDataMerge.mergeTable(base, local, persisted))
    }
}
