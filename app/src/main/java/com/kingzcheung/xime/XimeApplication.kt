package com.kingzcheung.xime

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kingzcheung.xime.correction.CorrectionPriors
import com.kingzcheung.xime.correction.KeyTapLogger
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.RimeUserFileSync
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.UnifiedConfigFile
import com.kingzcheung.xime.settings.UserConfigDir
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class XimeApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(16 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .build()
    }
    
    companion object {
        private const val TAG = "XimeApplication"
    }
    
    private val applicationScope = CoroutineScope(Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()

        FileLogger.init(this)
        KeyTapLogger.init(this)
        KeyTapLogger.enabled = SettingsPreferences.isKeyTapLogEnabled(this)
        // 邻键误触纠错（librime 插件版）：初始化先验推送器
        CorrectionPriors.init(this)
        CorrectionPriors.enabled = SettingsPreferences.isCorrectorEnabled(this)
        // 启动自检：确认生效 schema 里挂上了 correction_translator（写文件日志）
        if (CorrectionPriors.enabled) {
            Thread {
                runCatching { Thread.sleep(4000) }
                runCatching { CorrectionPriors.selfCheck(SettingsPreferences.getCurrentSchema(this)) }
            }.start()
        }
        AppFonts.initialize(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e("CrashHandler", "Uncaught exception on thread: ${thread.name}", throwable)
                FileLogger.flushNow()
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val isDebug = BuildConfig.DEBUG
        if (isDebug) {
            // 插件 console 日志落盘（xipm dev/logs 轮询回显，规避 ROM 后台日志限流）
            com.kingzcheung.xime.plugin.PluginDevConsoleFileSink.install(this)
        }
        PluginManager.configStoreFactory =
            PluginManager.PluginConfigStoreFactory { app, pluginId ->
                PluginConfigStoreImpl(app, pluginId)
            }
        PluginManager.wsHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.ws.WsHostApiImpl(this, pluginId)
        }
        // 大文件句柄仓库（流式上传/下载用）：与 http 能力共享同一实例，
        // backup.push 登记的备份包句柄才能被 host.http.upload 解析到。
        val blobStore = com.kingzcheung.xime.plugin.http.PluginBlobStore(
            File(cacheDir, "plugin-blobs")
        )
        PluginManager.blobStoreFactory = { blobStore }
        PluginManager.httpHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.http.HttpHostApiImpl(this, pluginId, blobStore)
        }
        PluginManager.sseHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.http.SseHostApiImpl(this, pluginId)
        }
        PluginManager.cryptoHostApiFactory = {
            com.kingzcheung.xime.plugin.crypto.CryptoHostApiImpl()
        }
        PluginManager.quickSendHostApiFactory = { _ ->
            com.kingzcheung.xime.plugin.QuickSendHostApiImpl(this)
        }
        PluginManager.clipboardHostApiFactory = { _ ->
            com.kingzcheung.xime.plugin.ClipboardHostApiImpl(this)
        }
        com.kingzcheung.xime.plugin.core.security.PluginErrorLog.initialize(
            com.kingzcheung.xime.plugin.FilePluginErrorStore(
                File(filesDir, "logs/plugins/errors.jsonl")
            )
        )
        PluginManager.initialize(this) {
            // 内置插件随包安装：debug 与 release 一致，装完即开箱可用
            PluginManager.installBundledPluginsFromAssets("plugins")

            PluginManager.loadEnabledPlugins()
        }
        
        ExtensionManager.initialize(this)

        // 从 xime.yaml 加载配色方案
        KeyboardThemes.initFromConfig(this)

        // 首次启动把用户配置释放到 /storage/emulated/0/rime/xime/（用户可用系统「文件」App、
        // 电脑 USB 或第三方编辑器直接打开修改），并把已有的私有配置迁移过去。
        // 幂等，每次启动调用都安全；异步执行避免阻塞冷启动——释放的内容要么是等价迁移、
        // 要么是纯注释模板，都不会改变本次启动已解析出的行为。
        // 内部顺序：先迁移旧目录 → 再生成统一配置 xime.yaml → 最后判定目录就绪状态。
        // 未授权时整体跳过；授权后由 MainActivity 在回到前台时立即重跑（无需重启应用）。
        applicationScope.launch {
            try {
                UserConfigDir.releaseUserConfig(this@XimeApplication)
            } catch (t: Throwable) {
                Log.w(TAG, "release user config dir failed", t)
            }
        }

        // 从 xime.yaml 的 style 读取默认主题和显示模式
        // color_scheme 可指定 dynamic（Material You 动态配色，仅 Android 12+，
        // 低版本 getThemeById 自动回退到内置配色）
        SettingsPreferences.defaultKeyboardTheme = KeysConfigHelper.loadDefaultThemeId(this)
        SettingsPreferences.defaultDarkMode = KeysConfigHelper.loadDefaultDarkMode(this)

        // 初始化模型运行时（内存管理 + 生命周期）
        ModelRuntime.attach(this)

        // 自动备份调度：每 12h 检查距上次备份是否超时，超时且备份插件已配置则自动上传。
        // 放在插件初始化（PluginManager.initialize）之后，确保能拿到已启用的备份插件实例。
        com.kingzcheung.xime.settings.AutoBackup.start(applicationScope, this)

        preInitializeRimeEngine()
    }
    
    private fun preInitializeRimeEngine() {
        if (RimeEngine.isInitialized()) {
            return
        }
        
        applicationScope.launch {
            try {
                val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeDataAsync(this@XimeApplication)
                val engine = RimeEngine.getInstance()
                engine.initialize(userDataDir, sharedDataDir)

                // 首次安装/升级后静默编译词库。ensureDeployment 内部带互斥且 hash 一致时跳过，
                // 统一负责 deploymentDone/hash 状态，避免与输入法服务的初始化重复触发全量编译。
                RimeConfigHelper.ensureDeployment(this@XimeApplication)

                // 此时 Rime 工作目录才真正就绪（方案与词库已从 assets 释放进来）。
                // 必须在这里再同步一次：上面的 releaseUserConfig 与本次引擎初始化是两个
                // 独立协程，谁先跑完并不确定。若只靠前者，全新安装时镜像目录会建成空的——
                // 用户授权后打开 `/storage/emulated/0/rime/xime/` 看不到 `rime/`，
                // 正好是本功能要消除的那个困惑。
                RimeUserFileSync.sync(this@XimeApplication)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-initialize Rime engine", e)
            }
        }
    }
}
// build-iteration-test
// build-iteration-test-2
// fast-build-test
// daily-iteration-1
// daily-iteration-2
// profile-test
// final-verification
// final-test-2
// real-iteration-test
// offline-test
// pack-script-final
// release-optimize-test
// rel-iteration-real

internal fun buildVersionTag(): String = "xime-rel-opt"
internal fun buildVersionTag2(): String = "xime-rel-opt2"
internal fun buildVersionTag3(): String = "rel-final-opt"
internal fun buildVersionTag4(): String = "rel-prof-4m"
internal fun buildVersionTag5(): String = "rel-final-opt2"
internal fun buildVersionTag6(): String = "r8-measure"
internal fun buildVersionTag7(): String = "gc-measure"
internal fun buildVersionTag8(): String = "prof-clean"
internal fun buildVersionTag9(): String = "base-bench"
internal fun buildVersionTag10(): String = "time-track"
internal fun buildVersionTag11(): String = "sub-jvm"
internal fun buildVersionTag12(): String = "r8-thread6"
internal fun buildVersionTag13(): String = "no-fullmode"
internal fun buildVersionTag14(): String = "release-lite"
internal fun buildVersionTag15(): String = "lite-inc"
internal fun buildVersionTag16(): String = "lite-stable"
internal fun buildVersionTag17(): String = "r8-thread2"
internal fun buildVersionTag18(): String = "lite-final"
internal fun buildVersionTag19(): String = "debug-first"
internal fun buildVersionTag20(): String = "debug-inc"
