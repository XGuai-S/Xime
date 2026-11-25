package com.kingzcheung.xime.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.*
import org.junit.Test

class WordTableReloadGuardTest {
    @Test
    fun `input during blocked disk read rejects old counters without blocking input`() {
        val lock = ReentrantLock()
        var live = mapOf("词" to 1)
        val guard = WordTableReloadGuard(lock) { live.toMap() }
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                val base = guard.snapshot()
                val disk = live.toMap()
                reading.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                guard.publish(base) { live = disk }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertTrue(lock.tryLock())
            try { live = mapOf("词" to 2) } finally { lock.unlock() }
            resume.countDown()
            assertFalse(result.get(5, TimeUnit.SECONDS))
            assertEquals(2, live["词"])
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `deletion and candidate blacklist changes reject old snapshot`() {
        val lock = ReentrantLock()
        var tables = listOf(mapOf("词" to 1), emptyMap())
        val guard = WordTableReloadGuard(lock) { tables.map { it.toMap() } }
        val base = guard.snapshot()
        lock.withLock { tables = listOf(emptyMap(), mapOf("词" to 1)) }
        assertFalse(guard.publish(base) { tables = base })
        assertTrue(tables[0].isEmpty())
        assertEquals(1, tables[1]["词"])
    }

    @Test
    fun `external version change rejects load without publishing metadata`() {
        val lock = ReentrantLock()
        var version = 1
        var loadedVersion = -1
        val guard = WordTableReloadGuard(lock) { version to mapOf("词" to 1) }
        val base = guard.snapshot()
        version = 2
        assertFalse(guard.publish(base) { loadedVersion = base.first })
        assertEquals(-1, loadedVersion)
    }

    @Test
    fun `successful publication prevents older load from overwriting it`() {
        val lock = ReentrantLock()
        var live = mapOf("词" to 1)
        val guard = WordTableReloadGuard(lock) { live.toMap() }
        val older = guard.snapshot()
        val newer = guard.snapshot()
        assertTrue(guard.publish(newer) { live = mapOf("词" to 3) })
        assertFalse(guard.publish(older) { live = mapOf("词" to 2) })
        assertEquals(3, live["词"])
    }

    @Test
    fun `host guards the reload with a version snapshot only`() {
        val source = com.kingzcheung.xime.RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt",
        ).readText()
        val snapshot = source.substringAfter("private val wordTableReloadGuard by lazy {")
            .substringBefore("private suspend fun loadWordTablesOnce()")
        // 守卫只需要「读取期间生词库是否被改动过」这一个信息。
        // 早期版本把五张表整份复制进快照，但除版本号外的字段从未被读取——
        // 白白复制数十万条词频/造词记录，每次开会话都做一次。
        assertTrue(
            "重载守卫必须只快照数据版本号",
            snapshot.contains("AutoWordStore.dataVersion()"),
        )
        for (table in listOf("candidateOrderTable", "wordFreqTable", "autoWordTemp", "autoWordFixed", "wordBlacklist")) {
            assertFalse(
                "不得再把整份 $table 复制进重载快照（无用的深拷贝）",
                snapshot.contains("$table.mapValues { it.value.toMap() }"),
            )
        }
        val load = source.substringAfter("private suspend fun loadWordTablesOnce()")
            .substringBefore("private fun maybeReloadWordTables()")
        assertTrue(load.indexOf("wordTableReloadGuard.snapshot()") < load.indexOf("CandidateOrderStore.load("))
        val publication = load.substringAfter("wordTableReloadGuard.publish(base) {")
            .substringBefore("if (!published)")
        assertTrue(publication.contains("wordTablesLoaded = true"))
        // 快照内容只有数据版本号（早期版本连五张表内容一起深拷贝，那些字段从未被读过）。
        // 这里钉住快照就是版本号本身这一形状。
        assertTrue(publication.contains("loadedAutoWordVersion = base"))
        assertTrue(load.substringAfter("if (!published)").substringBefore("if (cleanFixed !== rawFixed)")
            .contains("return@withContext false"))
        // 版本号有两个合法赋值点：
        //  - 发布块里 `loadedAutoWordVersion = base.version`（拍快照时的那一刻）
        //  - 重载收尾后 `loadedAutoWordVersion = AutoWordStore.dataVersion()`（对齐收尾落盘
        //    造成的 bump，否则会无限重载，见 WordTableReloadVersionTest）
        // 除此之外不得出现其他赋值（例如在无关路径里随手改写版本号）。
        // 只统计真正的代码赋值（行首缩进后紧跟变量名，排除注释与声明）
        val versionAssignments = source.lineSequence()
            .map { it.trim() }
            .count { line ->
                line.startsWith("loadedAutoWordVersion =") ||
                    line.startsWith("loadedAutoWordVersion=")
            }
        assertTrue(
            "版本号赋值点只允许 2 处（发布 + 收尾对齐），实际 $versionAssignments 处",
            versionAssignments == 2,
        )
    }

    @Test
    fun `unchanged load publishes all state under input lock`() {
        val lock = ReentrantLock()
        var live = 1
        val guard = WordTableReloadGuard(lock) { live }
        assertTrue(guard.publish(guard.snapshot()) {
            assertTrue(lock.isHeldByCurrentThread)
            live = 2
        })
        assertEquals(2, live)
    }
}
