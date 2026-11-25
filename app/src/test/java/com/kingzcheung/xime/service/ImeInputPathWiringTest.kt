package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮输入链路/插件层的接线回归。
 *
 * 1. 候选点选必须在**点击瞬间**快照文本与 actions：连打时任务排在按键之后执行，
 *    按 index 现读 candidateState 会「点 A 上屏 B」。
 * 2. 键盘显示后的延迟剪贴板重算必须持有 Runnable 引用，否则服务销毁后仍会执行。
 * 3. 插件 WS 复用连接时必须更新 listener，否则新 runtime 永远等不到 onOpen。
 * 4. HTTP 未知方法不得静默降级为 GET（WebDAV COPY/MOVE/LOCK 语义会错）。
 * 5. 卸载插件必须清掉网络授权与配置，否则同 id 重装会继承旧权限。
 */
class ImeInputPathWiringTest {

    private val router by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/service/ImeKeyRouter.kt").readText()
    }
    private val service by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt").readText()
    }

    @Test
    fun `候选点选在点击瞬间快照`() {
        val start = router.indexOf("internal fun selectCandidate(")
        assertTrue("找不到 selectCandidate", start >= 0)
        val body = router.substring(start).substringBefore("\n    internal fun ").substringBefore("\n    private fun ")
        assertTrue("必须构造点击快照", "CandidatePickSnapshot(" in body)
        assertTrue("必须把快照随任务传入", "snapshot = pick" in body)
        // 快照必须在 postEditorRimeJob 之前
        val snap = body.indexOf("CandidatePickSnapshot(")
        val post = body.indexOf("postEditorRimeJob {")
        assertTrue("快照必须先于入队", snap >= 0 && post > snap)
    }

    @Test
    fun `selectCandidateAsync 支持外部快照`() {
        assertTrue("必须接受 snapshot 参数", "snapshot: CandidatePickSnapshot? = null" in router)
        assertTrue("必须优先使用快照文本", "snapshot?.text" in router)
        assertTrue("必须优先使用快照 action", "snapshot?.action" in router)
    }

    @Test
    fun `键盘显示后的剪贴板重算可取消`() {
        assertTrue("必须有具名 Runnable 字段", "private val windowShownClipboardRefresh = Runnable" in service)
        assertTrue("必须用字段投递", "mainHandler.postDelayed(windowShownClipboardRefresh, 150)" in service)
        assertTrue("销毁时必须移除", "mainHandler.removeCallbacks(windowShownClipboardRefresh)" in service)
        assertFalse(
            "不得再用无法取消的匿名 lambda",
            "mainHandler.postDelayed({ refreshClipboardCandidates(" in service,
        )
    }

    @Test
    fun `插件 WS 复用连接时更新 listener`() {
        val ws = RepoFiles.file("src/main/java/com/kingzcheung/xime/plugin/ws/WsHostApiImpl.kt").readText()
        val start = ws.indexOf("override fun connect(")
        assertTrue("找不到 connect", start >= 0)
        val body = ws.substring(start).substringBefore("\n    override fun ")
        val reuse = body.indexOf("if (webSocket != null)")
        assertTrue("必须有复用分支", reuse >= 0)
        val reuseBlock = body.substring(reuse, minOf(reuse + 400, body.length))
        assertTrue("复用分支必须更新 listener", "this.listener = listener" in reuseBlock)
    }

    @Test
    fun `HTTP 未知方法不降级为 GET`() {
        val http = RepoFiles.file("src/main/java/com/kingzcheung/xime/plugin/http/HttpHostApiImpl.kt").readText()
        val start = http.indexOf("when (method.uppercase())")
        assertTrue("找不到方法分发", start >= 0)
        val body = http.substring(start).substringBefore("\n            }")
        assertFalse("未知方法不得降级为 GET", "else -> requestBuilder.get()" in body)
        assertTrue("未知方法应原样透传", "requestBuilder.method(method.uppercase(), body)" in body)
    }

    @Test
    fun `卸载插件清理持久化痕迹`() {
        val vm = RepoFiles.file("src/main/java/com/kingzcheung/xime/viewmodel/PluginsSettingsViewModel.kt").readText()
        assertTrue("卸载成功后必须清授权与配置", "clearPluginPersistence(context, pluginId)" in vm)
        val prefs = RepoFiles.file("src/main/java/com/kingzcheung/xime/settings/SettingsPreferences.kt").readText()
        assertTrue("必须清网络授权", "remove(\"plugin_net_auth_\$pluginId\")" in prefs)
        assertTrue("必须清待授权", "remove(\"plugin_net_pending_\$pluginId\")" in prefs)
        assertTrue("必须清插件配置", "deleteSharedPreferences(\"plugin_cfg_\$pluginId\")" in prefs)
    }
}
