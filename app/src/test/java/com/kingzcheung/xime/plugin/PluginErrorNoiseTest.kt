package com.kingzcheung.xime.plugin

import com.kingzcheung.xime.plugin.core.security.ErrorCategory
import com.kingzcheung.xime.plugin.core.security.PluginErrorNoise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 插件错误降噪规则（纯函数）。
 *
 * 对应真机实测（2026-11-19）：一次 WebDAV 断网失败会产生
 * 「[网络失败] HTTP 请求失败 (PROPFIND …)」+「[其他] list 失败: 同一句异常」两条记录，
 * 每条带 40+ 行 okhttp 框架堆栈 —— 用户看到 4 条其实只是 2 次失败。
 */
class PluginErrorNoiseTest {

    @Test
    fun `网络类异常判定为可丢弃堆栈`() {
        assertTrue(PluginErrorNoise.isNetworkLikeCause(UnknownHostException("no addr")))
        assertTrue(PluginErrorNoise.isNetworkLikeCause(ConnectException("refused")))
        assertTrue(PluginErrorNoise.isNetworkLikeCause(SocketTimeoutException("timeout")))
        assertTrue(PluginErrorNoise.isNetworkLikeCause(SSLException("handshake")))
        assertTrue(PluginErrorNoise.isNetworkLikeCause(InterruptedIOException("io")))
    }

    @Test
    fun `网络异常被包在 cause 链里也要识别`() {
        val wrapped = RuntimeException("http failed", UnknownHostException("no addr"))
        assertTrue(PluginErrorNoise.isNetworkLikeCause(wrapped))
    }

    @Test
    fun `非网络异常保留堆栈`() {
        assertFalse(PluginErrorNoise.isNetworkLikeCause(IllegalStateException("bug")))
        assertFalse(PluginErrorNoise.isNetworkLikeCause(null))
    }

    @Test
    fun `去重键包含插件_类别_操作_消息`() {
        val a = PluginErrorNoise.dedupeKey("p1", ErrorCategory.HTTP_ERROR, "PROPFIND", "boom")
        val b = PluginErrorNoise.dedupeKey("p1", ErrorCategory.HTTP_ERROR, "PROPFIND", "boom")
        val c = PluginErrorNoise.dedupeKey("p2", ErrorCategory.HTTP_ERROR, "PROPFIND", "boom")
        val d = PluginErrorNoise.dedupeKey("p1", ErrorCategory.OTHER, "PROPFIND", "boom")
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a != d)
    }

    @Test
    fun `插件侧重述宿主已记录的异常应被识别`() {
        val hostDetail = "Unable to resolve host \"dav.jianguoyun.com\": No address associated with hostname"
        assertTrue(
            PluginErrorNoise.isRethrowOfHostError(
                hostDetail,
                "list 失败: Unable to resolve host \"dav.jianguoyun.com\": No address associated with hostname",
            ),
        )
    }

    @Test
    fun `不同故障不会被误判为重述`() {
        assertFalse(PluginErrorNoise.isRethrowOfHostError("connection refused", "list 失败: 401 Unauthorized"))
        assertFalse(PluginErrorNoise.isRethrowOfHostError(null, "list 失败: 401"))
        assertFalse(PluginErrorNoise.isRethrowOfHostError("boom", ""))
    }
}
