package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎锁竞争失败时不得把「空组合」当成真实状态写回 UI。
 *
 * 缺陷：`tryLocked` 拿不到 rimeLock（部署/长词库持锁中）时返回默认空 composition，
 * 调用方无法区分「锁失败」与「引擎真空」，主线程 updateUI 会把空态写回候选栏与输入框
 * composing —— 用户打字中候选突然消失、已上屏的编码被清，感知是「字没了」。
 *
 * 修法：新增 `getCompositionOrNull()`（锁失败/未初始化返回 null），
 * 主线程刷新路径用它并在 null 时跳过刷新。
 */
class RimeCompositionLockFallbackTest {

    private val engine by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/rime/RimeEngine.kt").readText()
    }
    private val service by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt").readText()
    }

    @Test
    fun `可空组合接口存在且锁失败返回 null`() {
        val start = engine.indexOf("fun getCompositionOrNull(): RimeComposition?")
        assertTrue("必须有 getCompositionOrNull", start >= 0)
        val body = engine.substring(start).substringBefore("\n    fun ")
        assertTrue("锁失败必须返回 null 而不是空组合", "tryLock()" in body && "return null" in body)
    }

    @Test
    fun `updateUI 使用可空接口并跳过刷新`() {
        val start = service.indexOf("internal fun updateUI()")
        assertTrue("找不到 updateUI", start >= 0)
        val body = service.substring(start).substringBefore("\n    internal fun ").substringBefore("\n    private fun ")
        assertTrue("updateUI 必须用 getCompositionOrNull", "getCompositionOrNull()" in body)
        assertTrue("拿不到快照必须直接返回，不得写回 UI", "?: return" in body)
        assertFalse("updateUI 不得再用会返回空态默认值的 getComposition", "rimeEngine.getComposition()" in body)
    }

    @Test
    fun `T9 刷新路径同样处理锁失败`() {
        val callbacks = RepoFiles.file("src/main/java/com/kingzcheung/xime/service/ImeKeyboardCallbacks.kt").readText()
        assertTrue("T9 刷新必须用可空接口", "getCompositionOrNull()" in callbacks)
        assertTrue("锁失败必须跳过写回", "?: return@launch" in callbacks)
    }

    @Test
    fun `T9 刷新不再二次投递到主线程`() {
        val callbacks = RepoFiles.file("src/main/java/com/kingzcheung/xime/service/ImeKeyboardCallbacks.kt").readText()
        assertFalse(
            "withContext(Main) 内不得再套 mainHandler.post（二次投递会让旧结果覆盖新状态）",
            "mainHandler.post { service.sessionController.applyComposition" in callbacks,
        )
    }
}
