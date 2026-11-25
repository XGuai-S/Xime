package com.kingzcheung.xime.association

import android.content.Context
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

object AssociationManager {
    private const val TAG = "AssociationManager"

    // 每累计这么多次输入记录就异步落盘一次，避免进程被杀时丢失用户词频
    private const val SAVE_EVERY_RECORDS = 20

    @Volatile
    private var isInitialized = false
    private val mutex = Mutex()

    @Volatile
    private var fusionEngine: NgramFusionEngine? = null

    @Volatile
    private var context: Context? = null

    private val pendingRecords = AtomicInteger(0)

    // 落盘专用作用域：release 之后保存任务仍要跑完，所以不能绑定任何会被取消的作用域
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun initialize(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        // 必须转成 applicationContext：本对象是进程级单例，直接持有传入的 Context
        // 会把 InputMethodService / Activity 钉在内存里（服务重建后旧实例永不回收）。
        // 应用上下文与进程同生命周期，不会泄漏。
        val appContext = ctx.applicationContext
        context = appContext
        if (isInitialized) {
            FileLogger.d(TAG, "Already initialized")
            return@withContext true
        }

        mutex.withLock {
            if (isInitialized) {
                FileLogger.d(TAG, "Already initialized (in lock)")
                return@withContext true
            }

            try {
                val engine = NgramFusionEngine(appContext)

                // register 对重复 id 直接跳过，loader 会被永久持有，只能捕获应用上下文
                ModelRuntime.register(
                    id = "predictive_text",
                    loader = { OnnxAssociationEngine.initialize(appContext) },
                    releaser = { OnnxAssociationEngine.release() },
                    label = "智能联想模型"
                )
                val modelLoaded = ModelRuntime.load("predictive_text")
                FileLogger.i(TAG, "ModelRuntime.load(predictive_text) result: $modelLoaded")

                if (modelLoaded) {
                    ModelRuntime.keepWarm("predictive_text")
                    OnnxAssociationEngine.startWarmup()
                }

                val cacheInit = engine.initialize()
                FileLogger.i(TAG, "NgramFusionEngine init result: $cacheInit")

                // 先发布引擎，再置位 isInitialized，保证看到 true 的线程一定能拿到引擎
                fusionEngine = engine
                pendingRecords.set(0)
                isInitialized = modelLoaded

                FileLogger.i(TAG, "AssociationManager initialized: model=$modelLoaded, cache=$cacheInit")
                modelLoaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to initialize AssociationManager: ${e.message}", e)
                false
            }
        }
    }

    suspend fun predict(contextText: String, topK: Int = 20): List<AssociationCandidate> = withContext(Dispatchers.Default) {
        if (!ensureReady()) {
            return@withContext emptyList()
        }
        val engine = fusionEngine ?: return@withContext emptyList()

        try {
            val modelCandidates = OnnxAssociationEngine.predict(contextText, topK * 2)
            if (modelCandidates.isEmpty()) {
                return@withContext emptyList()
            }

            engine.fuseCandidates(modelCandidates, contextText).take(topK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Prediction failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 保证管理器与模型都处于可用状态，只在这一处处理"未就绪"：
     * 1. 管理器未初始化 -> 用已记录的应用上下文初始化；
     * 2. :inference 进程被系统回收（空闲回收是设计内行为，同 AsrInferenceService 的
     *    空闲释放）后，OnnxAssociationEngine 检测到失联会重置自身状态，而本类的
     *    isInitialized 仍为 true。必须按引擎状态重载（重新 bind + loadModel），
     *    否则联想会永远撞上引擎的 "Engine not initialized" 空转。
     * 重载放在互斥锁内，避免并发预测同时触发多次加载。
     */
    private suspend fun ensureReady(): Boolean {
        if (!isInitialized) {
            val saved = context
            if (saved == null) {
                FileLogger.w(TAG, "Context is null, cannot initialize")
                return false
            }
            if (!initialize(saved)) {
                FileLogger.e(TAG, "Initialization failed, returning empty list")
                return false
            }
        }

        if (!OnnxAssociationEngine.isInitialized()) {
            val reloaded = mutex.withLock {
                OnnxAssociationEngine.isInitialized() || ModelRuntime.load("predictive_text")
            }
            if (!reloaded) {
                FileLogger.e(TAG, "Reload predictive_text failed after service reclaim")
                return false
            }
        }
        return true
    }

    fun recordInput(text: String) {
        if (!isInitialized) return
        val engine = fusionEngine ?: return
        engine.recordUserInput(text)

        if (pendingRecords.incrementAndGet() >= SAVE_EVERY_RECORDS) {
            pendingRecords.set(0)
            ioScope.launch { engine.saveCache() }
        }
    }

    suspend fun saveUserData() {
        fusionEngine?.saveCache()
    }

    fun getCacheSize(): Int {
        return if (isInitialized) fusionEngine?.getCacheSize() ?: 0 else 0
    }

    fun isInitialized(): Boolean = isInitialized

    fun release() {
        // 以运行时的真实状态为准：初始化中断或 binder 失联时，本地标记可能已过期
        isInitialized = false
        context = null
        ModelRuntime.releaseWarm("predictive_text")

        val engine = fusionEngine
        fusionEngine = null
        if (engine != null) {
            ioScope.launch { engine.saveCache() }
        }
        if (ModelRuntime.isLoaded("predictive_text")) {
            ModelRuntime.unload("predictive_text")
        }
    }
}
