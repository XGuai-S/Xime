package com.kingzcheung.xime.plugin.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 宿主大文件句柄仓库：id 绑定插件、所有权语义、残留清理、体积上限常量。
 *
 * 安全边界：插件拿不到路径；跨插件引用必须解析失败（否则 A 插件可读 B 插件的备份包）。
 */
class PluginBlobStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): PluginBlobStore = PluginBlobStore(File(tmp.root, "blobs"))

    @Test
    fun `登记的 blob 仅登记方可解析`() {
        val store = newStore()
        val file = File(tmp.root, "archive.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = store.register("plugin-a", file)

        assertEquals("登记方应解析到原文件", file, store.resolve("plugin-a", id))
        assertNull("其它插件不得解析（跨插件引用拒绝）", store.resolve("plugin-b", id))
        assertNull("不存在的 id 返回 null", store.resolve("plugin-a", "no-such-id"))
    }

    @Test
    fun `owned=false 释放只注销不删文件（备份包由调用方清理）`() {
        val store = newStore()
        val file = File(tmp.root, "archive.zip").apply { writeBytes(byteArrayOf(1)) }
        val id = store.register("plugin-a", file, owned = false)

        store.release(id)

        assertTrue("文件所有权属调用方，释放后仍存在", file.isFile)
        assertNull("句柄已注销", store.resolve("plugin-a", id))
    }

    @Test
    fun `owned=true 释放连同文件一起删除（流式下载产物）`() {
        val store = newStore()
        val temp = store.createTempFile("plugin-a").apply { writeBytes(byteArrayOf(1, 2)) }
        val id = store.register("plugin-a", temp, owned = true)

        assertEquals(temp, store.resolve("plugin-a", id))
        store.release(id)

        assertFalse("仓库自有文件应被删除", temp.exists())
        assertNull(store.resolve("plugin-a", id))
        store.release(id) // 幂等：重复释放不抛异常
    }

    @Test
    fun `临时文件落在仓库目录内且互不相同`() {
        val store = newStore()
        val a = store.createTempFile("plugin-a")
        val b = store.createTempFile("plugin-a")

        assertEquals(File(tmp.root, "blobs"), a.parentFile)
        assertTrue("多次申请应得到不同文件", a.name != b.name)
    }

    @Test
    fun `文件被外部删除后解析返回 null 并清理句柄`() {
        val store = newStore()
        val file = File(tmp.root, "gone.zip").apply { writeBytes(byteArrayOf(1)) }
        val id = store.register("plugin-a", file)

        assertTrue(file.delete())

        assertNull(store.resolve("plugin-a", id))
    }

    @Test
    fun `初始化清理超过保留期的残留暂存文件`() {
        val root = File(tmp.root, "blobs").apply { mkdirs() }
        val stale = File(root, "blob-old.tmp").apply { writeBytes(byteArrayOf(1)) }
        val fresh = File(root, "blob-new.tmp").apply { writeBytes(byteArrayOf(1)) }
        val old = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        assertTrue(stale.setLastModified(old))

        PluginBlobStore(root)

        assertFalse("48 小时前的残留应被清理", stale.exists())
        assertTrue("新文件保留", fresh.exists())
    }

    @Test
    fun `单次流式下载上限为 512MB`() {
        assertNotNull(PluginBlobStore.MAX_BLOB_BYTES)
        assertEquals(512L * 1024 * 1024, PluginBlobStore.MAX_BLOB_BYTES)
    }

    // ---- 总量与句柄数闸门（防「反复下载不 release」把 cache 写满）----

    private fun storeWithLimits(maxTotalBytes: Long, maxEntries: Int): PluginBlobStore =
        PluginBlobStore(File(tmp.root, "blobs-limits"), maxTotalBytes, maxEntries)

    @Test
    fun `句柄数达到上限后拒绝登记`() {
        val store = storeWithLimits(maxTotalBytes = 10_000_000, maxEntries = 2)
        val a = File(tmp.root, "a.zip").apply { writeBytes(byteArrayOf(1)) }
        val b = File(tmp.root, "b.zip").apply { writeBytes(byteArrayOf(1)) }
        store.register("plugin-a", a)
        store.register("plugin-a", b)

        var thrown = false
        try {
            store.register("plugin-a", File(tmp.root, "c.zip").apply { writeBytes(byteArrayOf(1)) })
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue("第 3 个句柄应被拒绝（要求先 release）", thrown)
        assertEquals(2, store.entryCount())
    }

    @Test
    fun `释放后可以继续登记（上限只约束同时存在的句柄）`() {
        val store = storeWithLimits(maxTotalBytes = 10_000_000, maxEntries = 1)
        val a = File(tmp.root, "a.zip").apply { writeBytes(byteArrayOf(1)) }
        val id = store.register("plugin-a", a)
        store.release(id)

        val b = File(tmp.root, "b.zip").apply { writeBytes(byteArrayOf(1)) }
        assertNotNull("释放后应能登记新句柄", store.register("plugin-a", b))
    }

    @Test
    fun `目录超总量时回收未登记文件后再放行`() {
        val store = storeWithLimits(maxTotalBytes = 1000, maxEntries = 8)
        val root = File(tmp.root, "blobs-limits")
        // 两个 600 字节的未登记垃圾（模拟崩溃残留），合计 1200 > 1000
        val old = File(root, "blob-old.tmp").apply { writeBytes(ByteArray(600)) }
        assertTrue(old.setLastModified(System.currentTimeMillis() - 60_000))
        File(root, "blob-new.tmp").apply { writeBytes(ByteArray(600)) }

        assertNotNull("回收未登记文件后应能继续申请", store.createTempFile("plugin-a"))
        assertFalse("最旧的未登记文件应被回收", old.exists())
    }

    @Test
    fun `已登记的 blob 不会被容量回收误删`() {
        val store = storeWithLimits(maxTotalBytes = 1000, maxEntries = 8)
        val root = File(tmp.root, "blobs-limits")
        root.mkdirs()
        // 已登记文件（owned=false，调用方持有）比未登记垃圾更旧，但必须保留
        val kept = File(root, "kept.zip").apply { writeBytes(ByteArray(600)) }
        assertTrue(kept.setLastModified(System.currentTimeMillis() - 120_000))
        store.register("plugin-a", kept, owned = false)
        File(root, "junk.tmp").apply { writeBytes(ByteArray(600)) }

        assertNotNull(store.createTempFile("plugin-a"))
        assertTrue("已登记文件有活句柄，不得删除", kept.isFile)
        assertNull("未登记垃圾应被回收", File(root, "junk.tmp").takeIf { it.exists() })
    }

    @Test
    fun `目录仍超限时拒绝新的落盘（不产生半成品）`() {
        val store = storeWithLimits(maxTotalBytes = 100, maxEntries = 8)
        val root = File(tmp.root, "blobs-limits")
        root.mkdirs()
        // 全部是已登记文件（回收动不了），总量已超限
        val big = File(root, "big.zip").apply { writeBytes(ByteArray(200)) }
        store.register("plugin-a", big, owned = false)

        var thrown = false
        try {
            store.createTempFile("plugin-a")
        } catch (e: java.io.IOException) {
            thrown = true
        }
        assertTrue("超限应拒绝新落盘（由调用方转成 lastError）", thrown)
    }

    @Test
    fun `总量与句柄上限常量符合预期`() {
        assertEquals(1024L * 1024 * 1024, PluginBlobStore.MAX_TOTAL_BYTES)
        assertEquals(32, PluginBlobStore.MAX_ENTRIES)
    }
}
