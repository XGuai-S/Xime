package com.kingzcheung.xime.clipboard

import com.kingzcheung.xime.clipboard.ClipboardProbePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 补采入库判定（用户 2026-11 反馈的两次回归都在这里，测试即回归锁）。
 *
 * 第一次反馈「复制的内容在候选栏反复出现」：补采把旧剪贴板当新内容 revive。
 * 第二次反馈「复制了新内容有时候不在候选栏显示」：修过头了 —— 把新内容也吞掉了。
 */
class ClipboardProbePolicyTest {

    @Test
    fun `never seen content is always recorded even if unchanged`() {
        // 复制发生时本进程还没起来 → 监听器没回调、标志为 false，但内容确实是新的
        assertEquals(
            Action.RECORD,
            ClipboardProbePolicy.decide(
                seenInPanel = false, consumed = false, changedSinceRecord = false, stillVisible = false
            )
        )
    }

    @Test
    fun `seen and unchanged and still visible is skipped`() {
        // 核心回归 1：键盘每次弹出补采同一份旧剪贴板，绝不能重复入库（否则反复出现）
        assertEquals(
            Action.SKIP,
            ClipboardProbePolicy.decide(
                seenInPanel = true, consumed = false, changedSinceRecord = false, stillVisible = true
            )
        )
    }

    @Test
    fun `seen not consumed but no longer visible must be recorded`() {
        // 核心回归 2：复制后隔了一段时间才弹键盘（条目未消费但已滑出展示窗口）
        // 必须刷新时间戳让它重新进候选栏，否则用户说「复制了内容有时候不显示」
        assertEquals(
            Action.RECORD,
            ClipboardProbePolicy.decide(
                seenInPanel = true, consumed = false, changedSinceRecord = false, stillVisible = false
            )
        )
    }

    @Test
    fun `seen changed not consumed and visible is skipped`() {
        // 已在候选栏展示，不必再造一次变更事件（避免无谓刷新 timestamp）
        assertEquals(
            Action.SKIP,
            ClipboardProbePolicy.decide(
                seenInPanel = true, consumed = false, changedSinceRecord = true, stillVisible = true
            )
        )
    }

    @Test
    fun `seen changed and consumed is recorded to revive`() {
        // 用户又复制了同一段已被消费的文本 → 必须能重新进候选栏
        assertEquals(
            Action.RECORD,
            ClipboardProbePolicy.decide(
                seenInPanel = true, consumed = true, changedSinceRecord = true, stillVisible = false
            )
        )
    }

    @Test
    fun `seen consumed but unchanged is still skipped`() {
        // 已消费 + 剪贴板没变过 → 只是旧剪贴板残留，不该复活
        assertEquals(
            Action.SKIP,
            ClipboardProbePolicy.decide(
                seenInPanel = true, consumed = true, changedSinceRecord = false, stillVisible = false
            )
        )
    }
}