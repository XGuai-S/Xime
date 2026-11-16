package com.kingzcheung.xime.ai

import android.app.Application
import android.util.Log
import com.kingzcheung.xime.ai.data.AppRepository
import com.kingzcheung.xime.ai.network.AiPipelineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * AI 模块的进程级依赖容器。
 *
 * 移植说明：TypeFree 的 `TypeFreeApp : Application()` 同时持有 repository、aiEngine、
 * rimeDataManager、rimeInputEngine、flypyDictionary 五样东西。Xime 的 Application 是
 * `XimeApplication`，且 Rime 引擎由 `RimeEngine` 单例管理——这里只保留 AI 需要的两项
 * （repository + aiEngine），其余不重复持有。
 *
 * 初始化时机：**不在 Application.onCreate 里构造**（Xime 启动路径已很长，AI 属于
 * 可选功能）。改由 [XimeAiApp.init] 在首次打开 AI 面板时惰性初始化，避免给冷启动
 * 增加负担——这是与 TypeFree 的唯一行为差异，不影响功能。
 */
object XimeAiApp {

    private const val TAG = "XimeAiApp"

    @Volatile
    private var _repository: AppRepository? = null

    @Volatile
    private var _aiEngine: AiPipelineEngine? = null

    /** 供 AI 模块内部使用的作用域（进程级，SupervisorJob 保证单个任务失败不影响其他）。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 惰性初始化（幂等，可从任意线程调用）。
     *
     * @return true 表示已就绪
     */
    @Synchronized
    fun init(app: Application): Boolean {
        if (_repository != null && _aiEngine != null) return true
        return try {
            if (_repository == null) _repository = AppRepository(app)
            if (_aiEngine == null) _aiEngine = AiPipelineEngine()
            Log.i(TAG, "AI module initialized")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "AI module init failed", e)
            false
        }
    }

    /** 设置仓库（必须先于 [repository] 调用）。 */
    val repository: AppRepository
        get() = requireNotNull(_repository) { "XimeAiApp 未初始化：请先调用 init(app)" }

    /** AI 引擎（必须先于 [aiEngine] 调用）。 */
    val aiEngine: AiPipelineEngine
        get() = requireNotNull(_aiEngine) { "XimeAiApp 未初始化：请先调用 init(app)" }

    /** 是否已初始化（面板 UI 可据此决定是否显示"正在准备"）。 */
    fun isReady(): Boolean = _repository != null && _aiEngine != null

    /**
     * 确保已初始化；未就绪时用 [context] 的 Application 惰性 init。
     *
     * 供无障碍服务、悬浮胶囊、后台线程等「可能早于输入法/AI 面板」的入口调用，
     * 避免直接访问 [repository]/[aiEngine] 时抛 "XimeAiApp 未初始化" 导致进程崩溃。
     */
    fun ensureInit(context: android.content.Context): Boolean {
        if (isReady()) return true
        val app = context.applicationContext as? android.app.Application ?: return isReady()
        return init(app)
    }
}
