package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shift 键「长按进入编辑面板」的裁决规则与接线契约测试。
 *
 * 这类问题不会编译失败、也不一定崩，只表现成真机上的手感异常：
 * 长按顺带切了一次大小写、单击变得没反应、或者长按压根没接上线。
 * 手势本身跑在 Compose 指针作用域里无法在 JVM 上回放，因此这里锁两件事：
 * 1. 裁决优先级（[resolveShiftGesture] 是纯函数，可直接测）；
 * 2. 有没有真的接起来（源码级契约）。
 */
class ShiftLongPressGestureTest {

    // ── 裁决优先级 ──

    @Test
    fun `未滑动未长按就是单击`() {
        assertEquals(ShiftGestureOutcome.Tap, resolveShiftGesture(direction = null, longPressTriggered = false))
    }

    @Test
    fun `长按后不再是单击`() {
        // 关键：长按若落到单击，就会在打开编辑面板的同时顺带切换大小写
        assertEquals(ShiftGestureOutcome.LongPress, resolveShiftGesture(direction = null, longPressTriggered = true))
    }

    @Test
    fun `滑动压过长按`() {
        listOf("up", "down", "left", "right").forEach { dir ->
            assertEquals(
                "方向 $dir 应判为滑动",
                ShiftGestureOutcome.Swipe(dir),
                resolveShiftGesture(direction = dir, longPressTriggered = true),
            )
        }
    }

    @Test
    fun `按住不动必须判成长按而不是单击`() {
        // 反证：长按判定若依赖「方向非空」，按住不动会被判成单击
        assertEquals(ShiftGestureOutcome.LongPress, resolveShiftGesture(null, true))
        assertEquals(ShiftGestureOutcome.Tap, resolveShiftGesture(null, false))
    }

    // ── shift → esc 判定 ──

    @Test
    fun `有候选就显示 esc`() {
        assertTrue(shouldShiftShowEsc(candidateCount = 3, isComposing = false))
    }

    @Test
    fun `编码态无候选也显示 esc`() {
        // 编码打不出来时候选数为 0，但用户仍在编码态：要的是清编码，不是大小写切换
        assertTrue(shouldShiftShowEsc(candidateCount = 0, isComposing = true))
    }

    @Test
    fun `空闲态保持 shift 大小写切换`() {
        assertFalse(shouldShiftShowEsc(candidateCount = 0, isComposing = false))
    }

    // ── 接线契约 ──

    private val layout: String by lazy {
        RepoFiles.mainKotlinSources.single { it.name == "KeyboardLayout.kt" }.readText()
    }

    /** [ShiftCapsKeyButton] 的函数体（到下一个顶层声明为止）。 */
    private val shiftButton: String by lazy {
        layout.substringAfter("private fun ShiftCapsKeyButton(")
            .substringBefore("internal fun splitRowForLandscape(")
    }

    @Test
    fun `第三行第一个键长按接线到编辑面板`() {
        // 中文与英文键盘第三行第一个键都是 shift（layout.rows 决定），
        // 两个键盘共用 ShiftCell，因此只需这一处接线
        assertTrue(
            "ShiftCell 未把长按接到编辑面板",
            layout.contains("""onLongPress = { env.onGestureAction?.invoke(GestureAction.SWITCH_ROUTE, "edit") }"""),
        )
    }

    @Test
    fun `shift 键组件支持长按`() {
        assertTrue("缺少长按参数", shiftButton.contains("onLongPress: (() -> Unit)? = null"))
        assertTrue("长按参数未接入手势", shiftButton.contains("currentOnLongPress?.invoke()"))
        // 长按用独立 job + delay（与 KeyButton / SpaceKeyButton 同一套做法）
        assertTrue(
            "长按未使用既有判定方式",
            shiftButton.contains("delay(KeysConfigHelper.getKeyboardGestures().longPressMs.toLong())"),
        )
        // 长按判定必须抑制后续移动被当成滑动
        assertTrue(
            "长按后仍可能被当成滑动",
            shiftButton.contains("awaitSwipeDirection(down, longPressTriggered = { longPressTriggered })"),
        )
        // 单击必须等抬手：否则长按会先切一次大小写
        assertTrue("未等抬手就触发单击", shiftButton.contains("waitForUpOrCancellation()"))
    }

    @Test
    fun `长按判定不能把整个指针协程取消掉`() {
        // 指针作用域里的超时取消与普通协程超时不是一回事；用 withTimeout 捕错类型
        // 会让该键此后彻底失灵（且只在真机上才看得出来）
        val gesture = shiftButton.substringAfter(".pointerInput(Unit) {").substringBefore(".padding(")
        assertTrue(
            "长按判定应使用 scope.launch + delay，而不是 withTimeout",
            !gesture.contains("withTimeoutOrNull(KeysConfigHelper"),
        )
        assertTrue(
            "长按 job 必须在手势结束时取消，否则抬手后仍会触发",
            gesture.contains("longPressJob.cancel()"),
        )
    }

    @Test
    fun `中文与英文键盘的第三行第一个键都是 shift`() {
        // 用户是按位置描述这个键的；若默认布局把 shift 挪走，长按就会落在别的键上
        val yaml = RepoFiles.file("src/main/assets/xime.yaml").readText()
        val shiftRows = Regex("""^\s+- \[shift, .*]$""", RegexOption.MULTILINE).findAll(yaml).toList()
        assertEquals("中文与英文键盘应各有一条以 shift 开头的第三行", 2, shiftRows.size)
    }
}
