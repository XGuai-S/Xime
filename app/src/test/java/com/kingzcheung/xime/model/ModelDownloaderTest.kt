package com.kingzcheung.xime.model

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

class ModelDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `complete response atomically replaces destination and closes response`() = runBlocking {
        val directory = temp.newFolder("model")
        val target = directory.resolve("model.bin").apply { writeText("old-valid") }
        val body = TrackingBody("new-valid-model".toByteArray())

        ModelDownloader.downloadResponseToFile(response(body), target)

        assertEquals("new-valid-model", target.readText())
        assertTrue(body.closed)
        assertEquals(listOf("model.bin"), directory.listFiles()?.map { it.name })
    }

    @Test
    fun `failed body keeps prior destination and removes partial file`() = runBlocking {
        val directory = temp.newFolder("failed-model")
        val target = directory.resolve("model.bin").apply { writeText("old-valid") }
        val body = TrackingBody("partial-new-data".toByteArray(), failAfterFirstRead = true)

        val failure = runCatching { ModelDownloader.downloadResponseToFile(response(body), target) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("old-valid", target.readText())
        assertTrue(body.closed)
        assertEquals(listOf("model.bin"), directory.listFiles()?.map { it.name })
    }

    @Test
    fun `non-success response is closed and does not replace destination`() = runBlocking {
        val directory = temp.newFolder("http-error")
        val target = directory.resolve("model.bin").apply { writeText("old-valid") }
        val body = TrackingBody("not-model".toByteArray())

        val failure = runCatching {
            ModelDownloader.downloadResponseToFile(response(body, code = 503), target)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("old-valid", target.readText())
        assertTrue(body.closed)
        assertEquals(listOf("model.bin"), directory.listFiles()?.map { it.name })
    }

    @Test
    fun `cancellation propagates through downloadModel without replacing model`() = runBlocking {
        val root = temp.newFolder("files")
        val cache = temp.newFolder("cache")
        val modelDir = root.resolve("models/test-model").apply { mkdirs() }
        val target = modelDir.resolve("model.bin").apply { writeText("old-valid") }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(root)
        whenever(context.cacheDir).thenReturn(cache)
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val url = serveOnce(payload)
        val model = ModelInfo(
            id = "test-model",
            name = "Test model",
            description = "",
            category = ModelCategory.PREDICTION,
            versions = listOf(
                ModelVersion(files = listOf(ModelFile("model.bin", url)))
            )
        )
        val states = mutableListOf<ModelDownloadState>()
        lateinit var download: Job
        download = launch {
            ModelDownloader.downloadModel(context, model, onProgress = { state ->
                states += state
                if (state is ModelDownloadState.Downloading) download.cancel()
            })
        }
        download.join()

        assertTrue("cancelled job must remain cancelled", download.isCancelled)
        assertFalse("cancellation must not become a normal error state", states.any { it is ModelDownloadState.Error })
        assertFalse("cancellation must not report completion", states.any { it is ModelDownloadState.Complete })
        assertEquals("old-valid", target.readText())
        assertEquals(listOf("model.bin"), modelDir.listFiles()?.map { it.name })
    }

    @Test
    fun `second file failure keeps whole previous model`() = runBlocking {
        val root = temp.newFolder("multi-files")
        val context = modelContext(root)
        val dir = root.resolve("models/test-model").apply { mkdirs() }
        dir.resolve("first.bin").writeText("old-first")
        dir.resolve("second.bin").writeText("old-second")
        val model = testModel(ModelVersion(files = listOf(
            ModelFile("first.bin", serveOnce("new-first".toByteArray())),
            ModelFile("second.bin", serveOnce("error".toByteArray(), 503)),
        )))
        val states = mutableListOf<ModelDownloadState>()
        ModelDownloader.downloadModel(context, model, onProgress = { states += it })
        assertEquals("old-first", dir.resolve("first.bin").readText())
        assertEquals("old-second", dir.resolve("second.bin").readText())
        assertTrue(states.last() is ModelDownloadState.Error)
        assertEquals(listOf("test-model"), root.resolve("models").listFiles()!!.map { it.name })
    }

    @Test
    fun `archive failing after first entry preserves previous model`() = runBlocking {
        val root = temp.newFolder("archive-files")
        val context = modelContext(root)
        val dir = root.resolve("models/test-model").apply { mkdirs() }
        dir.resolve("first.bin").writeText("old-first")
        dir.resolve("second.bin").writeText("old-second")
        val tarBytes = ByteArrayOutputStream().also { bytes ->
            TarArchiveOutputStream(bytes).use { tar ->
                for ((name, body) in listOf("pkg/first.bin" to "new-first", "pkg/second.bin" to "new-second")) {
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = body.length.toLong() })
                    tar.write(body.toByteArray())
                    tar.closeArchiveEntry()
                }
            }
        }.toByteArray()
        // Keep first entry and second header, truncate the second entry's declared data.
        val brokenTar = tarBytes.copyOf(1536 + 2)
        val compressed = ByteArrayOutputStream().also { bytes ->
            BZip2CompressorOutputStream(bytes).use { it.write(brokenTar) }
        }.toByteArray()
        val states = mutableListOf<ModelDownloadState>()
        val url = serveOnce(compressed, requests = 3)
        ModelDownloader.downloadModel(context, testModel(ModelVersion(archiveUrl = url)), onProgress = { states += it })
        assertEquals("old-first", dir.resolve("first.bin").readText())
        assertEquals("old-second", dir.resolve("second.bin").readText())
        assertTrue(states.last() is ModelDownloadState.Error)
        assertEquals(listOf("test-model"), root.resolve("models").listFiles()!!.map { it.name })
    }

    @Test
    fun `external cancellation closes blocked socket and preserves model`() = runBlocking {
        val root = temp.newFolder("blocked-files")
        val context = modelContext(root)
        val dir = root.resolve("models/test-model").apply { mkdirs() }
        dir.resolve("model.bin").writeText("old-valid")
        val sent = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}/model.bin"
        val worker = thread(isDaemon = true) {
            server.use { listener -> listener.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val reader = BufferedReader(InputStreamReader(input))
                while (reader.readLine()?.isNotEmpty() == true) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\nx".toByteArray())
                    flush()
                }
                sent.countDown()
                try { while (input.read() >= 0) { } } finally { disconnected.countDown() }
            } }
        }
        val states = java.util.Collections.synchronizedList(mutableListOf<ModelDownloadState>())
        val download = launch(Dispatchers.IO) {
            ModelDownloader.downloadModel(context, testModel(ModelVersion(files = listOf(ModelFile("model.bin", url)))), onProgress = { states += it })
        }
        try {
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            withTimeout(3_000) { download.cancelAndJoin() }
            assertTrue(download.isCancelled)
            assertTrue(disconnected.await(3, TimeUnit.SECONDS))
            assertFalse(states.any { it is ModelDownloadState.Error || it is ModelDownloadState.Complete })
            assertEquals("old-valid", dir.resolve("model.bin").readText())
        } finally {
            download.cancel()
            server.close()
            worker.join(6_000)
        }
    }

    private fun modelContext(root: java.io.File): Context = mock<Context>().also {
        whenever(it.filesDir).thenReturn(root)
        whenever(it.cacheDir).thenReturn(temp.newFolder())
    }

    private fun testModel(version: ModelVersion) = ModelInfo(
        id = "test-model", name = "Test", description = "", category = ModelCategory.PREDICTION,
        versions = listOf(version),
    )

    private fun response(body: TrackingBody, code: Int = 200): Response =
        Response.Builder()
            .request(Request.Builder().url("http://localhost/model.bin").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Unavailable")
            .body(body)
            .build()

    private class TrackingBody(
        bytes: ByteArray,
        private val failAfterFirstRead: Boolean = false,
    ) : ResponseBody() {
        private val declaredLength = bytes.size.toLong()
        private val content = Buffer().write(bytes)
        private var readCount = 0
        var closed = false
            private set

        private val upstream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readCount++
                if (failAfterFirstRead && readCount > 1) throw IOException("simulated truncated response")
                return content.read(sink, byteCount)
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                closed = true
            }
        }
        private val buffered: BufferedSource = upstream.buffer()

        override fun source(): BufferedSource = buffered
        override fun contentLength(): Long = declaredLength
        override fun contentType(): okhttp3.MediaType? = null
    }

    private fun serveOnce(payload: ByteArray, code: Int = 200, requests: Int = 1): String {
        val server = ServerSocket(0)
        thread(isDaemon = true, name = "model-download-test-server") {
            server.use { listener ->
                repeat(requests) {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    val header = "HTTP/1.1 $code Status\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                    val output = socket.getOutputStream()
                    output.write(header)
                    output.write(payload)
                    output.flush()
                }
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}/model.bin"
    }
}
