package com.kingzcheung.xime.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kingzcheung.xime.util.FileLogger

/**
 * `:asr` 进程的空闲回收看门狗。
 *
 * ## 为什么需要它
 *
 * [AsrInferenceService] 是**绑定型**服务：进程能活多久，完全由「还有没有人绑着」决定。
 * 服务端已有的空闲回收（`IDLE_RELEASE_DELAY_MS`）只释放了模型句柄，**不会、也没法**解除
 * 客户端的绑定——绑定是客户端的资源。结果就是：
 *
 * - 任何一个取过 [AsrInferenceClient] 却漏了 `unbind()` 的路径（历史上就有几处），
 *   都会让 `:asr` 进程一直挂着；
 * - 实测一台机器上该进程 PSS 约 **232MB**（RSS 147MB + swap 195MB），
 *   而用户当时「使用本地模型」开关是**关着的**——开关只决定用不用它，不决定它还活着。
 *
 * ## 它怎么做
 *
 * 记录所有活着的客户端实例 + 最后一次真正的 ASR 调用时间。空闲超过
 * [AsrInferenceClient.IDLE_UNBIND_MS] 后，对所有仍绑着的客户端逐个 `unbind()`，
 * 再补一个 `stopService`。绑定全没了，系统就回收 `:asr` 进程，内存归零。
 * 下次真正要用时 `ensureBound()` 会重新拉起，代价是首次冷启动（模型加载）。
 *
 * 这样即使将来又有人漏写 `unbind()`，也不会再演变成「永久常驻」。
 *
 * ## 边界
 *
 * - 只在**主线程**做调度与解绑（`bindService`/`unbindService` 都要求主线程）。
 * - 会话进行中不会误杀：每次真正的 ASR 调用都会刷新 `lastActivityAtMs`，
 *   看门狗到点发现「刚用过」就顺延，不会中途解绑。
 * - 用户在设置里关掉「使用本地模型」时可调 [shutdownNow] 立刻回收，不必等超时。
 */
object AsrConnectionWatchdog {

    private const val TAG = "AsrConnWatchdog"

    private val handler = Handler(Looper.getMainLooper())

    /** 应用上下文：解绑与 stopService 都需要它（用 applicationContext，避免钉住 Service）。 */
    private var appContext: Context? = null

    /**
     * 判定是否该回收（纯函数，便于单测）。
     *
     * @param nowMs 当前时刻（`SystemClock.elapsedRealtime()`）
     * @param lastActivityMs 最后一次 ASR 调用时刻；0 表示从未用过（可直接回收）
     */
    internal fun shouldUnbind(
        nowMs: Long,
        lastActivityMs: Long,
        timeoutMs: Long = AsrInferenceClient.IDLE_UNBIND_MS
    ): Boolean {
        if (lastActivityMs <= 0L) return true
        return nowMs - lastActivityMs >= timeoutMs
    }

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 有 ASR 活动发生时调用：把回收检查推到「最后一次活动 + 超时」之后。 */
    fun scheduleIdleCheck() {
        handler.removeCallbacks(idleCheckRunnable)
        handler.postDelayed(idleCheckRunnable, AsrInferenceClient.IDLE_UNBIND_MS + 1_000L)
    }

    private val idleCheckRunnable = Runnable { runIdleCheck(force = false) }

    /** 立刻回收（用于「关掉本地识别」这类明确信号）。 */
    fun shutdownNow(reason: String) {
        handler.removeCallbacks(idleCheckRunnable)
        runIdleCheck(force = true, reason = reason)
    }

    private fun runIdleCheck(force: Boolean, reason: String = "") {
        val ctx0 = appContext
        // 用户显式开了「保持引擎常驻」就别动它：那是拿内存换首次响应速度的选择。
        // 强制回收（关掉本地识别）不受此限——用户已经明确不要这个引擎了。
        if (!force && ctx0 != null &&
            com.kingzcheung.xime.settings.SettingsPreferences.isSttKeepEngineAlive(ctx0)
        ) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = AsrInferenceClient.lastActivityAtMs
        val idle = force || shouldUnbind(now, last)
        if (!idle) {
            // 会话仍在用：顺延到「最后活动 + 超时」
            val delay = (last + AsrInferenceClient.IDLE_UNBIND_MS) - now + 1_000L
            handler.postDelayed(idleCheckRunnable, delay.coerceAtLeast(1_000L))
            return
        }
        val live = AsrInferenceClient.boundClientCount()
        val ctx = appContext
        if (live == 0) {
            // 没有客户端还绑着：进程本就该被系统回收了，无需动作
            return
        }
        FileLogger.i(
            TAG,
            "回收 :asr 进程：${if (force) "强制（$reason）" else "空闲 ${AsrInferenceClient.IDLE_UNBIND_MS / 1000}s"}，" +
                "解绑 $live 个客户端"
        )
        AsrInferenceClient.unbindAll()
        if (ctx != null) {
            runCatching { ctx.stopService(Intent(ctx, AsrInferenceService::class.java)) }
        }
    }
}
