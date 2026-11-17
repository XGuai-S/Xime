package com.kingzcheung.xime.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「剪贴板上屏后候选栏不再重复显示」的回归锁。
 *
 * 用户 2026-11 反馈：点选剪贴板候选上屏后，候选栏里它还在。
 * 成因是 consumed 落库引发的 Flow 回声把剩余条目重新推回候选栏。
 */
class ClipboardCandidateEchoPolicyTest {

    @Test
    fun `not suppressed always shows`() {
        assertTrue(
            ClipboardCandidateEchoPolicy.shouldShow(
                suppressed = false,
                currentItemIds = listOf(1L, 2L),
                baselineIds = emptySet(),
            )
        )
    }

    @Test
    fun `suppressed echo of known items is skipped`() {
        // 上屏 id=1 后，Flow 再发射一次：只剩 id=2（基线里已有）
        // 这正是「上屏了候选栏还在显示」的那次变更，必须跳过
        assertFalse(
            ClipboardCandidateEchoPolicy.shouldShow(
                suppressed = true,
                currentItemIds = listOf(2L),
                baselineIds = setOf(1L, 2L),
            )
        )
    }

    @Test
    fun `suppressed but empty list is skipped`() {
        assertFalse(
            ClipboardCandidateEchoPolicy.shouldShow(
                suppressed = true,
                currentItemIds = emptyList(),
                baselineIds = setOf(1L),
            )
        )
    }

    @Test
    fun `suppressed with brand new item must show`() {
        // 上屏后用户立刻又复制了新内容（id=99 不在基线里）：不能被抑制吃掉
        assertTrue(
            ClipboardCandidateEchoPolicy.shouldShow(
                suppressed = true,
                currentItemIds = listOf(99L, 2L),
                baselineIds = setOf(1L, 2L),
            )
        )
    }

    @Test
    fun `suppressed baseline empty shows anything`() {
        // 上屏时面板里本来没有条目（基线空），后续任何内容都算新内容
        assertTrue(
            ClipboardCandidateEchoPolicy.shouldShow(
                suppressed = true,
                currentItemIds = listOf(7L),
                baselineIds = emptySet(),
            )
        )
    }
}