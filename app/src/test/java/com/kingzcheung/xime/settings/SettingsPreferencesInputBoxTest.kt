package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 「输入框里放的到底是什么」判定的真值表测试。
 *
 * [SettingsPreferences.isCodeInInputBox] 与 [SettingsPreferences.isInputBoxComposing] 是跨功能
 * 安全阀：「候选首词显示在输入框」开启后，输入框 composing 区里是**候选词**而不是编码，
 * 判错会让清空/撤回/删除会话把候选词当成正文多删一截（真机上表现为莫名丢字），
 * 或把 composing 区残留在正文里。因此把两种设置的 4 种组合全部钉死。
 */
class SettingsPreferencesInputBoxTest {

    private val prefs: SharedPreferences = mock()
    private val context: Context = mock()

    /** 直接写死 pref 键名：键名被改名时测试立刻失败，避免静默换存储位置丢用户设置。 */
    private companion object {
        const val KEY_INPUT_TEXT_LOCATION = "input_text_location"
        const val KEY_CANDIDATE_FIRST_IN_INPUT_BOX = "candidate_first_in_input_box"
    }

    private fun stubPrefs(inputTextLocation: String, candidateFirstInInputBox: Boolean) {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(
            prefs.getString(KEY_INPUT_TEXT_LOCATION, SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR)
        ).thenReturn(inputTextLocation)
        whenever(
            prefs.getBoolean(KEY_CANDIDATE_FIRST_IN_INPUT_BOX, false)
        ).thenReturn(candidateFirstInInputBox)
    }

    @Test
    fun `编码占用输入框的判定真值表`() {
        // 编码显示=显示在输入框，候选首词关 → 编码确实在输入框里
        stubPrefs(SettingsPreferences.INPUT_TEXT_INPUT_BOX, candidateFirstInInputBox = false)
        assertEquals(true, SettingsPreferences.isCodeInInputBox(context))

        // 编码显示=显示在输入框，但候选首词开 → 输入框被候选词占用，编码让位
        stubPrefs(SettingsPreferences.INPUT_TEXT_INPUT_BOX, candidateFirstInInputBox = true)
        assertFalse(
            "候选首词开启后编码不该再算作在输入框里，否则撤回会把候选词当正文",
            SettingsPreferences.isCodeInInputBox(context)
        )

        // 编码显示=显示在候选栏 → 输入框里没有编码
        stubPrefs(SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR, candidateFirstInInputBox = false)
        assertFalse(SettingsPreferences.isCodeInInputBox(context))
        stubPrefs(SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR, candidateFirstInInputBox = true)
        assertFalse(SettingsPreferences.isCodeInInputBox(context))
    }

    @Test
    fun `输入框有 composing 区的判定真值表`() {
        // 任一模式为真即为真：两种模式下都必须走 endComposingInputBox 清空
        stubPrefs(SettingsPreferences.INPUT_TEXT_INPUT_BOX, candidateFirstInInputBox = false)
        assertEquals(true, SettingsPreferences.isInputBoxComposing(context))
        stubPrefs(SettingsPreferences.INPUT_TEXT_INPUT_BOX, candidateFirstInInputBox = true)
        assertEquals(true, SettingsPreferences.isInputBoxComposing(context))
        stubPrefs(SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR, candidateFirstInInputBox = true)
        assertEquals(true, SettingsPreferences.isInputBoxComposing(context))

        // 两者都关 → 输入框里没有 composing 区，可用 deleteSurroundingText 删除
        stubPrefs(SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR, candidateFirstInInputBox = false)
        assertFalse(SettingsPreferences.isInputBoxComposing(context))
    }

    @Test
    fun `候选首词开关默认关闭`() {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getBoolean(KEY_CANDIDATE_FIRST_IN_INPUT_BOX, false)).thenReturn(false)
        assertFalse(
            "新开关默认必须关闭，否则升级用户的行为会被静默改变",
            SettingsPreferences.showCandidateFirstInInputBox(context)
        )
    }
}
