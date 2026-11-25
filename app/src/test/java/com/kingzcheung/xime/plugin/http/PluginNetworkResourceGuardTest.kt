package com.kingzcheung.xime.plugin.http

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件宿主网络层的资源与内存收敛回归。
 *
 * 1. 错误响应体必须关闭：SSE/WS 非 2xx 时监听器拿到 Response 后不再处理，
 *    不关闭的 body 让连接无法归还连接池，反复失败会耗尽 fd。
 * 2. SSE 除单会话上限外必须有**全局**正文预算：8 会话各 8M 字符（UTF-16 约 128MB）
 *    在 QuickJS 堆外，JS 的 96MB 上限管不到，直接吃常驻进程的 Java 堆。
 * 3. 会话终态必须归还它占用的全局预算，否则已结束会话会永久占满额度。
 */
class PluginNetworkResourceGuardTest {

    private val sse by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/plugin/http/SseHostApiImpl.kt").readText()
    }
    private val errorBody by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/plugin/http/BoundedErrorBody.kt").readText()
    }

    @Test
    fun `错误响应体读取后必须关闭`() {
        val start = errorBody.indexOf("fun readErrorBody(")
        assertTrue("找不到 readErrorBody", start >= 0)
        val body = errorBody.substring(start).substringBefore("\n    /**")
        assertTrue("必须用 use 关闭 ResponseBody", "body.use {" in body)
        assertTrue("仍须有界读取", "MAX_ERROR_BODY_BYTES" in body)
    }

    @Test
    fun `SSE 有全局正文预算`() {
        assertTrue("必须有全局预算常量", "MAX_TOTAL_TEXT_CHARS_ALL_SESSIONS" in sse)
        assertTrue("必须有全局计数器", "totalCharsAcrossSessions" in sse)
        val start = sse.indexOf("override fun onEvent(")
        assertTrue("找不到 onEvent", start >= 0)
        val body = sse.substring(start).substringBefore("\n                override fun ")
        assertTrue("单会话仍须有上限", "MAX_TOTAL_TEXT_CHARS" in body)
        assertTrue("必须检查全局预算", "MAX_TOTAL_TEXT_CHARS_ALL_SESSIONS" in body)
    }

    @Test
    fun `会话结束归还全局预算`() {
        assertTrue("必须有统一释放方法", "private fun releaseSession(session: Session)" in sse)
        assertTrue("按 id 释放也必须归还", "private fun releaseSessionById(id: Int): Session?" in sse)
        // 终态回调不得再直接 sessions.remove 绕过归还
        val onClosed = sse.substring(sse.indexOf("override fun onClosed("))
            .substringBefore("override fun onFailure(")
        assertFalse("onClosed 不得直接 remove 绕过预算归还", "sessions.remove(" in onClosed)
        assertTrue("onClosed 必须走统一释放", "releaseSession(session)" in onClosed)
    }

    @Test
    fun `关闭会话走统一释放`() {
        val start = sse.indexOf("override fun close(")
        assertTrue("找不到 close", start >= 0)
        val body = sse.substring(start).substringBefore("\n    override fun ")
        assertFalse("close 不得直接 remove 绕过预算归还", "sessions.remove(" in body)
    }
}
