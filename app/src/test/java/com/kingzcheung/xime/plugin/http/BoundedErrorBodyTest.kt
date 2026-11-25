package com.kingzcheung.xime.plugin.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * M08 回归：SSE / WS 失败路径必须**有界读取**错误响应正文。
 *
 * 回归背景：`response.body.string().take(300)` 先把整个错误页读进堆再截断。
 * 错误页大小由服务端决定（网关/反代/恶意端可返回几百 MB），常驻输入法进程会 OOM。
 * 这里用「未知长度 + 持续产出的流」证明读取字节数被上限卡住，而不是读完再截。
 */
class BoundedErrorBodyTest {

    /** 未知 Content-Length、可产出 10MB 的响应体（模拟网关大错误页）。 */
    private class EndlessBody : okhttp3.ResponseBody() {
        @Volatile
        var produced = 0L

        override fun contentType() = "text/plain".toMediaType()

        override fun contentLength() = -1L

        /** 记录实际被读走的字节数：读取量超过上限即说明没有有界读取。 */
        override fun source(): okio.BufferedSource = object : okio.Source {
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

    private fun body(text: String) = text.toResponseBody("text/plain".toMediaType())

    @Test
    fun `超大错误体只读上限字节，不整体读入`() {
        val endless = EndlessBody()

        val text = BoundedErrorBody.readErrorBody(endless)

        // okio 按 8KB 段缓冲：request(2KB) 最多向底层要一个段，故上界是 8KB 而非恰好 2KB
        assertTrue(
            "读取量必须被上限卡住（旧实现 body.string() 会读完 10MB）：实际 ${endless.produced}",
            endless.produced <= 8L * 1024
        )
        assertEquals(BoundedErrorBody.MAX_ERROR_BODY_BYTES, text!!.length)
    }

    /**
     * 红测敏感性证明（不改生产代码）：同一 10MB 错误体，**旧表达式**
     * `body.string().take(300)` 会把整个正文读完，而新实现只读有界字节。
     */
    @Test
    fun `旧实现 body_string 会读完整个错误体（对照）`() {
        val endless = EndlessBody()

        // 旧实现（SseHostApiImpl 曾用的写法）
        val old = endless.string().take(300)

        assertEquals("旧实现读完整个错误体：全为 A", "${"A".repeat(300)}", old)
        assertTrue(
            "旧实现读取量远超 2KB 上限（实际 ${endless.produced} 字节）",
            endless.produced > BoundedErrorBody.MAX_ERROR_BODY_BYTES
        )
    }

    @Test
    fun `摘要截断到安全长度且压成单行`() {
        val long = "X".repeat(BoundedErrorBody.MAX_ERROR_BODY_BYTES)
        val summary = BoundedErrorBody.summarize(long)!!

        assertEquals(
            "摘要长度应为上限 + 省略号",
            BoundedErrorBody.MAX_SUMMARY_CHARS + 1, summary.length
        )
        assertTrue("摘要必须以省略号结尾", summary.endsWith("…"))
    }

    @Test
    fun `失败消息只含状态码与单行安全摘要`() {
        val message = BoundedErrorBody.failureMessage(
            503,
            body("upstream   error\n\tline2  <html>secret-token=abc</html>")
        )

        assertEquals("HTTP 503: upstream error line2 <html>secret-token=abc</html>", message)
        assertTrue("不得保留原始换行（多行内容会放大日志泄露面）", !message.contains("\n"))
    }

    @Test
    fun `读不到正文时只报 HTTP 码`() {
        assertNull("空正文返回 null", BoundedErrorBody.readErrorBody(body("")))
        assertEquals("HTTP 502", BoundedErrorBody.failureMessage(502, body("")))
        assertEquals("HTTP 502", BoundedErrorBody.failureMessage(502, null))
    }

    @Test
    fun `正文读取抛错时降级为只报状态码`() {
        val broken = object : okhttp3.ResponseBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength() = 1000L
            override fun source(): okio.BufferedSource = throw IOException("stream closed")
        }

        assertEquals("HTTP 500", BoundedErrorBody.failureMessage(500, broken))
    }

    @Test
    fun `readErrorBody never returns more than the byte cap`() {
        // 实测前提：okio 的 request(2048) 会拉满一个 segment（8KB），
        // 因此必须核对「对外可见的读取结果」严格不超过 MAX_ERROR_BODY_BYTES。
        val body = "x".repeat(64 * 1024).toResponseBody("text/plain".toMediaType())
        val text = BoundedErrorBody.readErrorBody(body)
        assertNotNull("大正文应能读到内容", text)
        assertTrue(
            "读取结果不得超过上限 ${BoundedErrorBody.MAX_ERROR_BODY_BYTES}，实际 ${text!!.length}",
            text.length <= BoundedErrorBody.MAX_ERROR_BODY_BYTES,
        )
    }

    @Test
    fun `failureMessage stays a single short line`() {
        val body = ("a\nb\nc\n" + "z".repeat(5000)).toResponseBody("text/plain".toMediaType())
        val message = BoundedErrorBody.failureMessage(502, body)
        assertTrue("必须带 HTTP 码", message.startsWith("HTTP 502"))
        assertFalse("必须是单行（不能把多行错误页搬进日志）", message.contains('\n'))
        assertTrue(
            "摘要长度必须受限",
            message.length <= BoundedErrorBody.MAX_SUMMARY_CHARS + "HTTP 502: ".length + 1,
        )
    }
}
