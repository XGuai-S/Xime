package com.kingzcheung.xime.plugin.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PluginBlobQuotaTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(bytes: Long = 10, slots: Int = 8): PluginBlobStore =
        PluginBlobStore(File(tmp.root, "blobs"), bytes, slots)

    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    @Test fun `精确到边界且耗尽在写前拒绝`() {
        val store = store()
        val first = store.createTempFile("a")
        val second = store.createTempFile("b")
        store.openTempOutputStream("a", first).use { a ->
            store.openTempOutputStream("b", second).use { b ->
                a.write(ByteArray(6))
                rejects<IOException> { b.write(ByteArray(5)) }
                assertEquals(0L, second.length())
                b.write(ByteArray(4))
                assertEquals(10L, store.usedBytes())
                rejects<IOException> { a.write(1) }
                assertEquals(6L, first.length())
            }
        }
        assertNotNull(store.register("a", first, true))
        assertNotNull(store.register("b", second, true))
        assertEquals(2, store.entryCount())
    }

    /** 把 store 内某个 id 的 LRU 时间戳改旧，模拟「很久没用过」。 */
    private fun ageEntry(store: PluginBlobStore, id: String, ageMs: Long) {
        val f = PluginBlobStore::class.java.getDeclaredField("entries")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val entries = f.get(store) as MutableMap<String, Any>
        val entry = entries[id] ?: return
        val ts = entry.javaClass.getDeclaredField("lastUsedMs")
        ts.isAccessible = true
        // 生产侧用单调时钟（elapsedRealtime），这里必须同源，否则改系统时间的场景测不出来
        ts.setLong(entry, android.os.SystemClock.elapsedRealtime() - ageMs)
    }

    @Test fun `刚注册的句柄在宽限期内不会被自己挤掉`() {
        // 关键取舍：如果 LRU 无宽限期，槽位满时新请求会淘汰「刚注册」的句柄，
        // 调用方手里的 id 转眼失效——比直接报错更难排查。
        // 这里让两个句柄都注册好占满槽位，且都是新鲜的 → 新请求应被拒绝。
        val store = store(slots = 2)
        val a = store.createTempFile("pluginA")
        store.openTempOutputStream("pluginA", a).use { it.write(1) }
        val idA = store.register("pluginA", a, true)
        assertNotNull(idA)
        val b = store.createTempFile("pluginB")
        store.openTempOutputStream("pluginB", b).use { it.write(1) }
        val idB = store.register("pluginB", b, true)
        assertNotNull(idB)
        // 两个都新鲜（宽限期内）：新请求必须被拒绝，不得淘汰它们
        rejects<IllegalStateException> { store.createTempFile("pluginC") }
        assertNotNull("宽限期内不得淘汰刚注册的 A", store.resolve("pluginA", idA!!))
        assertNotNull("宽限期内不得淘汰刚注册的 B", store.resolve("pluginB", idB!!))
        assertTrue(a.exists())
        assertTrue(b.exists())
    }

    @Test fun `宽限期外按 LRU 淘汰最久未用的句柄`() {
        // 缺陷背景：槽位是全插件共享的有限池。某个插件反复下载却不 release 就能占满，
        // 导致其它插件的 download 全部失败——一个插件的 bug 变成全部不可用。
        // 修法：存在「确实很久没用」的旧句柄时按 LRU 自愈，而不是直接拒绝。
        val store = store(slots = 2)
        val a = store.createTempFile("pluginA")
        store.openTempOutputStream("pluginA", a).use { it.write(1) }
        val idA = store.register("pluginA", a, true)
        assertNotNull(idA)
        val b = store.createTempFile("pluginB")
        store.openTempOutputStream("pluginB", b).use { it.write(1) }
        val idB = store.register("pluginB", b, true)
        assertNotNull(idB)
        // 把 A 的时间戳推远（模拟几分钟没用），B 保持新鲜
        ageEntry(store, idA!!, ageMs = 10 * 60_000L)
        val c = store.createTempFile("pluginC")
        store.openTempOutputStream("pluginC", c).use { it.write(1) }
        assertNotNull("应淘汰最久未用的 A 而不是抛错", store.register("pluginC", c, true))
        assertNull("A 的句柄已被 LRU 淘汰", store.resolve("pluginA", idA))
        // 文件**不立即删除**：可能有插件正在锁外读它（512MB 慢网上传可达几分钟）。
        // 交给 init 里的 STALE_MILLIS 陈旧清扫，那时肯定没人再用。
        assertTrue("淘汰时不得删文件（可能正被读取），留给陈旧清扫", a.exists())
        assertNotNull("较新的 B 必须保留", store.resolve("pluginB", idB!!))
    }

    @Test fun `被 LRU 淘汰的文件在保护窗口内不被字节回收删除`() {
        // 关键：淘汰只摘句柄不删文件，但字节回收（reclaimLocked）会删所有未受保护文件。
        // 若不给保护窗口，等于把「删正在读的文件」从 LRU 路径挪到字节回收路径。
        val store = store(bytes = 100, slots = 2)
        val a = store.createTempFile("pluginA")
        store.openTempOutputStream("pluginA", a).use { it.write(ByteArray(40)) }
        val idA = store.register("pluginA", a, true)
        assertNotNull(idA)
        val b = store.createTempFile("pluginB")
        store.openTempOutputStream("pluginB", b).use { it.write(ByteArray(40)) }
        val idB = store.register("pluginB", b, true)
        assertNotNull(idB)
        // 把 A 推旧，触发 LRU 淘汰（槽位满）
        ageEntry(store, idA!!, ageMs = 10 * 60_000L)
        val c = store.createTempFile("pluginC")
        store.openTempOutputStream("pluginC", c).use { it.write(ByteArray(10)) }
        assertNotNull(store.register("pluginC", c, true))
        // A 已被淘汰、文件保留。此时制造字节压力：C 已占 10 字节，再往 c 写 60 字节
        // 会超过 100 上限 → 触发 reclaimLocked。A 在保护窗口内，不得被删。
        assertTrue("淘汰后文件仍在", a.exists())
        runCatching {
            store.openTempOutputStream("pluginC", c).use { it.write(ByteArray(60)) }
        }
        assertTrue("保护窗口内不得被字节回收删除（可能正被读取）", a.exists())
    }

    @Test fun `LRU 只淘汰 owned 句柄不碰 callerOwned`() {
        // callerOwned 是插件自己的文件，本仓只登记不删除。LRU 绝不能代删。
        val store = store(slots = 2)
        val external = File(tmp.root, "plugin-own.bin").apply { writeBytes(ByteArray(4)) }
        val idExt = store.register("pluginA", external, owned = false)
        assertNotNull(idExt)
        val b = store.createTempFile("pluginB")
        store.openTempOutputStream("pluginB", b).use { it.write(1) }
        val idB = store.register("pluginB", b, true)
        assertNotNull(idB)
        // 两个槽都占满；把 callerOwned 也改旧，验证它仍不被淘汰
        ageEntry(store, idExt!!, ageMs = 10 * 60_000L)
        ageEntry(store, idB!!, ageMs = 10 * 60_000L)
        val c = store.createTempFile("pluginC")
        store.openTempOutputStream("pluginC", c).use { it.write(1) }
        assertNotNull("应淘汰 owned 的 B，保留 callerOwned", store.register("pluginC", c, true))
        assertTrue("callerOwned 文件不得被 LRU 删除", external.exists())
        assertNotNull("callerOwned 句柄不得被淘汰", store.resolve("pluginA", idExt))
        assertNull("owned 的 B 句柄被淘汰", store.resolve("pluginB", idB))
    }

    @Test fun `并发写入共用预算且pending不被回收`() {
        val store = store(bytes = 12)
        val first = store.createTempFile("a")
        val second = store.createTempFile("b")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val success = AtomicInteger()
        val rejected = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        listOf("a" to first, "b" to second).forEach { (owner, file) ->
            thread {
                try {
                    store.openTempOutputStream(owner, file).use { output ->
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        try {
                            output.write(ByteArray(8))
                            success.incrementAndGet()
                        } catch (_: IOException) {
                            rejected.incrementAndGet()
                        }
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    done.countDown()
                }
            }
        }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
        } finally {
            start.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("writer failed", it) }
        assertEquals(1, success.get())
        assertEquals(1, rejected.get())
        assertEquals(8L, store.usedBytes())
        assertTrue(first.exists())
        assertTrue(second.exists())
        // 新申请与容量边界均不能把未登记的下载半成品当垃圾。
        val third = store.createTempFile("c")
        store.openTempOutputStream("c", third).use { output ->
            rejects<IOException> { output.write(ByteArray(5)) }
            output.write(ByteArray(4))
        }
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertEquals(12L, store.usedBytes())
    }

    @Test fun `discard和release恢复额度并使旧流失效`() {
        val store = store()
        val first = store.createTempFile("a")
        val output = store.openTempOutputStream("a", first)
        output.write(ByteArray(10))
        store.discardTempFile("a", first)
        store.discardTempFile("a", first)
        output.close()
        rejects<IOException> { output.write(1) }
        assertFalse(first.exists())
        assertEquals(0L, store.usedBytes())
        val next = store.createTempFile("b")
        store.openTempOutputStream("b", next).use { it.write(ByteArray(10)) }
        val id = store.register("b", next, true)
        store.release(id)
        store.release(id)
        assertFalse(next.exists())
        assertEquals(0L, store.usedBytes())
        assertNotNull(store.createTempFile("c"))
    }

    @Test fun `打开流不能登记且关闭后不能重开`() {
        val store = store()
        val file = store.createTempFile("a")
        val output = store.openTempOutputStream("a", file)
        output.write(1)
        rejects<IllegalStateException> { store.register("a", file, true) }
        output.close()
        rejects<IllegalStateException> { store.openTempOutputStream("a", file) }
        val id = store.register("a", file, true)
        assertEquals(file, store.resolve("a", id))
        assertNull(store.resolve("b", id))
        store.discardTempFile("a", file)
        assertTrue(file.exists())
    }

    @Test fun `pending槽限制并发句柄且登记不重复占槽`() {
        val store = store(slots = 2)
        val ready = CountDownLatch(3)
        val start = CountDownLatch(1)
        val done = CountDownLatch(3)
        val made = java.util.Collections.synchronizedList(mutableListOf<File>())
        val rejected = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        repeat(3) {
            thread {
                ready.countDown()
                try {
                    check(start.await(5, TimeUnit.SECONDS))
                    try {
                        made.add(store.createTempFile("a"))
                    } catch (_: IllegalStateException) {
                        rejected.incrementAndGet()
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    done.countDown()
                }
            }
        }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
        } finally {
            start.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("creator failed", it) }
        assertEquals(2, made.size)
        assertEquals(1, rejected.get())
        assertEquals(0, store.entryCount())
        store.openTempOutputStream("a", made[0]).close()
        val id = store.register("a", made[0], true)
        assertEquals(1, store.entryCount())
        rejects<IllegalStateException> { store.createTempFile("b") }
        store.discardTempFile("a", made[1])
        assertNotNull(store.createTempFile("b"))
        store.release(id)
    }

    @Test fun `临时文件owner隔离和任意文件discard保护`() {
        val store = store()
        val file = store.createTempFile("a")
        rejects<IllegalArgumentException> { store.openTempOutputStream("b", file) }
        rejects<IllegalArgumentException> { store.register("b", file, true) }
        rejects<IllegalArgumentException> { store.discardTempFile("b", file) }
        val external = File(tmp.root, "backup.zip").apply { writeBytes(ByteArray(100)) }
        store.discardTempFile("a", external)
        assertTrue(external.exists())
        store.openTempOutputStream("a", file).use { it.write(ByteArray(10)) }
        assertTrue(file.exists())
        store.discardTempFile("a", file)
    }

    @Test fun `root外备份不计下载额度且owned false释放后保留`() {
        val store = store()
        val backup = File(tmp.root, "backup.zip").apply { writeBytes(ByteArray(100)) }
        val id = store.register("a", backup, false)
        val file = store.createTempFile("b")
        store.openTempOutputStream("b", file).use { it.write(ByteArray(10)) }
        store.release(id)
        store.discardTempFile("a", backup)
        assertTrue(backup.exists())
        assertEquals(10L, store.usedBytes())
    }

    @Test fun `root内已有owned false可超额登记但禁止新增落盘且释放不删除`() {
        val store = store()
        val backup = File(tmp.root, "blobs/backup.zip").apply { writeBytes(ByteArray(20)) }
        val id = store.register("a", backup, false)
        rejects<IOException> { store.createTempFile("b") }
        store.release(id)
        rejects<IOException> { store.createTempFile("b") }
        assertTrue(backup.exists())
        assertEquals(20L, store.usedBytes())
    }

    @Test fun `legacy直接写入temp登记校验实际长度且失败保留pending`() {
        val store = store()
        val file = store.createTempFile("a").apply { writeBytes(ByteArray(11)) }
        rejects<IOException> { store.register("a", file, true) }
        assertTrue(file.exists())
        rejects<IOException> { store.createTempFile("b") }
        store.discardTempFile("a", file)
        val next = store.createTempFile("a").apply { writeBytes(ByteArray(10)) }
        assertNotNull(store.register("a", next, true))
    }
}
