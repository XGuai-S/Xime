package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PunctuationCandidateSelectionTest {

    @Test
    fun `逗号选择次选`() {
        assertEquals(1, punctuationCandidateIndex("comma", 2))
        assertEquals(1, punctuationCandidateIndex("comma", 5))
    }

    @Test
    fun `句号选择三选`() {
        assertEquals(2, punctuationCandidateIndex("period", 3))
        assertEquals(2, punctuationCandidateIndex("period", 5))
    }

    @Test
    fun `候选不足时回退普通输入`() {
        assertNull(punctuationCandidateIndex("comma", 1))
        assertNull(punctuationCandidateIndex("period", 2))
        assertNull(punctuationCandidateIndex("other", 5))
    }
}
