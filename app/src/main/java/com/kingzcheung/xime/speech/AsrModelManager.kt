package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.speech.models.AsrModelProfile
import com.kingzcheung.xime.speech.models.AsrModelRegistry
import java.io.File

/**
 * ASR 模型管理与选择。
 *
 * 模型推理由自研的 streaming zipformer2 实现（libasr_jni.so）负责。
 * 模型清单与描述来自「扩展商店」远程索引（[ModelManager]，category=asr），
 * 文件角色映射以内置适配注册表（[AsrModelRegistry]）为准；
 * 索引未加载时回退到注册表清单。
 *
 * 选择规则：只在已下载（模型文件齐全）的模型里生效。用户选择记录在偏好里，
 * 但所选模型不存在或已被删除时自动改用其它已安装模型；未下载任何模型时
 * 没有生效模型（不默认指定某个模型）。
 */
class AsrModelManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "asr_model"
        private const val KEY_SELECTED_MODEL = "selected_model"

        /** 词表文件名（与 [com.kingzcheung.xime.service.AsrInferenceService] 的加载判定一致）。 */
        private const val TOKENS_FILE = "tokens.txt"

        /** 把索引里的 ModelInfo 转换为 ASR 专用模型信息（文件映射按 id 查注册表）。 */
        private fun toAsrModelInfo(info: com.kingzcheung.xime.model.ModelInfo): AsrModelInfo {
            val version = info.resolvedVersion()
            val fileNames = info.files.map { it.name }
            val profile = AsrModelRegistry.profileOrDefault(info.id)
            return profile.toAsrModelInfo(
                name = info.name,
                description = info.description,
                // 旧索引版本节点没有 size 字段（空串），回退到模型级 size
                size = version?.size?.takeIf { it.isNotBlank() } ?: info.size,
                downloadUrl = info.archiveUrl ?: profile.downloadUrl,
                files = fileNames
            )
        }

        private fun AsrModelProfile.toAsrModelInfo(
            name: String = this.name,
            description: String = this.description,
            size: String = this.size,
            downloadUrl: String = this.downloadUrl,
            files: List<String> = listOf(encoderFile, decoderFile, joinerFile, tokensFile)
        ): AsrModelInfo = AsrModelInfo(
            id = id,
            name = name,
            description = description,
            language = language,
            size = size,
            downloadUrl = downloadUrl,
            modelType = "transducer",
            files = files,
            encoderFile = encoderFile,
            decoderFile = decoderFile,
            joinerFile = joinerFile
        )

        /** 注册表定义顺序（内置模型优先）；索引新增、注册表未知的模型排在最后。 */
        private fun registryOrder(id: String): Int =
            AsrModelRegistry.profiles.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: Int.MAX_VALUE

        /**
         * 选出生效模型：用户选择且已安装的优先；否则取已安装模型中注册表顺序
         * 最靠前者（同一份文件在 :asr 进程判断结果一致）；无已安装模型时返回 null。
         */
        internal fun pickEffectiveModel(
            models: List<AsrModelInfo>,
            selectedId: String?,
            isInstalled: (String) -> Boolean
        ): AsrModelInfo? {
            if (selectedId != null) {
                models.find { it.id == selectedId && isInstalled(it.id) }?.let { return it }
            }
            return models.filter { isInstalled(it.id) }.minByOrNull { registryOrder(it.id) }
        }
    }

    data class AsrModelInfo(
        val id: String,
        val name: String,
        val description: String = "",
        val language: String,
        val size: String,
        val downloadUrl: String,
        val modelType: String = "transducer",
        val files: List<String>,
        val encoderFile: String = "",
        val decoderFile: String = "",
        val joinerFile: String = "",
        val needsAutoPunctuation: Boolean = true
    )

    /**
     * ASR 分类的模型清单：索引条目 + 未被索引覆盖的内置适配
     * （后者保证索引上架前选中内置模型也能解析出正确的文件映射）。
     */
    fun getAsrModels(): List<AsrModelInfo> {
        val fromIndex = ModelManager.getModelsByCategory(ModelCategory.ASR)
            .map { toAsrModelInfo(it) }
        if (fromIndex.isEmpty()) return AsrModelRegistry.profiles.map { it.toAsrModelInfo() }
        val indexedIds = fromIndex.map { it.id }.toSet()
        val builtInOnly = AsrModelRegistry.profiles
            .filter { it.id !in indexedIds }
            .map { it.toAsrModelInfo() }
        return fromIndex + builtInOnly
    }

    /** 所有 ASR 模型 id，用于判断某个 id 是否为已知 ASR 模型。 */
    fun getAsrModelIds(): Set<String> = getAsrModels().map { it.id }.toSet()

    /** 生效模型是否可用（模型文件齐全）。 */
    fun isModelReady(): Boolean = getSelectedModelInfo() != null

    /**
     * 按 id 解析模型信息：索引/注册表优先，索引未收录的内置/未知 id 按注册表布局兜底。
     * 加载端（:asr 进程）以模型目录名调用，避免跨进程读偏好拿到尚未同步的选择。
     */
    fun getModelInfo(modelId: String): AsrModelInfo =
        getAsrModels().find { it.id == modelId }
            ?: AsrModelRegistry.profileOrDefault(modelId).toAsrModelInfo()

    /**
     * 指定模型的模型文件是否齐全。归档解包后文件可能位于模型目录的子目录，
     * 因此用递归查找；与加载时的文件判定保持一致。
     */
    fun isModelInstalled(modelId: String): Boolean {
        ModelStorage.migrateLegacyForModel(context, modelId)
        val dir = ModelStorage.getModelDir(context, modelId)
        if (!dir.isDirectory) return false
        val info = getModelInfo(modelId)
        return listOf(info.encoderFile, info.decoderFile, info.joinerFile, TOKENS_FILE)
            .all { name -> findFile(dir, name)?.let { it.exists() && it.length() > 0 } == true }
    }

    /** 生效模型目录；未下载任何模型时为 null。 */
    fun getSelectedModelDir(): File? {
        val info = getSelectedModelInfo() ?: return null
        // 兼容旧版：自动迁移 asr_models/<id>/ 下的模型文件
        ModelStorage.migrateLegacyForModel(context, info.id)
        return ModelStorage.getModelDir(context, info.id)
    }

    /** 用户显式选择的模型 id；从未选择过时为 null（不默认指定模型）。 */
    fun getSelectedModelId(): String? {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getString(KEY_SELECTED_MODEL, null)?.takeIf { it.isNotBlank() }
    }

    /** 当前生效模型（用户选择优先，否则回退到已安装模型）；未下载任何模型时为 null。 */
    fun getSelectedModelInfo(): AsrModelInfo? {
        val models = getAsrModels()
        val selectedId = getSelectedModelId()
        pickEffectiveModel(models, selectedId, ::isModelInstalled)?.let { return it }
        // 索引未加载（如 :asr 进程）时所选模型不在清单内，但文件已就绪：按注册表布局兜底
        if (selectedId != null && isModelInstalled(selectedId)) {
            return models.find { it.id == selectedId }
                ?: AsrModelRegistry.profileOrDefault(selectedId).toAsrModelInfo()
        }
        return null
    }

    fun setModel(modelId: String) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(KEY_SELECTED_MODEL, modelId).apply()
    }

    fun findFile(dir: File, fileName: String): File? {
        val direct = File(dir, fileName)
        if (direct.exists()) return direct
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findFile(child, fileName)
                if (found != null) return found
            }
        }
        return null
    }
}
