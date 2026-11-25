package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.http.BlobStore
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * M07 回归：下载 blob 必须有插件侧释放入口，且 runtime 关闭时回收该插件全部 blob。
 *
 * 回归背景：`host.http.download` 2xx 会把响应体落盘为 blob 并登记（owned=true），
 * 但 SDK 里**没有任何释放入口** —— 插件下载完的临时文件只能等 24 小时残留清理或
 * 容量回收。PluginBlobStore 只有 32 个句柄槽位，一个反复下载的插件占满后，
 * 其它插件的 download / backup.pull 全部失败（`blob 句柄已达上限`）。
 */
class JsHttpBlobReleaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pluginId = "com.kingzcheung.xime.plugin.blob_test"

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    /** 模拟 app 层 PluginBlobStore 的槽位语义：登记即占槽，release 归还。 */
    private class FakeBlobStore : BlobStore {
        val files = mutableMapOf<String, File>()
        val owners = mutableMapOf<String, String>()
        val released = mutableListOf<String>()

        override fun createTempFile(ownerPluginId: String): File =
            File.createTempFile("blob-", ".tmp", null)

        override fun register(ownerPluginId: String, file: File, owned: Boolean): String {
            val id = UUID.randomUUID().toString()
            files[id] = file
            owners[id] = ownerPluginId
            return id
        }

        override fun resolve(pluginId: String, id: String): File? =
            files[id]?.takeIf { owners[id] == pluginId && it.isFile }

        override fun release(id: String) {
            released += id
            files.remove(id)?.delete()
            owners.remove(id)
        }

        override fun releaseAll(ownerPluginId: String): Int {
            val ids = owners.filterValues { it == ownerPluginId }.keys.toList()
            ids.forEach { release(it) }
            return ids.size
        }
    }

    /** 宿主实现：download 落盘登记，并提供 release 能力（与 HttpHostApiImpl 同口径）。 */
    private class DownloadingHttpApi(private val store: FakeBlobStore, private val owner: String) : HttpHostApi {
        var releaseAllCalls = 0

        override fun request(
            method: String, url: String, headers: Map<String, String>,
            body: ByteArray?, timeoutMillis: Int?
        ): HttpResponse? = HttpResponse(200)

        override fun download(
            method: String, url: String, headers: Map<String, String>, timeoutMillis: Int?
        ): HttpResponse? {
            val temp = store.createTempFile(owner)
            temp.writeBytes(byteArrayOf(1, 2, 3))
            val id = store.register(owner, temp, owned = true)
            return HttpResponse(status = 200, blobId = id, size = 3)
        }

        override fun releaseBlob(blobId: String): Boolean {
            if (blobId.isEmpty()) return true
            // 只允许释放本插件名下的句柄（跨插件释放等于越权）
            if (store.resolve(owner, blobId) == null) return true
            store.release(blobId)
            return true
        }

        override fun releaseAllBlobs(): Int {
            releaseAllCalls++
            return store.releaseAll(owner)
        }

        override fun lastError(): String? = null
    }

    private val pluginJs = """
        globalThis.plugin = {
          // 下载后立即释放（插件侧唯一能归还槽位的路径）
          downloadAndRelease: async function () {
            var r = await host.http.download('GET', 'https://dav.example.com/x.zip', {});
            await host.http.releaseBlob(r.blobId);
            return r.blobId;
          },
          // 只下载，不释放（用于验证卸载回收）
          downloadOnly: async function () {
            var r = await host.http.download('GET', 'https://dav.example.com/y.zip', {});
            return r.blobId;
          },
          releaseBogus: async function () {
            await host.http.releaseBlob('no-such-blob');
            return 'ok';
          }
        };
    """.trimIndent()

    private fun makeRuntime(api: HttpHostApi, dirName: String = "blob-release"): JsScriptRuntime {
        val dir = tmp.newFolder(dirName)
        File(dir, "main.js").writeText(pluginJs)
        return JsScriptRuntime(
            pluginId = pluginId,
            pluginDir = dir,
            entryScript = "main.js",
            configStore = InMemoryConfigStore(),
            httpHostApi = api
        )
    }

    @Test(timeout = 60_000)
    fun `插件可显式释放下载句柄，槽位立即归还`() {
        val store = FakeBlobStore()
        val runtime = makeRuntime(DownloadingHttpApi(store, pluginId))
        try {
            assertTrue(runtime.load())
            val blobId = runtime.callAsync("downloadAndRelease")?.toString()

            assertNotNull("下载应返回 blobId", blobId)
            assertEquals("插件侧 release 必须真正到达仓库", listOf(blobId), store.released)
            assertTrue("句柄应从仓库移除", !store.files.containsKey(blobId))
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `runtime 关闭时回收该插件全部 blob`() {
        val store = FakeBlobStore()
        val api = DownloadingHttpApi(store, pluginId)
        val runtime = makeRuntime(api, "blob-release-close")
        var blobId: String? = null
        try {
            assertTrue(runtime.load())
            blobId = runtime.callAsync("downloadOnly")?.toString()
            assertNotNull(blobId)
            assertTrue("关闭前句柄仍存在", store.files.containsKey(blobId))
        } finally {
            runtime.close()
        }

        assertEquals("close() 必须让宿主回收本插件全部句柄", 1, api.releaseAllCalls)
        assertTrue(
            "下载句柄必须被回收（否则 32 槽位被下载占满，妨碍其他插件）",
            blobId in store.released
        )
        assertTrue("句柄已注销", !store.files.containsKey(blobId))
    }

    @Test(timeout = 60_000)
    fun `释放不存在的句柄不抛异常`() {
        val store = FakeBlobStore()
        val runtime = makeRuntime(DownloadingHttpApi(store, pluginId), "blob-release-idempotent")
        try {
            assertTrue(runtime.load())
            assertEquals("幂等释放不应 reject", "ok", runtime.callAsync("releaseBogus")?.toString())
            assertTrue("未知 id 不应真的调用 release", store.released.isEmpty())
        } finally {
            runtime.close()
        }
    }
}
