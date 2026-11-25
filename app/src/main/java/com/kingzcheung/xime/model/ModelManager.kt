package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.runBlocking
import java.io.File

object ModelManager {

    private const val TAG = "ModelManager"

    private var initialized = false
    private val _modelsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<ModelInfo>>(emptyList())

    /** 可观察的模型清单（远程 index 加载后自动更新） */
    val modelsFlow: kotlinx.coroutines.flow.StateFlow<List<ModelInfo>> = _modelsFlow

    fun initialize() {
        if (initialized) return
        initialized = true
        FileLogger.i(TAG, "ModelManager initialized")
    }

    /** 模型清单完全来自远程 index（xime_index.base_urls 指向的 models/index.yaml）。
     *  以 StateFlow 内的不可变列表为唯一状态源：读方拿到的是发布时的快照，
     *  与后续刷新互不干扰（历史上共享可变列表曾被遍历方并发 clear/addAll，
     *  连点刷新触发 ConcurrentModificationException 闪退）。 */
    private fun allModels(): List<ModelInfo> = _modelsFlow.value

    fun getAllModels(): List<ModelInfo> = allModels()

    fun getModel(id: String): ModelInfo? = allModels().find { it.id == id }

    fun getModelsByCategory(category: ModelCategory): List<ModelInfo> =
        allModels().filter { it.category == category }

    suspend fun loadFromRemote(context: Context) {
        val remote = ModelIndexLoader.loadFromRemote(context)
        if (remote.isNotEmpty()) {
            FileLogger.i(TAG, "Loaded ${remote.size} models from remote index")
        } else {
            FileLogger.w(TAG, "Remote index returned empty, no models available")
        }
        _modelsFlow.value = remote
    }

    fun isUsingRemoteIndex(): Boolean = _modelsFlow.value.isNotEmpty()

    fun isModelDownloaded(context: Context, id: String): Boolean {
        val model = getModel(id) ?: return false
        return isModelDownloaded(context, model)
    }

    fun isModelDownloaded(context: Context, model: ModelInfo): Boolean {
        // 检测前先尝试迁移旧路径模型到统一目录，保证已下载的旧版可用
        if (runCatching { ModelPaths.validateFiles(model.files); ModelStorage.getModelDir(context, model.id) }.isFailure) return false
        ModelStorage.migrateLegacyForModel(context, model.id)
        val dir = getModelStorageDir(context, model) ?: return false
        if (!dir.exists()) return false
        val files = resolveFiles(dir, model) ?: return false
        return files.all { it.isFile && it.length() > 0 }
    }

    fun getModelStorageDir(context: Context, model: ModelInfo): File? {
        // 统一规则：所有模型一律存 filesDir/models/<id>/
        return runCatching { ModelStorage.getModelDir(context, model.id) }.getOrNull()
    }

    fun getModelStorageDir(context: Context, id: String): File? {
        val model = getModel(id) ?: return null
        return getModelStorageDir(context, model)
    }

    /** 该模型是否正被下载/删除事务占用（存储清理入口用来跳过进行中的模型目录）。 */
    fun isModelBusy(context: Context, id: String): Boolean =
        ModelOperationGate.isDownloading(context, id)

    fun getModelSizeOnDisk(context: Context, id: String): Long {
        val model = getModel(id) ?: return 0
        val dir = getModelStorageDir(context, model) ?: return 0
        if (!dir.exists()) return 0

        val files = resolveFiles(dir, model) ?: return 0
        return files.sumOf { if (it.isFile) it.length() else 0L }
    }

    fun getDownloadedModels(context: Context): List<ModelInfo> {
        return allModels().filter { isModelDownloaded(context, it) }
    }

    suspend fun downloadModel(
        context: Context,
        id: String,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val model = getModel(id)
        if (model == null) {
            onProgress(ModelDownloadState.Error("未知模型: $id"))
            return
        }
        downloadModel(context, model, onProgress)
    }

    suspend fun downloadModel(
        context: Context,
        model: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit,
        version: ModelVersion? = null
    ) {
        // 下载事务进模型目录的串行域：与删除/迁移互斥，删除入口可取消这里的任务
        ModelOperationGate.runExclusive(context, model.id, download = true) {
            ModelDownloader.downloadModel(context, model, onProgress, version)
        }
    }

    fun deleteModel(context: Context, id: String): Boolean {
        val model = getModel(id) ?: return false
        return deleteModel(context, model)
    }

    /**
     * 删除模型文件：先取消该模型的活动下载并等它结束，再在串行域内删文件，
     * 保证不会与「逐文件提交」交错，也不会让回滚覆盖别的操作。
     */
    fun deleteModel(context: Context, model: ModelInfo): Boolean {
        val download = ModelOperationGate.activeDownload(context, model.id)
        download?.cancel()
        if (download != null) runCatching { runBlocking { download.join() } }
        return runCatching {
            runBlocking {
                ModelOperationGate.runExclusive(context, model.id) { deleteModelLocked(context, model) }
            }
        }.getOrDefault(false)
    }

    /** 调用方必须已持有该模型的串行域。 */
    private fun deleteModelLocked(context: Context, model: ModelInfo): Boolean {
        val dir = getModelStorageDir(context, model) ?: return false
        if (!dir.exists()) return false

        val files = resolveFiles(dir, model) ?: return false
        if (files.any { it.exists() && !it.isFile }) return false
        var success = true
        for (file in files) {
            if (file.exists() && !file.delete()) {
                success = false
            }
        }
        if (success) {
            com.kingzcheung.xime.settings.MarketVersionStore.removeModelVersion(context, model.id)
        }

        return success
    }

    private fun resolveFiles(dir: File, model: ModelInfo): List<File>? = runCatching {
        ModelPaths.validateFiles(model.files)
        model.files.map { file ->
            if (model.archiveUrl != null) {
                ModelPaths.findFile(dir, file.name) ?: ModelPaths.child(dir, file.name)
            } else {
                ModelPaths.child(dir, file.name)
            }
        }
    }.getOrNull()
}
