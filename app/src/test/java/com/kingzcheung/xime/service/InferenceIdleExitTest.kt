package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:inference` 独立进程空闲回收。
 *
 * 缺陷：空闲退出分支用 `stopSelf()`，但 InferenceService 是**绑定型服务**，
 * stopSelf 不会终止进程——进程变成 cached empty 常驻（模型已卸载仍占内存）。
 * 同仓 `:asr` 已踩过同样的坑并改用 `Process.killProcess`。
 *
 * 修法：把「是否该退出」判据抽成纯函数便于验证；退出分支改用 killProcess，
 * 并在结束前释放 native 共享环境。
 */
class InferenceIdleExitTest {

    @Test
    fun `未到空闲时长不退出`() {
        assertFalse(InferenceIdlePolicy.shouldExit(idleMs = 59_000L, stillLoaded = false, idleDelayMs = 60_000L))
    }

    @Test
    fun `到空闲时长且模型已卸载才退出`() {
        assertTrue(InferenceIdlePolicy.shouldExit(idleMs = 60_000L, stillLoaded = false, idleDelayMs = 60_000L))
        assertTrue(InferenceIdlePolicy.shouldExit(idleMs = 120_000L, stillLoaded = false, idleDelayMs = 60_000L))
    }

    @Test
    fun `仍持有模型时不退出`() {
        assertFalse(InferenceIdlePolicy.shouldExit(idleMs = 120_000L, stillLoaded = true, idleDelayMs = 60_000L))
    }

    /** 截取 idleExitRunnable 到 onBind 之间的源码，避免正则被嵌套大括号截断。 */
    private fun idleRunnableSource(src: String): String {
        val start = src.indexOf("private val idleExitRunnable")
        assertTrue("找不到 idleExitRunnable", start >= 0)
        val rest = src.substring(start)
        val end = rest.indexOf("override fun onBind")
        return if (end > 0) rest.substring(0, end) else rest
    }

    @Test
    fun `空闲退出用 killProcess 而非无效的 stopSelf`() {
        val src = RepoFiles.file("src/main/java/com/kingzcheung/xime/service/InferenceService.kt").readText()
        assertTrue(
            "绑定型服务 stopSelf 不终止进程，必须用 killProcess",
            "Process.killProcess(android.os.Process.myPid())" in src,
        )
        // idle 退出分支里不得再靠 stopSelf 作为退出手段
        assertFalse("空闲退出分支不得用 stopSelf", "stopSelf()" in idleRunnableSource(src))
    }

    @Test
    fun `kill 前释放 native 共享环境`() {
        val src = RepoFiles.file("src/main/java/com/kingzcheung/xime/service/InferenceService.kt").readText()
        assertTrue("结束进程前应释放 ONNX 共享环境", "releaseSharedEnv()" in idleRunnableSource(src))
    }
}
