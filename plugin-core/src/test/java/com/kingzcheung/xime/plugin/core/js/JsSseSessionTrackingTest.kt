package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.http.SseHostApi
import com.kingzcheung.xime.plugin.core.js.http.SseHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * M06 runtime 侧回归：连接**立即失败**时不得在 activeSseSessions 里留下死 id。
 *
 * 回归背景：旧实现等 `connect()` 返回后才 `activeSseSessions.add(sessionId)`，
 * 而宿主是「发起连接 → 回到这里」。连接立即失败时 onError 早已跑完（remove 空转），
 * 随后 connect 返回才 add —— 该 id 永远留在集合里。后果：
 * 1) runtime.close() 会对已结束的会话重复 close；
 * 2) 集合随会话数无限增长（常驻输入法进程里是持续的内存增长）。
 *
 * 修复：宿主在建连前回调 `onSessionAllocated`，runtime 在那里登记；
 * 因此 onError 的 remove 命中，集合回到基线。
 */
class JsSseSessionTrackingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    /**
     * 宿主替身：模拟「连接立即失败」——
     * 先按新契约回调 onSessionAllocated（真实宿主在建连前调用），
     * 再同步触发 onError，最后才返回 id。
     *
     * @param notifyBeforeConnect false = 模拟旧宿主（不回调 onSessionAllocated）
     */
    private class ImmediateFailureSseHostApi(
        private val notifyBeforeConnect: Boolean,
        private val id: Int = 100
    ) : SseHostApi {
        var listener: SseHostListener? = null
        val closedIds = mutableListOf<Int>()

        override fun connect(
            url: String,
            headers: Map<String, String>,
            listener: SseHostListener,
            timeoutMillis: Int?,
            method: String,
            body: ByteArray?
        ): Int {
            this.listener = listener
            if (notifyBeforeConnect) listener.onSessionAllocated(id)
            // 立即失败：终态在 connect 返回之前就跑完
            listener.onError("connection refused")
            return id
        }

        override fun close(sessionId: Int) { closedIds.add(sessionId) }

        override fun lastError(): String? = null
    }

    private val pluginJs = """
        globalThis.plugin = {
          errorMsg: "",
          open: async function () {
            return host.http.stream("https://api.example.com/stream", {});
          },
          get_error: function () { return this.errorMsg; },
          sse: {
            onData: function (id, text) {},
            onDone: function (id, text) {},
            onError: function (id, message) { globalThis.plugin.errorMsg = message; }
          }
        };
    """.trimIndent()

    private fun makeRuntime(api: SseHostApi): JsScriptRuntime {
        val dir = tmp.newFolder("sse-track")
        File(dir, "main.js").writeText(pluginJs)
        return JsScriptRuntime(
            pluginId = "com.kingzcheung.xime.plugin.sse_track",
            pluginDir = dir,
            entryScript = "main.js",
            configStore = InMemoryConfigStore(),
            sseHostApi = api
        )
    }

    private fun activeSessions(runtime: JsScriptRuntime): Set<*> {
        val field = JsScriptRuntime::class.java.getDeclaredField("activeSseSessions")
            .apply { isAccessible = true }
        return field.get(runtime) as Set<*>
    }

    @Test(timeout = 60_000)
    fun `连接立即失败后活动会话集合回到基线`() {
        val api = ImmediateFailureSseHostApi(notifyBeforeConnect = true)
        val runtime = makeRuntime(api)
        try {
            assertTrue(runtime.load())
            assertEquals("立即失败仍返回会话 id", 100, (runtime.callAsync("open") as Number).toInt())

            assertEquals(
                "终态已到达，活动集合必须为空（旧实现此处残留该 id）",
                0, activeSessions(runtime).size
            )
        } finally {
            runtime.close()
        }
        assertEquals(
            "结束时不应再重复 close 已结束的会话",
            emptyList<Int>(), api.closedIds
        )
    }

    @Test(timeout = 180_000)
    fun `连续 12 次立即失败不累积死会话`() {
        val api = ImmediateFailureSseHostApi(notifyBeforeConnect = true)
        val runtime = makeRuntime(api)
        try {
            assertTrue(runtime.load())
            repeat(12) {
                runtime.callAsync("open")
                assertEquals("每次终态后集合都应清空", 0, activeSessions(runtime).size)
            }
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun `旧宿主不回调 onSessionAllocated 时仍能正常结束并清理`() {
        // 兼容性：未实现 onSessionAllocated 的宿主（或默认空实现）时，
        // runtime 在 connect 返回后补登记；onError 已先行跑过（这次无法清理），
        // 但 closeStream / close() 仍能清掉它。
        val api = ImmediateFailureSseHostApi(notifyBeforeConnect = false)
        val runtime = makeRuntime(api)
        try {
            assertTrue(runtime.load())
            runtime.callAsync("open")
        } finally {
            runtime.close()
        }
        assertTrue("close() 必须清理掉残留 id", runtime.isClosedForTest())
    }
}

/** 反射读 closed 标志（close() 后为 true）。 */
private fun JsScriptRuntime.isClosedForTest(): Boolean {
    val field = JsScriptRuntime::class.java.getDeclaredField("closed").apply { isAccessible = true }
    return field.getBoolean(this)
}
