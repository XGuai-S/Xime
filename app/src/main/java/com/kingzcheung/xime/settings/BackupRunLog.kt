package com.kingzcheung.xime.settings

import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackupLogLevel { INFO, SUCCESS, WARNING, ERROR }
enum class BackupRunTarget { LOCAL, CLOUD }
enum class BackupRunStage { REFRESHING_DICTIONARY, PACKING, SAVING_LOCAL, UPLOADING, CLEANING_REMOTE }
enum class BackupRunOutcome { SUCCESS, PACKING_FAILED, SAVING_FAILED, UPLOAD_FAILED, FAILED, CANCELLED }
enum class BackupArchiveCategory(val label: String) {
    RIME_CONFIGURATION("输入法配置"),
    SCHEME_AND_DICTIONARY("方案、词库及资源"),
    USER_DICTIONARY("自造词快照"),
    USER_DATA("用户排序与词频数据"),
    PLUGINS("插件包与注册信息"),
    SETTINGS("应用及插件设置"),
    CLIPBOARD_RESOURCES("剪贴板图片资源"),
    BACKUP_METADATA("备份清单"),
    OTHER("其他备份文件"),
}

data class BackupRunEntry(val timestamp: Long, val message: String, val level: BackupLogLevel)
data class BackupRunState(
    val runId: Long = 0,
    val running: Boolean = false,
    val entries: List<BackupRunEntry> = emptyList(),
    val target: BackupRunTarget? = null,
    val counts: Map<BackupArchiveCategory, Int> = emptyMap(),
    val archiveBytes: Long = 0,
    val outcome: BackupRunOutcome? = null,
)

/** 仅接收类型化阶段与数字；不接收文件名、正文、异常或远端响应。 */
object BackupRunLog {
    private val store = BackupRunLogStore()
    val state: StateFlow<BackupRunState> = store.state
    fun begin(target: BackupRunTarget): Long = store.begin(target)
    fun stage(runId: Long, stage: BackupRunStage) = store.stage(runId, stage)
    fun entryPacked(runId: Long, category: BackupArchiveCategory) = store.entryPacked(runId, category)
    fun clipboardSnapshot(runId: Long, texts: Int, images: Int, quickSend: Int) =
        store.clipboardSnapshot(runId, texts, images, quickSend)
    fun skippedClipboard(runId: Long) = store.skippedClipboard(runId)
    fun archiveReady(runId: Long, bytes: Long) = store.archiveReady(runId, bytes)
    fun dictionarySnapshotUnavailable(runId: Long) = store.dictionarySnapshotUnavailable(runId)
    fun remoteCleanupFailed(runId: Long) = store.remoteCleanupFailed(runId)
    fun finish(runId: Long, outcome: BackupRunOutcome) = store.finish(runId, outcome)
}

/** 所有日志更新均为 best effort，连时钟/观察器异常也不能改变实际备份结果。 */
internal class BackupRunLogStore(private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_ENTRIES = 80
        private const val BYTES_PER_MB = 1024.0 * 1024.0
        fun formatArchiveMegabytes(bytes: Long): String =
            String.format(Locale.ROOT, "%.2f MB", bytes / BYTES_PER_MB)
    }
    private val lock = Any()
    private var nextRunId = 0L
    private val mutableState = MutableStateFlow(BackupRunState())
    val state: StateFlow<BackupRunState> = mutableState.asStateFlow()

    fun begin(target: BackupRunTarget): Long = runCatching {
        synchronized(lock) {
            val id = ++nextRunId
            val message = if (target == BackupRunTarget.CLOUD) "开始完整云备份" else "开始完整本地备份"
            mutableState.value = BackupRunState(id, true, listOf(BackupRunEntry(clock(), message, BackupLogLevel.INFO)), target)
            id
        }
    }.getOrDefault(0L)

    private fun update(runId: Long, change: (BackupRunState) -> BackupRunState) {
        runCatching {
            synchronized(lock) {
                val current = mutableState.value
                if (runId != 0L && current.runId == runId && current.running) mutableState.value = change(current)
            }
        }
    }

    private fun BackupRunState.append(message: String, level: BackupLogLevel = BackupLogLevel.INFO): BackupRunState =
        copy(entries = (entries + BackupRunEntry(clock(), message, level)).takeLast(MAX_ENTRIES))

    fun stage(runId: Long, stage: BackupRunStage) = update(runId) { state ->
        state.append(when (stage) {
            BackupRunStage.REFRESHING_DICTIONARY -> "正在刷新自造词快照"
            BackupRunStage.PACKING -> "正在打包完整备份"
            BackupRunStage.SAVING_LOCAL -> "正在保存本地备份"
            BackupRunStage.UPLOADING -> "正在上传完整备份"
            BackupRunStage.CLEANING_REMOTE -> "正在整理远端历史备份"
        })
    }

    fun entryPacked(runId: Long, category: BackupArchiveCategory) = update(runId) { state ->
        val count = (state.counts[category] ?: 0) + 1
        val next = state.copy(counts = state.counts + (category to count))
        if (count == 1) next.append("正在打包：${category.label}") else next
    }

    fun clipboardSnapshot(runId: Long, texts: Int, images: Int, quickSend: Int) = update(runId) { state ->
        if (texts < 0 || images < 0 || quickSend < 0) state else
            state.append("剪贴板快照：普通文本 $texts 条，图片 $images 条，快捷发送 $quickSend 条")
    }

    fun skippedClipboard(runId: Long) = update(runId) { state ->
        state.append(ClipboardCloudPolicy.backupSkipMessage(false) ?: "剪贴板备份已关闭", BackupLogLevel.INFO)
    }

    fun archiveReady(runId: Long, bytes: Long) = update(runId) { state ->
        if (bytes < 0) state else {
            var next = state.copy(archiveBytes = bytes)
            state.counts.forEach { (category, count) -> next = next.append("已打包：${category.label} $count 个文件") }
            next.append("完整备份打包完成：${formatArchiveMegabytes(bytes)}", BackupLogLevel.SUCCESS)
        }
    }

    fun dictionarySnapshotUnavailable(runId: Long) = update(runId) {
        it.append("自造词快照未刷新，将使用已有快照", BackupLogLevel.WARNING)
    }
    fun remoteCleanupFailed(runId: Long) = update(runId) {
        it.append("远端历史备份整理未完成，本次上传已完成", BackupLogLevel.WARNING)
    }
    fun finish(runId: Long, outcome: BackupRunOutcome) = update(runId) { state ->
        val (message, level) = when (outcome) {
            BackupRunOutcome.SUCCESS -> "完整备份成功" to BackupLogLevel.SUCCESS
            BackupRunOutcome.PACKING_FAILED -> "完整备份失败：打包未完成" to BackupLogLevel.ERROR
            BackupRunOutcome.SAVING_FAILED -> "完整备份失败：本地保存未完成" to BackupLogLevel.ERROR
            BackupRunOutcome.UPLOAD_FAILED -> "完整备份失败：上传未完成" to BackupLogLevel.ERROR
            BackupRunOutcome.FAILED -> "完整备份流程未完成" to BackupLogLevel.ERROR
            BackupRunOutcome.CANCELLED -> "完整备份流程已取消，已完成的步骤不会撤销" to BackupLogLevel.WARNING
        }
        state.append(message, level).copy(running = false, outcome = outcome)
    }
}

/** 只统计业务显式关闭成功的条目，隐式收尾不算复制完成；回调不接收原始名字。 */
internal class BackupArchiveOutputStream(
    output: OutputStream,
    private val checkActive: () -> Unit = {},
    private val record: (BackupArchiveCategory) -> Unit,
) :
    ZipOutputStream(output) {
    private var activeCategory: BackupArchiveCategory? = null
    private var suppressRecording = false
    override fun putNextEntry(entry: ZipEntry) {
        checkActive()
        val previous = suppressRecording
        suppressRecording = true
        try {
            super.putNextEntry(entry)
        } finally {
            suppressRecording = previous
        }
        activeCategory = if (entry.isDirectory) null else runCatching { categoryOf(entry.name) }.getOrNull()
    }
    override fun finish() {
        val previous = suppressRecording
        suppressRecording = true
        try {
            super.finish()
        } finally {
            suppressRecording = previous
        }
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkActive()
        super.write(bytes, offset, length)
    }
    override fun closeEntry() {
        val category = activeCategory
        super.closeEntry()
        activeCategory = null
        if (!suppressRecording && category != null) runCatching { record(category) }
    }
    companion object {
        internal fun categoryOf(entryName: String): BackupArchiveCategory {
            val name = entryName.replace('\\', '/').lowercase()
            return when {
                name.startsWith("_xime_backup/clipboard/images/") -> BackupArchiveCategory.CLIPBOARD_RESOURCES
                name.startsWith("_xime_backup/clipboard/") -> BackupArchiveCategory.BACKUP_METADATA
                name == "_xime_backup/settings.json" || name.startsWith("_xime_backup/plugin_configs/") -> BackupArchiveCategory.SETTINGS
                name.startsWith("_xime_backup/plugins/") || name in setOf("_xime_backup/plugins.json", "_xime_backup/plugins.xml") -> BackupArchiveCategory.PLUGINS
                name.startsWith("_xime_backup/schemas/") -> BackupArchiveCategory.SCHEME_AND_DICTIONARY
                name.startsWith("_xime_backup/") -> BackupArchiveCategory.BACKUP_METADATA
                name.startsWith("sync/") && name.endsWith(".userdb.txt") -> BackupArchiveCategory.USER_DICTIONARY
                name.endsWith(".schema.yaml") || name.endsWith(".dict.yaml") ||
                    listOf("opencc/", "lua/", "fonts/", "themes/").any { name.startsWith(it) } -> BackupArchiveCategory.SCHEME_AND_DICTIONARY
                name in setOf("candidate_order.txt", "auto_word_temp.txt", "auto_word.txt", "word_freq.txt", "word_blacklist.txt") -> BackupArchiveCategory.USER_DATA
                name.endsWith(".yaml") || name in setOf("custom_phrase.txt", "symbols.yaml") -> BackupArchiveCategory.RIME_CONFIGURATION
                else -> BackupArchiveCategory.OTHER
            }
        }
    }
}
