package com.kingzcheung.xime.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * H11：模型下载 / 删除必须在**模型目录**级别互斥，不能只靠 ViewModel 的 activeDownloads。
 *
 * 这里模拟「下载进行中时另一个入口删除同一模型」：删除必须取消并等待该下载结束，
 * 不允许留下写回的文件、残留暂存目录或「下载完成」状态；不同模型之间不互相阻塞。
 */
class ModelMutualExclusionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun modelContext(root: File, cache: File): Context = mock<Context>().also { context ->
        whenever(context.filesDir).thenReturn(root)
        whenever(context.cacheDir).thenReturn(cache)
        // deleteModel 成功后会清本地版本记录；JVM 单测里没有真实 prefs
        val prefs = mock<android.content.SharedPreferences>()
        val editor = mock<android.content.SharedPreferences.Editor>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.remove(any())).thenReturn(editor)
        whenever(editor.commit()).thenReturn(true)
    }

    private fun model(id: String, url: String) = ModelInfo(
        id = id,
        name = "Test",
        description = "",
        category = ModelCategory.PREDICTION,
        versions = listOf(ModelVersion(files = listOf(ModelFile("model.bin", url)))),
    )

    /**
     * 只回响应头与少量正文就挂起的服务端：保证下载停在「进行中」，
     * 由测试决定何时放行，便于构造与删除交错的时间窗。
     */
    private class StallServer {
        private val socket = ServerSocket(0)
        val requestReceived = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val worker = thread(isDaemon = true, name = "stall-model-server") {
            runCatching {
                socket.use { listener ->
                    listener.accept().use { connection ->
                        connection.soTimeout = 15_000
                        val reader = BufferedReader(InputStreamReader(connection.getInputStream()))
                        while (reader.readLine()?.isNotEmpty() == true) { }
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\n\r\n".toByteArray())
                            write("partial-body".toByteArray())
                            flush()
                        }
                        requestReceived.countDown()
                        release.await(15, TimeUnit.SECONDS)
                    }
                }
            }
        }

        val url: String get() = "http://127.0.0.1:${socket.localPort}/model.bin"

        fun close() {
            release.countDown()
            runCatching { socket.close() }
            worker.join(5_000)
        }
    }

    @Test
    fun `删除进行中的同名模型下载时取消并等待且不留半成品`() = runBlocking {
        val root = temp.newFolder("files-delete-race")
        val cache = temp.newFolder("cache-delete-race")
        val context = modelContext(root, cache)
        val modelsRoot = File(root, "models")
        val modelDir = File(modelsRoot, "test-model").apply { mkdirs() }
        val installed = File(modelDir, "model.bin").apply { writeText("old-valid") }
        val server = StallServer()
        val info = model("test-model", server.url)
        val states = Collections.synchronizedList(mutableListOf<ModelDownloadState>())
        val download: Job = launch(Dispatchers.IO) {
            ModelManager.downloadModel(context, info, onProgress = { states += it })
        }
        var deleted = false
        try {
            assertTrue("下载应已开始", server.requestReceived.await(5, TimeUnit.SECONDS))
            val stagingDirs = modelDir.listFiles().orEmpty().filter { it.name.startsWith(".model-download-") }
            assertEquals("活动下载的暂存目录必须位于模型专属目录内", 1, stagingDirs.size)

            // 另一个入口（存储清理页 / 另一 VM 实例）直接删同一模型
            deleted = withTimeout(15_000) {
                withContext(Dispatchers.IO) { ModelManager.deleteModel(context, info) }
            }

            assertTrue("删除应成功", deleted)
            assertTrue("删除返回时该模型的下载任务必须已结束", download.isCompleted)
            assertFalse(
                "下载被删除取消后不得再报告完成: $states",
                states.any { it is ModelDownloadState.Complete },
            )
            assertFalse("删除后不得被下载写回文件", installed.exists())
            assertEquals(
                "不得残留下载暂存目录",
                listOf("test-model"),
                modelsRoot.list()!!.toList().sorted(),
            )
            assertTrue("旧模型文件应已被删除", modelDir.listFiles().orEmpty().isEmpty())
        } finally {
            server.close()
            download.cancel()
            download.join()
        }
    }

    @Test
    fun `删除一个模型不会被另一个模型的下载阻塞`() = runBlocking {
        val root = temp.newFolder("files-other-model")
        val cache = temp.newFolder("cache-other-model")
        val context = modelContext(root, cache)
        val modelsRoot = File(root, "models")
        val busyDir = File(modelsRoot, "busy-model").apply { mkdirs() }
        File(busyDir, "model.bin").writeText("partial")
        val otherDir = File(modelsRoot, "other-model").apply { mkdirs() }
        val otherFile = File(otherDir, "model.bin").apply { writeText("other-valid") }
        val server = StallServer()
        val busy = model("busy-model", server.url)
        val other = model("other-model", server.url)
        val download: Job = launch(Dispatchers.IO) {
            ModelManager.downloadModel(context, busy, onProgress = { })
        }
        try {
            assertTrue("下载应已开始", server.requestReceived.await(5, TimeUnit.SECONDS))
            val deleted = withTimeout(3_000) {
                withContext(Dispatchers.IO) { ModelManager.deleteModel(context, other) }
            }
            assertTrue("另一个模型的删除应立即完成", deleted)
            assertFalse("另一个模型的文件应已删除", otherFile.exists())
            assertFalse("下载中的模型不应被牵连", download.isCompleted)
            assertTrue("下载中的模型文件仍应存在", File(busyDir, "model.bin").exists())
        } finally {
            server.close()
            download.cancel()
            download.join()
        }
    }

    @Test
    fun `下载事务进行中时旧路径迁移必须避让`() = runBlocking {
        val root = temp.newFolder("files-migrate-race")
        val cache = temp.newFolder("cache-migrate-race")
        val context = modelContext(root, cache)
        // ochwpro 的旧版模型文件在 filesDir 根目录
        val legacy = File(root, "ochwpro.onnx").apply { writeText("legacy-onnx") }
        val modelDir = File(root, "models/ochwpro")
        val server = StallServer()
        val info = model("ochwpro", server.url)
        val download: Job = launch(Dispatchers.IO) {
            ModelManager.downloadModel(context, info, onProgress = { })
        }
        try {
            assertTrue("下载应已开始", server.requestReceived.await(5, TimeUnit.SECONDS))

            val migrated = ModelStorage.migrateLegacyForModel(context, "ochwpro")

            assertFalse("下载事务进行中时迁移必须避让", migrated)
            assertFalse(
                "迁移不得插进下载事务的目录",
                File(modelDir, "ochwpro.onnx").exists(),
            )
            assertEquals("legacy-onnx", legacy.readText())
        } finally {
            server.close()
            download.cancel()
            download.join()
        }
    }
}
