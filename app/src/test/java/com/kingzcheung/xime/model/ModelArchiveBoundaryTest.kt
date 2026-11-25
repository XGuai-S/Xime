package com.kingzcheung.xime.model

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ModelArchiveBoundaryTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Entry(val name: String, val body: String = "new", val link: Byte? = null)

    private fun archive(entries: List<Entry>): ByteArray {
        val tarBytes = ByteArrayOutputStream().also { bytes ->
            TarArchiveOutputStream(bytes).use { tar ->
                for (item in entries) {
                    val entry = if (item.link == null) TarArchiveEntry(item.name, true)
                        else TarArchiveEntry(item.name, item.link, true)
                    val body = if (item.link == null) item.body.toByteArray() else byteArrayOf()
                    entry.size = body.size.toLong()
                    if (item.link != null) entry.linkName = "model.bin"
                    tar.putArchiveEntry(entry)
                    tar.write(body)
                    tar.closeArchiveEntry()
                }
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { bytes ->
            BZip2CompressorOutputStream(bytes).use { it.write(tarBytes) }
        }.toByteArray()
    }

    private suspend fun download(root: File, entries: List<Entry>): List<ModelDownloadState> {
        val bytes = archive(entries)
        val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        // 关闭 ServerSocket 不保证唤醒阻塞中的 accept()（取决于内核实现），
        // 因此用 accept 超时 + 停止标志让夹具线程确定性退出，不依赖 close() 唤醒。
        val stopping = java.util.concurrent.atomic.AtomicBoolean(false)
        listener.soTimeout = 200
        val responses = AtomicInteger()
        val worker = thread(isDaemon = true, name = "model-archive-boundary") {
            try {
                while (!stopping.get()) {
                    val socket = try {
                        listener.accept()
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    socket.use {
                        it.soTimeout = 5_000
                        val reader = it.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) { }
                        responses.incrementAndGet()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (failure: java.io.IOException) {
                if (!stopping.get() && !listener.isClosed) throw failure
            }
        }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(root)
        whenever(context.cacheDir).thenReturn(temp.newFolder())
        val states = mutableListOf<ModelDownloadState>()
        try {
            val model = ModelInfo(id = "test-model", name = "Test", description = "",
                category = ModelCategory.PREDICTION,
                versions = listOf(ModelVersion(archiveUrl = "http://127.0.0.1:${listener.localPort}/model.tar.bz2")))
            ModelDownloader.downloadModel(context, model, onProgress = { states += it })
            assertEquals("unsafe archives must not be downloaded repeatedly", 1, responses.get())
            return states
        } finally {
            stopping.set(true)
            listener.close()
            worker.join(6_000)
            assertFalse("local fixture must terminate", worker.isAlive)
        }
    }

    private suspend fun rejected(entries: (File) -> List<Entry>) {
        val root = temp.newFolder()
        val model = root.resolve("models/test-model").apply { mkdirs() }
        model.resolve("model.bin").writeText("old-valid")
        val outside = root.resolve("outside.bin").apply { writeText("outside-valid") }
        val states = download(root, entries(root))
        assertEquals("old-valid", model.resolve("model.bin").readText())
        assertEquals("outside-valid", outside.readText())
        assertTrue("invalid archive must report failure", states.last() is ModelDownloadState.Error)
        assertFalse(states.any { it is ModelDownloadState.Complete })
        assertEquals(listOf("model.bin"), model.listFiles()!!.map { it.name })
        assertEquals(listOf("test-model"), root.resolve("models").listFiles()!!.map { it.name })
    }

    @Test fun `parent segments cannot overwrite installed model outside staging`() = runBlocking {
        rejected { listOf(Entry("pkg/../../test-model/model.bin")) }
    }

    @Test fun `parent segments cannot overwrite files outside model root`() = runBlocking {
        rejected { listOf(Entry("pkg/../../../outside.bin")) }
    }

    @Test fun `absolute archive names are rejected`() = runBlocking {
        rejected { root -> listOf(Entry(root.resolve("outside.bin").absolutePath)) }
    }

    @Test fun `symbolic links are not accepted as model files`() = runBlocking {
        rejected { listOf(Entry("pkg/link.bin", link = TarConstants.LF_SYMLINK)) }
    }

    @Test fun `hard links are not accepted as model files`() = runBlocking {
        rejected { listOf(Entry("pkg/link.bin", link = TarConstants.LF_LINK)) }
    }

    @Test fun `drive qualified archive names are rejected before stripping root`() = runBlocking {
        rejected { listOf(Entry("C:/outside.bin")) }
    }

    @Test fun `backslash traversal is rejected before stripping root`() = runBlocking {
        rejected { listOf(Entry("pkg/..\\outside.bin")) }
    }

    @Test fun `special device entries are rejected`() = runBlocking {
        rejected { listOf(Entry("pkg/device", link = TarConstants.LF_FIFO)) }
    }

    @Test fun `valid earlier entries do not commit when later entry is unsafe`() = runBlocking {
        rejected { listOf(Entry("pkg/model.bin"), Entry("pkg/../../../outside.bin")) }
    }

    @Test fun `normal nested files including consecutive dots remain supported`() = runBlocking {
        val root = temp.newFolder()
        val states = download(root, listOf(Entry("pkg/model.bin", "main"), Entry("pkg/nested/a..bin", "nested")))
        assertTrue(states.last() is ModelDownloadState.Complete)
        assertEquals("main", root.resolve("models/test-model/model.bin").readText())
        assertEquals("nested", root.resolve("models/test-model/nested/a..bin").readText())
    }
}
