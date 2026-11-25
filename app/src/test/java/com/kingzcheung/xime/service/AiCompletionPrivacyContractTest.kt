package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 网络补全的敏感输入守卫必须早于初始化和光标读取。 */
class AiCompletionPrivacyContractTest {
    private val source by lazy {
        RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt"
        ).readText()
    }

    private val scheduleBody by lazy {
        source.substringAfter("internal fun scheduleAiCompletionIfEnabled() {")
            .substringBefore("internal val sessionController")
    }

    @Test
    fun `不学习标记与密码标记同时阻止AI补全`() {
        assertTrue(scheduleBody.contains("if (editorSecret || editorNoLearn) return"))
    }

    @Test
    fun `敏感输入守卫先于AI初始化和光标读取`() {
        val guard = scheduleBody.indexOf("if (editorSecret || editorNoLearn) return")
        val init = scheduleBody.indexOf("XimeAiApp.ensureInit(")
        val cursor = scheduleBody.indexOf("getTextBeforeCursor(")
        assertTrue(guard >= 0 && init > guard && cursor > guard)
    }

    @Test
    fun `回填守卫先于光标读取并读取当前配置`() {
        val callback = scheduleBody.substringAfter("mainHandler.post {")
        val gate = callback.indexOf("aiCompletionGate.runIfCurrent(")
        val cursor = callback.indexOf("getTextBeforeCursor(")
        assertTrue(gate >= 0 && cursor > gate)
        assertTrue(callback.contains("aiCompletionState(repo.settings.value)"))
    }

    @Test
    fun `缓存同步回调也必须携带先创建的请求票据`() {
        val ticket = scheduleBody.indexOf("aiCompletionGate.begin(")
        val schedule = scheduleBody.indexOf("completer.scheduleCompletion(")
        assertTrue(ticket >= 0 && schedule > ticket)
        val cancel = source.substringAfter("internal fun cancelAiCompletion() {").substringBefore("    }")
        assertTrue(cancel.contains("aiCompletionGate.invalidate()"))
    }

    @Test
    fun `输入会话和窗口生命周期立即取消旧补全`() {
        for (method in listOf("onStartInput(", "onFinishInput()", "onFinishInputView(", "onWindowHidden()", "onDestroy()", "hideKeyboard()")) {
            val body = source.substringAfter("fun $method").substringAfter("{").take(200)
            assertTrue("$method 必须作废旧请求", body.contains("cancelAiCompletion()"))
        }
    }

    @Test
    fun `不学习状态仍由宿主标记独立维护`() {
        assertTrue(source.contains("editorNoLearn = EditorInfoClassifier.shouldSkipLearning(attribute)"))
        assertTrue(source.contains("editorSecret = EditorInfoClassifier.isSecretEditor(attribute)"))
    }
}
