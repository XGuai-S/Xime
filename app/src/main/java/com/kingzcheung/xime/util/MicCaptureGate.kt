package com.kingzcheung.xime.util

import android.os.SystemClock

/**
 * 进程级麦克风占用闸门。
 *
 * 背景：本应用有两条独立的录音路径，各自 new AudioRecord(...)，彼此完全不知道对方存在：
 *  - 键盘语音（com.kingzcheung.xime.speech.SpeechRecognitionManager）
 *  - 悬浮胶囊（com.kingzcheung.xime.ai.audio.WavAudioRecorder）
 *
 * Android 上同一进程再开第二个 AudioRecord 通常能「初始化成功」，但 startRecording() 之后
 * 只有一路能真正拿到数据，另一路读到的全是 0，表现为「两边都在转圈、谁都识别不出字」，
 * 且日志里看不出任何错误。用户视角就是「按了麦克风没反应」。
 *
 * 这里用一把进程内的「谁在录」标记：先到者持有，后来者立刻拿到失败并给出**可读的占用者名字**，
 * 由调用方转成用户提示（而不是静默录出静音）。
 *
 * 关于租约（LEASE）：闸门最大的风险是「某条路径异常退出忘了 release，麦克风被永久锁死」。
 * 因此每次持有都记时间戳，超过 LEASE_TIMEOUT_MS 后允许后来者强制接管并写警告日志。
 * 阈值取 5 分钟——远超任何正常单次语音输入（胶囊最长 120s、键盘语音一般 <60s），
 * 正常使用不会误抢，异常泄漏也能自愈。
 *
 * 关于跨进程：本对象是**进程内单例**。键盘语音与悬浮胶囊目前都在默认进程，互斥成立；
 * 一旦任一组件被加上 `android:process`，两条路径会各自持有一把互不相干的锁，闸门静默失效。
 * 因此 [tryAcquire] 首次调用记录进程名，之后发现不一致就写一条明确 ERROR（见 [checkProcessOwnership]）。
 */
object MicCaptureGate {

    private const val TAG = "MicCaptureGate"

    /** 单次持有允许的最长时间；超过视为泄漏，允许强制接管。 */
    internal const val LEASE_TIMEOUT_MS = 5 * 60 * 1000L

    private val lock = Any()
    private var holder: String? = null
    private var acquiredAtMs = 0L

    /** 首次 tryAcquire 时记录的调用方进程名；null = 尚未记录或取不到。 */
    private var recordedProcessName: String? = null

    /** 进程不一致只告警一次，避免每次起录音都刷同一条 ERROR。 */
    private var processMismatchWarned = false

    /**
     * 进程名提供者。默认读 [android.app.Application.getProcessName]（API 28+，与 minSdk 一致）；
     * JVM 单测可替换成固定值，不依赖 Android 运行时。
     */
    @Volatile
    internal var processNameProvider: () -> String? = { readProcessName() }

    /** 告警输出，单测可替换以断言文案。 */
    @Volatile
    internal var errorReporter: (String) -> Unit = { FileLogger.e(TAG, it) }

    private fun readProcessName(): String? = try {
        android.app.Application.getProcessName()
    } catch (_: Throwable) {
        null
    }

    /**
     * 进程归属断言：闸门是进程内单例，只有两条录音路径同进程时才互斥。
     * 一旦有人给语音或胶囊组件加 `android:process`，这里会记录到不同的进程名并告警。
     */
    private fun checkProcessOwnership(owner: String) {
        val current = processNameProvider() ?: return
        val recorded = recordedProcessName
        if (recorded == null) {
            recordedProcessName = current
            return
        }
        if (recorded != current && !processMismatchWarned) {
            processMismatchWarned = true
            errorReporter(
                "麦克风闸门已失效：两条录音路径分属不同进程" +
                    "（已记录=$recorded，当前=$current，请求者=$owner），麦克风互斥不生效。" +
                    "请检查 AndroidManifest 中语音/悬浮胶囊组件是否被加了 android:process"
            )
        }
    }

    /**
     * 尝试占用麦克风。
     *
     * @return true = 已占用（含「本来就是我占着」的刷新场景）。
     */
    fun tryAcquire(owner: String, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        synchronized(lock) {
            checkProcessOwnership(owner)
            val current = holder
            if (current == null) {
                holder = owner
                acquiredAtMs = nowMs
                return true
            }
            if (current == owner) {
                // 同一持有者重复占用（键盘的「预启动」→「正式录音」就是这个模式）：刷新租约即可
                acquiredAtMs = nowMs
                return true
            }
            if (nowMs - acquiredAtMs > LEASE_TIMEOUT_MS) {
                FileLogger.w(
                    TAG,
                    "麦克风被 $current 占用超过 ${LEASE_TIMEOUT_MS}ms 仍未释放，强制接管给 $owner"
                )
                holder = owner
                acquiredAtMs = nowMs
                return true
            }
            FileLogger.w(TAG, "麦克风占用失败：当前持有者=$current，请求者=$owner")
            return false
        }
    }

    /** 释放占用；只有持有者本人能释放，重复调用无副作用。 */
    fun release(owner: String) {
        synchronized(lock) {
            if (holder == owner) {
                holder = null
                acquiredAtMs = 0L
            }
        }
    }

    /** 当前持有者名字；无人持有时为 null。用于给用户拼提示文案。 */
    fun currentHolder(): String? = synchronized(lock) { holder }

    /** 仅供测试重置状态。 */
    internal fun resetForTest() {
        synchronized(lock) {
            holder = null
            acquiredAtMs = 0L
            recordedProcessName = null
            processMismatchWarned = false
            processNameProvider = { readProcessName() }
            errorReporter = { FileLogger.e(TAG, it) }
        }
    }
}
