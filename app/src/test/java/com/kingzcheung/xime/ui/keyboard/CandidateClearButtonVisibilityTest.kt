package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第五项第 1 条：编码态候选栏右侧「清空」按键的显示条件。
 *
 * 只有存在可清空编码的状态（中文/英文候选态）才显示；纯联想、剪贴板、计算器态
 * 没有编码可清，显示出来就是「点了没反应」的死按钮。
 *
 * 显隐同时受 `xime.yaml keyboard.candidates.show_clear_button` 控制（第六项：配置定义统一写入
 * xime.yaml），关闭后即使处于编码态也不显示。
 */
class CandidateClearButtonVisibilityTest {

    @Test
    fun `中文编码态显示清空键`() {
        assertTrue(
            shouldShowCandidateClearButton(
                CandidateBarState.ChineseCandidates(
                    candidates = listOf("你好"),
                    inputText = "ni",
                ),
                hasClearInputCallback = true,
                enabled = true,
            )
        )
    }

    @Test
    fun `英文编码态显示清空键`() {
        assertTrue(
            shouldShowCandidateClearButton(
                CandidateBarState.EnglishCandidates(candidates = listOf("hello")),
                hasClearInputCallback = true,
                enabled = true,
            )
        )
    }

    @Test
    fun `宿主未提供回调时不显示`() {
        assertFalse(
            shouldShowCandidateClearButton(
                CandidateBarState.ChineseCandidates(candidates = listOf("你好"), inputText = "ni"),
                hasClearInputCallback = false,
                enabled = true,
            )
        )
    }

    @Test
    fun `配置关闭清空键时不显示`() {
        // keyboard.candidates.show_clear_button: false
        assertFalse(
            shouldShowCandidateClearButton(
                CandidateBarState.ChineseCandidates(candidates = listOf("你好"), inputText = "ni"),
                hasClearInputCallback = true,
                enabled = false,
            )
        )
        assertFalse(
            shouldShowCandidateClearButton(
                CandidateBarState.EnglishCandidates(candidates = listOf("hello")),
                hasClearInputCallback = true,
                enabled = false,
            )
        )
    }

    @Test
    fun `无编码可清的状态不显示清空键`() {
        val noCodeStates = listOf(
            CandidateBarState.Idle,
            CandidateBarState.AssociationOnly(candidates = listOf("联想词")),
            CandidateBarState.ClipboardDisplay(candidates = listOf("剪贴板内容")),
            CandidateBarState.Calculator(candidates = listOf("2"), expression = "1+1", result = "2"),
        )
        for (state in noCodeStates) {
            assertFalse(
                "${state::class.simpleName} 不应显示编码清空键",
                shouldShowCandidateClearButton(state, hasClearInputCallback = true, enabled = true),
            )
        }
    }
}
