package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮四项需求的接线契约测试。
 *
 * 这四项都属于「不报错、不崩，只在真机上才看得出来」的行为：
 * 1. 大写金额模式负数结果必须带「负」，否则会当成正数错钱；
 * 2. 中英键盘删除键左滑要能按住连删且幅度越大越快（图标键此前显式关闭重复）；
 * 3. 第三行第一个键（shift）有候选或处于编码态时要变 esc 并能清空候选/编码；
 * 4. 换行键上滑要重复输出上一次上屏内容。
 *
 * 因此这里锁住「有没有真的接起来」：配置项、动作映射、渲染分支缺一处都会静默失效。
 */
class KeyboardBehaviorContractTest {

    private fun source(name: String): String = RepoFiles.mainKotlinSources
        .single { it.name == name }
        .readText()
        .replace("\r\n", "\n")

    private fun section(layout: String): Map<String, KeyBinding> {
        val text = RepoFiles.file("src/main/assets/xime.yaml").readText()
        return KeysConfigHelper.parseKeyboardYamlSection(text, layout, emptyMap())
            ?: error("解析 keyboard.$layout.keys 失败")
    }

    // ── 2. 中英键盘删除键左滑按住连删 ──

    @Test
    fun `中英键盘删除键左滑均为按住快速删除`() {
        for (layout in listOf("qwerty", "qwerty_en")) {
            val delete = section(layout)["delete"] ?: error("$layout 缺少 delete")
            assertEquals(
                "$layout 删除键左滑应为按住快速删除",
                GestureAction.REPEAT_DELETE, delete.swipeLeft?.action,
            )
        }
    }

    @Test
    fun `图标删除键把重复开关接到手势层`() {
        // 中英键盘删除键走 SwipeableIconKeyButton（图标键），它此前显式 repeats = false，
        // 左滑只删一次。这里锁住重复开关与调用点的接线。
        val keyButton = source("KeyButton.kt")
        assertTrue(
            "图标键缺少 swipeLeftRepeats 参数：左滑无法按住连删",
            keyButton.contains("swipeLeftRepeats: Boolean = false"),
        )
        assertTrue(
            "图标键未用实时滑动幅度驱动重复速率",
            keyButton.contains("repeats = currentSwipeLeftRepeats"),
        )
        assertTrue(
            "图标键未把滑动幅度换算成重复速率",
            keyButton.contains("amplitudeDp = with(density) { abs(dragOffsetX).toDp().value }"),
        )

        val layout = source("KeyboardLayout.kt")
        assertTrue(
            "删除键未把 repeat_delete 配置接到重复开关",
            layout.contains("swipeLeftRepeats = delLeft.action == GestureAction.REPEAT_DELETE"),
        )
    }

    @Test
    fun `重复删除速率随幅度单调加快`() {
        // 幅度越大间隔越短：反证「越滑越慢」这类只在真机上能感觉出来的回归
        val slow = com.kingzcheung.xime.keyboard.SwipeRepeatSpeed.intervalMs(40f)
        val mid = com.kingzcheung.xime.keyboard.SwipeRepeatSpeed.intervalMs(75f)
        val fast = com.kingzcheung.xime.keyboard.SwipeRepeatSpeed.intervalMs(110f)
        assertTrue("幅度越大间隔必须越短：$slow / $mid / $fast", slow > mid && mid > fast)
    }

    @Test
    fun `图标键按住连删必须在抬手与取消时停止`() {
        // 回归（真机反馈「左滑后不能正常打字、会闪」）：只启动不停止时，
        // 协程循环在抬手后继续狂删，界面持续闪烁且无法输入。
        val keyButton = source("KeyButton.kt")
        // 图标键（SwipeableIconKeyButton）这一段：从其签名到下一个顶层组件之间
        val iconKey = keyButton.substringAfter("fun SwipeableIconKeyButton(")
            .substringBefore("@Composable\nfun SwipeableKeyButtonLandscape(")
        val dragEnd = iconKey.substringAfter("onDragEnd = {").substringBefore("hasReachedClearThreshold")
        val dragCancel = iconKey.substringAfter("onDragCancel = {").substringBefore("dragActivated = false")
        assertTrue(
            "抬手时必须停止重复删除循环，否则会一直删下去（界面闪、无法打字）",
            dragEnd.contains("swipeRepeatRunner.stop()"),
        )
        assertTrue(
            "手势被系统取消时也必须停止重复删除循环",
            dragCancel.contains("swipeRepeatRunner.stop()"),
        )
    }

    // ── 3. 第三行第一个键（shift）候选态变 esc ──

    @Test
    fun `有候选时第三行第一个键显示 esc 并清空候选`() {
        val layout = source("KeyboardLayout.kt")
        val branch = layout.substringAfter("if (env.hasCandidates) {").substringBefore("val shiftBinding")
        assertTrue("候选态未显示 esc", branch.contains("text = \"esc\""))
        assertTrue(
            "esc 点击应清空当前候选（clear_composition 链路）",
            branch.contains("env.onKeyPress(\"clear_composition\")"),
        )
    }

    @Test
    fun `编码态不管有没有候选都显示 esc`() {
        // 无候选且非编码态时必须保持原 shift 行为，否则大小写切换一直失效；
        // 编码态（编码打不出来时候选数为 0）则必须转 esc，否则清不了已敲的编码。
        val layout = source("KeyboardLayout.kt")
        assertTrue(
            "hasCandidates 应由候选数量或编码态推导",
            layout.contains(
                "val hasCandidates = shouldShiftShowEsc(callbacks.candidateCount(), callbacks.isComposing())"
            ),
        )
        assertTrue(
            "缺少 shouldShiftShowEsc 纯函数（判定逻辑无法单测）",
            layout.contains("internal fun shouldShiftShowEsc(candidateCount: Int, isComposing: Boolean): Boolean"),
        )
        val env = layout.substringAfter("class QwertyRowEnv(").substringBefore("\n)")
        assertTrue("env 缺少 hasCandidates 字段", env.contains("val hasCandidates: Boolean = false"))
    }

    // ── 4. 换行键上滑重复上一次上屏 ──

    @Test
    fun `中英键盘换行键上滑为重复上一次上屏`() {
        for (layout in listOf("qwerty", "qwerty_en")) {
            val enter = section(layout)["enter"] ?: error("$layout 缺少 enter")
            assertEquals(
                "$layout 换行键上滑应重复上一次上屏内容",
                GestureAction.REPEAT, enter.swipeUp?.action,
            )
            // 角标文案固定为「重复」：过长会截断，用户看不出这是重复
            assertEquals("$layout 换行键上滑角标文案", "重复", enter.swipeUp?.label)
        }
    }

    @Test
    fun `换行键上滑未配置时也退化为重复上次`() {
        // 自定义配置删掉 swipe_up 时行为不能消失（内置默认兜底）
        val layout = source("KeyboardLayout.kt")
        val cell = layout.substringAfter("private fun EnterCell(").substringBefore("private fun DeleteCell(")
        assertTrue(
            "换行键未接上滑动作",
            cell.contains("onSwipe = { invokeKeyAction(enterSwipeUp"),
        )
        assertTrue(
            "换行键上滑默认动作应为 repeat",
            cell.contains("?: KeyAction(GestureAction.REPEAT, label = \"重复\")"),
        )
    }
}
