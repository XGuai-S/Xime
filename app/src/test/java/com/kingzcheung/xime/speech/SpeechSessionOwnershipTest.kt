package com.kingzcheung.xime.speech

import android.content.Context
import android.os.Handler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.Mockito.mock as jmock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 语音会话所有权回归。
 *
 * 缺陷场景：
 * 1. 取消 A 后立刻显式开始 B，旧实现只把全局 `suppressResults` 复位，
 *    A 的迟到最终包到达时会被当作 B 的结果写进当前输入框；
 * 2. 旧会话的录音线程晚退出时仍调用共享后端的 `stop()`，把 B 正在用的引擎停掉。
 */
class SpeechSessionOwnershipTest {
    private class Fixture {
        val backend: AsrBackend = mock()
        val handler: Handler = jmock(Handler::class.java)
        @Volatile var mainQueue: ((Runnable) -> Unit)? = null
        lateinit var manager: SpeechRecognitionManager
        val posted = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        var results = mutableListOf<String>()
        var errors = mutableListOf<String>()

        init {
            whenever(backend.initialize()).thenReturn(true)
            whenever(backend.start()).thenReturn(true)
            whenever(handler.post(any<Runnable>())).thenAnswer {
                posted.add(it.getArgument<Runnable>(0)); true
            }
            whenever(handler.postDelayed(any<Runnable>(), any<Long>())).thenReturn(true)
            manager = SpeechRecognitionManager(jmock(Context::class.java), handler) { backend }
            manager.setCallbacks(
                onResult = { results += it },
                onPartialResult = {},
                onStateChange = {},
                onError = { message, _ -> errors += message },
            )
        }

        /** 让 Handler.post 排队的主线程任务真正执行（测试里没有 Looper）。 */
        fun drainMain() {
            while (true) (posted.poll() ?: break).run()
        }

        fun invokeResult(text: String) {
            val m = manager.javaClass.getDeclaredMethod("handleResult", String::class.java)
            m.isAccessible = true
            m.invoke(manager, text)
            drainMain()
        }

        fun invokeError(text: String) {
            val m = manager.javaClass.getDeclaredMethod("handleError", String::class.java)
            m.isAccessible = true
            m.invoke(manager, text)
            drainMain()
        }

        fun field(name: String): Any? = manager.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(manager)
        }

        fun setField(name: String, value: Any?) {
            manager.javaClass.getDeclaredField(name).run {
                isAccessible = true
                set(manager, value)
            }
        }
    }

    @Test
    fun `result arriving after cancel is not delivered`() {
        val f = Fixture()
        f.setField("activeSession", 1)
        f.setField("audioFlowing", true)
        f.invokeResult("这段不该上屏")
        assertEquals(listOf("这段不该上屏"), f.results)

        f.manager.cancelRecognition()
        f.invokeResult("取消后的尾巴")
        assertEquals("取消后的结果不得送达", 1, f.results.size)
    }

    @Test
    fun `result arriving after cancel is dropped even when a new session already started`() {
        val f = Fixture()
        // A 已经在送音频
        f.setField("activeSession", 1)
        f.setField("audioFlowing", true)
        f.invokeResult("A 的结果")
        assertEquals(1, f.results.size)

        // 取消 A，随后显式开始 B（B 已登记会话但还没送音频）
        f.manager.cancelRecognition()
        f.setField("suppressResults", false)
        f.setField("activeSession", 2)
        f.setField("audioFlowing", false)

        // A 的迟到最终包此刻到达
        f.invokeResult("A 的迟到尾巴")
        assertEquals("B 尚未送音频时到达的最终包不得被当作 B 的结果", 1, f.results.size)
    }

    @Test
    fun `error from a session without active audio is not reported`() {
        val f = Fixture()
        f.setField("activeSession", 0)
        f.invokeError("旧会话连接断开")
        assertEquals("无活动会话的错误不得打扰用户", 0, f.errors.size)
    }

    @Test
    fun `cancel clears the active session and the audio gate`() {
        val f = Fixture()
        f.setField("activeSession", 7)
        f.setField("audioFlowing", true)
        f.manager.cancelRecognition()
        assertEquals(0, f.field("activeSession"))
        assertEquals(false, f.field("audioFlowing"))
    }

    @Test
    fun `stale recording thread does not stop the backend owned by a newer session`() {
        // 用真实 RecordingThread 的内部判定：会话号被新会话取代后 ownsBackend 必须为 false
        val f = Fixture()
        f.setField("sessionId", 2)
        val threadClass = f.manager.javaClass.declaredClasses.single { it.simpleName == "RecordingThread" }
        // 内部类构造器第一个参数是外部类实例；取参数个数为 4 的那个（backend, preStarted, session）
        val ctor = threadClass.declaredConstructors.single { it.parameterCount == 4 }
        ctor.isAccessible = true
        val thread = ctor.newInstance(f.manager, f.backend, null, 1) as Thread
        val owns = threadClass.getDeclaredMethod("ownsBackend").apply { isAccessible = true }
        assertEquals("旧会话不得认为仍拥有后端", false, owns.invoke(thread))

        val current = ctor.newInstance(f.manager, f.backend, null, 2) as Thread
        assertEquals("当前会话应拥有后端", true, owns.invoke(current))
    }

    @Test
    fun `starting a session resets the audio gate so late packets are rejected`() {
        val f = Fixture()
        f.setField("activeSession", 1)
        f.setField("audioFlowing", true)
        // 模拟新会话登记（startRecording 的核心状态变更）
        f.setField("activeSession", 2)
        f.setField("audioFlowing", false)
        f.invokeResult("上一会话的尾巴")
        assertEquals("新会话未送音频前的最终包必须丢弃", 0, f.results.size)
    }
}
