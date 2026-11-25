package com.kingzcheung.xime.service

import android.content.Context
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.whenever

@RunWith(MockitoJUnitRunner.Silent::class)
class VoiceUndoIntegrationTest {
    @Mock private lateinit var context: Context
    @Mock private lateinit var mainHandler: Handler
    @Mock private lateinit var inputConnection: InputConnection

    private lateinit var service: XimeInputMethodService
    private lateinit var voiceHandler: VoiceRecognitionHandler
    private lateinit var textCommit: ImeTextCommit

    @Before
    fun setUp() {
        service = mock(XimeInputMethodService::class.java)
        voiceHandler = VoiceRecognitionHandler(
            context = context,
            onStateChanged = {},
            getState = { InputUIState() },
            getInputConnection = { inputConnection },
            mainHandler = mainHandler,
        )
        doReturn(voiceHandler).`when`(service).voiceRecognitionHandler
        whenever(service.currentInputConnection).thenReturn(inputConnection)
        textCommit = ImeTextCommit(service)
    }

    private fun prepareUndo(
        expected: String,
        anchor: String = "prefix-at-cursor-",
        connection: InputConnection = inputConnection,
        currentTail: String = anchor + expected,
        selectedText: CharSequence? = null,
    ) {
        voiceHandler.voiceInputConnection = connection
        voiceHandler.voiceInputPrefixAnchor = anchor
        voiceHandler.textWrittenByVoiceInput = expected
        whenever(connection.getTextBeforeCursor(anyInt(), anyInt())).thenReturn(currentTail)
        whenever(connection.getSelectedText(anyInt())).thenReturn(selectedText)
        whenever(connection.finishComposingText()).thenReturn(true)
    }

    @Test
    fun `long input removes only exact voice suffix and clears undo bookkeeping`() {
        val expected = "recognized voice text"
        val anchor = "01234567890123456789012345678901"
        assertEquals(32, anchor.length)
        prepareUndo(expected, anchor, currentTail = anchor + expected)

        textCommit.performUndo()

        verify(inputConnection).deleteSurroundingText(expected.length, 0)
        verify(inputConnection, never()).sendKeyEvent(any())
        assertEquals("", voiceHandler.textWrittenByVoiceInput)
        assertEquals("", voiceHandler.voiceInputPrefixAnchor)
        assertNull(voiceHandler.voiceInputConnection)
    }

    @Test
    fun `non-empty selection rejects undo and still clears bookkeeping`() {
        val expected = "voice"
        prepareUndo(expected, selectedText = "selected")

        textCommit.performUndo()

        verify(inputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        verify(inputConnection, never()).sendKeyEvent(any())
        assertUndoBookkeepingCleared()
    }

    @Test
    fun `different current connection rejects undo`() {
        val original = inputConnection
        val current = mock(InputConnection::class.java)
        prepareUndo("voice", connection = original)
        whenever(service.currentInputConnection).thenReturn(current)

        textCommit.performUndo()

        verify(original, never()).deleteSurroundingText(anyInt(), anyInt())
        verify(current, never()).deleteSurroundingText(anyInt(), anyInt())
        assertUndoBookkeepingCleared()
    }

    @Test
    fun `changed text after anchor rejects undo`() {
        prepareUndo("voice", currentTail = "prefix-at-cursor-edited")

        textCommit.performUndo()

        verify(inputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        assertUndoBookkeepingCleared()
    }

    @Test
    fun `emoji undo uses UTF16 length and does not send delete keys when batch delete declines`() {
        val emoji = "😊"
        assertEquals(2, emoji.length)
        prepareUndo(emoji)
        whenever(inputConnection.deleteSurroundingText(emoji.length, 0)).thenReturn(false)

        textCommit.performUndo()

        verify(inputConnection).deleteSurroundingText(2, 0)
        verify(inputConnection, never()).sendKeyEvent(any<KeyEvent>())
        assertUndoBookkeepingCleared()
    }

    @Test
    fun `empty voice suffix never deletes existing text`() {
        prepareUndo(expected = "", currentTail = "")

        textCommit.performUndo()

        verify(inputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        assertUndoBookkeepingCleared()
    }

    @Test
    fun `declined composing finish cannot delete text around an active composing region`() {
        prepareUndo("voice")
        whenever(inputConnection.finishComposingText()).thenReturn(false)

        textCommit.performUndo()

        verify(inputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        assertUndoBookkeepingCleared()
    }

    @Test
    fun `text changed while finishing composing rejects deletion`() {
        prepareUndo("voice")
        whenever(inputConnection.finishComposingText()).thenAnswer {
            whenever(inputConnection.getTextBeforeCursor(anyInt(), anyInt())).thenReturn("changed")
            true
        }

        textCommit.performUndo()

        verify(inputConnection, never()).deleteSurroundingText(anyInt(), anyInt())
        assertUndoBookkeepingCleared()
    }

    private fun assertUndoBookkeepingCleared() {
        assertEquals("", voiceHandler.textWrittenByVoiceInput)
        assertEquals("", voiceHandler.voiceInputPrefixAnchor)
        assertNull(voiceHandler.voiceInputConnection)
        assertTrue(voiceHandler.textWrittenByVoiceInput.isEmpty())
    }
}
