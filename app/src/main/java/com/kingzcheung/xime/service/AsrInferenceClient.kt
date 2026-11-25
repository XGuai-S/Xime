package com.kingzcheung.xime.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 离线语音识别服务客户端，绑定 [AsrInferenceService]（:asr 独立进程）。
 */
class AsrInferenceClient(private val context: Context) {

    companion object {
        private const val TAG = "AsrInferenceClient"

        /**
         * 空闲多久就把 `:asr` 进程放掉。
         *
         * 取值权衡：太短会让连续几次语音输入之间反复冷启动（模型加载慢），太长则相当于
         * 一直常驻（实测常驻 PSS ≈232MB）。3 分钟覆盖「说完一句、想一下、再说一句」的
         * 常见节奏，又能在真正停下来之后把内存放掉。
         */
        internal const val IDLE_UNBIND_MS = 3 * 60 * 1000L

        /** 所有仍活着的客户端（弱引用，避免看门狗把临时客户端钉在内存里）。 */
        private val liveClients = CopyOnWriteArrayList<WeakReference<AsrInferenceClient>>()

        /** 最后一次真正的 ASR 调用时刻（elapsedRealtime）；0 = 从未用过。 */
        @Volatile
        var lastActivityAtMs: Long = 0L
            private set

        /** 只刷新活动时间（不重排看门狗）——留给每 ~20ms 一帧的音频热路径。 */
        internal fun markActivity() {
            lastActivityAtMs = SystemClock.elapsedRealtime()
        }

        /** 会话边界：刷新活动时间并把回收检查推到超时之后。 */
        internal fun touch() {
            markActivity()
            AsrConnectionWatchdog.scheduleIdleCheck()
        }

        /** 仍处于绑定状态的客户端数量。 */
        internal fun boundClientCount(): Int {
            pruneDeadRefs()
            return liveClients.count { it.get()?.bound == true }
        }

        /** 解绑所有客户端（主线程调用）。绑定清空后系统即可回收 `:asr` 进程。 */
        internal fun unbindAll() {
            pruneDeadRefs()
            liveClients.forEach { ref -> ref.get()?.unbind() }
            liveClients.clear()
        }

        private fun pruneDeadRefs() {
            liveClients.removeAll { it.get() == null }
        }
    }

    init {
        liveClients.add(WeakReference(this))
    }

    interface AsrCallback {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(message: String)
    }

    private var service: IInferenceAsrService? = null
    private var bound = false

    /** 当前识别会话号（服务端 startAsr 返回）；0 = 没有会话。后续推音频/结束/取消都要带上它。 */
    @Volatile
    private var sessionId = 0L

    /** 连接等待锁：一次性 CountDownLatch，countDown 后即失效——
     *  重绑前必须在 [ensureBound] 中重建，否则断线重连后会立即假醒。 */
    @Volatile
    private var connectLatch = CountDownLatch(1)

    private val asrCallbackStub = object : IInferenceAsrCallback.Stub() {
        private var callback: AsrCallback? = null

        fun attach(cb: AsrCallback) { callback = cb }
        fun detach() { callback = null }

        override fun onPartialResult(text: String) {
            callback?.onPartialResult(text)
        }

        override fun onFinalResult(text: String) {
            callback?.onFinalResult(text)
        }

        override fun onError(message: String) {
            callback?.onError(message)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IInferenceAsrService.Stub.asInterface(binder)
            bound = true
            connectLatch.countDown()
            FileLogger.i(TAG, "Connected to AsrInferenceService")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            FileLogger.w(TAG, "AsrInferenceService disconnected (crash?)")
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            bound = false
            connectLatch.countDown()
            FileLogger.e(TAG, "AsrInferenceService binding died")
        }
    }

    suspend fun ensureBound(): Boolean {
        if (bound && service != null) return true

        // bindService 必须在主线程调用；等待结果放到 IO 线程避免阻塞 UI
        val boundOk = withContext(Dispatchers.Main) {
            synchronized(this@AsrInferenceClient) {
                if (bound && service != null) {
                    true
                } else {
                    // 服务进程可能崩溃/被回收后重启：latch 是一次性的，重绑前必须重建，
                    // 否则旧 latch 已 countDown 会让 await 立即假醒（实际未连接），
                    // 重绑后的第一次 startAsr 必然失败（同 InferenceClient 的修复）
                    connectLatch = CountDownLatch(1)
                    val intent = Intent(context, AsrInferenceService::class.java)
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }
            }
        }
        if (!boundOk) return false
        return withContext(Dispatchers.IO) {
            connectLatch.await(5, TimeUnit.SECONDS)
            val ready = bound && service != null
            if (ready) touch()
            ready
        }
    }

    fun unbind() {
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            // 重复解绑或未绑定成功时系统抛 IllegalArgumentException，属正常收尾路径
            FileLogger.d(TAG, "unbind ignored: ${e.message}")
        }
        bound = false
        service = null
        // 解绑也算一次「活动」：让看门狗重新起算，别紧接着又跑一遍空检查
        touch()
    }

    private fun requireService(): IInferenceAsrService {
        return service ?: throw IllegalStateException("AsrInferenceService not bound")
    }

    suspend fun startAsr(modelDir: String, callback: AsrCallback): Boolean = withContext(Dispatchers.IO) {
        touch()
        try {
            asrCallbackStub.attach(callback)
            val id = requireService().startAsr(modelDir, asrCallbackStub)
            sessionId = id
            id != 0L
        } catch (e: Exception) {
            FileLogger.e(TAG, "startAsr failed", e)
            sessionId = 0L
            asrCallbackStub.detach()
            false
        }
    }

    fun pushAsrAudio(audioData: ByteArray) {
        // 录音期间必须持续刷新活动时间：否则一次超过超时阈值的长录音会在中途被判空闲。
        // 这里只写一个 volatile 时间戳，不碰 Handler，20ms 一帧的开销可忽略。
        markActivity()
        try {
            requireService().pushAsrAudio(sessionId, audioData)
        } catch (e: Exception) {
            // 音频帧每 ~20ms 一帧，服务断开时会连续失败；只记 debug，避免日志被刷爆
            FileLogger.d(TAG, "pushAsrAudio dropped: ${e.message}")
        }
    }

    suspend fun stopAsr(): String = withContext(Dispatchers.IO) {
        touch()
        try {
            requireService().stopAsr(sessionId)
        } catch (e: Exception) {
            FileLogger.w(TAG, "stopAsr failed: ${e.message}")
            ""
        } finally {
            sessionId = 0L
            asrCallbackStub.detach()
        }
    }

    fun cancelAsr() {
        touch()
        try {
            requireService().cancelAsr(sessionId)
        } catch (e: Exception) {
            FileLogger.d(TAG, "cancelAsr ignored: ${e.message}")
        }
        sessionId = 0L
        asrCallbackStub.detach()
    }

    suspend fun releaseAsr() {
        touch()
        try {
            requireService().releaseAsr()
        } catch (e: Exception) {
            FileLogger.d(TAG, "releaseAsr ignored: ${e.message}")
        }
    }

    /** 同步"保持引擎常驻"设置到 :asr 进程（服务端据此决定是否空闲自动释放模型）。 */
    suspend fun setKeepModelAlive(keepAlive: Boolean) {
        try {
            requireService().setKeepModelAlive(keepAlive)
        } catch (e: Exception) {
            FileLogger.d(TAG, "setKeepModelAlive ignored: ${e.message}")
        }
    }
}
