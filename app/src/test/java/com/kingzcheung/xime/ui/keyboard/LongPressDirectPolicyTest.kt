package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按「直接上屏角标」判定（用户 2026-11）：
 * 开关开启时，长按不再弹气泡，而是直接上屏键面上原有的角标字符（右上角那个提示字）；
 * 没有角标、或没配上滑动作的键，保持原来的长按行为。
 */
class LongPressDirectPolicyTest {

    @Test
    fun `开关开启且有角标时直接上屏`() {
        assertTrue(
            LongPressDirectPolicy.shouldCommitBadge(
                enabled = true, hasBadge = true, hasSwipeHandler = true,
            )
        )
    }

    @Test
    fun `开关关闭时保留长按气泡`() {
        assertFalse(
            LongPressDirectPolicy.shouldCommitBadge(
                enabled = false, hasBadge = true, hasSwipeHandler = true,
            )
        )
    }

    @Test
    fun `没有角标的键不受影响`() {
        assertFalse(
            LongPressDirectPolicy.shouldCommitBadge(
                enabled = true, hasBadge = false, hasSwipeHandler = true,
            )
        )
    }

    @Test
    fun `没有上滑处理体时不接管长按`() {
        assertFalse(
            LongPressDirectPolicy.shouldCommitBadge(
                enabled = true, hasBadge = true, hasSwipeHandler = false,
            )
        )
    }
}
