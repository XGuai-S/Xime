package com.kingzcheung.xime.ai

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 模块的剪贴板死代码已移除，历史明文缓存会被清掉。
 *
 * 背景：早期版本在 AI 模块里另存了一份剪贴板（`clipboard_v1`），**与主剪贴板的
 * 敏感识别完全无关**——用户复制的密码/卡号会以明文躺在 SharedPreferences 里。
 * 这套 API 早已无调用者，属于"看起来在同步、实际上谁也没读"的死代码。
 */
class ClipboardDeadCodeRemovedTest {

    private val repo by lazy {
        RepoFiles.mainKotlinSources
            .single { it.name == "AppRepository.kt" }.readText()
    }

    @Test
    fun `clipboard apis are removed`() {
        listOf(
            "fun recordClipboardText(",
            "fun togglePinClipboardItem(",
            "fun deleteClipboardItem(",
            "fun clearUnpinnedClipboardItems(",
            "private fun saveClipboardList(",
            "private fun loadClipboardInternal(",
        ).forEach { sig ->
            assertFalse(
                "AI 模块不该再有剪贴板写入 API：$sig（它绕过了主剪贴板的敏感识别）",
                repo.contains(sig),
            )
        }
    }

    @Test
    fun `legacy clipboard cache is purged on init`() {
        assertTrue(
            "必须在构造时抹掉历史明文缓存，否则密码会一直留在 SharedPreferences 里",
            repo.contains("putString(KEY_CLIPBOARD, null)"),
        )
    }

    @Test
    fun `clipboard state flow is gone`() {
        assertFalse(
            "不该再有 clipboardItems StateFlow —— 留着会让人以为 AI 还在同步剪贴板",
            repo.contains("_clipboardItems"),
        )
    }
}
