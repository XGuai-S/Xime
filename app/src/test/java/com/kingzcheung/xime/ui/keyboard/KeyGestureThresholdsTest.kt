package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键手势阈值的**关系**约束。
 *
 * 这里断言的是阈值之间的相对关系，而不是具体数值 —— 数值可以按手感调整，
 * 但下面每条关系一旦破坏都是真实故障（故障表现见各测试注释与
 * [KeyGestureThresholds] 的文档）。
 */
class KeyGestureThresholdsTest {

    @Test
    fun `点击取消阈值必须与光标手势激活阈值同值`() {
        // 不同值会在「点击已取消、但光标手势尚未激活」的位移区间产生死区，表现为打字吃键。
        // 该断言同时守护设计本身：TAP_CANCEL_DP 若被改成独立字面量并发生漂移，此处立即失败。
        assertEquals(
            "点击取消阈值必须与光标手势激活阈值同值",
            KeyGestureThresholds.CURSOR_ACTIVATION_DP.toDouble(),
            KeyGestureThresholds.TAP_CANCEL_DP.toDouble(),
            0.0,
        )
    }

    @Test
    fun `横屏横向接管阈值必须低于光标手势激活阈值`() {
        // 高于或等于它，配置了左右滑的键会被父层光标手势抢走横向滑动。
        assertTrue(
            "横屏横向接管阈值必须低于光标手势激活阈值",
            KeyGestureThresholds.LANDSCAPE_HORIZONTAL_SWIPE_SUPPRESS_DP <
                KeyGestureThresholds.CURSOR_ACTIVATION_DP,
        )
    }

    @Test
    fun `气泡显示提前量必须在 0 到 1 开区间`() {
        // 等于 1 时气泡与滑动触发同时发生（等于看不见），大于 1 则永远不显示。
        assertTrue(
            "气泡显示提前量必须大于 0",
            KeyGestureThresholds.BUBBLE_SHOW_RATIO > 0f,
        )
        assertTrue(
            "气泡显示提前量必须小于 1",
            KeyGestureThresholds.BUBBLE_SHOW_RATIO < 1f,
        )
    }

    @Test
    fun `横屏竖向滑动阈值必须比竖屏灵敏`() {
        // 横屏键高更矮，沿用竖屏阈值会让竖向滑动几乎触发不了。
        assertTrue(
            "横屏竖向滑动阈值必须小于竖屏",
            KeyGestureThresholds.LANDSCAPE_VERTICAL_SWIPE_DP <
                KeyGestureThresholds.VERTICAL_SWIPE_DP,
        )
    }

    @Test
    fun `拖动取消阈值必须小于横向接管阈值`() {
        // 「判定是否点击」必须早于「横向手势接管」，否则点击会被无谓取消。
        assertTrue(
            "拖动取消阈值必须小于横向接管阈值",
            KeyGestureThresholds.DRAG_CANCEL_DP <
                KeyGestureThresholds.LANDSCAPE_HORIZONTAL_SWIPE_SUPPRESS_DP,
        )
    }

    @Test
    fun `所有阈值必须为正数`() {
        val values = mapOf(
            "CURSOR_ACTIVATION_DP" to KeyGestureThresholds.CURSOR_ACTIVATION_DP,
            "CURSOR_STEP_DP" to KeyGestureThresholds.CURSOR_STEP_DP,
            "VERTICAL_SWIPE_DP" to KeyGestureThresholds.VERTICAL_SWIPE_DP,
            "HORIZONTAL_SWIPE_DP" to KeyGestureThresholds.HORIZONTAL_SWIPE_DP,
            "HORIZONTAL_SWIPE_SUPPRESS_DP" to KeyGestureThresholds.HORIZONTAL_SWIPE_SUPPRESS_DP,
            "DRAG_CANCEL_DP" to KeyGestureThresholds.DRAG_CANCEL_DP,
            "SWIPE_DIRECTION_DP" to KeyGestureThresholds.SWIPE_DIRECTION_DP,
            "CODE_KEYBOARD_SWIPE_UP_DP" to KeyGestureThresholds.CODE_KEYBOARD_SWIPE_UP_DP,
            "LANDSCAPE_VERTICAL_SWIPE_DP" to KeyGestureThresholds.LANDSCAPE_VERTICAL_SWIPE_DP,
            "LANDSCAPE_HORIZONTAL_SWIPE_DP" to KeyGestureThresholds.LANDSCAPE_HORIZONTAL_SWIPE_DP,
            "LANDSCAPE_HORIZONTAL_SWIPE_SUPPRESS_DP" to
                KeyGestureThresholds.LANDSCAPE_HORIZONTAL_SWIPE_SUPPRESS_DP,
        )
        values.forEach { (name, value) ->
            assertTrue("$name 必须为正数，实际 $value", value > 0f)
        }
    }
}
