package com.kingzcheung.xime.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自写剪贴板回声抑制的判定（纯函数）。
 *
 * 这些断言直接对应两条用户可见症状：
 * - 「上屏了还在候选栏显示」= 自写文本被当成真实复制又入库（revive 复活了刚上屏那行）；
 * - 「复制了却不显示」= 抑制窗口把真实复制也吃掉了。
 */
class ClipboardSelfWritePolicyTest {

    private val text = "候选栏抽取」那类结构重构，我的建议不变"

    @Test
    fun `窗口内读到同一文本应抑制`() {
        assertTrue(
            ClipboardSelfWritePolicy.shouldSuppressClipCapture(
                text = text,
                selfText = text,
                nowMs = 1_000L,
                suppressUntilMs = 2_500L,
            )
        )
    }

    @Test
    fun `同一次写入被读多次必须每次都抑制（ROM 连发回调）`() {
        // 现场日志：21:55:34.113 第一次读跳过 → 21:55:34.152 第二次读（39ms 后）被当成真实复制，
        // 把刚上屏的 id=595 revive 回候选栏。旧实现「命中即清标记」，第二次就漏了。
        val until = 3_000L
        val first = ClipboardSelfWritePolicy.shouldSuppressClipCapture(text, text, 1_000L, until)
        val second = ClipboardSelfWritePolicy.shouldSuppressClipCapture(text, text, 1_039L, until)
        val third = ClipboardSelfWritePolicy.shouldSuppressClipCapture(text, text, 1_400L, until)
        assertTrue(first)
        assertTrue("第二次读（39ms 后）也必须抑制", second)
        assertTrue("补采（~400ms 后）也必须抑制", third)
    }

    @Test
    fun `窗口过期后同一文本视为真实复制`() {
        assertFalse(
            ClipboardSelfWritePolicy.shouldSuppressClipCapture(
                text = text,
                selfText = text,
                nowMs = 2_500L,
                suppressUntilMs = 2_500L,
            )
        )
        assertFalse(
            ClipboardSelfWritePolicy.shouldSuppressClipCapture(
                text = text,
                selfText = text,
                nowMs = 9_999L,
                suppressUntilMs = 2_500L,
            )
        )
    }

    @Test
    fun `文本不同不抑制（真实复制了别的内容）`() {
        assertFalse(
            ClipboardSelfWritePolicy.shouldSuppressClipCapture(
                text = "别的内容",
                selfText = text,
                nowMs = 1_000L,
                suppressUntilMs = 2_500L,
            )
        )
    }

    @Test
    fun `没有自写标记时不抑制`() {
        assertFalse(
            ClipboardSelfWritePolicy.shouldSuppressClipCapture(
                text = text,
                selfText = null,
                nowMs = 1_000L,
                suppressUntilMs = 2_500L,
            )
        )
    }

    @Test
    fun `空文本与空标记不抑制`() {
        assertFalse(ClipboardSelfWritePolicy.shouldSuppressClipCapture(null, text, 1_000L, 2_500L))
        assertFalse(ClipboardSelfWritePolicy.shouldSuppressClipCapture("", "", 1_000L, 2_500L))
        assertFalse(ClipboardSelfWritePolicy.shouldSuppressClipCapture(text, "", 1_000L, 2_500L))
    }

    @Test
    fun `抑制窗口为 1_5 秒`() {
        assertTrue(ClipboardSelfWritePolicy.SUPPRESS_WINDOW_MS in 500L..3_000L)
    }

    // ---- 空操作写判定 ----

    @Test
    fun `剪贴板已是同一文本时算空操作`() {
        assertTrue(ClipboardSelfWritePolicy.isNoOpWrite(text, text))
    }

    @Test
    fun `内容不同不算空操作`() {
        assertFalse(ClipboardSelfWritePolicy.isNoOpWrite("旧内容", text))
    }

    @Test
    fun `剪贴板为空或非文本时不算空操作（该写还是要写）`() {
        assertFalse(ClipboardSelfWritePolicy.isNoOpWrite(null, text))
        assertFalse(ClipboardSelfWritePolicy.isNoOpWrite("", text))
    }

    @Test
    fun `空白内容不会误判为空操作`() {
        assertFalse(ClipboardSelfWritePolicy.isNoOpWrite("   ", text))
    }
}
