package com.kingzcheung.xime.plugin.http

import android.content.Context
import com.kingzcheung.xime.plugin.core.js.http.SseHostListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * M06 回归：SSE 会话必须在**发起连接之前**登记。
 *
 * 回归背景：旧实现先 `factory.newEventSource(...)`、再 `sessions[id] = session`。
 * 若连接立即失败/立即结束（DNS 立刻报错、401 立刻返回、网关立即断开），
 * onFailure/onClosed 会在 newEventSource 返回**之前**（或紧邻其后）于别的线程跑完：
 * 此时 `sessions` 里还没有该会话 → `sessions.remove(id)` 空转 → 名额永久泄漏。
 * 泄漏满 MAX_SESSIONS(8) 后，该插件再也建不了 SSE 会话（AI 流式回复整体失效）。
 *
 * 这里注入一个「构造期间就同步回调终态」的 EventSource，稳定复现该窗口。
 */
class SseSessionRegistrationTest {

    private class RecordingListener : SseHostListener {
        var done: String? = null
        var error: String? = null
        val data = mutableListOf<String>()
        override fun onData(text: String) { data += text }
        override fun onDone(fullText: String) { done = fullText }
        override fun onError(message: String) { error = message }
    }

    /** 立即终态：newEventSource 里同步触发 onFailure/onClosed 后返回。 */
    private fun immediateTerminalSource(failure: Boolean, listenerHolder: (EventSourceListener) -> Unit) =
        EventSource.Factory { request, listener ->
            listenerHolder(listener)
            object : EventSource {
                override fun request(): Request = request
                override fun cancel() {}
            }.also {
                if (failure) {
                    listener.onFailure(it, IOException("connection refused"), null)
                } else {
                    listener.onClosed(it)
                }
            }
        }

    private fun newApi(factory: (OkHttpClient) -> EventSource.Factory): SseHostApiImpl =
        SseHostApiImpl(
            context = mock<Context>(),
            pluginId = "com.kingzcheung.xime.test",
            eventSourceFactory = factory,
            authorizer = { null }
        )

    private fun sessionCount(api: SseHostApiImpl): Int {
        val field = SseHostApiImpl::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(api) as Map<Int, *>).size
    }

    @Test
    fun `连接立即失败时不得泄漏会话名额`() {
        val api = newApi({ _ -> immediateTerminalSource(failure = true) {} })
        val listener = RecordingListener()

        val id = api.connect("https://example.com/sse", emptyMap(), listener)

        assertTrue("建连成功应返回会话 id", id >= 0)
        assertEquals(
            "终态已到达，会话必须已从表中移除（旧实现 remove 空转 → 名额永久泄漏）",
            0, sessionCount(api)
        )
        assertEquals("失败原因必须交付给插件（旧实现回调时 current 还是 null，直接 return）",
            "connection refused", listener.error)
    }

    @Test
    fun `连接立即正常结束时不得泄漏会话名额`() {
        val api = newApi({ _ -> immediateTerminalSource(failure = false) {} })
        val listener = RecordingListener()

        api.connect("https://example.com/sse", emptyMap(), listener)

        assertEquals("正常结束同样要清掉名额", 0, sessionCount(api))
    }

    @Test
    fun `连续 20 次立即失败后仍能继续建连（不占满 MAX_SESSIONS）`() {
        val api = newApi({ _ -> immediateTerminalSource(failure = true) {} })
        var lastId = -1
        repeat(20) {
            lastId = api.connect("https://example.com/sse", emptyMap(), RecordingListener())
            assertEquals("每次终态后名额都应归还", 0, sessionCount(api))
        }
        assertTrue("名额未泄漏时应始终可建连（上限 8）", lastId >= 0)
    }

    @Test
    fun `建连抛异常时撤掉已登记的会话`() {
        val api = newApi({ _ ->
            EventSource.Factory { _, _ -> throw IllegalStateException("factory boom") }
        })

        val id = api.connect("https://example.com/sse", emptyMap(), RecordingListener())

        assertEquals("建连异常返回 -1", -1, id)
        assertEquals("已登记的会话必须撤销", 0, sessionCount(api))
    }

    @Test
    fun `主动 close 幂等且重复 close 不抛异常`() {
        val api = newApi({ _ -> immediateTerminalSource(failure = false) {} })
        val id = api.connect("https://example.com/sse", emptyMap(), RecordingListener())

        api.close(id)
        api.close(id)

        assertEquals(0, sessionCount(api))
    }

    /**
     * 红测敏感性证明（不改生产代码）：用一段**独立复刻旧顺序**的最小实现
     * （先建连、后登记）跑同一个「立即终态」场景，证明名额确实会泄漏。
     * 上面的生产代码用例之所以会被它卡住，正是因为这个顺序差。
     */
    @Test
    fun `旧顺序先建连后登记会泄漏名额（对照）`() {
        val sessions = ConcurrentHashMap<Int, Any>()
        var leased = 0
        val listener = RecordingListener()

        // ---- 旧顺序：newEventSource 先跑完（终态回调已在这里执行） ----
        val factory = immediateTerminalSource(failure = true) {}
        val session = Any()
        factory.newEventSource(
            Request.Builder().url("https://example.com/sse").build(),
            object : EventSourceListener() {
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    // 此刻 sessions 里还没有该会话 → remove 空转
                    if (!sessions.removeValue(session)) return
                    listener.onError(t?.message ?: "连接失败")
                }
            }
        )
        // ---- 旧顺序的下一步：补登记（名额就这样留下了） ----
        sessions[1] = session
        leased = sessions.size

        assertEquals("旧顺序下名额残留 1 个（终态已走完却仍占位）", 1, leased)
        assertNull("旧顺序下插件收不到失败原因（回调时 current 还是 null）", listener.error)
    }

    private fun ConcurrentHashMap<Int, Any>.removeValue(value: Any): Boolean =
        entries.removeIf { it.value === value }
}
