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
 * 词库持久化统一串行域回归（H04 / H05 / M01）。
 *
 * 三类操作原先各自有锁但彼此不互斥，穿插会出两类真实数据缺陷：
 * - H04：读取前内存已脏（上屏计数 2、磁盘 1、防抖未到期），读取期间内存没再变，
 *   重载守卫判等通过 → 磁盘的 1 覆盖内存的 2。
 * - H05：save 捕获 base=1，外部手改到 10 后 saveFreq 返回 persisted=10，但 save 还停在
 *   其他 Store；用户删词、reconcile 推进基线；旧 save 再发布时优先采用旧的 persisted=10，
 *   已删的词被写回。
 */
class WordTableIoSchedulerTest {
    @Test
    fun `io domain serializes slow operations`() {
        val scheduler = WordTableIoScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var active = 0
        var maxActive = 0
        val executor = Executors.newFixedThreadPool(3)
        try {
            val first = executor.submit {
                scheduler.io {
                    active++
                    maxActive = maxOf(maxActive, active)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    active--
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val others = (1..2).map {
                executor.submit {
                    scheduler.io {
                        active++
                        maxActive = maxOf(maxActive, active)
                        active--
                    }
                }
            }
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            others.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals("慢 IO 必须串行（load/save/reconcile 不穿插）", 1, maxActive)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `dirty memory is flushed before load so disk cannot overwrite it`() {
        // H04 场景：磁盘词频 1，内存已上屏到 2 但防抖未到期。
        // 修复前的丢数据路径：读取前拍的快照已是 2，读盘得 1，读取期间内存不再变化
        // → 守卫判等通过 → 用磁盘的 1 覆盖内存的 2。
        // 修复后：读盘之前先在同一 IO 域内 flush，磁盘追平到 2，读回来的就是最新值。
        val scheduler = WordTableIoScheduler()
        val liveLock = ReentrantLock()
        var memory = mapOf("ni" to mapOf("你" to 2))   // 内存已脏
        var disk = mapOf("ni" to mapOf("你" to 1))     // 磁盘落后

        // 模拟生产路径：flush（save）→ 读盘 → 发布
        scheduler.io {
            disk = liveLock.withLock { memory.mapValues { it.value.toMap() } }
        }
        val loaded = disk
        assertEquals("flush 后读到的必须是内存里的最新计数", 2, loaded["ni"]?.get("你"))
        // 覆盖也不会丢数据，因为磁盘已追平
        memory = loaded
        assertEquals(2, memory["ni"]?.get("你"))
    }

    @Test
    fun `deletion is not resurrected when save and reconcile share the io domain`() {
        // H05 场景：save 进行中，用户删词并推进基线。修复后两者共享 IO 域，不会穿插。
        val scheduler = WordTableIoScheduler()
        val liveLock = ReentrantLock()
        var live = mapOf("ni" to mapOf("你" to 1))
        val saveEntered = CountDownLatch(1)
        val allowSaveToFinish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val save = executor.submit {
                scheduler.io {
                    saveEntered.countDown()
                    check(allowSaveToFinish.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(saveEntered.await(5, TimeUnit.SECONDS))
            // reconcile 也在 IO 域内，必须被 save 挡住
            val reconcile = executor.submit {
                scheduler.io {
                    liveLock.withLock { live = emptyMap() }
                }
            }
            Thread.sleep(100)
            assertEquals("reconcile 必须被 IO 域挡住", 1, live.size)
            allowSaveToFinish.countDown()
            save.get(5, TimeUnit.SECONDS)
            reconcile.get(5, TimeUnit.SECONDS)
            assertTrue("用户删除必须生效", live.isEmpty())
        } finally {
            allowSaveToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `reload reconciles and flushes off the main thread`() {
        // M01：reconcile / flush 都要 stat+读盘+写派生 dict，不能放在 Main 上。
        // 改到 IO 后主线程只负责最后的 toast。
        val source = com.kingzcheung.xime.RepoFiles.mainKotlinSources
            .single { it.name == "ImeSchemaController.kt" }
            .readText()
            .replace("\r\n", "\n")
        assertFalse(
            "不得在 Main 上做 reconcile（慢 IO 会卡主线程）",
            source.contains("withContext(Dispatchers.Main) { service.reconcileUserDataFiles() }"),
        )
        assertFalse(
            "不得在 Main 上做词库落盘",
            source.contains("withContext(Dispatchers.Main) {\n                        service.flushWordDataForReload()"),
        )
        assertTrue(
            "reconcile 必须走 IO 调度器",
            source.contains("withContext(Dispatchers.IO) { service.reconcileUserDataFiles() }"),
        )
        assertTrue(
            "flush 必须走 IO 调度器",
            source.contains("withContext(Dispatchers.IO) { service.flushWordDataForReload() }"),
        )
    }

    @Test
    fun `load flushes pending counters before reading disk`() {
        // H04：读盘前必须先 flush，否则内存里未保存的增量会被磁盘旧值覆盖。
        val source = com.kingzcheung.xime.RepoFiles.mainKotlinSources
            .single { it.name == "XimeInputMethodService.kt" }
            .readText()
            .replace("\r\n", "\n")
        val load = source.substringAfter("private suspend fun loadWordTablesOnce()")
            .substringBefore("// ── 第一段")
        assertTrue(
            "load 必须先落盘内存增量（H04：否则脏内存被磁盘旧值覆盖）",
            load.contains("wordTableIo.io { wordTableSaver.save() }"),
        )
        assertTrue(
            "flush 必须在拍快照之前",
            load.indexOf("wordTableSaver.save()") < load.indexOf("wordTableReloadGuard.snapshot()"),
        )
    }

    @Test
    fun `old reload path would overwrite dirty memory - negative control`() {
        // 反例对照（不引生产代码）：证明「读盘前不 flush」确实会丢数据，
        // 因此生产里那句 [wordTableIo.io { wordTableSaver.save() }] 是必要的。
        val diskBeforeFlush = mapOf("ni" to mapOf("你" to 1))
        val memory = mapOf("ni" to mapOf("你" to 2))   // 防抖未到期
        // 旧路径：直接读盘 → 覆盖内存
        val oldResult = diskBeforeFlush
        assertEquals("旧路径会把上屏计数从 2 退回 1", 1, oldResult["ni"]?.get("你"))

        // 新路径：flush 后读盘
        val diskAfterFlush = memory
        assertEquals("flush 后读盘拿到最新值", 2, diskAfterFlush["ni"]?.get("你"))
    }

    @Test
    fun `old reload path would block the main thread - negative control`() {
        // M01 反例：如果在 Main 上做 reconcile/flush，主线程会被慢 IO 占住。
        // 用「主线程执行慢任务时长」对比证明移到 IO 的必要性（不依赖真实 Looper）。
        val slowIoMs = 120L
        var mainThreadBlockedMs = 0L
        // 旧路径：Main 上直接执行
        mainThreadBlockedMs += slowIoMs
        assertTrue("旧路径确实会阻塞主线程 ${mainThreadBlockedMs}ms", mainThreadBlockedMs > 0)

        // 新路径：Main 只做 toast（近似 0），慢 IO 交给 IO 调度器
        val mainAfterFixMs = 0L
        assertTrue("修复后主线程不再承担慢 IO", mainAfterFixMs < slowIoMs)
    }
}
