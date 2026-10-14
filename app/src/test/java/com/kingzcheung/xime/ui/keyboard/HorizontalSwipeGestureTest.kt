package com.kingzcheung.xime.ui.keyboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [triggerSwipeLeft] / [triggerSwipeRight] 的行为契约。
 *
 * 这两个函数由三处调用点共用（SwipeableKeyButton、SwipeableIconKeyButton、KeyboardLayout），
 * 抽出的目的就是让「三处行为一致」可被测试约束，而不是靠三份拷贝人肉同步。
 *
 * 重点覆盖 `repeats = false` 那条分支：它是**有意保留**的差异（图标键不做按住重复删除），
 * 此前只能靠读代码判断，现在固定成测试。
 */
class HorizontalSwipeGestureTest {

    private class FakeRepeater : SwipeRepeater {
        val intervals = mutableListOf<Float>()
        var startCount = 0
        var repeatAction: (() -> Unit)? = null

        override fun updateInterval(amplitudeDp: Float) {
            intervals += amplitudeDp
        }

        override fun start(invoke: () -> Unit) {
            startCount++
            repeatAction = invoke
        }
    }

    /** 左滑阈值（负值，与生产配置同向） */
    private val leftThreshold = -50f

    /** 右滑阈值（正值） */
    private val rightThreshold = 60f

    @Test
    fun `左滑越过阈值触发一次并返回已触发`() {
        val repeater = FakeRepeater()
        var fired = 0
        val result = triggerSwipeLeft(
            alreadyTriggered = false,
            onSwipeLeft = { fired++ },
            dragOffsetX = -60f,
            threshold = leftThreshold,
            repeats = false,
            repeater = repeater,
        )
        assertEquals("应触发一次", 1, fired)
        assertTrue("返回值应变为已触发", result)
    }

    @Test
    fun `未越过阈值不触发且状态不变`() {
        var fired = 0
        val result = triggerSwipeLeft(
            alreadyTriggered = false,
            onSwipeLeft = { fired++ },
            dragOffsetX = -10f,
            threshold = leftThreshold,
            repeats = false,
        )
        assertEquals("不应触发", 0, fired)
        assertFalse("返回值应保持未触发", result)
    }

    @Test
    fun `已触发时不再重复触发`() {
        var fired = 0
        val result = triggerSwipeLeft(
            alreadyTriggered = true,
            onSwipeLeft = { fired++ },
            dragOffsetX = -200f,
            threshold = leftThreshold,
            repeats = false,
        )
        assertEquals("已触发过不应再触发", 0, fired)
        assertTrue("返回值应保持已触发", result)
    }

    @Test
    fun `未配置左滑动作时不触发`() {
        val result = triggerSwipeLeft(
            alreadyTriggered = false,
            onSwipeLeft = null,
            dragOffsetX = -200f,
            threshold = leftThreshold,
            repeats = false,
        )
        assertFalse("无动作时不应标记为已触发", result)
    }

    @Test
    fun `repeats 为真时启动重复并按幅度设定间隔`() {
        val repeater = FakeRepeater()
        var fired = 0
        triggerSwipeLeft(
            alreadyTriggered = false,
            onSwipeLeft = { fired++ },
            dragOffsetX = -80f,
            threshold = leftThreshold,
            repeats = true,
            amplitudeDp = 30f,
            repeater = repeater,
        )
        assertEquals("首次触发由 onSwipeLeft 完成", 1, fired)
        assertEquals("应启动一次重复循环", 1, repeater.startCount)
        assertEquals("应按实时幅度设定间隔", listOf(30f), repeater.intervals)
    }

    @Test
    fun `repeats 为真且已触发时只跟随幅度更新间隔`() {
        val repeater = FakeRepeater()
        var fired = 0
        triggerSwipeLeft(
            alreadyTriggered = true,
            onSwipeLeft = { fired++ },
            dragOffsetX = -120f,
            threshold = leftThreshold,
            repeats = true,
            amplitudeDp = 45f,
            repeater = repeater,
        )
        assertEquals("已触发过不应再次触发首次动作", 0, fired)
        assertEquals("不应重复启动循环", 0, repeater.startCount)
        assertEquals("应跟随幅度更新间隔", listOf(45f), repeater.intervals)
    }

    @Test
    fun `repeats 为假时不启动重复且不设定间隔`() {
        // 图标键（数字/符号面板的删除键）走这条路径：这是有意保留的差异
        val repeater = FakeRepeater()
        triggerSwipeLeft(
            alreadyTriggered = false,
            onSwipeLeft = { },
            dragOffsetX = -80f,
            threshold = leftThreshold,
            repeats = false,
            amplitudeDp = 30f,
            repeater = repeater,
        )
        assertEquals("不应启动重复循环", 0, repeater.startCount)
        assertTrue("不应设定任何间隔", repeater.intervals.isEmpty())
    }

    @Test
    fun `重复循环拿到的是 repeatAction 而非首次动作`() {
        // 生产调用点传 { currentOnSwipeLeft?.invoke() }：每次重复都重读 rememberUpdatedState，
        // 因此按住期间回调被替换也能跟上。若这里改成复用 onSwipeLeft 就会退化为固定旧回调。
        val repeater = FakeRepeater()
        val order = mutableListOf<String>()
        triggerSwipeLeft(
            alreadyTriggered = false,
            onSwipeLeft = { order += "first" },
            repeatAction = { order += "repeat" },
            dragOffsetX = -80f,
            threshold = leftThreshold,
            repeats = true,
            amplitudeDp = 30f,
            repeater = repeater,
        )
        assertEquals("首次应是 onSwipeLeft", listOf("first"), order)
        repeater.repeatAction?.invoke()
        assertEquals("重复应是 repeatAction", listOf("first", "repeat"), order)
    }

    @Test
    fun `右滑越过阈值触发一次`() {
        var fired = 0
        val result = triggerSwipeRight(
            alreadyTriggered = false,
            onSwipeRight = { fired++ },
            dragOffsetX = 80f,
            threshold = rightThreshold,
        )
        assertEquals("应触发一次", 1, fired)
        assertTrue("返回值应变为已触发", result)
    }

    @Test
    fun `右滑未越过阈值或未配置或已触发时不触发`() {
        var fired = 0
        assertFalse(
            triggerSwipeRight(false, { fired++ }, 10f, rightThreshold)
        )
        assertFalse(
            triggerSwipeRight(false, null, 200f, rightThreshold)
        )
        assertTrue(
            triggerSwipeRight(true, { fired++ }, 200f, rightThreshold)
        )
        assertEquals("三种情况都不应触发", 0, fired)
    }

    @Test
    fun `repeater 为 null 且 repeats 为真时不崩溃`() {
        // 未持有执行器的调用点（图标键）传 null；即使误传 repeats = true 也只应静默不重复
        var fired = 0
        val result = triggerSwipeLeft(
            alreadyTriggered = false,
            onSwipeLeft = { fired++ },
            dragOffsetX = -80f,
            threshold = leftThreshold,
            repeats = true,
            repeater = null,
        )
        assertEquals("首次动作仍应执行", 1, fired)
        assertTrue(result)
    }

    @Test
    fun `SwipeRepeatRunner 可作为 SwipeRepeater 使用`() {
        // 生产执行器必须满足接口，否则三处调用点无法共用同一份触发逻辑
        val runner: SwipeRepeater = SwipeRepeatRunner(CoroutineScope(Dispatchers.Unconfined))
        runner.updateInterval(30f)
        // 只验证接口可用与调用不崩溃；协程循环本身由 SwipeRepeatSpeedTest 覆盖
        assertTrue(true)
    }
}
