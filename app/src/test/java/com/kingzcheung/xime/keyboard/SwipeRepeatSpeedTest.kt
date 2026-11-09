package com.kingzcheung.xime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「滑动幅度 → 重复速率」映射的回归测试。
 *
 * 真机手感需要调参，但「幅度越大越快」这个方向性契约必须锁住：常量一旦被改反，
 * 表现会变成「越滑越慢」——真机上极难发现，用户也不会为此专门提 issue。
 */
class SwipeRepeatSpeedTest {

    @Test
    fun `幅度越大间隔越短`() {
        val samples = listOf(0f, 20f, 40f, 55f, 70f, 90f, 110f, 200f)
        val intervals = samples.map { SwipeRepeatSpeed.intervalMs(it) }
        intervals.zipWithNext { smallerAmplitude, largerAmplitude ->
            assertTrue("幅度增大后间隔不应变长，实际序列=$intervals", largerAmplitude <= smallerAmplitude)
        }
    }

    @Test
    fun `低于下限用最慢速率`() {
        assertEquals(SwipeRepeatSpeed.SLOWEST_INTERVAL_MS, SwipeRepeatSpeed.intervalMs(0f))
        assertEquals(
            SwipeRepeatSpeed.SLOWEST_INTERVAL_MS,
            SwipeRepeatSpeed.intervalMs(SwipeRepeatSpeed.MIN_AMPLITUDE_DP)
        )
    }

    @Test
    fun `高于上限用最快速率`() {
        assertEquals(
            SwipeRepeatSpeed.FASTEST_INTERVAL_MS,
            SwipeRepeatSpeed.intervalMs(SwipeRepeatSpeed.MAX_AMPLITUDE_DP)
        )
        assertEquals(SwipeRepeatSpeed.FASTEST_INTERVAL_MS, SwipeRepeatSpeed.intervalMs(10_000f))
    }

    @Test
    fun `异常输入按最慢速率处理`() {
        // 若被算成 0 会退化成无间隔忙循环，在输入法里等于瞬间删光正文
        assertEquals(SwipeRepeatSpeed.SLOWEST_INTERVAL_MS, SwipeRepeatSpeed.intervalMs(Float.NaN))
        assertEquals(SwipeRepeatSpeed.SLOWEST_INTERVAL_MS, SwipeRepeatSpeed.intervalMs(Float.POSITIVE_INFINITY))
        assertEquals(SwipeRepeatSpeed.SLOWEST_INTERVAL_MS, SwipeRepeatSpeed.intervalMs(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `间隔始终落在快慢区间内`() {
        for (amplitude in 0..300) {
            val interval = SwipeRepeatSpeed.intervalMs(amplitude.toFloat())
            assertTrue(
                "幅度 $amplitude 的间隔 $interval 越界",
                interval in SwipeRepeatSpeed.FASTEST_INTERVAL_MS..SwipeRepeatSpeed.SLOWEST_INTERVAL_MS
            )
        }
    }

    @Test
    fun `左滑触发阈值处不落在最快速率`() {
        // 竖屏按键左滑触发阈值 50dp：刚触发就已经最快会让用户一滑就狂删正文
        assertTrue(
            "50dp 处不应已是最快速率",
            SwipeRepeatSpeed.intervalMs(50f) > SwipeRepeatSpeed.FASTEST_INTERVAL_MS
        )
    }
}
