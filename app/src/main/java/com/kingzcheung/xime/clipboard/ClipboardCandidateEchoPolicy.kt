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
 * 所以上屏时必须显式关闭展示态并抑制这次回声，但**不能长期抑制**：
 * 用户上屏后马上又复制了新内容，必须照常展示。
 */
internal object ClipboardCandidateEchoPolicy {

    /**
     * 上屏后的这次剪贴板变更，是否应该重新把内容放进候选栏。
     *
     * @param suppressed        是否处于「刚上屏」的抑制窗口内
     * @param currentItemIds    本次变更后候选栏会展示的条目 id
     * @param baselineIds       抑制开始时已知的条目 id 快照
     * @return true 表示要展示（并把抑制解除）；false 表示跳过（回声，继续保持抑制）
     */
    fun shouldShow(
        suppressed: Boolean,
        currentItemIds: List<Long>,
        baselineIds: Set<Long>,
    ): Boolean {
        if (!suppressed) return true
        // 抑制窗口内：只要出现基线之外的新 id，说明用户确实又复制了新内容，必须展示
        return currentItemIds.any { it !in baselineIds }
    }
}