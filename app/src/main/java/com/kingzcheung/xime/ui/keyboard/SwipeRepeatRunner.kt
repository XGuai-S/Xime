package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.SwipeRepeatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 「按住持续重复」执行器：按键左滑触发后，按实时速率重复调用动作，抬手即停。
 *
 * 用于删除键左滑的快速删除。标准键与横屏紧凑键共用同一份实现，避免两处各写一遍协程循环
 * 导致行为漂移（真机上这种漂移极难发现）。
 *
 * 速率由 [SwipeRepeatSpeed] 从**实时**滑动幅度映射得到，按住期间持续拖动会即时改变速率。
 *
 * 实例必须用 `remember` 持有。它挂在 [scope] 上（传 `rememberCoroutineScope()`），
 * 键离开组合时作用域取消，循环自然结束，不需要额外的 DisposableEffect。
 */
internal class SwipeRepeatRunner(private val scope: CoroutineScope) : SwipeRepeater {

    private var job: Job? = null
    private var intervalMs = SwipeRepeatSpeed.SLOWEST_INTERVAL_MS

    /** 用当前滑动幅度（dp）刷新重复间隔；幅度越大间隔越短。 */
    override fun updateInterval(amplitudeDp: Float) {
        intervalMs = SwipeRepeatSpeed.intervalMs(amplitudeDp)
    }

    /**
     * 启动重复循环（先停掉上一次）。[invoke] 每到达一次间隔调用一次。
     * 首次触发由调用方自行执行，本循环只负责「之后」的重复。
     */
    override fun start(invoke: () -> Unit) {
        stop()
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                invoke()
            }
        }
    }

    /** 停止重复循环。抬手、手势取消、或按键离开组合时调用。 */
    fun stop() {
        job?.cancel()
        job = null
    }
}
