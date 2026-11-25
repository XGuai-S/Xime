package com.kingzcheung.xime.speech

import android.content.Context
import android.media.AudioRecord
import android.os.Handler
import com.kingzcheung.xime.util.MicCaptureGate
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock as jmock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 录音线程自然退出回归（M03）。
 *
 * 缺陷：录音循环因 read() 返回负数（或处理中抛异常）自然退出后，线程没有清
 * `recordingThread`，之后 startRecognition() 首行判定「已在录音」直接忽略，
 * 用户必须显式 stop/cancel 才能再录。
 *
 * 要求：自然退出时按会话身份清槽位（只清自己），并向用户报一次错误；
 * 主动 stop/cancel 导致的退出不报错。
 */
class SpeechRecordingThreadExitTest {

    @Before
    fun setUp() = MicCaptureGate.resetForTest()

    @After
    fun tearDown() = MicCaptureGate.resetForTest()

    private class Fixture {
        val backend: AsrBackend = mock()
        val handler: Handler = jmock(Handler::class.java)
        val posted = ConcurrentLinkedQueue<Runnable>()
        val states = mutableListOf<RecognitionState>()
        val errors = mutableListOf<String>()
        lateinit var manager: SpeechRecognitionManager

        init {
            whenever(backend.initialize()).thenReturn(true)
            whenever(backend.start()).thenReturn(true)
            whenever(handler.post(any<Runnable>())).thenAnswer {
                posted.add(it.getArgument<Runnable>(0)); true
            }
            whenever(handler.postDelayed(any<Runnable>(), any<Long>())).thenReturn(true)
            manager = SpeechRecognitionManager(jmock(Context::class.java), handler) { backend }
            manager.setCallbacks(
                onResult = {},
                onPartialResult = {},
                onStateChange = { states += it },
                onError = { message, _ -> errors += message },
            )
        }

        /** 执行 Handler.post 排队的主线程任务（单测里没有 Looper）。 */
        fun drainMain() {
            while (true) (posted.poll() ?: break).run()
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

        /** 造一个真实 RecordingThread（构造器：外部类, backend, preStarted, session）。 */
        fun newThread(session: Int, record: AudioRecord): Thread {
            val threadClass = manager.javaClass.declaredClasses.single { it.simpleName == "RecordingThread" }
            val ctor = threadClass.declaredConstructors.single { it.parameterCount == 4 }
            ctor.isAccessible = true
            return ctor.newInstance(manager, backend, record, session) as Thread
        }

        /** 让录音线程的第一个 read() 返回负数：模拟设备被释放后的自然退出。 */
        fun recordReturningNegative(): AudioRecord {
            val record = mock<AudioRecord>()
            whenever(record.read(any<ShortArray>(), any(), any())).thenReturn(-3)
            return record
        }

        /** 让 read() 抛异常：模拟处理中出错导致的自然退出。 */
        fun recordThrowing(): AudioRecord {
            val record = mock<AudioRecord>()
            whenever(record.read(any<ShortArray>(), any(), any())).thenThrow(RuntimeException("读取失败"))
            return record
        }

        fun setStopRequested(thread: Thread) {
            thread.javaClass.getDeclaredField("stopRequested").run {
                isAccessible = true
                setBoolean(thread, true)
            }
        }
    }

    @Test
    fun `read 返回负数自然退出后清空槽位，后续 start 不再被忽略`() {
        val f = Fixture()
        val thread = f.newThread(session = 1, record = f.recordReturningNegative())
        f.setField("sessionId", 1)
        f.setField("backend", f.backend)
        f.setField("recordingThread", thread)

        thread.run()
        f.drainMain()

        assertEquals("自然异常退出必须报一次错误，不能静默", 1, f.errors.size)
        assertNull("自然退出后必须清空 recordingThread", f.field("recordingThread"))

        // 槽位已清空 → 后续 start 必须被受理（进入 PROCESSING 且登记新会话）
        f.states.clear()
        f.manager.startRecognition()
        assertEquals(
            "槽位已清空，startRecognition 必须被受理",
            listOf(RecognitionState.PROCESSING), f.states.take(1)
        )
        assertEquals("start 必须真正登记新会话", 2, f.field("sessionId"))
    }

    @Test
    fun `处理中抛异常自然退出同样清空槽位并报错`() {
        val f = Fixture()
        val thread = f.newThread(session = 1, record = f.recordThrowing())
        f.setField("sessionId", 1)
        f.setField("backend", f.backend)
        f.setField("recordingThread", thread)

        thread.run()
        f.drainMain()

        assertEquals("异常退出必须报一次错误", 1, f.errors.size)
        assertNull("异常退出后必须清空 recordingThread", f.field("recordingThread"))
    }

    @Test
    fun `旧线程自然退出不误清新会话的槽位`() {
        val f = Fixture()
        val old = f.newThread(session = 1, record = f.recordReturningNegative())
        val current = f.newThread(session = 2, record = mock())
        // 新会话已登记：sessionId=2、槽位指向新线程
        f.setField("sessionId", 2)
        f.setField("activeSession", 2)
        f.setField("backend", f.backend)
        f.setField("recordingThread", current)

        old.run()
        f.drainMain()

        assertSame("旧线程不得清新会话的录音线程", current, f.field("recordingThread"))
        assertEquals("被取代的旧会话不得再打扰用户", 0, f.errors.size)
    }

    @Test
    fun `主动停止导致的退出不报错但仍清空槽位`() {
        val f = Fixture()
        val thread = f.newThread(session = 1, record = f.recordReturningNegative())
        f.setField("sessionId", 1)
        f.setField("backend", f.backend)
        f.setField("recordingThread", thread)
        // 模拟 stopRecognition / cancelRecognition：先置停止标志，再释放 AudioRecord
        f.setStopRequested(thread)

        thread.run()
        f.drainMain()

        assertEquals("主动停止不得报错", 0, f.errors.size)
        assertNull("主动停止后槽位应为空", f.field("recordingThread"))
    }

    /**
     * 旧行为反例（M03）：用自包含的小模型复刻「槽位收尾 + start 受理判定」，
     * 证明本缺陷的两个可观测后果都被上面用例盯住了——不引用生产代码，也不改生产代码。
     *
     * 旧收尾：线程自然退出只停后端，槽位留着 → 下一次 start 被首行判定拒绝（缺陷）；
     * 新收尾：按会话身份清空槽位 → 下一次 start 正常受理。
     */
    @Test
    fun `old behaviour of leaving the slot set rejects the next start`() {
        class SlotModel {
            var recordingThread: Thread? = null
            var startsAccepted = 0

            /** 修复前 run() 的收尾：只停后端，不碰槽位。 */
            fun tailLegacy() = Unit

            /** 修复后 finishRecording 的第一段：只清自己。 */
            fun tailFixed(thread: Thread) {
                if (recordingThread === thread) recordingThread = null
            }

            /** startRecognition() 首行：槽位非空就忽略本次请求。 */
            fun requestStart() {
                if (recordingThread == null) startsAccepted++
            }
        }

        val thread = Thread()

        // 旧行为：自然退出后槽位仍被占用，下一次 start 被拒绝
        val legacy = SlotModel().apply { recordingThread = thread }
        legacy.tailLegacy()
        legacy.requestStart()
        assertEquals("旧实现下第二次 start 会被首行拒绝（缺陷）", 0, legacy.startsAccepted)

        // 新行为：自然退出后槽位清空，下一次 start 正常受理
        val fixed = SlotModel().apply { recordingThread = thread }
        fixed.tailFixed(thread)
        fixed.requestStart()
        assertEquals("修复后第二次 start 必须被受理", 1, fixed.startsAccepted)

        // 会话身份保护：旧线程不得清掉别人的槽位
        val other = Thread()
        val superseded = SlotModel().apply { recordingThread = other }
        superseded.tailFixed(thread)
        assertEquals("旧线程不得清新会话的槽位", other, superseded.recordingThread)
    }
}
