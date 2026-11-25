package com.kingzcheung.xime.plugin.core.runtime

import android.app.Application
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.model.InitState
import com.kingzcheung.xime.plugin.core.model.PluginFrameworkContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.runtime.loader.LoadedPluginInfo
import com.kingzcheung.xime.plugin.core.security.crash.PluginCrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

object PluginManager {

    private const val TAG = "PluginManager"

    fun interface PluginConfigStoreFactory {
        fun create(application: Application, pluginId: String): PluginConfigStore
    }

    @Volatile
    var configStoreFactory: PluginConfigStoreFactory =
        PluginConfigStoreFactory { _, _ -> NoopPluginConfigStore }

    /**
     * 宿主 WebSocket 白名单 API 提供者（app 层注入，按插件创建以便做域名/授权校验）。
     * 工厂参数为插件 id，宿主据此查询插件声明的域名与用户授权。
     */
    @Volatile
    var wsHostApiFactory: ((pluginId: String) -> com.kingzcheung.xime.plugin.core.js.ws.WsHostApi)? = null

    /**
     * 宿主 HTTP 白名单 API 提供者（app 层注入，剪贴板同步等插件使用）。
     * 工厂参数为插件 id，宿主据此校验域名白名单与用户授权。
     */
    @Volatile
    var httpHostApiFactory: ((pluginId: String) -> com.kingzcheung.xime.plugin.core.js.http.HttpHostApi)? = null

    /**
     * 宿主大文件句柄仓库提供者（app 层注入，流式上传/下载用）。
     *
     * 必须与 [httpHostApiFactory] 返回的实例共享同一仓库：backup 扩展点登记的备份包句柄，
     * 由 `host.http.upload` 侧解析；否则句柄解析不到（上传报"源不存在"）。
     */
    @Volatile
    var blobStoreFactory: ((pluginId: String) -> com.kingzcheung.xime.plugin.core.js.http.BlobStore)? = null

    /**
     * 宿主 SSE 流式 HTTP 白名单 API 提供者（app 层注入，AI 长文本流式生成等插件使用）。
     * 工厂参数为插件 id，宿主据此校验域名白名单与用户授权（同 httpHostApiFactory）。
     */
    @Volatile
    var sseHostApiFactory: ((pluginId: String) -> com.kingzcheung.xime.plugin.core.js.http.SseHostApi)? = null

    /** 宿主加密/编码原语提供者（S3 SigV4 签名等，app 层注入）。 */
    @Volatile
    var cryptoHostApiFactory: (() -> com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi)? = null

    /**
     * 宿主快捷发送只读 API 提供者（app 层注入，读 ClipboardManager 内存缓存）。
     * 仅当插件 manifest 声明 `capabilities.quick_send_read: true` 时，
     * 生命周期管理器才会调用本工厂（未声明插件拿不到 host.quickSend）。
     */
    @Volatile
    var quickSendHostApiFactory: ((pluginId: String) -> com.kingzcheung.xime.plugin.core.js.QuickSendHostApi)? = null

    /**
     * 宿主剪贴板只读 API 提供者（app 层注入，读系统剪贴板）。
     * 仅当插件 manifest 声明 `capabilities.clipboard_read: true` 时注入。
     */
    @Volatile
    var clipboardHostApiFactory: ((pluginId: String) -> com.kingzcheung.xime.plugin.core.js.ClipboardHostApi)? = null

    private var frameworkContext: PluginFrameworkContext? = null
    private val _loadedPluginsFlow = MutableStateFlow<Map<String, LoadedPluginInfo>>(emptyMap())
    private val _pluginInstancesFlow = MutableStateFlow<Map<String, IPluginEntryClass>>(emptyMap())

    val initStateFlow: StateFlow<InitState>
        get() = requireContext().initState

    val loadedPluginsFlow: StateFlow<Map<String, LoadedPluginInfo>>
        get() = _loadedPluginsFlow

    val pluginInstancesFlow: StateFlow<Map<String, IPluginEntryClass>>
        get() = _pluginInstancesFlow

    val isInitialized: Boolean
        get() = frameworkContext?.initState?.value == InitState.INITIALIZED

    /**
     * 插件 JS 运行时空闲回收阈值（毫秒）；**0 = 关闭**。
     *
     * 默认 15 分钟：常驻输入法里 7 个插件同时活着时主进程 RSS 162MB（实测），
     * 而大多数插件一次会话只用一两次。空闲超时后释放运行时，下次调用自动重建
     * （首次会慢一点），内存还给系统 —— 内存压力小了，系统换页也少，间接省电。
     *
     * 关闭方式：应用启动时把它设为 0（见 XimeApplication 的配置读取），
     * 或在 xime.yaml 里配 `plugins.idle_reclaim_ms: 0`。
     */
    @Volatile
    var idleReclaimMs: Long = 15 * 60 * 1000L

    /** 空闲回收的检查间隔。 */
    private const val IDLE_REAPER_INTERVAL_MS = 60_000L

    /** 启动空闲回收协程（幂等）。 */
    @Volatile
    private var reaperStarted = false

    private fun startIdleReaperIfNeeded() {
        if (reaperStarted) return
        reaperStarted = true
        managerScope.launch {
            while (true) {
                kotlinx.coroutines.delay(IDLE_REAPER_INTERVAL_MS)
                val idleMs = idleReclaimMs
                if (idleMs <= 0L) continue
                val ctx = frameworkContext ?: continue
                val now = android.os.SystemClock.elapsedRealtime()
                val reclaimed = ctx.lifecycleManager.reclaimIdlePlugins(
                    nowMs = now,
                    idleMs = idleMs,
                    // 后台同步和定时备份依赖已加载的插件实例。
                    excludeCategories = setOf(
                        com.kingzcheung.xime.plugin.core.model.PluginCategory.CLIPBOARD_SYNC,
                        com.kingzcheung.xime.plugin.core.model.PluginCategory.BACKUP,
                    ),
                )
                if (reclaimed.isNotEmpty()) {
                    Log.i(TAG, "reclaimed idle plugins: $reclaimed")
                    updateFlows()
                }
            }
        }
    }

    val installerManager: com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
        get() = requireContext().installerManager

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun requireContext(): PluginFrameworkContext {
        return frameworkContext
            ?: throw IllegalStateException("PluginManager has not been initialized.")
    }

    @Synchronized
    fun initialize(
        context: Application,
        onSetup: (suspend () -> Unit)? = null
    ) {
        if (frameworkContext != null && frameworkContext?.initState?.value != InitState.NOT_INITIALIZED) {
            Log.d(TAG, "Already initialized, skipping")
            return
        }

        Log.d(TAG, "Starting initialization...")
        PluginCrashHandler.initialize(context)
        frameworkContext = PluginFrameworkContext(context)
        
        requireContext().initState.value = InitState.INITIALIZING

        requireContext().initializeLifecycleManager()
        requireContext().initState.value = InitState.INITIALIZED
        startIdleReaperIfNeeded()
        Log.d(TAG, "Framework initialized")

        managerScope.launch {
            try {
                Log.d(TAG, "Executing onSetup asynchronously...")
                onSetup?.invoke()
                updateFlows()
                Log.d(TAG, "onSetup completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "onSetup failed", e)
            }
        }
        Log.d(TAG, "Initialization complete (onSetup running in background)")
    }

    private fun updateFlows() {
        _loadedPluginsFlow.value = requireContext().loadedPlugins.toMap()
        _pluginInstancesFlow.value = requireContext().pluginInstances.toMap()
        Log.d(TAG, "Flows updated: ${_pluginInstancesFlow.value.size} instances")
    }

    suspend fun awaitInitialization() {
        if (isInitialized) return
        initStateFlow.first { it == InitState.INITIALIZED }
    }

    suspend fun launchPlugin(pluginId: String): Boolean {
        val result = requireContext().lifecycleManager.launchPlugin(pluginId)
        requireContext().lifecycleManager.touchPlugin(pluginId)
        updateFlows()
        return result
    }

    suspend fun unloadPlugin(pluginId: String) {
        requireContext().lifecycleManager.unloadPlugin(pluginId)
        updateFlows()
    }

    suspend fun loadEnabledPlugins(): Int {
        Log.d(TAG, "loadEnabledPlugins called")
        val result = requireContext().lifecycleManager.loadEnabledPlugins()
        updateFlows()
        Log.d(TAG, "loadEnabledPlugins result: $result")
        return result
    }

    /** 记录一次「插件被使用」；宿主热路径（候选变换等）直接访问运行时时需自行调用。 */
    fun touchPlugin(pluginId: String) {
        frameworkContext?.lifecycleManager?.touchPlugin(pluginId)
    }

    fun getPluginInstance(pluginId: String): IPluginEntryClass? {
        // 取用即视为一次使用：刷新空闲计时，避免刚被调用的插件被回收
        frameworkContext?.lifecycleManager?.touchPlugin(pluginId)
        val existing = requireContext().pluginInstances[pluginId]
        if (existing != null) return existing
        // 实例缺失但插件仍启用 → 很可能被空闲回收掉了（reclaimIdlePlugins 不 relaunch）。
        // 这里异步补一次加载，调用方本次仍拿到 null（各自有降级路径），
        // 下次取用即可用。不 relaunch 的话一次误回收 = 该插件在本次进程内长期失效
        // （表情空、ASR 降级、候选变换静默失效），只能重启恢复。
        relaunchIfEnabled(pluginId)
        return null
    }

    /** 已启用但当前无实例时，异步补一次加载；并发调用只会有一个真正执行。 */
    private fun relaunchIfEnabled(pluginId: String) {
        val ctx = frameworkContext ?: return
        if (relaunching.putIfAbsent(pluginId, true) != null) return
        managerScope.launch {
            try {
                val enabled = runCatching { ctx.pluginRegistry.getPluginById(pluginId)?.enabled == true }
                    .getOrDefault(false)
                if (enabled && ctx.pluginInstances[pluginId] == null) {
                    Log.i(TAG, "插件实例缺失（可能被空闲回收），异步重新加载: $pluginId")
                    launchPlugin(pluginId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "异步重新加载插件失败: $pluginId", e)
            } finally {
                relaunching.remove(pluginId)
            }
        }
    }

    /** 正在进行异步补加载的插件 id，避免同一插件并发拉起多个运行时。 */
    private val relaunching = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun getPluginInfo(pluginId: String): LoadedPluginInfo? {
        return requireContext().loadedPlugins[pluginId]
    }

    fun getAllPluginInstances(): Map<String, IPluginEntryClass> {
        val snapshot = requireContext().pluginInstances.toMap()
        // 全量取用也视为「在用」：表情面板 / ASR 列表 / 工具列表都走这里，
        // 取到的实例紧接着就会被调用。不 touch 的话这些插件 lastUsedAtMs 不更新，
        // 活跃使用中也可能被空闲回收误杀。
        //
        // 注意回收后不会自动 relaunch（getPluginInstance 只读表），
        // 一次误回收 = 该插件在本次进程内长期失效（表情空、ASR 降级、候选变换静默失效），
        // 所以这里的记账是必要的，不是锦上添花。
        frameworkContext?.lifecycleManager?.let { lm ->
            for (id in snapshot.keys) lm.touchPlugin(id)
        }
        return snapshot
    }

    fun getAllInstallPlugins(): List<PluginInfo> {
        return requireContext().pluginRegistry.getAllPlugins()
    }

    /**
     * 向所有运行实例广播下行事件。
     * 实际投递取决于插件是否声明了对应 `capabilities.events`，未声明/未启用通道的实例静默跳过。
     *
     * @return 成功投递（进入通道）的实例数。
     */
    fun dispatchEvent(event: com.kingzcheung.xime.plugin.core.js.PluginEvent): Int {
        val context = frameworkContext ?: return 0
        var delivered = 0
        for ((id, loaded) in context.loadedPlugins) {
            val runtime = loaded.script ?: continue
            if (runtime.dispatchEvent(event)) {
                // 事件投递成功也算「在用」：否则纯事件驱动的插件（如打字统计）
                // lastUsedAtMs 永不更新，活跃使用中也可能被空闲回收误杀。
                context.lifecycleManager.touchPlugin(id)
                delivered++
            }
        }
        return delivered
    }

    /** 判断插件是否兼容当前主应用版本（宿主侧读取自身版本）。 */
    fun isPluginHostCompatible(plugin: PluginInfo): Boolean {
        val hostVersion = com.kingzcheung.xime.plugin.core.util.VersionUtil.getHostVersionName(requireContext().application)
        return com.kingzcheung.xime.plugin.core.util.VersionUtil.isHostSupported(
            hostVersion ?: "", plugin.minHostVersion, plugin.maxHostVersion
        )
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Boolean {
        return try {
            val pluginInfo = requireContext().pluginRegistry.getPluginById(pluginId) ?: return false
            if (pluginInfo.enabled == enabled) return true
            val updatedPluginInfo = pluginInfo.copy(enabled = enabled)
            requireContext().pluginRegistry.updatePlugin(updatedPluginInfo)
            requireContext().pluginRegistry.flushToDisk()
            if (!enabled) {
                // 禁用时同步卸载运行中实例，避免插件继续后台运行（网络会话/剪贴板监听）
                unloadPlugin(pluginId)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun installPluginFromAssets(
        assetsPath: String,
        forceOverwrite: Boolean = true,
        onlyIfNewer: Boolean = false,
    ): Boolean {
        return try {
            val context = requireContext().application
            val pluginFile = File(context.cacheDir, "temp_plugin.xipk")
            context.assets.open(assetsPath).use { input ->
                pluginFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val result = installerManager.installPlugin(
                pluginFile, forceOverwrite, onlyIfNewer = onlyIfNewer, source = PluginSource.ASSET
            )
            pluginFile.delete()
            result is com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.InstallResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "installPluginFromAssets failed", e)
            false
        }
    }

    /**
     * 安装随包内置的插件（`assets/<assetsDir>` 目录下的 xipk 包）。
     *
     * debug 与 release 都要调用：内置插件是"开箱可用"的产品决定（如 WebDAV 云备份），
     * 只在 debug 生效等于发布版没有它。已装同版本会被 [installPluginFromAssets] 的
     * onlyIfNewer 跳过，因此每次启动调用是幂等的，也不会覆盖用户自行升级的版本。
     */
    suspend fun installBundledPluginsFromAssets(assetsDir: String = "plugins"): Int {
        Log.d(TAG, "installBundledPluginsFromAssets: $assetsDir")
        val context = requireContext().application
        var installedCount = 0

        try {
            val assetFiles = context.assets.list(assetsDir) ?: return 0
            Log.d(TAG, "Found ${assetFiles.size} files in assets/$assetsDir: ${assetFiles.toList()}")
            
            for (fileName in assetFiles) {
                if (fileName.endsWith(".xipk")) {
                    val assetPath = "$assetsDir/$fileName"
                    // 内置同步加版本守卫：不降级覆盖（保护 dev 热更新）
                    if (installPluginFromAssets(assetPath, forceOverwrite = true, onlyIfNewer = true)) {
                        installedCount++
                        Log.d(TAG, "Installed: $fileName")
                    } else {
                        Log.w(TAG, "Failed to install: $fileName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "installBundledPluginsFromAssets failed", e)
        }

        Log.d(TAG, "Total installed: $installedCount")
        return installedCount
    }
}