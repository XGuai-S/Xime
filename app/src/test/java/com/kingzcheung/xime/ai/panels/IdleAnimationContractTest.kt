package com.kingzcheung.xime.ai.panels

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 空闲面板不应保留无限动画时钟；只有录音态才创建脉冲动画。 */
class IdleAnimationContractTest {
    private fun source(rel: String) =
        RepoFiles.file("src/main/java/com/kingzcheung/xime/ai/$rel").readText()

    private val chrome by lazy { source("ime/chrome/SubPanelChrome.kt") }
    private val voiceMain by lazy { source("panels/VoiceMainPanel.kt") }
    private val jev by lazy { source("panels/JevCopilotPanel.kt") }

    @Test
    fun `active pulse helper only starts animation when active`() {
        assertTrue(chrome.contains("internal fun rememberActivePulse("))
        assertTrue(chrome.contains("if (!active) return initialValue"))
    }

    @Test
    fun `voice trigger and voice main use active-only pulse`() {
        assertTrue(chrome.contains("rememberActivePulse("))
        assertTrue(voiceMain.contains("rememberActivePulse("))
    }

    @Test
    fun `jev panel does not create infinite animations at rest`() {
        assertTrue(jev.contains("rememberActivePulse("))
        assertFalse(jev.contains("rememberInfiniteTransition"))
    }
}
