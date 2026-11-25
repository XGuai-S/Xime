package com.kingzcheung.xime.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class ClipboardPrivacyTest {
    private val key = ClipboardPrivacy.SENSITIVE_EXTRA

    private class Fixture {
        val clip: ClipData = mock(ClipData::class.java)
        val description: ClipDescription = mock(ClipDescription::class.java)
        val extras: PersistableBundle = mock(PersistableBundle::class.java)
        val item: ClipData.Item = mock(ClipData.Item::class.java)
    }

    private fun fixture(flag: Boolean? = null, text: CharSequence? = "fixture text"): Fixture =
        Fixture().also {
            `when`(it.clip.description).thenReturn(it.description)
            `when`(it.clip.itemCount).thenReturn(1)
            `when`(it.clip.getItemAt(0)).thenReturn(it.item)
            `when`(it.description.extras).thenReturn(it.extras)
            `when`(it.extras.containsKey(key)).thenReturn(flag != null)
            `when`(it.extras.getBoolean(key, true)).thenReturn(flag ?: true)
            `when`(it.item.text).thenReturn(text)
        }

    private fun assertNoItemRead(fixture: Fixture) {
        verify(fixture.clip, never()).getItemAt(anyInt())
        verify(fixture.item, never()).getText()
    }

    @Test
    fun `compatibility key matches the framework constant without an SDK gate`() {
        assertEquals(ClipDescription.EXTRA_IS_SENSITIVE, key)
    }

    @Test
    fun `sensitive clip is rejected before requesting an item`() {
        val f = fixture(flag = true)
        assertNull(ClipboardPrivacy.readText { f.clip })
        assertNoItemRead(f)
        verify(f.clip, never()).getItemCount()
    }

    @Test
    fun `explicit non-sensitive flag permits plain text`() {
        val f = fixture(flag = false)
        assertEquals("fixture text", ClipboardPrivacy.readText { f.clip })
    }

    @Test
    fun `missing sensitive flag permits ordinary metadata`() {
        val f = fixture()
        assertEquals("fixture text", ClipboardPrivacy.readText { f.clip })
        verify(f.extras, never()).getBoolean(key, true)
    }

    @Test
    fun `missing extras permits ordinary clips`() {
        val f = fixture()
        `when`(f.description.extras).thenReturn(null)
        assertEquals("fixture text", ClipboardPrivacy.readText { f.clip })
    }

    @Test
    fun `missing description is fail closed`() {
        val f = fixture()
        `when`(f.clip.description).thenReturn(null)
        assertNull(ClipboardPrivacy.readText { f.clip })
        assertTrue(ClipboardPrivacy.isSensitive(null))
        assertNoItemRead(f)
    }

    @Test
    fun `description getter failure does not read text`() {
        val f = fixture()
        `when`(f.clip.description).thenThrow(IllegalStateException("fixture metadata failure"))
        assertNull(ClipboardPrivacy.readText { f.clip })
        assertNoItemRead(f)
    }

    @Test
    fun `extras decoding failure is fail closed for capture and public reads`() {
        val f = fixture()
        `when`(f.description.extras).thenThrow(IllegalStateException("fixture extras failure"))
        assertTrue(ClipboardPrivacy.isSensitive(f.description))
        assertNull(ClipboardPrivacy.readText { f.clip })
        assertNoItemRead(f)
    }

    @Test
    fun `flag lookup failure is fail closed`() {
        val f = fixture()
        `when`(f.extras.containsKey(key)).thenThrow(IllegalStateException("fixture lookup failure"))
        assertNull(ClipboardPrivacy.readText { f.clip })
        assertNoItemRead(f)
    }

    @Test
    fun `flag decoding failure is fail closed`() {
        val f = fixture(flag = true)
        `when`(f.extras.getBoolean(key, true)).thenThrow(IllegalStateException("fixture flag failure"))
        assertNull(ClipboardPrivacy.readText { f.clip })
        assertNoItemRead(f)
    }

    @Test
    fun `present malformed flag uses the denying default`() {
        val f = fixture(flag = true)
        // PersistableBundle 返回调用方的默认值来处理类型不匹配或空值。
        `when`(f.extras.getBoolean(key, true)).thenAnswer { it.arguments[1] as Boolean }
        assertNull(ClipboardPrivacy.readText { f.clip })
        verify(f.extras).getBoolean(key, true)
        assertNoItemRead(f)
    }

    @Test
    fun `clipboard provider failure returns no text`() {
        assertNull(ClipboardPrivacy.readText { throw SecurityException("fixture access failure") })
    }

    @Test
    fun `null clipboard returns no text`() {
        assertNull(ClipboardPrivacy.readText { null })
    }

    @Test
    fun `empty clipboard does not request an item`() {
        val f = fixture()
        `when`(f.clip.itemCount).thenReturn(0)
        assertNull(ClipboardPrivacy.readText { f.clip })
        assertNoItemRead(f)
    }

    @Test
    fun `invalid item count does not request an item`() {
        val f = fixture()
        `when`(f.clip.itemCount).thenReturn(-1)
        assertNull(ClipboardPrivacy.readText { f.clip })
        assertNoItemRead(f)
    }

    @Test
    fun `item count failure returns no text`() {
        val f = fixture()
        `when`(f.clip.itemCount).thenThrow(IllegalStateException("fixture count failure"))
        assertNull(ClipboardPrivacy.readText { f.clip })
        assertNoItemRead(f)
    }

    @Test
    fun `item retrieval failure returns no text`() {
        val f = fixture()
        `when`(f.clip.getItemAt(0)).thenThrow(IndexOutOfBoundsException("fixture item failure"))
        assertNull(ClipboardPrivacy.readText { f.clip })
    }

    @Test
    fun `null item and null text are safe`() {
        val missingItem = fixture()
        `when`(missingItem.clip.getItemAt(0)).thenReturn(null)
        assertNull(ClipboardPrivacy.readText { missingItem.clip })
        val missingText = fixture(text = null)
        assertNull(ClipboardPrivacy.readText { missingText.clip })
    }

    @Test
    fun `text getter failure returns no text`() {
        val f = fixture()
        `when`(f.item.text).thenThrow(IllegalStateException("fixture text failure"))
        assertNull(ClipboardPrivacy.readText { f.clip })
    }

    @Test
    fun `empty and non-String text retain existing read semantics`() {
        val empty = fixture(text = "")
        assertEquals("", ClipboardPrivacy.readText { empty.clip })
        val sequence = fixture(text = StringBuilder("sequence text"))
        assertEquals("sequence text", ClipboardPrivacy.readText { sequence.clip })
    }

    @Test
    fun `privacy metadata is checked before any text access`() {
        val f = fixture(flag = false)
        assertEquals("fixture text", ClipboardPrivacy.readText { f.clip })
        val order = inOrder(f.clip, f.description, f.extras, f.item)
        order.verify(f.clip).getDescription()
        order.verify(f.description).getExtras()
        order.verify(f.extras).containsKey(key)
        order.verify(f.extras).getBoolean(key, true)
        order.verify(f.clip).getItemCount()
        order.verify(f.clip).getItemAt(0)
        order.verify(f.item).getText()
    }

    @Test
    fun `internal same-text comparison preserves sensitive clip as a no-op`() {
        val f = fixture(flag = true)
        assertNull(ClipboardPrivacy.readText { f.clip })
        assertTrue(ClipboardPrivacy.isNoOpWrite("fixture text") { f.clip })
        verify(f.item).getText()
        verify(f.description).getExtras()
    }

    @Test
    fun `internal comparison remains a no-op even if sensitivity metadata is unreadable`() {
        val f = fixture(flag = true)
        `when`(f.description.extras).thenThrow(IllegalStateException("fixture extras failure"))
        assertTrue(ClipboardPrivacy.isNoOpWrite("fixture text") { f.clip })
        verify(f.clip, never()).getDescription()
    }

    @Test
    fun `different text and absent clipboard are not no-op writes`() {
        val f = fixture(flag = true)
        assertFalse(ClipboardPrivacy.isNoOpWrite("different text") { f.clip })
        assertFalse(ClipboardPrivacy.isNoOpWrite("fixture text") { null })
        assertFalse(ClipboardPrivacy.isNoOpWrite("fixture text") { throw SecurityException("fixture") })
        val empty = fixture(text = "")
        assertFalse(ClipboardPrivacy.isNoOpWrite("") { empty.clip })
    }
}
