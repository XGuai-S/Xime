package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无线导入上传结果的接线契约测试。
 *
 * 回归背景：`importUploadPart` 从 `routeUpload` 内联代码抽成私有方法时，
 * `return true` / `return result.success` 被误写在 `_uploadResults.trySend(...)` **之前**，
 * 于是插件与内容文件上传成功后不再发送结果通知——前端右侧的「已导入」列表永远为空，
 * 而这不会编译失败、也不抛异常，只有真机上传后才能看出来。
 *
 * 这里锁两点：
 * 1. 成功通知必须真的可达（`trySend` 不能被写在 `return` 之后）；
 * 2. 插件分支与内容分支都要发通知。
 */
class WirelessUploadResultTest {

    private val source: String =
        RepoFiles.mainKotlinSources.single { it.name == "WirelessImportHelper.kt" }.readText()

    /** 去掉注释行，只对可执行代码做断言（避免注释里的字样造成误判）。 */
    private fun executable(text: String): String =
        text.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    @Test
    fun `插件分支发送成功通知`() {
        val code = executable(source)
        val branch = code.substringAfter("is ImportManager.ImportResult.Plugin ->")
            .substringBefore("is ImportManager.ImportResult.Content ->")
        assertTrue(
            "插件导入成功后必须发送上传结果，否则前端「已导入」列表不更新",
            branch.contains("_uploadResults.trySend(UploadResult(fileName = name, success = true))"),
        )
        assertTrue(
            "插件分支必须返回 true（路由据此判定 saved）",
            branch.contains("return true"),
        )
    }

    @Test
    fun `内容分支发送成功或失败通知`() {
        val code = executable(source)
        val branch = code.substringAfter("is ImportManager.ImportResult.Content ->")
            .substringBefore("else ->")
        assertTrue(
            "内容导入必须发送上传结果（成功与失败都要），否则前端状态与真实结果脱节",
            branch.contains("_uploadResults.trySend("),
        )
        assertTrue(
            "内容分支必须把 ImportManager 的结果作为返回值",
            branch.contains("return result.success"),
        )
    }

    @Test
    fun `通知发送在返回之前`() {
        val code = executable(source)
        val branch = code.substringAfter("is ImportManager.ImportResult.Plugin ->")
            .substringBefore("is ImportManager.ImportResult.Content ->")
        val sendAt = branch.indexOf("_uploadResults.trySend(")
        val returnAt = branch.indexOf("return true")
        assertTrue("找不到插件分支的上传通知", sendAt >= 0)
        assertTrue("找不到插件分支的返回", returnAt >= 0)
        assertTrue(
            "上传通知被写在了 return 之后（不可达代码）：前端永远不会收到导入结果",
            sendAt < returnAt,
        )
    }
}
