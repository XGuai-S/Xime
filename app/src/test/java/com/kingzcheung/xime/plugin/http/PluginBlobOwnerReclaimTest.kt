package com.kingzcheung.xime.plugin.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * M07 回归：blob 句柄槽位是**全插件共享**的，插件卸载必须能整体回收。
 *
 * 回归背景：下载 2xx 会 `register(owned=true)` 占一个槽位，而 `PluginBlobStore` 只有
 * 32 个句柄。旧实现既没有插件侧释放入口，也没有按插件回收——一个反复下载的插件
 * 卸载后仍占着槽位与磁盘文件，之后**所有插件**的 download / backup.pull 都会失败
 * （`blob 句柄已达上限（32），请先 release 不再使用的句柄`）。
 */
class PluginBlobOwnerReclaimTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(maxEntries: Int = 4): PluginBlobStore =
        PluginBlobStore(File(tmp.root, "blobs"), maxTotalBytes = 64L * 1024 * 1024, maxEntries = maxEntries)

    private fun downloadedFile(store: PluginBlobStore, owner: String): String {
        val temp = store.createTempFile(owner)
        store.openTempOutputStream(owner, temp).use { it.write(ByteArray(16)) }
        return store.register(owner, temp, owned = true)
    }

    @Test
    fun `卸载插件后其 blob 槽位全部归还，其他插件可继续下载`() {
        val store = newStore(maxEntries = 2)
        val ids = List(2) { downloadedFile(store, "plugin-a") }
        assertEquals("plugin-a 占满 2 个槽位", 2, store.entryCount())

        // 旧实现：没有按插件回收 → plugin-b 直接撞上限
        assertEquals("回收 plugin-a 的 2 个句柄", 2, store.releaseAll("plugin-a"))

        assertEquals("槽位已归还", 0, store.entryCount())
        ids.forEach { assertNull("句柄已注销", store.resolve("plugin-a", it)) }
        // 磁盘文件也应释放（owned=true 且仓库自有）
        assertEquals("仓库目录不应残留文件", 0, File(tmp.root, "blobs").walkTopDown().count { it.isFile })

        val other = downloadedFile(store, "plugin-b")
        assertEquals("其他插件应能继续下载", other, other)
        assertEquals("plugin-b 占 1 个槽位", 1, store.entryCount())
        assertTrue(store.resolve("plugin-b", other) != null)
    }

    @Test
    fun `回收只影响目标插件`() {
        val store = newStore(maxEntries = 8)
        val a = downloadedFile(store, "plugin-a")
        val b = downloadedFile(store, "plugin-b")

        val reclaimed = store.releaseAll("plugin-a")

        assertEquals(1, reclaimed)
        assertNull("plugin-a 的句柄已注销", store.resolve("plugin-a", a))
        assertTrue("plugin-b 的句柄必须保留", store.resolve("plugin-b", b) != null)
    }

    @Test
    fun `回收包含未登记的半成品下载文件`() {
        val store = newStore(maxEntries = 8)
        // 下载中断（未 register）留下的 pending 文件：owner 已不存在，只能靠按插件回收清掉
        val pending = store.createTempFile("plugin-a")
        store.openTempOutputStream("plugin-a", pending).use { it.write(ByteArray(1024)) }
        assertTrue(pending.isFile)

        val reclaimed = store.releaseAll("plugin-a")

        assertEquals(1, reclaimed)
        assertFalse("半成品文件应被删除", pending.exists())
        assertEquals("不应残留登记项", 0, store.entryCount())
    }

    @Test
    fun `回收是无副作用的幂等操作`() {
        val store = newStore(maxEntries = 8)
        downloadedFile(store, "plugin-a")

        assertEquals(1, store.releaseAll("plugin-a"))
        assertEquals("重复回收返回 0", 0, store.releaseAll("plugin-a"))
        assertEquals("回收不存在的插件返回 0", 0, store.releaseAll("plugin-zzz"))
    }

    @Test
    fun `owned=false 的调用方文件回收时保留文件只注销句柄`() {
        val store = newStore(maxEntries = 8)
        val file = File(tmp.root, "caller-owned.zip").apply { writeBytes(ByteArray(8)) }
        val id = store.register("plugin-a", file, owned = false)

        assertEquals(1, store.releaseAll("plugin-a"))

        assertNull("句柄已注销", store.resolve("plugin-a", id))
        assertTrue("所有权属调用方，文件必须保留", file.isFile)
    }

    @Test
    fun `句柄已满时其他插件 download 会失败（缺陷后果的证据）`() {
        val store = newStore(maxEntries = 1)
        downloadedFile(store, "plugin-a")

        var failed = false
        try {
            downloadedFile(store, "plugin-b")
        } catch (e: IllegalStateException) {
            failed = true
        }
        assertTrue("plugin-a 未释放时 plugin-b 无法下载", failed)

        store.releaseAll("plugin-a")
        // 回收后可继续
        assertTrue(store.entryCount() == 0)
        downloadedFile(store, "plugin-b")
        assertEquals(1, store.entryCount())
    }

    @Test
    fun `caller owned file is never deleted by the store`() {
        val dir = kotlin.io.path.createTempDirectory("blob-caller-live").toFile()
        try {
            val store = PluginBlobStore(dir, maxTotalBytes = Long.MAX_VALUE)
            val external = File(dir, "plugin-live.bin").apply { writeBytes(ByteArray(512)) }
            store.register("p1", external, owned = false)
            val protectedSet = store.javaClass
                .getDeclaredField("callerOwned")
                .apply { isAccessible = true }
                .let { field ->
                    @Suppress("UNCHECKED_CAST")
                    (field.get(store) as Set<String>)
                }
            assertTrue(
                "插件自带文件必须始终受保护（本仓只登记、绝不删除）",
                protectedSet.any { it.contains("plugin-live.bin") },
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
