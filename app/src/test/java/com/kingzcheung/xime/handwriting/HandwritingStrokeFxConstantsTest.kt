package com.kingzcheung.xime.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test

class HandwritingStrokeFxConstantsTest {

    @Test
    fun `视觉淡出延迟保持450毫秒`() {
        assertEquals(450L, HW_FADE_OUT_MS)
    }
}
