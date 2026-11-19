package com.kingzcheung.xime.plugin.core.runtime.lifecycle

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.js.JsAsrPluginAdapter
import com.kingzcheung.xime.plugin.core.js.JsBackupPluginAdapter
import com.kingzcheung.xime.plugin.core.js.JsClipboardSyncPluginAdapter
import com.kingzcheung.xime.plugin.core.js.JsEmojiPluginAdapter
import com.kingzcheung.xime.plugin.core.js.JsPluginAdapter
import com.kingzcheung.xime.plugin.core.js.JsScriptRuntime
import com.kingzcheung.xime.plugin.core.js.JsToolPluginAdapter
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import com.kingzcheung.xime.plugin.core.runtime.installer.PluginRegistry
import com.kingzcheung.xime.plugin.core.runtime.loader.LoadedPluginInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PluginLifecycleManager(
    private val application: Application,
    private val pluginRegistry: PluginRegistry,
    private val installerManager: InstallerManager,
    private val loadedPlugins: ConcurrentHashMap<String, LoadedPluginInfo>,
    private val pluginInstances: ConcurrentHashMap<String, IPluginEntryClass>
) {

    companion object {
        private const val TAG = "PluginLifecycle"
    }

    /**
     * 每个插件的生命周期串行锁。
     *
     * 之前 launchPlugin / unloadPlugin / loadEnabledPlugins 全都只靠 ConcurrentHashMap
     * 做「有没有」判断，而判断与写入之间是跨挂起点的长流程（建 QuickJS 运行时、
     * 跑 onLoad）。并发触发（如插件列表刷新与用户手动启用/禁用同时发生）会：
     *   1) 两个协程同时通过 containsKey==false，各自 new 一个 JsScriptRuntime，
     *      后写入的覆盖前一个 → 前一个引擎线程池永不回收（泄漏 + 事件重复分发）；
     *   2) unload 与 load 交错，造成 pluginInstances 里有实例但 loadedPlugins 里没有，
     *      或反过来，后续调用拿到半初始化状态。
     * 这里用 per-plugin Mutex 把整段「查 → 建 → 注册」串行化；Mutex 按 pluginId 细分，
     * 不同插件之间仍然并行，不影响 loadEnabledPlugins 的启动耗时。
     */
    private val lifecycleLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(pluginId: String): Mutex =
        lifecycleLocks.getOrPut(pluginId) { Mutex() }

    suspend fun launchPlugin(pluginId: String): Boolean =
        lockFor(pluginId).withLock { launchPluginLocked(pluginId) }

    private suspend fun launchPluginLocked(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (loadedPlugins.containsKey(pluginId)) {
                return@withContext reloadPluginLocked(pluginId)
            }
            launchSinglePlugin(pluginId)
        } catch (e: Throwable) {
            if (loadedPlugins.containsKey(pluginId)) {
                unloadPluginLocked(pluginId)
            }
            false
        }
    }

    suspend fun unloadPlugin(pluginId: String): Boolean {
        return lockFor(pluginId).withLock { unloadPluginLocked(pluginId) }
    }

    /**
     * 插件最近一次被真正调用的时刻（elapsedRealtime）。
     *
     * 用于空闲回收：JS 运行时常驻很吃内存（实测 7 个插件常驻时主进程 RSS 162MB），
     * 而绝大多数插件一次会话只会被用一两次。空闲一段时间后释放运行时，下次调用再重建，
     * 首次调用会慢一点（重建 QuickJS + 跑 onLoad），换来内存归还。
     */
    private val lastUsedAtMs = ConcurrentHashMap<String, Long>()

    internal fun touchPlugin(pluginId: String) {
        lastUsedAtMs[pluginId] = SystemClock.elapsedRealtime()
    }

    internal fun lastUsedOf(pluginId: String): Long = lastUsedAtMs[pluginId] ?: 0L

    /**
     * 回收空闲插件：返回被回收的插件 id 列表。
     *
     * 只回收**加载中且空闲超时**的插件；[excludeCategories] 里的类别永不回收
     * （剪贴板同步这类常驻后台的插件一回收就会断掉同步链路）。
     * 回收走正常 [unloadPlugin]（onUnload → 关脚本 → 摘出表），下次调用方拿到 null 时
     * 会重新 [launchPlugin]，因此不需要调用方改代码。
     */
    suspend fun reclaimIdlePlugins(
        nowMs: Long,
        idleMs: Long,
        excludeCategories: Set<PluginCategory> = emptySet(),
    ): List<String> {
        if (idleMs <= 0L) return emptyList()
        val victims = synchronized(lastUsedAtMs) {
            loadedPlugins.keys.filter { id ->
                val info = loadedPlugins[id] ?: return@filter false
                if (info.pluginInfo.category in excludeCategories) return@filter false
                val last = lastUsedAtMs[id] ?: 0L
                last > 0L && nowMs - last >= idleMs
            }
        }
        val reclaimed = ArrayList<String>(victims.size)
        for (id in victims) {
            // 二次确认：可能在筛选之后刚被调用过
            val last = lastUsedOf(id)
            if (last <= 0L || nowMs - last < idleMs) continue
            if (unloadPlugin(id)) reclaimed += id
        }
        return reclaimed
    }

    private suspend fun unloadPluginLocked(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        if (!loadedPlugins.containsKey(pluginId)) return@withContext false

        pluginInstances[pluginId]?.let { instance ->
            try {
                instance.onUnload()
            } catch (e: Exception) {
                Log.w(TAG, "plugin onUnload failed: $pluginId", e)
            }
        }

        // 释放 JS 运行时（QuickJS 引擎 + 线程池）。原来只调 onUnload 就把实例从 map
        // 摘掉，engine 从未 close → 每次插件热重载/禁用都泄漏一个引擎实例。
        // close() 内部幂等，且会先跑 onUnload（此处已跑过，二次调用无副作用）。
        loadedPlugins.remove(pluginId)?.script?.let { script ->
            try {
                script.close()
            } catch (e: Exception) {
                Log.w(TAG, "plugin script close failed: $pluginId", e)
            }
        }
        pluginInstances.remove(pluginId)
        lastUsedAtMs.remove(pluginId)
        true
    }

    suspend fun loadEnabledPlugins(): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "loadEnabledPlugins called")
        val allPlugins = pluginRegistry.getAllPlugins()
        Log.d(TAG, "All plugins from registry: ${allPlugins.map { "${it.id}(enabled=${it.enabled})" }}")

        val hostVersion = com.kingzcheung.xime.plugin.core.util.VersionUtil.getHostVersionName(application)
        val enabledPlugins = allPlugins.filter { plugin ->
            if (!plugin.enabled || loadedPlugins.containsKey(plugin.id)) return@filter false
            if (!plugin.supportsPlatform(PluginInfo.PLATFORM_ANDROID)) {
                Log.w(TAG, "Plugin ${plugin.id} 目标平台为 ${plugin.platforms}，非当前平台，跳过加载")
                return@filter false
            }
            val compatible = com.kingzcheung.xime.plugin.core.util.VersionUtil.isHostSupported(
                hostVersion ?: "", plugin.minHostVersion, plugin.maxHostVersion
            )
            if (!compatible) {
                Log.w(TAG, "Plugin ${plugin.id} 不兼容当前主应用版本，跳过加载")
            }
            compatible
        }
        Log.d(TAG, "Enabled plugins to load: ${enabledPlugins.map { it.id }}")

        if (enabledPlugins.isEmpty()) return@withContext 0

        var successCount = 0
        for (plugin in enabledPlugins) {
            if (lockFor(plugin.id).withLock { launchSinglePlugin(plugin.id) }) {
                successCount++
            } else {
                Log.w(TAG, "Failed to load: ${plugin.id}")
            }
        }
        Log.d(TAG, "loadEnabledPlugins completed: $successCount loaded")
        successCount
    }

    private suspend fun launchSinglePlugin(pluginId: String): Boolean {
        val pluginInfo = pluginRegistry.getPluginById(pluginId)
        if (pluginInfo == null) {
            Log.w(TAG, "Plugin info not found: $pluginId")
            return false
        }
        val hostVersion = com.kingzcheung.xime.plugin.core.util.VersionUtil.getHostVersionName(application)
        if (!com.kingzcheung.xime.plugin.core.util.VersionUtil.isHostSupported(
                hostVersion ?: "", pluginInfo.minHostVersion, pluginInfo.maxHostVersion
            )
        ) {
            Log.w(TAG, "Plugin $pluginId 不兼容当前主应用版本，拒绝加载")
            return false
        }
        if (!pluginInfo.supportsPlatform(PluginInfo.PLATFORM_ANDROID)) {
            Log.w(TAG, "Plugin $pluginId 目标平台为 ${pluginInfo.platforms}，拒绝加载")
            return false
        }

        val loadedPlugin = loadPlugin(pluginInfo)
        if (loadedPlugin == null) {
            Log.w(TAG, "Failed to load plugin: $pluginId")
            return false
        }
        loadedPlugins[pluginId] = loadedPlugin

        val instance = instantiatePlugin(loadedPlugin)
        if (instance == null) {
            Log.w(TAG, "Failed to instantiate plugin: $pluginId")
            // 注意：此处已持有该插件的 lifecycleLock，必须调 Locked 版本，
            // 直接调 unloadPlugin 会二次 withLock 自锁（Mutex 不可重入）。
            unloadPluginLocked(pluginId)
            return false
        }
        pluginInstances[pluginId] = instance
        Log.d(TAG, "Loaded: $pluginId")

        return true
    }

    private suspend fun reloadPluginLocked(pluginId: String): Boolean {
        unloadPluginLocked(pluginId)
        return launchSinglePlugin(pluginId)
    }

    private fun loadPlugin(plugin: PluginInfo): LoadedPluginInfo? {
        return try {
            val entryFile = File(plugin.path)
            if (!entryFile.exists()) {
                Log.w(TAG, "Plugin entry script not found: ${plugin.path}")
                return null
            }
            val pluginDir = entryFile.parentFile ?: File(plugin.path).parentFile
            val runtime = JsScriptRuntime(
                pluginId = plugin.id,
                pluginDir = pluginDir,
                entryScript = plugin.entryScript ?: "main.js",
                configStore = PluginManager.configStoreFactory.create(application, plugin.id),
                wsHostApi = PluginManager.wsHostApiFactory?.invoke(plugin.id),
                httpHostApi = PluginManager.httpHostApiFactory?.invoke(plugin.id),
                cryptoHostApi = PluginManager.cryptoHostApiFactory?.invoke(),
                sseHostApi = PluginManager.sseHostApiFactory?.invoke(plugin.id),
                // 数据类 API 按 manifest 能力声明门禁注入：未声明连实例都不创建（host 表不挂）
                quickSendHostApi = if (plugin.capabilities?.quickSendRead == true) {
                    PluginManager.quickSendHostApiFactory?.invoke(plugin.id)
                } else null,
                clipboardHostApi = if (plugin.capabilities?.clipboardRead == true) {
                    PluginManager.clipboardHostApiFactory?.invoke(plugin.id)
                } else null,
                // host.asr 上行表仅 speech 型插件注入
                injectAsr = plugin.type == "speech"
            )
            // 按能力声明启用下行事件通道：未声明 events 的插件零开销、零行为变化。
            runtime.initEvents(plugin.capabilities?.events?.toSet() ?: emptySet())
            LoadedPluginInfo(pluginInfo = plugin, script = runtime)
        } catch (e: Exception) {
            Log.e(TAG, "loadPlugin failed for ${plugin.id}", e)
            null
        }
    }

    private fun instantiatePlugin(loadedPlugin: LoadedPluginInfo): IPluginEntryClass? {
        val plugin = loadedPlugin.pluginInfo
        return try {
            val pluginContext = PluginContext(
                application = application,
                pluginInfo = plugin,
                configStore = PluginManager.configStoreFactory.create(application, plugin.id)
            )
            val adapter: JsPluginAdapter = when (plugin.category) {
                PluginCategory.ASR ->
                    JsAsrPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.EMOJI ->
                    JsEmojiPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.CLIPBOARD_SYNC ->
                    JsClipboardSyncPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.BACKUP ->
                    JsBackupPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext,
                        // 流式传输句柄仓库：与 host.http.upload/download 共享同一实例
                        blobStore = PluginManager.blobStoreFactory?.invoke(plugin.id)
                    )
                PluginCategory.TOOL ->
                    JsToolPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                else ->
                    JsPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
            }
            adapter.onLoad(pluginContext)
            adapter
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate JS plugin ${plugin.id}", e)
            null
        }
    }
}
