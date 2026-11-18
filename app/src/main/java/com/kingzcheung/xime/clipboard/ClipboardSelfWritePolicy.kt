package com.kingzcheung.xime.clipboard

/**
 * 「本应用自己写回系统剪贴板」的回声抑制判定（纯函数，便于单测）。
 *
 * 背景（用户 2026-11 反复反馈「上屏了还在候选栏显示」）：
 *
 * 点选剪贴板候选上屏时，宿主会把这段文本**写回系统剪贴板**（让用户能在别处粘贴），
 * 这会触发 `OnPrimaryClipChangedListener`，于是宿主又把自己刚上屏的内容当新内容采集
 * 一遍；那条被 `markConsumedExact` 标记过的行被 `revive` 复活（`consumed` 复位为 0），
 * 候选栏就把它又推出来了。
 *
 * 旧实现用一次性标记：命中即 `selfWrittenText = null`。但一次写入会读到**不止一次**：
 * MIUI/HyperOS 的剪贴板监听会连发两次，紧随其后的补采（[CaptureSource.PROBE]）还会再读
 * 一次。日志实测两次读只隔 39ms：
 *
 * ```
 * 21:55:34.113 Self-written text clip, skip capture   ← 第一次：吃掉标记
 * 21:55:34.152 剪贴板入库: len=20 id=595              ← 第二次：标记已空 → 复活 id=595
 * ```
 *
 * 因此改成**窗口期**判定：命中后不清标记，只要还在窗口内，同一文本的每次读取都跳过。
 */
object ClipboardSelfWritePolicy {

    /**
     * 自写标记的有效窗口。
     *
     * 要盖住：ROM 的重复回调（实测 <100ms）、紧随其后的窗口补采（150ms 兜底 + 补采链路，
     * 实测 <1s）。又不能太长：用户「上屏后马上又自己复制同一段文本」必须能正常入库并复活，
     * 1.5s 足够短，正常人不会在 1.5 秒内重复复制同一段内容。
     */
    const val SUPPRESS_WINDOW_MS = 1_500L

    /**
     * 本次读到的文本是否属于「本应用刚写回的那条」，应跳过采集。
     *
     * @param text 本次读到的剪贴板文本
     * @param selfText 本应用最近写回的文本（null 表示没有待抑制的写入）
     * @param nowMs 当前单调时钟（`SystemClock.uptimeMillis()`）
     * @param suppressUntilMs 抑制窗口截止时刻
     */
    fun shouldSuppressClipCapture(
        text: String?,
        selfText: String?,
        nowMs: Long,
        suppressUntilMs: Long,
    ): Boolean {
        if (text.isNullOrEmpty() || selfText == null) return false
        if (selfText != text) return false
        return nowMs < suppressUntilMs
    }

    /**
     * 写回系统剪贴板是否是**空操作**（当前剪贴板内容已经一模一样）。
     *
     * 是空操作就不要写：`setPrimaryClip` 必然触发一次 `OnPrimaryClipChangedListener`，
     * 而这次回调正是「上屏后候选栏又出现」的源头。剪贴板候选上屏时内容**本来就在剪贴板里**，
     * 绝大多数情况都是空操作，直接跳过等于从根上不产生回声。
     *
     * 注意只对纯文本比较：当前剪贴板是图片/URI（`currentClipboardText` 为 null）时不算空操作，
     * 该写还是要写。
     */
    fun isNoOpWrite(currentClipboardText: String?, text: String): Boolean =
        !currentClipboardText.isNullOrEmpty() && currentClipboardText == text
}
