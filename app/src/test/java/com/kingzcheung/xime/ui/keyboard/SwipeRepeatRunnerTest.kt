package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.SwipeRepeatSpeed
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按住持续重复」执行器的虚拟时间测试（无需真机）。
 *
 * 真机手感要调参，但三条机制必须可回归：按间隔重复、抬手即停、幅度改变速率。
 * 这三条任意一条坏了，真机表现都是「滑了没反应」或「松手还在删」——属于用户会当成
 * 输入法坏掉的故障，所以用虚拟时间把机制本身钉死。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SwipeRepeatRunnerTest {

    @Test
    fun `按最慢间隔重复触发`() = runTest {
        val runner = SwipeRepeatRunner(this)
        var count = 0
        runner.updateInterval(0f) // 幅度不足 → 最慢速率
        runner.start { count++ }

        // 首次触发由调用方（手势层）完成，循环只负责「之后」的重复
        advanceTimeBy(SwipeRepeatSpeed.SLOWEST_INTERVAL_MS * 3 + 1)
        runCurrent()
        assertEquals(3, count)
        runner.stop()
    }

    @Test
    fun `按最快间隔重复触发`() = runTest {
        val runner = SwipeRepeatRunner(this)
        var count = 0
        runner.updateInterval(SwipeRepeatSpeed.MAX_AMPLITUDE_DP)
        runner.start { count++ }

        advanceTimeBy(SwipeRepeatSpeed.FASTEST_INTERVAL_MS * 4 + 1)
        runCurrent()
        assertEquals(4, count)
        runner.stop()
    }

    @Test
    fun `相同时间内幅度越大触发次数越多`() = runTest {
        val window = SwipeRepeatSpeed.SLOWEST_INTERVAL_MS * 4

        var slowCount = 0
        val slow = SwipeRepeatRunner(this)
        slow.updateInterval(SwipeRepeatSpeed.MIN_AMPLITUDE_DP)
        slow.start { slowCount++ }
        advanceTimeBy(window)
        runCurrent()
        slow.stop()

        var fastCount = 0
        val fast = SwipeRepeatRunner(this)
        fast.updateInterval(SwipeRepeatSpeed.MAX_AMPLITUDE_DP)
        fast.start { fastCount++ }
        advanceTimeBy(window)
        runCurrent()
        fast.stop()

        assertTrue("同窗口内快速档应删得更多：slow=$slowCount fast=$fastCount", fastCount > slowCount)
    }

    @Test
    fun `stop 之后不再触发`() = runTest {
        val runner = SwipeRepeatRunner(this)
        var count = 0
        runner.updateInterval(SwipeRepeatSpeed.MAX_AMPLITUDE_DP)
        runner.start { count++ }

        advanceTimeBy(SwipeRepeatSpeed.FASTEST_INTERVAL_MS * 2 + 1)
        runCurrent()
        val beforeStop = count
        runner.stop()

        advanceTimeBy(SwipeRepeatSpeed.FASTEST_INTERVAL_MS * 10)
        runCurrent()
        assertEquals("抬手后必须停止，否则松手还在删", beforeStop, count)
    }

    @Test
    fun `重复 start 不会并行出两个循环`() = runTest {
        val runner = SwipeRepeatRunner(this)
        var count = 0
        runner.updateInterval(SwipeRepeatSpeed.MAX_AMPLITUDE_DP)
        runner.start { count++ }
        runner.start { count++ }

        advanceTimeBy(SwipeRepeatSpeed.FASTEST_INTERVAL_MS * 3 + 1)
        runCurrent()
        // 并行两个循环会得到 6；start 必须先停掉上一个
        assertEquals(3, count)
        runner.stop()
    }
}
