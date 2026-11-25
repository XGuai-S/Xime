package com.kingzcheung.xime.plugin.ws

import android.content.Context
import com.kingzcheung.xime.plugin.core.js.ws.WsHostListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.IOException

/**
 * M08 回归：WS 握手失败的响应正文必须**有界读取**。
 *
 * 回归背景：`onFailure` 里 `r.body.string()` 先把整个错误页读进堆再解析。
 * 错误页大小由服务端/网关决定（可到几百 MB），常驻输入法进程会 OOM。
 * 这里用 10MB 且未知 Content-Length 的响应体，断言实际被读走的字节数不超过上限。
 */
class WsFailureBodyBoundTest {

    private fun newApi() = WsHostApiImpl(mock<Context>(), "com.kingzcheung.xime.test")

    private fun field(name: String) =
        WsHostApiImpl::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** 记录 onError 内容的监听器。 */
    private class RecordingListener : WsHostListener {
        var error: String? = null
        override fun onOpen() {}
        override fun onMessage(text: String) {}
        override fun onBinary(data: ByteArray) {}
        override fun onError(message: String) { error = message }
        override fun onClose() {}
    }

    /** 10MB 可读完的响应体（不用无限流，避免旧实现把测试挂死）。 */
    private class HugeBody : ResponseBody() {
        @Volatile
        var produced = 0L

        override fun contentType() = "text/plain".toMediaType()

        override fun contentLength() = -1L

        override fun source() = object : okio.Source {
            private val chunk = ByteArray(8 * 1024) { 'A'.code.toByte() }
            private val total = 10L * 1024 * 1024

            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                val left = total - produced
                if (left <= 0) return -1
                val n = minOf(byteCount, chunk.size.toLong(), left).toInt()
                sink.write(chunk, 0, n)
                produced += n
                return n.toLong()
            }

            override fun timeout() = okio.Timeout.NONE
            override fun close() {}
        }.buffer()
    }

    private fun failure(code: Int, body: ResponseBody): String {
        val api = newApi()
        val socket = mock<WebSocket>()
        field("webSocket").set(api, socket)
        val listener = RecordingListener()
        field("listener").set(api, listener)
        val wsListener = field("wsListener").get(api) as WebSocketListener

        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/ws").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("error")
            .body(body)
            .build()

        wsListener.onFailure(socket, IOException("handshake failed"), response)
        return listener.error!!
    }

    @Test
    fun `握手失败的 10MB 错误体只读上限字节`() {
        val body = HugeBody()
        failure(502, body)

        // okio 按 8KB 段缓冲：上限 2KB 时最多向底层要一个段，上界是 8KB 而非 10MB
        assertTrue(
            "读取量必须被上限卡住（旧实现 body.string() 会读完 10MB）：实际 ${body.produced}",
            body.produced <= 8L * 1024
        )
    }

    @Test
    fun `服务端 JSON 错误只取 error 字段，不把正文整段写进消息`() {
        val message = failure(
            401,
            """{"error":"token expired","detail":"secret=abc123"}"""
                .toResponseBody("application/json".toMediaType())
        )

        assertEquals("HTTP 401: token expired", message)
    }

    @Test
    fun `网关非 JSON 错误页只报状态码，正文不进消息`() {
        val message = failure(
            502,
            "<html>internal hostname abc-123</html>".toResponseBody("text/html".toMediaType())
        )

        assertEquals("HTTP 502", message)
    }
}
