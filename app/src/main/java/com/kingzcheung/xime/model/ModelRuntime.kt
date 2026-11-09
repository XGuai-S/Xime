package com.kingzcheung.xime.model

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 模型运行时生命周期管理器。
 *
 * 统一管理 AI 模型的内存加载、释放、引用计数和内存压力回调。
 * 三个等级：
 * - **HOT**（[keepWarm]）：常驻内存，`onTrimMemory` 不会释放
 * - **WARM**（默认）：引用计数归零后自动释放
 * - **COLD** / **OFF**：未加载
 *
 * 业务层通过 [load]/[unload] 管理引用计数，通过 [keepWarm]/[releaseWarm] 控制常驻标记。
 * 引擎自身在 [markLoaded]/[markUnloaded] 同步状态。
 */
object ModelRuntime {

    private const val TAG = "ModelRuntime"

    private data class Registration(
        val loader: suspend () -> Boolean,
        val releaser: () -> Unit,
        val label: String
    )

    private class State {
        @Volatile var refCount = 0
        @Volatile var loaded = false
        @Volatile var hot = false
        val mutex = Mutex()
    }

    private val registry = ConcurrentHashMap<String, Registration>()
    private val states = ConcurrentHashMap<String, State>()
    private var attached = false

    /** 注册 [ComponentCallbacks2] 监听系统内存压力。在 [Application.onCreate] 中调用一次。 */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        (context.applicationContext as Application).registerComponentCallbacks(trimCallbacks)
        FileLogger.i(TAG, "ModelRuntime attached")
    }

    /**
     * 注册模型的加载/释放函数。
     * [loader] 在首次 [load] 时调用，[releaser] 在引用归零且非 HOT 时触发。
     * 重复注册同一 [id] 会被跳过。
     */
    fun register(
        id: String,
        loader: suspend () -> Boolean,
        releaser: () -> Unit,
        label: String = id
    ) {
        if (registry.containsKey(id)) {
            FileLogger.w(TAG, "Model '$id' already registered, skipping")
            return
        }
        registry[id] = Registration(loader, releaser, label)
        FileLogger.i(TAG, "Registered model: $label ($id)")
    }

    /**
     * 由引擎在初始化成功后调用，标记模型为已加载。
     * 用于引擎自注册场景（引擎在 [initialize] 内主动同步状态到 ModelRuntime）。
     */
    fun markLoaded(id: String) {
        val state = states.getOrPut(id) { State() }
        if (!state.loaded) {
            state.loaded = true
            state.refCount = 1
        }
    }

    /** 由引擎在释放后调用，标记模型为未加载。 */
    fun markUnloaded(id: String) {
        states[id]?.let {
            it.loaded = false
            it.refCount = 0
        }
    }

    /**
     * 加载模型（引用计数 +1）。
     * 首次加载时调用 [register] 注册的 [loader]，后续调用仅递增引用计数。
     * @return 加载是否成功
     */
    suspend fun load(id: String): Boolean {
        val reg = registry[id] ?: run {
            FileLogger.w(TAG, "load: unknown model '$id'")
            return false
        }
        val state = states.getOrPut(id) { State() }
        state.mutex.withLock {
            if (state.loaded) {
                state.refCount++
                FileLogger.d(TAG, "${reg.label}: already loaded (refCount=${state.refCount})")
                return true
            }
            FileLogger.i(TAG, "${reg.label}: loading...")
            state.loaded = try {
                reg.loader()
            } catch (e: Exception) {
                FileLogger.e(TAG, "${reg.label}: load failed", e)
                false
            }
            if (state.loaded) {
                state.refCount = 1
                FileLogger.i(TAG, "${reg.label}: loaded (refCount=1)")
            }
            return state.loaded
        }
    }

    /**
     * 尝试引用已加载的模型（引用计数 +1）。
     * 如果模型尚未加载则返回 false，不触发加载。
     */
    fun tryLoad(id: String): Boolean {
        val state = states[id] ?: return false
        if (!state.loaded) return false
        state.refCount++
        return true
    }

    /**
     * 卸载模型（引用计数 -1）。
     * 引用计数归零且非 HOT 时调用 [register] 注册的 [releaser]，释放引擎资源。
     */
    fun unload(id: String) {
        val state = states[id] ?: return
        val reg = registry[id] ?: return

        state.refCount = (state.refCount - 1).coerceAtLeast(0)
        if (state.refCount == 0 && !state.hot && state.loaded) {
            FileLogger.i(TAG, "${reg.label}: releasing (refCount=0, !hot)")
            try {
                reg.releaser()
            } catch (e: Exception) {
                FileLogger.e(TAG, "${reg.label}: release failed", e)
            }
            state.loaded = false
        }
    }

    /** 标记模型为常驻（HOT），[onTrimMemory] 和引用归零不会释放它。 */
    fun keepWarm(id: String) {
        states[id]?.hot = true
    }

    /** 取消常驻标记，允许引用归零时释放。 */
    fun releaseWarm(id: String) {
        states[id]?.hot = false
    }

    /** 查询模型是否已加载。 */
    fun isLoaded(id: String): Boolean = states[id]?.loaded ?: false

    /** 返回所有已加载模型及其常驻状态。 */
    fun getLoadedModels(): Map<String, Boolean> =
        states.filter { it.value.loaded }.mapValues { it.value.hot }

    /**
     * 响应系统内存压力。
     * [ComponentCallbacks2.TRIM_MEMORY_MODERATE] 及以上释放所有非 HOT 模型。
     */
    fun onTrimMemory(level: Int) {
        val shouldRelease = level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (!shouldRelease) return

        val toRelease = states.filter { (_, state) ->
            state.loaded && !state.hot
        }

        if (toRelease.isEmpty()) return

        FileLogger.i(TAG, "onTrimMemory($level): releasing ${toRelease.size} model(s)")
        toRelease.forEach { (id, state) ->
            val reg = registry[id]
            FileLogger.i(TAG, "  releasing: ${reg?.label ?: id}")
            try {
                reg?.releaser?.invoke()
            } catch (e: Exception) {
                FileLogger.e(TAG, "  release failed: ${reg?.label ?: id}", e)
            }
            state.loaded = false
            state.refCount = 0
        }
    }

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            this@ModelRuntime.onTrimMemory(level)
        }

        override fun onLowMemory() {
            onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}
    }
}
