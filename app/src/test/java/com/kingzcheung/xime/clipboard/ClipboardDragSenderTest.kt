package com.kingzcheung.xime.clipboard

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拖拽 flags：跨应用必须 GLOBAL，带 URI 时必须给接收方读权限（issue #1062 回归）。 */
class ClipboardDragSenderTest {

    @Test
    fun `跨应用前提：任何拖拽都带 DRAG_FLAG_GLOBAL`() {
        assertTrue(ClipboardDragSender.dragFlags(hasUri = false) and View.DRAG_FLAG_GLOBAL != 0)
        assertTrue(ClipboardDragSender.dragFlags(hasUri = true) and View.DRAG_FLAG_GLOBAL != 0)
    }

    @Test
    fun `图片拖拽带 URI 读权限，文本拖拽不带`() {
        assertTrue(
            ClipboardDragSender.dragFlags(hasUri = true) and View.DRAG_FLAG_GLOBAL_URI_READ != 0
        )
        assertEquals(
            0, ClipboardDragSender.dragFlags(hasUri = false) and View.DRAG_FLAG_GLOBAL_URI_READ
        )
    }

    @Test
    fun `只读共享：不授予 URI 写权限`() {
        assertEquals(0, ClipboardDragSender.dragFlags(hasUri = true) and View.DRAG_FLAG_GLOBAL_URI_WRITE)
    }
}
