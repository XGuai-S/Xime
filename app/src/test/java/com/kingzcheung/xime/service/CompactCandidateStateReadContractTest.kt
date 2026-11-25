package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactCandidateStateReadContractTest {
    private val source = RepoFiles.mainKotlinSources
        .single { it.name == "XimeInputMethodService.kt" }
        .readText()
        .replace("\r\n", "\n")

    private val keyboardContent = source.substringAfter("private fun KeyboardContent() {")
        .substringBefore("private fun CompactCandidatePane(")

    private val compactPane = source.substringAfter("private fun CompactCandidatePane(")
        .substringBefore("override fun onConfigureWindow(")

    @Test
    fun `regular keyboard composition does not read candidate state at its root`() {
        assertFalse(
            "candidateState.value must stay out of KeyboardContent so regular key layouts do not subscribe to candidate updates",
            keyboardContent.contains("candidateState.value"),
        )
        assertTrue(keyboardContent.contains("if (state.isCompact)"))
        assertTrue(keyboardContent.contains("CompactCandidatePane("))
    }

    @Test
    fun `compact candidate subtree owns its candidate state subscription`() {
        assertTrue(compactPane.contains("val candidates = candidateState.value"))
        assertTrue(compactPane.contains("HardwareKeyboardCandidateBar("))
    }
}
