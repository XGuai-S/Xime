package com.kingzcheung.xime.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「剪贴板上屏后候选栏是否重新展示」的回归锁。
 *
 * 用户 2026-11 反馈：点选剪贴板候选上屏后，候选栏里它还在。
 * 修完之后又发现「复制了新内容有时候不显示」——因为抑制用错了判据（id 快照）。
 */
class ClipboardCandidateEchoPolicyTest {

    @Test
    fun `not suppressed always shows`() {
        assertTrue(
            ClipboardCandidateEchoPolicy.shouldShow(suppressed = false, freshItemCount = 0)
        )
    }

    @Test
    fun `suppressed echo is skipped`() {
        // 上屏后 Flow 因 consumed 落库再发射一次，此时没有任何「上屏之后才来的内容」
        // 这正是「上屏了候选栏还在显示」的那次变更，必须跳过
        assertFalse(
            ClipboardCandidateEchoPolicy.shouldShow(suppressed = true, freshItemCount = 0)
        )
    }

    @Test
    fun `suppressed with genuinely new content must show`() {
        // 上屏后用户立刻又复制了新内容（时间戳晚于上屏时刻）：不能被抑制吃掉。
        // 判据用时间戳而非 id 基线 —— 入库与上屏是异步链路，id 基线会把刚落库的新内容算进去。
        assertTrue(
            ClipboardCandidateEchoPolicy.shouldShow(suppressed = true, freshItemCount = 1)
        )
        assertTrue(
            ClipboardCandidateEchoPolicy.shouldShow(suppressed = true, freshItemCount = 3)
        )
    }
}