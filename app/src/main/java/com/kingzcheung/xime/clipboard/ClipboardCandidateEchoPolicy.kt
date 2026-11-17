package com.kingzcheung.xime.clipboard

/**
 * 「剪贴板上屏后候选栏是否应重新展示」的判定。
 *
 * 现象（用户 2026-11 反馈）：点选一条剪贴板候选上屏后，候选栏里它还在（或其它几条还在）。
 *
 * 成因是一条**回声**链路：
 * 1. 点选上屏 → 对该条目标记 `consumed=1`；
 * 2. `clipboardItems` 是数据库 Flow，`consumed` 落库必然再发射一次；
 * 3. 候选栏的 collector 只要判定「空闲」（非组字、无编码）就调 `showClipboardCandidates`；
 * 4. 于是「过滤掉刚上屏那条之后的剩余条目」又被推回候选栏。
 *
 * 所以上屏时必须显式关闭展示态并抑制这次回声，但**不能长期抑制**，
 * 也不能把「上屏后马上又复制的新内容」一起吃掉。
 */
internal object ClipboardCandidateEchoPolicy {

    /**
     * 上屏后的这次剪贴板变更，是否应该重新把内容放进候选栏。
     *
     * 判据用**时间戳**而不是 id 快照：只看有没有「上屏时刻之后才复制的内容」。
     *
     * 为什么不用 id 基线：上屏瞬间记录的「已知 id 集合」可能**已经包含**刚落库的新内容
     * （入库与上屏是两个异步链路，顺序不保证），那样的新条目会被误判成回声而跳过展示，
     * 表现就是「有时候复制了新内容不显示」。时间戳判据不受两条链路先后影响：
     * 上屏之后再复制的内容，timestamp 必然大于上屏时刻。
     *
     * @param suppressed     是否处于「刚上屏」的抑制窗口内
     * @param freshItemCount 本次变更中 timestamp 晚于上屏时刻的条目数
     */
    fun shouldShow(
        suppressed: Boolean,
        freshItemCount: Int,
    ): Boolean {
        if (!suppressed) return true
        // 抑制窗口内：只有真正的「新内容」才展示，纯回声（frozen 列表）跳过
        return freshItemCount > 0
    }
}