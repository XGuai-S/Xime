package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 部署/重载提示用**标准系统 Toast**（与输入法界面里其它提示同一写法）。
 *
 * ## 历史
 *
 * 曾经为了「键盘收起后也能看到」加过两套重量级方案：
 * 键盘内提示条（`InputUIState.transientHint`）和系统级浮层（`OverlayHint`，
 * 依赖「显示在其他应用上层」权限）。
 *
 * 用户明确要求：**直接用系统 Toast，跟输入法界面里的那样**。因此两者都已移除。
 *
 * ## 本测试钉住的不变量
 *
 * 1. 部署提示用 `Toast.makeText(service, ..., LENGTH_SHORT)`——与项目里其它
 *    能正常显示的 Toast 完全同款（Service 上下文，不用 applicationContext）。
 * 2. Toast 在 `requestHideSelf` **之前**提交：收起键盘后 Service 失去前台窗口，
 *    Android 11+ 对后台应用的 Toast 有限制。
 * 3. 不再引入浮层/键盘内提示这类额外机制。
 */
class SchemaDeployToastTest {
    private val source: String = RepoFiles.mainKotlinSources
        .single { it.name == "ImeSchemaController.kt" }
        .readText()
        .replace("\r\n", "\n")

    private val reloadConfig: String = source
        .substringAfter("internal fun reloadConfig() {")
        .substringBefore("// 部署投递到 key-processing 队列")

    @Test
    fun `deploy toast uses the same plain Toast as the rest of the IME`() {
        assertTrue(
            "开始部署必须弹标准 Toast",
            reloadConfig.contains("service, \"方案部署中...\", android.widget.Toast.LENGTH_SHORT"),
        )
        assertTrue(
            "完成部署必须弹标准 Toast",
            source.contains("service, \"方案部署完成\", android.widget.Toast.LENGTH_SHORT"),
        )
        assertFalse(
            "不得用 applicationContext 构造 Toast（部分 ROM 兼容性差）",
            source.contains("applicationContext, message"),
        )
    }

    @Test
    fun `toast is submitted before hiding the keyboard`() {
        val toastIndex = reloadConfig.indexOf("方案部署中...")
        val hideIndex = reloadConfig.indexOf("requestHideSelf(0)")
        assertTrue("部署提示必须存在", toastIndex >= 0)
        assertTrue("收起键盘必须存在", hideIndex >= 0)
        assertTrue(
            "Toast 必须在 requestHideSelf 之前提交（键盘收起后 Service 失去前台性）",
            toastIndex < hideIndex,
        )
    }

    @Test
    fun `no extra hint machinery is introduced`() {
        assertFalse(
            "不再保留键盘内提示字段",
            RepoFiles.mainKotlinSources.single { it.name == "InputUIState.kt" }
                .readText().contains("transientHint"),
        )
        assertFalse(
            "不再保留系统浮层实现",
            RepoFiles.mainKotlinSources.any { it.name == "OverlayHint.kt" },
        )
        assertFalse(
            "不得再调用已移除的 showDeployFeedback",
            source.contains("showDeployFeedback"),
        )
    }
}
