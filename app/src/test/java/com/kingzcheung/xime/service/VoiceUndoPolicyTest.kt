package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceUndoPolicyTest {
    @Test
    fun `production undo uses exact text and originating connection`() {
        val commit = RepoFiles.mainKotlinSources.single { it.name == "ImeTextCommit.kt" }.readText()
        val voice = RepoFiles.mainKotlinSources.single { it.name == "VoiceRecognitionHandler.kt" }.readText()
        assertTrue(commit.contains("voice.textWrittenByVoiceInput"))
        assertTrue(commit.contains("connection === voice.voiceInputConnection"))
        assertTrue(commit.contains("currentTextBeforeCursor = currentTail"))
        assertTrue(voice.contains("voiceInputPrefixAnchor = voiceInputConnection?.getTextBeforeCursor(32, 0)"))
        assertTrue(voice.contains("textWrittenByVoiceInput = voiceCommittedText"))
    }

    @Test
    fun `undo matches exact voice suffix on long text`() {
        val voice = "语音"
        val prefix = "a".repeat(1010)
        assertEquals(voice.length, voiceUndoLength(voice, prefix.takeLast(32), prefix.takeLast(32) + voice, true))
        assertEquals(0, voiceUndoLength(voice, prefix.takeLast(32), prefix + "手改", true))
        assertEquals(0, voiceUndoLength(voice, prefix.takeLast(32), prefix + voice, false))
    }

    @Test
    fun `undo does not infer deletion from a truncated window`() {
        val voice = "x".repeat(30)
        val prefix = "p".repeat(1001)
        assertEquals(voice.length, voiceUndoLength(voice, prefix.takeLast(32), prefix.takeLast(32) + voice, true))
        assertEquals(0, voiceUndoLength(voice, prefix.takeLast(32), "p".repeat(1000) + voice, true))
        assertEquals(0, voiceUndoLength(voice, prefix.takeLast(32), null, true))
    }
}
