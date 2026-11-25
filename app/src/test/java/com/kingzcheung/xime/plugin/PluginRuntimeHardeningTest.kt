package com.kingzcheung.xime.plugin

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件系统本轮三项修复的接线回归。
 *
 * 1. **配置值自动授权不得全表扫描**：插件有 `host.config.set(key, value)` 任意写配置的能力。
 *    旧实现遍历 configStore 所有键，恶意插件把 `https://evil.com` 塞进任意字段，
 *    用户在设置页点一次「保存」就静默授权——等于绕过「用户显式授权」。
 * 2. **空闲回收后要能复活**：`reclaimIdlePlugins` 卸载实例后不会自动 relaunch，
 *    而 `getPluginInstance` 只读表 → 一次误回收 = 本次进程内长期失效（表情空/ASR 降级）。
 * 3. **网络回调不得阻塞 OkHttp 线程**：SSE/WS 回调在 OkHttp 读线程直接调用，
 *    同步等待最多 5s 会让心跳发不出去、事件堆积。
 */
class PluginRuntimeHardeningTest {

    private val configForm by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/ui/settings/PluginConfigFormScreen.kt").readText()
    }
    private val manager by lazy {
        RepoFiles.file("plugin-core/src/main/java/com/kingzcheung/xime/plugin/core/runtime/PluginManager.kt").readText()
    }
    private val runtime by lazy {
        RepoFiles.file("plugin-core/src/main/java/com/kingzcheung/xime/plugin/core/js/JsScriptRuntime.kt").readText()
    }

    @Test
    fun `自动授权只扫描表单可见字段`() {
        val start = configForm.indexOf("fun autoAuthorizeConfiguredHosts()")
        assertTrue("找不到 autoAuthorizeConfiguredHosts", start >= 0)
        val body = configForm.substring(start).substringBefore("\n    @Composable")
        assertFalse(
            "不得遍历 configStore 全部键（插件可自行写入任意键）",
            "configStore.keys()" in body,
        )
        assertTrue("必须遍历表单声明的字段", "fields.forEach" in body)
        assertTrue("必须限定输入类字段", "UiNodeType.TEXT" in body)
    }

    @Test
    fun `实例缺失时异步补加载`() {
        val start = manager.indexOf("fun getPluginInstance(")
        assertTrue("找不到 getPluginInstance", start >= 0)
        val body = manager.substring(start).substringBefore("\n    fun ")
        assertTrue("实例缺失必须触发补加载", "relaunchIfEnabled(pluginId)" in body)
        assertTrue("必须有补加载实现", "private fun relaunchIfEnabled(" in manager)
        // 必须校验插件仍启用，不能把已禁用的拉起来
        assertTrue("必须校验 enabled", "getPluginById(pluginId)?.enabled == true" in manager)
        // 并发保护
        assertTrue("必须有并发去重", "relaunching.putIfAbsent" in manager)
    }

    /** 截取 dispatchCallback 方法体（到下一个同级方法/注释块为止）。 */
    private fun dispatchCallbackBody(): String {
        val start = runtime.indexOf("private fun dispatchCallback(")
        assertTrue("找不到 dispatchCallback", start >= 0)
        val rest = runtime.substring(start)
        val end = rest.indexOf("\n    private fun ").let { if (it < 0) rest.length else it }
        return rest.substring(0, end)
    }

    @Test
    fun `网络回调不阻塞调用线程`() {
        val body = dispatchCallbackBody()
        assertTrue("必须投递到执行器", "executor.execute" in body)
        // 只看代码行，不看注释：注释里会引用旧的 future.get 写法做说明
        val codeLines = body.lineSequence()
            .map { it.substringBefore("//") }
            .filter { it.isNotBlank() }
            .toList()
        assertFalse(
            "不得同步等待回调结果（会阻塞 OkHttp 读线程）",
            codeLines.any { "future.get(" in it },
        )
        assertTrue("必须处理执行器已关闭", "RejectedExecutionException" in body)
    }

    @Test
    fun `回调失败仍记日志不吞异常`() {
        assertTrue("失败必须写日志", "logScriptError(" in dispatchCallbackBody())
    }
}
