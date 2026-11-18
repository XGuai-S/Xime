package com.kingzcheung.xime.plugin

import com.kingzcheung.xime.plugin.core.security.ErrorCategory
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 插件错误日志的 JSONL 落盘实现。
 *
 * - 文件路径由构造方指定（宿主传 filesDir/logs/plugins/errors.jsonl）。
 * - 每行一个 JSON 对象（kotlinx-serialization-json，无外部依赖）；stackTrace 含换行由 JSON 转义保证单行。
 * - 上限 [MAX_TOTAL_LINES] 行 / [MAX_FILE_BYTES] 字节，超限时从最旧行截断（错误频率低，重写开销可忽略）。
 * - 调用方（PluginErrorLog 的 storeExecutor）串行访问，本类无需内部加锁。
 */
class FilePluginErrorStore(private val errorFile: File) : PluginErrorLog.PluginErrorStore {

    companion object {
        private const val TAG = "FilePluginErrorStore"
        private const val MAX_TOTAL_LINES = 300
        private const val MAX_FILE_BYTES = 512 * 1024

        /** 累计追加多少次做一次压缩重写（控制写放大与文件膨胀的折中）。 */
        private const val COMPACT_EVERY = 30

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /** 单条错误 → JSON 行；无法编码时返回 null（调用方丢弃该条）。 */
        internal fun toLine(error: PluginErrorLog.PluginError): String? {
            return try {
                buildJsonObject {
                    put("t", error.timestamp)
                    put("p", error.pluginId)
                    put("o", error.operation)
                    put("m", error.message)
                    error.stackTrace?.let { put("s", it) }
                    put("c", error.category.name)
                }.toString()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "encode error line failed", e)
                null
            }
        }

        /** JSON 行 → 错误；坏行返回 null（跳过，不中断恢复）。 */
        internal fun fromLine(line: String): PluginErrorLog.PluginError? {
            return try {
                val v = json.parseToJsonElement(line) as? JsonObject ?: return null
                fun str(key: String): String? = (v[key] as? JsonPrimitive)?.contentOrNull
                PluginErrorLog.PluginError(
                    timestamp = (v["t"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis(),
                    pluginId = str("p") ?: return null,
                    operation = str("o").orEmpty(),
                    message = str("m").orEmpty(),
                    stackTrace = str("s"),
                    category = runCatching {
                        ErrorCategory.valueOf(str("c").orEmpty())
                    }.getOrDefault(ErrorCategory.OTHER)
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "skip malformed error line: ${line.take(120)}")
                null
            }
        }
    }

    private fun readAllLines(): List<String> {
        if (!errorFile.exists()) return emptyList()
        return try {
            errorFile.readLines()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "read error file failed", e)
            emptyList()
        }
    }

    private fun writeLines(lines: List<String>) {
        try {
            errorFile.parentFile?.mkdirs()
            errorFile.writeText(lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "write error file failed", e)
        }
    }

    override fun load(): List<PluginErrorLog.PluginError> {
        val lines = readAllLines()
        cachedLineCount = lines.size
        return lines.mapNotNull { fromLine(it) }
    }

    /** 距上次压缩的追加次数（压缩会做一次全量重写，不能每条都做）。 */
    private var appendsSinceCompact = 0

    /** 当前文件行数缓存（-1 = 未知，需要读一次）。压缩/清空后会同步更新。 */
    @Volatile
    private var cachedLineCount = -1

    /**
     * 追加一条错误。
     *
     * **只 append 一行**，不再「读全文件 → 拼一行 → 全量重写」。
     * 旧实现每条错误都要 readAllLines() + writeText()，最坏是 512KB 读 + 512KB 写；
     * 插件错误风暴（例如断网时 WebDAV 同步每次重试都失败）会变成持续的闪存写放大，
     * 而且这段都在 storeExecutor 单线程上跑，队列越积越长。
     *
     * 上限仍由 [compact] 保证：文件超字节上限、行数达 [MAX_TOTAL_LINES]、
     * 或累计追加达 [COMPACT_EVERY] 次时做一次压缩重写。
     */
    override fun append(pluginId: String, error: PluginErrorLog.PluginError) {
        val line = toLine(error) ?: return
        val ok = try {
            errorFile.parentFile?.mkdirs()
            errorFile.appendText(line + "\n")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "append error file failed", e)
            false
        }
        if (!ok) return
        appendsSinceCompact++
        val count = if (cachedLineCount < 0) readAllLines().size else cachedLineCount + 1
        cachedLineCount = count
        if (errorFile.length() > MAX_FILE_BYTES ||
            count >= MAX_TOTAL_LINES ||
            appendsSinceCompact >= COMPACT_EVERY
        ) {
            appendsSinceCompact = 0
            compact()
        }
    }

    /** 压缩重写：保留最后 [MAX_TOTAL_LINES] 行，并按 [MAX_FILE_BYTES] 继续丢最旧行。 */
    private fun compact() {
        val lines = readAllLines()
        var kept = if (lines.size > MAX_TOTAL_LINES) lines.takeLast(MAX_TOTAL_LINES) else lines
        while (kept.size > 1 && kept.sumOf { it.length + 1 } > MAX_FILE_BYTES) {
            kept = kept.drop(1)
        }
        writeLines(kept)
        cachedLineCount = kept.size
    }

    override fun clearEverything() {
        writeLines(emptyList())
        cachedLineCount = 0
        appendsSinceCompact = 0
    }

    override fun clearAll(pluginId: String) {
        val kept = readAllLines().filter { line ->
            fromLine(line)?.pluginId != pluginId
        }
        writeLines(kept)
        cachedLineCount = kept.size
        appendsSinceCompact = 0
    }

    init {
        android.util.Log.i(TAG, "Plugin error file: ${errorFile.absolutePath}")
    }
}