package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数字键盘「大写金额模式」的接线契约测试。
 *
 * 规则本身在 [CapitalAmountKeysTest] / [CapitalAmountInputStateTest] /
 * `RmbCapitalFormatterTest` 里测；这里锁的是「有没有真的接起来」——
 * 这类问题不会编译失败、也不会崩，只是功能静默不存在：
 * - 手势没绑到「0」键 → 用户长按没反应；
 * - 角标规则接错键 → 键面出现两个浮标、或每个键都长角标；
 * - 回调没从服务层透传到键盘 → 进了模式但不上屏；
 * - 只接了一个宿主入口（本文件对应两个渲染入口）→ 某条路径下功能失效。
 */
class CapitalAmountModeContractTest {

    private fun source(name: String): String = RepoFiles.mainKotlinSources
        .single { it.name == name }
        .readText()

    private val numberLayout by lazy { source("NumberKeyboardLayout.kt") }

    /** 去掉注释行，只对可执行代码做断言（避免注释里的字样造成误判）。 */
    private fun executable(text: String): String =
        text.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    // ── 手势绑定在 0 键 ──

    @Test
    fun `0 键同时绑定长按与上滑两种进入手势`() {
        val code = executable(numberLayout)
        assertTrue(
            "缺少长按绑定：长按 0 键无反应",
            code.contains("onLongClick = if (isZeroToggleKey) onToggleCapital else null"),
        )
        assertTrue(
            "缺少上滑绑定：上滑 0 键无反应",
            code.contains("onSwipe = if (isZeroToggleKey)"),
        )
    }

    @Test
    fun `5 键不再承载进入手势`() {
        // 反证：进入手势留在 5 键而角标画在 0 键，用户会去长按带角标的 0 键却没反应
        val code = executable(numberLayout)
        val row456 = code.substringAfter("listOf(\"4\", \"5\", \"6\")")
            .substringBefore("listOf(\"7\", \"8\", \"9\")")
        assertFalse("4-5-6 行不应再出现进入手势绑定", row456.contains("onToggleCapital"))
        assertFalse("4-5-6 行不应再出现手势判定", row456.contains("isToggleKey"))
    }

    @Test
    fun `进入手势与角标键是同一个键`() {
        // 角标是「这个键能切模式」的指示：两者必须落在同一个键上
        val code = executable(numberLayout)
        val zeroKey = code.substringAfter("val isZeroToggleKey").substringBefore("KeyButton(")
        assertTrue(
            "0 键的手势判定应比对 TOGGLE_DIGIT，而不是硬编码",
            zeroKey.contains("CapitalAmountKeys.TOGGLE_DIGIT == \"0\""),
        )
        assertTrue(
            "角标应取 0 键的角标规则",
            code.contains("badgeText = CapitalAmountKeys.badge(\"0\", capitalMode)"),
        )
    }

    @Test
    fun `上滑目标与角标同字以避免重复渲染提示`() {
        // KeyButton 在「上滑提示文字 == 角标」时只画角标。若两处不同字，
        // 键面会同时出现角标与上滑提示两个浮标，视觉上叠在一起（即用户反馈的"两个角标"）
        val code = executable(numberLayout)
        assertTrue(
            "上滑目标应取角标同字（否则与角标重复渲染）",
            code.contains("swipeText = if (isZeroToggleKey) CapitalAmountKeys.badge(\"0\", capitalMode) else null"),
        )
    }

    @Test
    fun `纯 KeyButton 也要去重上滑提示与角标`() {
        // 九键/笔画走 SwipeableKeyButton，它自带该去重；数字键盘走纯 KeyButton，原先没有去重，
        // 于是上滑提示与角标同字时会叠两个浮标。仅"取同字"还不够——去重本身必须存在。
        assertTrue(
            "纯 KeyButton 缺少上滑提示/角标去重：同字时会同时渲染两个浮标",
            source("KeyButton.kt").contains("if (!swipeText.isNullOrEmpty() && swipeText != badgeText) {"),
        )
    }

    @Test
    fun `无可重写能力时手势不生效`() {
        // 预览/独立渲染（onReplaceCommittedText == null）下进入模式会"按了没反应"，
        // 因此 toggleCapital 必须为 null，让 0 键退化成普通数字键
        val code = executable(numberLayout)
        assertTrue(
            "toggleCapital 必须在无重写回调时为 null",
            code.contains("val toggleCapital: (() -> Unit)? = if (replaceCommitted == null) null else"),
        )
        assertTrue(
            "isZeroToggleKey 必须同时要求 onToggleCapital 非空",
            code.contains("onToggleCapital != null"),
        )
    }

    @Test
    fun `模式开关由 Compose state 单点持有`() {
        // 状态机不自己存 active，避免「Compose state 与状态机字段」两份真相漂移
        val code = executable(numberLayout)
        assertTrue(
            "模式开关应注入状态机而不是各存一份",
            code.contains("CapitalAmountInputState(isActive = { capitalMode }, setActive = { capitalMode = it })"),
        )
        assertFalse(
            "状态机不该自带 active 字段",
            Regex("var\\s+active\\b").containsMatchIn(source("CapitalAmountInputState.kt")),
        )
    }

    // ── 键面与角标 ──

    @Test
    fun `所有数字键都走统一的键面与角标规则`() {
        val code = executable(numberLayout)
        // 1-2-3 / 4-5-6 / 7-8-9 / 0 共四处数字键
        assertEquals(
            "每个数字键都应套用大写键面规则",
            4,
            Regex("text = CapitalAmountKeys\\.face\\(").findAll(code).count(),
        )
        assertEquals(
            "每个数字键都应套用角标规则",
            4,
            Regex("badgeText = CapitalAmountKeys\\.badge\\(").findAll(code).count(),
        )
    }

    @Test
    fun `键面与角标规则集中在单一实现`() {
        // 规则分散到各键的调用点会漂移（改了大写模式却漏了某一个键）
        val keys = source("CapitalAmountKeys.kt")
        assertTrue("键面规则应有唯一实现", keys.contains("fun face("))
        assertTrue("角标规则应有唯一实现", keys.contains("fun badge("))
        assertFalse(
            "键盘侧不该手写大写映射",
            executable(numberLayout).contains("RmbCapitalFormatter.capitalDigit("),
        )
    }

    // ── 上屏路径 ──

    @Test
    fun `两个渲染入口都透传重写回调`() {
        // NumberKeyboardLayout 有两个宿主：KeyboardView（Panel 页）与 KeyboardLayoutScreen（布局状态）
        listOf("KeyboardView.kt", "KeyboardLayoutScreen.kt").forEach { name ->
            assertTrue(
                "$name 未透传 onReplaceCommittedText：该路径下大写金额模式不会上屏",
                source(name).contains("onReplaceCommittedText = callbacks.onReplaceCommittedText"),
            )
        }
    }

    @Test
    fun `回调体走静默上屏并复用替换已上屏文本的既有能力`() {
        val callbacks = source("ImeKeyboardCallbacks.kt")
        assertTrue(
            "未接线到服务层",
            callbacks.contains("onReplaceCommittedText = { expected, replacement ->"),
        )
        val body = callbacks.substringAfter("private fun handleReplaceCommittedText(")
            .substringBefore("\n    /**")
        assertTrue("首次上屏应静默提交", body.contains("commitTextSilently(replacement)"))
        assertTrue(
            "整串重写应复用 replaceBeforeCursor（含光标前文本校验）",
            body.contains("service.replaceBeforeCursor(expected, replacement)"),
        )
        assertFalse(
            "逐键重写不该走全量 commitText（每次都会触发联想推理，候选栏闪烁）",
            body.contains("service.commitText("),
        )
    }

    @Test
    fun `键盘侧不自行判断输入上限`() {
        // 重复小数点/位数上限的裁决集中在 RmbCapitalFormatter.appendInput，
        // 分散到键盘会让规则漂移（状态机与渲染层各判一套）
        val code = executable(numberLayout)
        assertTrue("按键应交给状态机决策", code.contains("capitalInput.press("))
        assertFalse(
            "键盘侧不该出现手写的重复小数点判断",
            code.contains("raw.contains(\".\")"),
        )
        assertTrue(
            "状态机应把算式结构裁决委托给 CapitalAmountExpression",
            source("CapitalAmountInputState.kt").contains("CapitalAmountExpression.append(raw, key)"),
        )
        assertTrue(
            "每个操作数的位数上限仍应只有一处实现（appendInput）",
            source("CapitalAmountExpression.kt").contains("RmbCapitalFormatter.appendInput(currentOperand(expr), input)"),
        )
    }

    // ── 加减乘除 ──

    @Test
    fun `运算符由大写金额模式接管而不是交回宿主的计算器`() {
        // 数字键盘左列就是 + - * /。若状态机把它们当「其它按键」交回宿主，
        // 就会退出大写模式并把算式丢给普通计算器，屏幕上再也不会出现结果的大写金额。
        val state = executable(source("CapitalAmountInputState.kt"))
        assertTrue(
            "运算符必须走算式累积路径",
            state.contains("CapitalAmountExpression.isOperandInput(key) || CapitalAmountExpression.isOperator(key)"),
        )
        assertFalse(
            "运算符不得落在退出模式的 else 分支里（isCapitalInput 判定要包含运算符）",
            Regex("val isCapitalInput = key\\.length == 1 && \\(key\\[0\\]\\.isDigit\\(\\) \\|\\| key == \"\\.\"\\)")
                .containsMatchIn(state),
        )
    }

    @Test
    fun `求值只有一处实现`() {
        // 大写金额模式的算式与普通模式的计算器必须是同一个 exp4j 用法，
        // 否则同一个算式会在两个地方给出两个答案
        assertTrue(
            "计算器引擎应委托给抽出的求值实现",
            source("CalculatorEngine.kt").contains("ExpressionEvaluator.evaluate(expression)"),
        )
        assertTrue(
            "算式取值应复用同一处求值",
            source("CapitalAmountExpression.kt").contains("ExpressionEvaluator.evaluate(body)"),
        )
    }
}
