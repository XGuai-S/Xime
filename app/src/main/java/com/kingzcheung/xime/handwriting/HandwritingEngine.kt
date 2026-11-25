package com.kingzcheung.xime.handwriting

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.service.InferenceClient
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.runBlocking
import java.io.File

data class HandwritingCandidate(
    val char: String,
    val score: Float,
)

/**
 * 手写识别客户端（主进程）：只负责模型生命周期管理与 IPC 代理。
 * 笔画预处理、ONNX 推理与 idx→汉字映射全部在 :inference 进程完成
 * （见 [HandwritingInference]），主进程不加载 ONNX 运行库、不常驻词表。
 */
object HandwritingEngine {
    private const val TAG = "HandwritingEngine"
    private const val DEFAULT_TOP_K = 10
    /** 自愈重试节流：:inference 进程回收后重载失败时，短时间内不重复 bind 空转。 */
    private const val REINIT_RETRY_INTERVAL_MS = 5_000L

    @Volatile
    private var initialized = false
    private var inferenceClient: InferenceClient? = null
    private var appContext: Context? = null

    @Volatile
    private var lastReinitAttemptMs = 0L

    /** 手写模型可用性检查的负结果缓存时长（见 [hasModel]）。 */
    private const val MODEL_CHECK_NEGATIVE_TTL_MS = 3_000L

    /** 最近一次 hasModel 检查的时间点与结果（正结果永久缓存）。 */
    @Volatile
    private var modelCheckAtMs = 0L
    @Volatile
    private var modelCheckPositive = false

    /** 绑定/加载失败的用户提示节流：30s 内最多一次，避免连续落笔时刷屏。 */
    private const val BIND_FAILURE_TOAST_INTERVAL_MS = 30_000L
    @Volatile
    private var lastBindFailureToastMs: Long? = null

    /** 实际弹提示的出口；单测替换成收集器，不依赖 Android Toast。 */
    internal var toastDispatcher: (String) -> Unit = { msg ->
        val ctx = appContext
        if (ctx != null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * :inference 绑定/模型加载失败的用户提示（节流）。
     *
     * 此前失败只有日志，用户看到的是「手写板写了没候选」，无从判断是模型没装、
     * 服务进程没起来还是 binder 断了。这里给一次可读提示，30s 内不重复。
     */
    internal fun notifyBindFailure(nowMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(this) {
            val last = lastBindFailureToastMs
            if (last != null && nowMs - last < BIND_FAILURE_TOAST_INTERVAL_MS) return
            lastBindFailureToastMs = nowMs
        }
        toastDispatcher("手写识别服务未就绪，请稍后重试")
    }

    /** 仅供测试重置状态。 */
    internal fun resetToastThrottleForTest() {
        lastBindFailureToastMs = null
        toastDispatcher = { msg ->
            val ctx = appContext
            if (ctx != null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun initialize(context: Context): Boolean {
        if (initialized && inferenceClient?.isBound() == true) return true

        val ctx = context.applicationContext
        val modelDir = runCatching { ModelStorage.getModelDir(ctx, "ochwpro") }.getOrNull() ?: return false
        // 兼容旧版：旧版手写模型在 filesDir 根目录
        ModelStorage.migrateLegacyForModel(ctx, "ochwpro")
        val modelFile = runCatching { com.kingzcheung.xime.model.ModelPaths.child(modelDir, "ochwpro.onnx") }.getOrNull() ?: return false
        val charIndexFile = runCatching { com.kingzcheung.xime.model.ModelPaths.child(modelDir, "char_index.json") }.getOrNull() ?: return false

        if (!modelFile.isFile || !charIndexFile.isFile) {
            Log.w(TAG, "Model files not found: $modelFile, $charIndexFile")
            return false
        }

        try {
            val client = InferenceClient(ctx)
            // 先释放旧 client 的绑定，避免重复 initialize 泄漏 ServiceConnection
            inferenceClient?.unbind()
            inferenceClient = client
            // 单例持有 applicationContext，避免钉住 InputMethodService / Activity
            appContext = ctx.applicationContext
            client.onDisconnected = {
                // 进程被回收后系统 AUTO_CREATE 自动重启只恢复 binder，模型不会自己回来：
                // 仅软重置本地"已加载"标志（不 unbind，保留自动重启），下次落笔由
                // ensureEngineReady 检测到未初始化后重载，避免"重启抢先→静默空结果"
                initialized = false
            }

            // 调用方均在后台线程（键盘 LaunchedEffect(IO)/切方案 Thread），runBlocking 桥接 IPC
            val bound = runBlocking { client.ensureBound() }
            if (!bound) {
                Log.e(TAG, "Failed to bind InferenceService for handwriting")
                notifyBindFailure()
                return false
            }
            val loaded = runBlocking {
                client.loadModel(
                    InferenceClient.MODEL_HANDWRITING,
                    modelFile.absolutePath,
                    charIndexFile.absolutePath
                )
            }
            if (!loaded) {
                Log.e(TAG, "Failed to load handwriting model in inference process")
                notifyBindFailure()
                return false
            }

            initialized = true
            Log.i(TAG, "Handwriting engine initialized via IPC")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "HandwritingEngine init failed: ${e.message}", e)
            FileLogger.e(TAG, "HandwritingEngine init failed: ${e.message}", e)
            return false
        }
    }

    fun isInitialized(): Boolean = initialized

    /**
     * 手写模型是否可用（含旧版 filesDir 根目录模型迁移后检查）。
     *
     * **带缓存**：这个函数的调用点在 KeyboardView 的 composition 里（工具栏按钮可用性判定），
     * 每次重组都会调一次。而它内部要跑一遍 `migrateLegacyForModel`（目录列举 + 可能的搬迁）
     * 和两次 `File.exists()`。候选栏每敲一键就重组一次，等于把磁盘操作挂在了打字的每帧上。
     *
     * 缓存策略故意不对称：
     *  - **正结果永久缓存**：模型装好之后不会自己消失，反复 stat 没有意义；
     *  - **负结果只缓存 [MODEL_CHECK_NEGATIVE_TTL_MS]**：用户可能刚在设置里装完模型就回键盘，
     *    永久缓存会让工具栏按钮「装了也出不来」。3s 足够覆盖连续重组，又不至于让用户困惑。
     *
     * 副作用说明：用户在外部删除模型文件后，本进程内仍会认为「有模型」（按钮继续显示），
     * 点进去由 [ensureEngineReady] 失败兜底——比每次重组都访问磁盘更划算。
     */
    fun hasModel(context: Context): Boolean {
        if (modelCheckPositive) return true
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - modelCheckAtMs < MODEL_CHECK_NEGATIVE_TTL_MS) return false
        val exists = runCatching {
            ModelStorage.migrateLegacyForModel(context, "ochwpro")
            val modelDir = ModelStorage.getModelDir(context, "ochwpro")
            com.kingzcheung.xime.model.ModelPaths.child(modelDir, "ochwpro.onnx").isFile &&
                com.kingzcheung.xime.model.ModelPaths.child(modelDir, "char_index.json").isFile
        }.getOrDefault(false)
        modelCheckAtMs = now
        if (exists) modelCheckPositive = true
        return exists
    }

    fun predict(
        strokes: List<List<Pair<Float, Float>>>,
        topK: Int = DEFAULT_TOP_K
    ): List<HandwritingCandidate> {
        if (strokes.isEmpty()) return emptyList()
        if (!ensureEngineReady()) return emptyList()
        val client = inferenceClient ?: return emptyList()

        // 原始笔画扁平化：points = [x0,y0,x1,y1,...]，counts = 每笔画点数
        var totalPoints = 0
        for (stroke in strokes) totalPoints += stroke.size
        val points = FloatArray(totalPoints * 2)
        val counts = IntArray(strokes.size)
        var pi = 0
        strokes.forEachIndexed { i, stroke ->
            counts[i] = stroke.size
            for (pt in stroke) {
                points[pi++] = pt.first
                points[pi++] = pt.second
            }
        }

        val candidates = runBlocking { client.recognizeHandwriting(points, counts, topK) }

        // IPC 期间服务进程被回收（客户端已吞 DeadObjectException 并重置绑定）：
        // 重置本地状态，下次 predict 由 ensureEngineReady 自愈重载
        if (!client.isBound()) {
            release()
            return emptyList()
        }
        return candidates
    }

    /**
     * 预测前确保引擎可用。:inference 进程被系统回收（设计内行为，同联想模型）
     * 后在此按需重载（重新 bind + loadModel）实现自愈。从未初始化过时直接返回
     * false，保持"无模型时 predict 返回空"的既有语义，不自动拉起服务。
     */
    private fun ensureEngineReady(): Boolean {
        val ctx = appContext ?: return false
        if (initialized && inferenceClient?.isBound() == true) return true

        release()
        val now = SystemClock.elapsedRealtime()
        if (now - lastReinitAttemptMs < REINIT_RETRY_INTERVAL_MS) return false
        lastReinitAttemptMs = now
        return initialize(ctx)
    }

    fun release() {
        if (!initialized && inferenceClient == null) return
        initialized = false
        inferenceClient?.apply {
            try {
                runBlocking { unloadModel(InferenceClient.MODEL_HANDWRITING) }
            } catch (_: Exception) {
            }
            unbind()
        }
        inferenceClient = null
    }
}
