package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「切换输入法后切回本输入法」不得闪退的回归护栏。
 *
 * 根因（真机反馈）：`RimeEngine` 是**进程级单例**，而切换输入法时系统会销毁并重建
 * `XimeInputMethodService`。旧实现在 `onDestroy` 里调 `rimeEngine.destroy()`，它会执行
 * native `rime->finalize()` 释放 librime 全局资源；服务重建时 `initRimeEngine` 又走
 * `rimeEngine.initialize()` → `rime->initialize()`。librime 在同一进程内
 * 「finalize 之后再 initialize」并不保证可重入，于是切回本输入法就崩。
 *
 * 修法：服务销毁只结束**会话**（`endSession`），引擎留在进程里复用；`initRimeEngine`
 * 检测到引擎已初始化时跳过二次 `initialize`。这两个不变量都在这里钉住——它们不报错、
 * 不崩在单测里，只在真机切换输入法时才暴露。
 */
class RimeEngineLifecycleTest {

    private fun source(name: String): String = RepoFiles.mainKotlinSources
        .single { it.name == name }
        .readText()
        .replace("\r\n", "\n")

    @Test
    fun `服务销毁只结束会话而不销毁引擎`() {
        val service = source("XimeInputMethodService.kt")
        val onDestroy = service.substringAfter("override fun onDestroy()")
            .substringBefore("internal fun hideKeyboard()")
        assertTrue(
            "服务销毁应只结束会话（endSession）",
            onDestroy.contains("rimeEngine.endSession()"),
        )
        assertFalse(
            "服务销毁不得 finalize 引擎：切回本输入法会二次 initialize 而崩",
            onDestroy.contains("rimeEngine.destroy()"),
        )
    }

    @Test
    fun `引擎已初始化时跳过二次 initialize`() {
        val service = source("XimeInputMethodService.kt")
        val init = service.substringAfter("private fun initRimeEngine()")
            .substringBefore("private fun startInitWatchdog()")
        assertTrue(
            "initRimeEngine 必须先判断引擎是否已初始化",
            init.contains("if (RimeEngine.isInitialized())"),
        )
        assertTrue(
            "已初始化时应复用引擎（不调 initialize）",
            init.contains("engine already initialized, reusing"),
        )
    }

    @Test
    fun `RimeEngine 提供只结束会话的入口`() {
        val engine = source("RimeEngine.kt")
        assertTrue(
            "缺少 endSession：服务切走时无法在不 finalize 的前提下释放会话",
            engine.contains("fun endSession()"),
        )
        assertTrue(
            "endSession 应调用 nativeEndSession",
            engine.contains("nativeEndSession()"),
        )
        assertTrue(
            "endSession 不应调用 nativeDestroy",
            !engine.substringAfter("fun endSession()")
                .substringBefore("private external fun")
                .contains("nativeDestroy()"),
        )
    }

    @Test
    fun `同步失败仍重建输入会话并恢复页大小`() {
        val jni = RepoFiles.file("app/src/main/jni/librime_jni/rime_jni.cc").readText()
            .replace("\r\n", "\n")
        val sync = jni.substringAfter("bool syncUserData()")
            .substringBefore("用户词库操作统一包装")
        val wait = sync.indexOf("while (rime->is_maintenance_mode())")
        val beforeWait = sync.substringAfter("Bool result = rime->sync_user_data();").substringBefore("while (rime->is_maintenance_mode())")
        assertFalse("同步返回 false 时也不能跳过会话恢复", Regex("\\breturn\\b").containsMatchIn(beforeWait))
        val acquire = sync.indexOf("T9DigitUserDict::AcquireDb()")
        assertTrue("维护退出后才恢复 T9 句柄", acquire > wait && wait >= 0)
        val recreate = sync.indexOf("session_id_ = rime->create_session()", acquire + 1)
        val reapply = sync.indexOf("reapplyPageSizeIfNeeded()", recreate + 1)
        val result = sync.indexOf("return result;", reapply + 1)
        assertTrue("同步后要释放数据库锁", acquire >= 0)
        assertTrue("同步后要重建 session", recreate > acquire)
        assertTrue("恢复 session 后重新应用页大小", reapply > recreate)
        assertTrue("成功或失败状态在资源恢复后返回", result > reapply)
    }

    @Test
    fun `native 侧 endSession 不调用 finalize`() {
        // 契约的最后一环：C++ 侧 endSession 只能 destroy_session，不能 rime->finalize()
        val jni = RepoFiles.file("app/src/main/jni/librime_jni/rime_jni.cc").readText()
            .replace("\r\n", "\n")
        val endSession = jni.substringAfter("void endSession()")
            .substringBefore("void compactT9UserDict()")
        assertTrue(
            "endSession 应销毁会话",
            endSession.contains("destroy_session(session_id_)"),
        )
        assertFalse(
            "endSession 不得 finalize（否则切回本输入法会二次 initialize 而崩）",
            endSession.contains("finalize()"),
        )
    }
}
