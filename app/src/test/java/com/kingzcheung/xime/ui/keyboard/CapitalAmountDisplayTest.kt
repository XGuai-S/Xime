package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数字键盘「大写金额模式」候选栏回显（算式 + 大写金额）的规则与接线测试。
 *
 * 该模式把输入框内容整串重写成大写金额，用户因此看不见自己按了哪些数字——
 * 候选栏回显的算式就是唯一的「运算过程」。这条链路横跨键盘 Composable → 服务回调 →
 * CandidateState → CandidateBarState → 候选栏，任何一环断开都只是「候选栏空着」，
 * 不报错也不崩，所以两件事都要锁：
 * 1. 显示规则（纯函数，可直接测）；
 * 2. 有没有真的接起来（源码级契约）。
 */
class CapitalAmountDisplayTest {

    // ── 显示规则 ──

    @Test
    fun `模式外不显示`() {
        // 退出模式后输入框里留下的金额是普通文本，候选栏不该再挂着算式
        assertFalse(CapitalAmountKeys.shouldDisplay("壹佰陆拾捌元整", capitalMode = false))
    }

    @Test
    fun `模式内但没有金额时不显示`() {
        // 刚进入模式（什么都还没按）、或退格退到没有数值：输入框里没有金额，
        // 候选栏再挂一条只会是噪音
        assertFalse(CapitalAmountKeys.shouldDisplay("", capitalMode = true))
        // 反证：只按「在模式内」判断，进入模式后候选栏会空挂一条没有金额的候选
        assertTrue(CapitalAmountKeys.shouldDisplay("壹元整", capitalMode = true))
    }

    @Test
    fun `两个候选与普通计算器同形`() {
        // 截图里的普通计算器是「1+1 = 2」这种两条：第一个纯结果、第二个带算式。
        // 大写模式必须同形，用户才不会觉得是两套东西。
        val candidates = CapitalAmountKeys.candidates("123+45", "壹佰陆拾捌元整")
        assertEquals(2, candidates.size)
        assertEquals("壹佰陆拾捌元整", candidates[0])
        assertEquals("123+45 = 壹佰陆拾捌元整", candidates[1])
    }

    @Test
    fun `纯数字也带上算式`() {
        // 输入框里是「壹佰贰拾叁元整」，候选栏里的「123」是用户唯一能看见的原始输入
        assertEquals(
            listOf("壹佰贰拾叁元整", "123 = 壹佰贰拾叁元整"),
            CapitalAmountKeys.candidates("123", "壹佰贰拾叁元整"),
        )
    }

    @Test
    fun `末尾运算符原样带上`() {
        // 按下运算符时金额不变（值仍是 123），但算式要立刻显示出来，
        // 否则用户看不出刚才那一按是否生效
        assertEquals(
            listOf("壹佰贰拾叁元整", "123+ = 壹佰贰拾叁元整"),
            CapitalAmountKeys.candidates("123+", "壹佰贰拾叁元整"),
        )
    }

    @Test
    fun `算式末尾小数点也带上`() {
        assertEquals(
            listOf("壹佰贰拾叁元整", "123. = 壹佰贰拾叁元整"),
            CapitalAmountKeys.candidates("123.", "壹佰贰拾叁元整"),
        )
    }

    // ── 候选栏状态优先级 ──

    @Test
    fun `大写金额回显优先于候选与联想`() {
        // 此刻屏幕上的真实状态是「一笔已上屏的金额」，此时还渲染引擎候选，
        // 用户会以为能点选——而点下去只会把金额再上屏一次
        val state = CandidateBarState.from(
            candidates = listOf("候选一", "候选二"),
            candidateComments = listOf("", ""),
            inputText = "abc",
            isComposing = true,
            associationCandidates = listOf("联想"),
            isShowingRecentClipboard = false,
            hasNextPage = false,
            capitalAmountExpression = "123+45",
            capitalAmountAmount = "壹佰陆拾捌元整",
        )
        assertTrue("应是 CapitalAmount 状态，实际 $state", state is CandidateBarState.CapitalAmount)
        assertEquals("123+45", (state as CandidateBarState.CapitalAmount).expression)
        assertEquals("壹佰陆拾捌元整", state.amount)
        assertEquals(
            listOf("壹佰陆拾捌元整", "123+45 = 壹佰陆拾捌元整"),
            state.candidates,
        )
    }

    @Test
    fun `金额为空时回落到原有分支`() {
        // 反证：若只按算式非空判断，模式外的空算式会顶掉正常的候选渲染
        val state = CandidateBarState.from(
            candidates = listOf("候选一"),
            candidateComments = listOf(""),
            inputText = "abc",
            isComposing = true,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = false,
            hasNextPage = false,
            capitalAmountExpression = "",
            capitalAmountAmount = "",
        )
        assertTrue("应回落到 ChineseCandidates，实际 $state", state is CandidateBarState.ChineseCandidates)
    }

    @Test
    fun `剪贴板态下金额为空不受影响`() {
        val state = CandidateBarState.from(
            candidates = listOf("clip"),
            candidateComments = listOf(""),
            inputText = "",
            isComposing = false,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = true,
            hasNextPage = false,
        )
        assertTrue("应是 ClipboardDisplay，实际 $state", state is CandidateBarState.ClipboardDisplay)
    }

    @Test
    fun `大写金额只靠金额判定`() {
        // 关键场景：**没有任何候选**、只有大写金额。
        // 这必须是 CapitalAmount——回显的依据只能是「此刻有大写金额」。
        // 反证：若这一支额外依赖「无候选」之外的任何候选侧条件，这里会掉到 Idle，
        // 用户按了键却看不到算式（不报错、也不崩，只在真机上看得见）。
        val noCandidates = CandidateBarState.from(
            candidates = emptyList(),
            candidateComments = emptyList(),
            inputText = "",
            isComposing = false,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = false,
            hasNextPage = false,
            capitalAmountExpression = "123",
            capitalAmountAmount = "壹佰贰拾叁元整",
        )
        assertTrue(
            "无候选时仍应是 CapitalAmount，实际 $noCandidates",
            noCandidates is CandidateBarState.CapitalAmount,
        )

        // 非剪贴板态、有候选：回显同样优先于候选渲染
        val withCandidates = CandidateBarState.from(
            candidates = listOf("候选一"),
            candidateComments = listOf(""),
            inputText = "abc",
            isComposing = true,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = false,
            hasNextPage = false,
            capitalAmountExpression = "123+45",
            capitalAmountAmount = "壹佰陆拾捌元整",
        )
        assertTrue(
            "有候选时仍应是 CapitalAmount，实际 $withCandidates",
            withCandidates is CandidateBarState.CapitalAmount,
        )

        // 剪贴板态开着时同样须回显金额
        val withClipboard = CandidateBarState.from(
            candidates = listOf("clip"),
            candidateComments = listOf(""),
            inputText = "",
            isComposing = false,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = true,
            hasNextPage = false,
            capitalAmountExpression = "123+45",
            capitalAmountAmount = "壹佰陆拾捌元整",
        )
        assertTrue(
            "剪贴板态下仍应是 CapitalAmount，实际 $withClipboard",
            withClipboard is CandidateBarState.CapitalAmount,
        )

        // 计算器态开着时同理：回显优先于计算器候选
        val withCalculator = CandidateBarState.from(
            candidates = listOf("3"),
            candidateComments = listOf("1+2=3"),
            inputText = "",
            isComposing = false,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = false,
            hasNextPage = false,
            isCalculatorActive = true,
            capitalAmountExpression = "123+45",
            capitalAmountAmount = "壹佰陆拾捌元整",
        )
        assertTrue(
            "计算器态下仍应是 CapitalAmount，实际 $withCalculator",
            withCalculator is CandidateBarState.CapitalAmount,
        )
        assertEquals("123+45", (withCalculator as CandidateBarState.CapitalAmount).expression)
    }

    @Test
    fun `回显依据是金额而不是算式`() {
        // 算式非空但没有金额：不该显示。
        // 反证：若判定改用「算式非空」，退格退到没有数值、或刚进模式只按了小数点时，
        // 候选栏会挂出一行没有金额的残算式（而输入框里其实什么都没有）。
        val expressionOnly = CandidateBarState.from(
            candidates = emptyList(),
            candidateComments = emptyList(),
            inputText = "",
            isComposing = false,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = false,
            hasNextPage = false,
            capitalAmountExpression = "123+",
            capitalAmountAmount = "",
        )
        assertTrue(
            "只有算式、没有金额时不该显示，实际 $expressionOnly",
            expressionOnly is CandidateBarState.Idle,
        )
    }

    // ── 接线契约 ──

    private fun source(name: String): String = RepoFiles.mainKotlinSources
        .single { it.name == name }
        .readText()

    private val numberLayout by lazy { source("NumberKeyboardLayout.kt") }
    private val candidateBar by lazy { source("CandidateBar.kt") }
    private val keyboardView by lazy { source("KeyboardView.kt") }

    @Test
    fun `每次按键与切模式后都上报回显`() {
        // 必须断言 reportCapitalDisplay() 在 dispatchKey **函数体内**，
        // 否则「把这一行挪走但函数仍存在」的改动会逃过测试。
        // 锚点用 dispatchKey 到 DisposableEffect 之间这一段（含函数体）。
        val dispatch = numberLayout.substringAfter("val dispatchKey: (String) -> Unit =")
            .substringBefore("DisposableEffect(Unit)")
        assertTrue("dispatchKey 未上报回显", dispatch.contains("reportCapitalDisplay()"))
        assertTrue("dispatchKey 未执行按键", dispatch.contains("capitalInput.press("))

        // 切模式也必须上报：否则进入模式后候选栏空着，退出后仍留着旧算式
        val toggle = numberLayout.substringAfter("val toggleCapital: (() -> Unit)? =")
            .substringBefore("val dispatchKey:")
        assertTrue("切模式未切换状态", toggle.contains("capitalInput.toggle()"))
        assertTrue("切模式未上报回显", toggle.contains("reportCapitalDisplay()"))
    }

    @Test
    fun `键盘销毁时清除回显`() {
        // 换面板/收起键盘会销毁本 Composable，模式状态随之丢失；
        // 不清会让候选栏留着一行对不上任何输入的陈旧算式
        assertTrue("缺少销毁清理", numberLayout.contains("DisposableEffect(Unit) {"))
        assertTrue(
            "销毁时未清空回显",
            numberLayout.contains("""if (capitalMode) onCapitalAmountDisplay?.invoke("", "")"""),
        )
    }

    @Test
    fun `回显走与计算器相同的候选渲染`() {
        // 与截图同形：两个候选由 itemsIndexed 正常渲染（第一个高亮、都可点），
        // 不再有独立的展示组件——那会得到与计算器不同的第二套外观
        val branch = candidateBar.substringAfter("is CandidateBarState.CapitalAmount -> {")
            .substringBefore("is CandidateBarState.Idle")
        assertTrue("回显应产生候选", branch.contains("displayCandidates = s.candidates.take(20)"))
        assertTrue("不该再有独立的展示组件", !candidateBar.contains("CapitalAmountDisplayItem("))
    }

    @Test
    fun `点选结果会替换金额而不是重复上屏`() {
        // 金额已经随按键上屏，所以 index 1 必须是 replace 而不是 commit：
        // 直接 commit 会在屏幕上留下「壹佰陆拾捌元整123+45 = 壹佰陆拾捌元整」
        val router = source("ImeKeyRouter.kt")
        val branch = router.substringAfter("capitalState.capitalAmountAmount.isNotEmpty()")
            .substringBefore("if (service.calculatorEngine.isActive()")
        assertTrue("未替换已上屏金额", branch.contains("service.replaceBeforeCursor(capitalAmount, formula)"))
        assertTrue("不该直接 commit 金额", !branch.contains("commitText("))
    }

    @Test
    fun `大写金额点选必须先于计算器判断`() {
        // 反证：若计算器分支在前，上一笔普通模式残留的计算器状态会先接走这次点选，
        // 按自己的算式长度删文本并提交过期结果，把屏幕上那笔金额删掉。
        // 候选栏显示谁，点选就必须归谁。
        val router = source("ImeKeyRouter.kt")
        val capitalAt = router.indexOf("capitalState.capitalAmountAmount.isNotEmpty()")
        val calculatorAt = router.indexOf("if (service.calculatorEngine.isActive() && isCalculatorKeyboard)")
        assertTrue("找不到大写金额分支", capitalAt >= 0)
        assertTrue("找不到计算器分支", calculatorAt >= 0)
        assertTrue("大写金额分支必须排在计算器之前", capitalAt < calculatorAt)
    }

    @Test
    fun `点选后退出模式并作废累积`() {
        // 不退出的话，点完结果下一个数字会接在旧算式上继续算
        val router = source("ImeKeyRouter.kt")
        val branch = router.substringAfter("capitalState.capitalAmountAmount.isNotEmpty()")
            .substringBefore("if (service.calculatorEngine.isActive()")
        assertTrue("点选后未清回显", branch.contains("clearCapitalAmountDisplay()"))
        assertTrue("点选后未发退出信号", branch.contains("capitalAmountExitSignal = "))
        // 键盘侧消费信号：必须断言**判定本身**在 effect 体内。
        // 只断言 exit() 存在的话，「把判定改成 if(false)」会让信号永不生效却照样通过。
        val effect = numberLayout.substringAfter("LaunchedEffect(capitalAmountExitSignal) {")
            .substringBefore("val bubbleData")
        assertTrue(
            "键盘未比较信号值（改判条件就永远不退出）",
            effect.contains("capitalAmountExitSignal != consumedExitSignal.value"),
        )
        assertTrue("键盘未退出模式", effect.contains("capitalInput.exit()"))
        assertTrue("退出后未同步清回显", effect.contains("reportCapitalDisplay()"))
    }

    @Test
    fun `退出不是反向切换`() {
        // 复用 toggle 会把用户反向按回模式里，下一个数字又接在旧算式上
        val state = source("CapitalAmountInputState.kt")
        val exitFn = state.substringAfter("fun exit() {").substringBefore("fun press(")
        assertTrue("退出应用 setActive(false)", exitFn.contains("setActive(false)"))
        assertTrue("退出不该复用 toggle", !exitFn.contains("toggle()"))
    }

    @Test
    fun `退出信号两端字段名一致`() {
        // 服务 → 键盘的信号链，任一处漏传都会表现为「点选后仍留在模式里」
        assertTrue("InputUIState 缺少信号", source("InputUIState.kt").contains("val capitalAmountExitSignal: Long = 0,"))
        assertTrue("KeyboardUiState 缺少信号", source("KeyboardViewModel.kt").contains("val capitalAmountExitSignal: Long = 0L,"))
        assertTrue(
            "服务未下发信号",
            source("XimeInputMethodService.kt").contains("capitalAmountExitSignal = state.capitalAmountExitSignal,"),
        )
        assertTrue("KeyboardView 未传信号", keyboardView.contains("capitalAmountExitSignal = state.capitalAmountExitSignal,"))
        assertTrue(
            "KeyboardLayoutScreen 未传信号",
            source("KeyboardLayoutScreen.kt").contains("capitalAmountExitSignal = uiState.capitalAmountExitSignal,"),
        )
    }

    @Test
    fun `服务层把回显写入候选状态`() {
        val callbacks = source("ImeKeyboardCallbacks.kt")
        assertTrue(
            "未接线 onCapitalAmountDisplay",
            callbacks.contains("onCapitalAmountDisplay = { expression, amount ->"),
        )
        assertTrue("未写入算式", callbacks.contains("capitalAmountExpression = expression,"))
        assertTrue("未写入金额", callbacks.contains("capitalAmountAmount = amount,"))
    }

    @Test
    fun `两处宿主都传递回显回调`() {
        // 主键盘路径与面板路径各渲染一次 NumberKeyboardLayout，漏一处就有一处不显示
        assertTrue(
            "KeyboardView 未传递",
            keyboardView.contains("onCapitalAmountDisplay = callbacks.onCapitalAmountDisplay,"),
        )
        val screen = source("KeyboardLayoutScreen.kt")
        assertTrue(
            "KeyboardLayoutScreen 未传递",
            screen.contains("onCapitalAmountDisplay = callbacks.onCapitalAmountDisplay,"),
        )
    }

    @Test
    fun `候选状态字段贯通到候选栏`() {
        val state = source("CandidateState.kt")
        assertTrue("CandidateState 缺少算式字段", state.contains("val capitalAmountExpression: String = \"\","))
        assertTrue("CandidateState 缺少金额字段", state.contains("val capitalAmountAmount: String = \"\","))
        // remember 的 key 少一项会导致候选栏读到旧回显（显示滞后一次按键）
        assertTrue(
            "KeyboardView 未把回显纳入重组 key",
            keyboardView.contains("cs.capitalAmountExpression, cs.capitalAmountAmount,"),
        )
        assertTrue(
            "KeyboardView 未传入工厂",
            keyboardView.contains("capitalAmountExpression = cs.capitalAmountExpression,"),
        )
    }
}
