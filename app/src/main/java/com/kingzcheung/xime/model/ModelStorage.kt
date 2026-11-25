package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File

/**
 * 统一的模型存储目录管理。
 *
 * 所有模型统一存放到 `filesDir/models/<modelId>/`，按模型 id 区分。
 *
 * 旧版本曾把模型分散存放在 `filesDir/`（联想）、`filesDir/asr_models/<id>/`（ASR）。
 * 通过 [migrateLegacy] 在首次访问时把旧目录中的
 * 模型文件自动迁移到新目录，保证升级后已下载的模型仍可用。
 */
object ModelStorage {

    private const val TAG = "ModelStorage"

    /** 统一根目录：filesDir/models/ */
    fun getModelsRoot(context: Context): File = ModelPaths.child(context.filesDir, "models").also {
        require(!it.exists() || it.isDirectory) { "模型根路径不是目录" }
    }

    /** 某模型的目录：filesDir/models/<modelId>/ */
    fun getModelDir(context: Context, modelId: String): File {
        require(ModelPaths.isSafeId(modelId)) { "模型标识不安全" }
        return ModelPaths.child(context.filesDir, "models/$modelId").also {
            require(!it.exists() || it.isDirectory) { "模型路径不是目录" }
        }
    }

    /**
     * 已知模型的旧路径迁移表。key 为模型 id，value 为旧目录下的文件名。
     * 旧目录由 [legacyDirFor] 根据模型 id 推导。
     */
    private fun legacyDirFor(context: Context, modelId: String): File? {
        return when (modelId) {
            "predictive-text-small" -> context.filesDir
            // base 联想模型是新增版本，无旧路径，无需迁移
            "predictive-text-base" -> null
            // 手写模型：旧版在 filesDir 根目录
            "ochwpro" -> context.filesDir
            else -> {
                // ASR 类：旧目录为 filesDir/asr_models/<id>/
                val asrDir = ModelPaths.child(context.filesDir, "asr_models/$modelId")
                if (asrDir.exists() && asrDir.isDirectory) asrDir else null
            }
        }
    }

    /**
     * 按已知旧路径迁移模型到统一目录。
     * 调用方需在加载/检测模型前调用，确保已下载的旧版模型可用。
     * 联想模型（predictive-text-small）旧路径在 filesDir 根目录，仅迁移其两个模型文件，
     * 避免误移 rime 等其他数据。
     */
    fun migrateLegacyForModel(context: Context, modelId: String): Boolean {
        if (!ModelPaths.isSafeId(modelId)) return false
        if (runCatching { getModelDir(context, modelId) }.isFailure) return false
        // 下载/删除占着该模型的串行域时放弃迁移：否则旧文件会被搬进正在提交的目录
        return ModelOperationGate.tryIfIdle(context, modelId) { migrateLegacyLocked(context, modelId) } ?: false
    }

    private fun migrateLegacyLocked(context: Context, modelId: String): Boolean {
        // 旧版联想 small 与手写模型直接放在 filesDir 根目录，仅迁移其专属文件，避免误移其他数据
        if (modelId == "predictive-text-small") {
            return migrateLegacy(
                context, modelId,
                listOf(context.filesDir),
                listOf("vocab.json", "model_int8_dynamic.onnx")
            )
        }
        if (modelId == "ochwpro") {
            return migrateLegacy(
                context, modelId,
                listOf(context.filesDir),
                listOf("ochwpro.onnx", "char_index.json")
            )
        }
        val legacyDir = runCatching { legacyDirFor(context, modelId) }.getOrNull() ?: return false
        val targetDir = getModelDir(context, modelId)
        if (hasMigratedFiles(targetDir)) return true
        if (!targetDir.isDirectory && !targetDir.mkdirs()) return false
        val sources = legacyDir.listFiles().orEmpty().filter {
            !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isFile && it.length() > 0
        }
        val targets = runCatching { sources.map { ModelPaths.child(targetDir, it.name) } }.getOrNull() ?: return false
        var allOk = true
        sources.zip(targets).forEach { (f, target) ->
            if (!target.exists() && !moveModelFile(f, target)) allOk = false
        }
        return allOk
    }

    /**
     * 移动单个模型文件。rename 失败时先复制到同目录临时名、校验长度后再改名，
     * 成功才删源：直接 copyTo 目标中途失败会留下截断的 .onnx，而「目录非空」
     * 即视为已迁移，之后永不重试、源文件也不再被读取。
     */
    internal fun moveModelFile(source: File, target: File): Boolean = moveModelFileImpl(source, target)

    /** 目标目录已有正式文件即视为已迁移；被杀残留的 .partial 不算，否则半截迁移永不重试。 */
    private fun hasMigratedFiles(dir: File): Boolean =
        dir.listFiles()?.any { !it.name.endsWith(".partial") } == true

    private fun moveModelFileImpl(source: File, target: File): Boolean {
        if (source.renameTo(target)) {
            FileLogger.i(TAG, "Migrated ${source.name} to ${target.absolutePath}")
            return true
        }
        val partial = File(target.parentFile, "${target.name}.partial")
        return try {
            source.copyTo(partial, overwrite = true)
            if (partial.length() != source.length() || !partial.renameTo(target)) {
                partial.delete()
                FileLogger.e(TAG, "Failed to migrate ${source.name}: copy incomplete")
                false
            } else {
                source.delete()
                FileLogger.i(TAG, "Copied ${source.name} to ${target.absolutePath}")
                true
            }
        } catch (e: Exception) {
            partial.delete()
            FileLogger.e(TAG, "Failed to migrate ${source.name}: ${e.message}")
            false
        }
    }

    /**
     * 迁移旧路径中的模型文件到统一目录。
     *
     * @param legacyDirs 可能存放旧模型的目录（按优先级，最先找到文件的优先）
     * @param fileNames  需要迁移的文件名（只迁移这些文件，避免误移其他数据）
     * @return true 表示找到了旧模型并迁移成功（或新目录已有模型无需迁移）
     */
    private fun migrateLegacy(context: Context, modelId: String, legacyDirs: List<File>, fileNames: List<String>): Boolean {
        val targetDir = getModelDir(context, modelId)
        if (!fileNames.all { ModelPaths.isSafeRelativePath(it) }) return false

        // 新目录已有模型文件，无需迁移
        if (hasMigratedFiles(targetDir)) {
            return true
        }

        // 在旧目录中查找指定文件
        val legacyFiles = mutableListOf<Pair<File, File>>() // (source, targetName)
        for (legacyDir in legacyDirs) {
            if (!legacyDir.exists() || !legacyDir.isDirectory) continue
            for (name in fileNames) {
                val f = runCatching { ModelPaths.child(legacyDir, name) }.getOrNull() ?: return false
                if (f.exists() && f.isFile && f.length() > 0) {
                    val target = runCatching { ModelPaths.child(targetDir, name) }.getOrNull() ?: return false
                    legacyFiles.add(Pair(f, target))
                }
            }
        }

        if (legacyFiles.isEmpty()) return false

        // 迁移到新目录
        targetDir.mkdirs()
        var allOk = true
        for ((source, target) in legacyFiles) {
            if (!moveModelFile(source, target)) allOk = false
        }
        return allOk
    }
}
