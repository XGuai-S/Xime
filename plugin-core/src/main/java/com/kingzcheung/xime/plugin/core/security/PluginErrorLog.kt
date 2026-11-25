package com.kingzcheung.xime.plugin.core.security

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 插件错误日志（内存环形缓冲 + 可插拔持久化）。
 *
 * - 内存：每插件最多保留 [MAX_ERRORS_PER_PLUGIN] 条，插件中心 UI 实时读取。
 * - 持久化：注册 [PluginErrorStore] 后，新错误异步落盘、启动时恢复（进程重启不丢）。
 *   未注册 store 时退化为纯内存（测试/旧宿主行为不变）。
 * - 数据面：错误带 [ErrorCategory]，UI 可渲染用户可读的原因与建议
 *   （[userMessage]/[userHint]），终端的[用户带不出信息]问题靠"复制诊断信息"闭环解决。
 */
object PluginErrorLog {

    private const val TAG = "PluginErrorLog"
    private const val MAX_ERRORS_PER_PLUGIN = 20

    /** 持久化存取接口（app 层实现，如文件 JSONL）。实现无需线程安全：宿主串行调用。 */
    interface PluginErrorStore {
        /** 启动时全量恢复（返回历史错误，顺序任意）。 */
        fun load(): List<PluginError>

        /** 追加一条错误。 */
        fun append(pluginId: String, error: PluginError)

        /** 清除某插件全部错误（含持久化）。 */
        fun clearAll(pluginId: String)

        /**
         * 清除**所有**插件的错误（含持久化）。
         *
         * 有默认空实现：老的实现类（测试 fake 等）不必改也不用管磁盘。
         * 真实实现必须覆盖，否则「全部清除」只清内存、重启后历史错误原样回来
         * （用户 2026-11-19 的「清不干净」就属这一类）。
         */
        fun clearEverything() {}
    }

    data class PluginError(
        val timestamp: Long = System.currentTimeMillis(),
        val pluginId: String,
        val operation: String,
        val message: String,
        val stackTrace: String? = null,
        val category: ErrorCategory = ErrorCategory.OTHER
    )

    private val errorLogs = ConcurrentHashMap<String, MutableList<PluginError>>()

    @Volatile
    private var store: PluginErrorStore? = null

    /** 落盘专用单线程：文件 IO 不阻塞调用线程（JS 执行线程/IO 协程）。 */
    private val storeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xime-plugin-error-store").apply { isDaemon = true }
    }

    /** 注册持久化 store 并恢复历史错误。仅首次生效（幂等，防止重复恢复）。 */
    fun initialize(store: PluginErrorStore?) {
        if (store == null || this.store != null) return
        this.store = store
        try {
            val loaded = store.load()
            if (loaded.isEmpty()) return
            errorLogs.clear()
            for (error in loaded) {
                errorLogs.getOrPut(error.pluginId) { mutableListOf() }.also { list ->
                    synchronized(list) { list.add(error) }
                }
            }
            // 恢复后按内存上限截断，避免历史累积拖垮 UI 与后续落盘
            errorLogs.values.forEach { list -> synchronized(list) { trim(list) } }
            Log.i(TAG, "Restored ${loaded.size} plugin errors from store")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore plugin errors from store", e)
            errorLogs.clear()
        }
    }

    /** 仅供测试：重置恢复状态与内存缓冲（生产代码不调用）。 */
    /** 仅供测试：重置恢复状态与内存缓冲（生产代码不调用）。 */
    internal fun resetForTest() {
        store = null
        errorLogs.clear()
    }

    /** 同一错误的合并窗口：窗口内重复只累加次数，不再新增条目、也不重复落盘。 */
    private const val MERGE_WINDOW_MS = 60_000L

    /** 上层错误详情 → 插件侧二次上报的合并窗口（宿主记完 HTTP 失败后插件很快会再记一条）。 */
    private const val RETHROW_WINDOW_MS = 3_000L

    /** 最近一条网络/HTTP 错误的详情（按插件），用于吞掉紧随其后的插件侧重述。 */
    private val recentHostErrorDetail = ConcurrentHashMap<String, Pair<String, Long>>()

    /** 去重键 → (最近时间, 累计次数)。 */
    private val recentKeys = ConcurrentHashMap<String, Pair<Long, Int>>()

    fun logError(
        pluginId: String,
        operation: String,
        message: String,
        throwable: Throwable? = null,
        category: ErrorCategory = ErrorCategory.OTHER
    ) {
        val now = System.currentTimeMillis()

        // 1) 插件侧的二次上报：宿主刚记录过同一个异常（同一句 message），直接吞掉。
        if (category == ErrorCategory.OTHER) {
            val host = recentHostErrorDetail[pluginId]
            if (host != null && now - host.second < RETHROW_WINDOW_MS &&
                PluginErrorNoise.isRethrowOfHostError(host.first, message)
            ) {
                Log.w(TAG, "[$pluginId] 插件侧重复上报已合并（上层错误已记录）: $message")
                return
            }
        }

        // 2) 同一错误（同类别/同操作/同消息）在 60s 内重复：只累加次数，不再新增条目与落盘
        val key = PluginErrorNoise.dedupeKey(pluginId, category, operation, message)
        val prev = recentKeys[key]
        if (prev != null && now - prev.first < MERGE_WINDOW_MS) {
            val count = prev.second + 1
            recentKeys[key] = now to count
            bumpMergedCount(pluginId, message, count)
            Log.w(TAG, "[$pluginId] 重复错误已合并（60s 内第 $count 次）: $operation / $message")
            return
        }
        recentKeys[key] = now to 1

        Log.e(TAG, "[$pluginId] $operation: $message", throwable)

        // 3) 网络类异常不存堆栈：40+ 行 okhttp/InetAddress 框架帧对排查没有价值，
        //    真正有用的是消息里的域名与原因（用户 2026-11-19 实测的噪声来源）。
        val keepStack = throwable != null && !PluginErrorNoise.isNetworkLikeCause(throwable)
        val error = PluginError(
            timestamp = now,
            pluginId = pluginId,
            operation = operation,
            message = message,
            stackTrace = if (keepStack) throwable?.stackTraceToString() else null,
            category = category
        )

        if (category != ErrorCategory.OTHER) {
            recentHostErrorDetail[pluginId] = message to now
        }

        errorLogs.getOrPut(pluginId) { mutableListOf() }.let { list ->
            synchronized(list) {
                list.add(error)
                trim(list)
            }
        }

        store?.let { s ->
            try {
                storeExecutor.execute { s.append(pluginId, error) }
            } catch (e: Exception) {
                Log.e(TAG, "store.append 提交失败（不影响内存记录）", e)
            }
        }
    }

    /**
     * 合并计数回写到内存里那条错误的文案上（例如「…（60s 内重复 3 次）」）。
     *
     * 只改内存、不落盘：目的是让用户在列表里看到「这是反复发生的同一个问题」，
     * 而不是刷出几十条一模一样的记录（那正是旧行为的噪声来源）。
     */
    private fun bumpMergedCount(pluginId: String, message: String, count: Int) {
        val list = errorLogs[pluginId] ?: return
        synchronized(list) {
            for (i in list.indices.reversed()) {
                val item = list[i]
                if (!item.message.startsWith(message)) continue
                val base = item.message.substringBefore("（60s 内重复 ")
                list[i] = item.copy(
                    message = if (count > 1) "$base（60s 内重复 $count 次）" else base,
                    timestamp = System.currentTimeMillis(),
                )
                return
            }
        }
    }

    /** 读取时也复制一份：并发 add/trim 下裸 toList() 会抛 ConcurrentModificationException。 */
    @Suppress("UNCHECKED_CAST")
    fun getErrors(pluginId: String): List<PluginError> {
        val list = errorLogs[pluginId] ?: return emptyList()
        return synchronized(list) { (list as List<PluginError>).toList() }
    }

    @Suppress("UNCHECKED_CAST")
    fun getAllErrors(): Map<String, List<PluginError>> {
        return errorLogs.mapValues { (_, list) ->
            synchronized(list) { (list as List<PluginError>).toList() }
        }
    }

    fun clearErrors(pluginId: String) {
        errorLogs.remove(pluginId)
        recentHostErrorDetail.remove(pluginId)
        recentKeys.keys.removeAll { it.startsWith("$pluginId\t") }
        store?.let { s ->
            try {
                storeExecutor.execute { s.clearAll(pluginId) }
            } catch (e: Exception) {
                Log.e(TAG, "store.clearAll 提交失败（不影响内存清除）", e)
            }
        }
    }

    fun clearAllErrors() {
        errorLogs.clear()
        recentKeys.clear()
        recentHostErrorDetail.clear()
        // 必须连磁盘一起清：只清内存的话重启后 initialize() 又把历史错误读回来，
        // 用户看到的就是「清了还有」。
        store?.let { s ->
            try {
                storeExecutor.execute { s.clearEverything() }
            } catch (e: Exception) {
                Log.e(TAG, "store.clearEverything 提交失败（不影响内存清除）", e)
            }
        }
    }

    fun hasErrors(pluginId: String): Boolean {
        val list = errorLogs[pluginId] ?: return false
        return synchronized(list) { list.isNotEmpty() }
    }

    fun getLastError(pluginId: String): PluginError? {
        val list = errorLogs[pluginId] ?: return null
        return synchronized(list) { list.lastOrNull() }
    }

    private fun trim(list: MutableList<PluginError>) {
        if (list.size > MAX_ERRORS_PER_PLUGIN) {
            val overflow = list.size - MAX_ERRORS_PER_PLUGIN
            list.subList(0, overflow).clear()
        }
    }

    // ---- 用户可读文案（终端用户导向，非技术细节） ----

    /** 可读的失败原因（一句话，面向用户）。 */
    fun userMessage(error: PluginError): String {
        return when (error.category) {
            ErrorCategory.SCRIPT_ERROR ->
                "插件脚本执行出错（${error.operation}）"
            ErrorCategory.NETWORK_DENIED -> "插件尝试访问未授权的服务器，已被阻止"
            ErrorCategory.HTTP_ERROR -> "插件网络请求失败（${error.operation}）"
            ErrorCategory.TIMEOUT_POISONED -> "插件执行超时，已停止运行"
            ErrorCategory.STREAM_ERROR -> "插件网络连接中断"
            ErrorCategory.OTHER -> "插件运行出现问题（${error.operation}）"
        }
    }

    /** 处理建议（面向用户）。 */
    fun userHint(error: PluginError): String {
        return when (error.category) {
            ErrorCategory.SCRIPT_ERROR ->
                "请更新插件，或联系插件作者并附上诊断信息（含下方技术详情）。"
            ErrorCategory.NETWORK_DENIED ->
                "如需该服务器，请到插件中心进入本插件，在「网络访问」中完成授权后再试。"
            ErrorCategory.HTTP_ERROR ->
                "请检查网络连接或服务器状态后重试。"
            ErrorCategory.TIMEOUT_POISONED ->
                "请重新加载插件；若反复出现请更新插件或联系作者（疑似死循环）。"
            ErrorCategory.STREAM_ERROR ->
                "网络恢复后请重试；若频繁中断请联系插件作者。"
            ErrorCategory.OTHER ->
                "请查看下方技术详情；必要时联系插件作者并附诊断信息。"
        }
    }
}