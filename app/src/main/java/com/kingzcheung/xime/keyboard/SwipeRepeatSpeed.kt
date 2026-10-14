package com.kingzcheung.xime.keyboard

import kotlin.math.roundToLong

/**
 * 「滑动幅度 → 重复速率」映射。
 *
 * 用于删除键左滑的快速删除：按住不放时按固定间隔重复删除，**滑动幅度越大间隔越短**
 * （删得越快），抬手即停。
 *
 * 抽成不依赖 Compose 的纯函数便于单测：真机手感需要调参，但映射关系本身必须可回归
 * ——否则改一个常量就可能把「越滑越快」改成「越滑越慢」而无人察觉。
 *
 * 幅度取滑动位移的绝对值（dp）。低于 [MIN_AMPLITUDE_DP] 一律最慢，高于
 * [MAX_AMPLITUDE_DP] 一律最快，中间线性插值。
 */
object SwipeRepeatSpeed {

    /**
     * 达到或低于此幅度（dp）时用最慢速率。
     * 取值需 ≤ 按键左滑触发阈值（50dp），保证刚触发时落在慢速端、不会一上来就狂删。
     */
    const val MIN_AMPLITUDE_DP = 40f

    /** 达到或高于此幅度（dp）时用最快速率。 */
    const val MAX_AMPLITUDE_DP = 110f

    /** 最慢重复间隔（毫秒）。 */
    const val SLOWEST_INTERVAL_MS = 160L

    /** 最快重复间隔（毫秒）。留 40ms 下限，避免过快导致一次误触删除大量正文。 */
    const val FASTEST_INTERVAL_MS = 40L

    /**
     * 按滑动幅度取重复间隔（毫秒）：幅度越大间隔越短。
     *
     * 非有限值（NaN/Inf）按最慢速率处理——异常输入若被算成 0 会变成无间隔忙循环，
     * 在输入法里等于瞬间删光正文。
     */
    fun intervalMs(amplitudeDp: Float): Long {
        if (!amplitudeDp.isFinite()) return SLOWEST_INTERVAL_MS
        val ratio = ((amplitudeDp - MIN_AMPLITUDE_DP) / (MAX_AMPLITUDE_DP - MIN_AMPLITUDE_DP))
            .coerceIn(0f, 1f)
        val span = SLOWEST_INTERVAL_MS - FASTEST_INTERVAL_MS
        return SLOWEST_INTERVAL_MS - (span * ratio).roundToLong()
    }
}
