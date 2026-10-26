package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.UserConfigDir
import java.io.File

/**
 * 黑名单词库：长按候选「删除」的词记在这里（带删除时的编码），之后不再出现在候选栏。
 *
 * ## 为什么单独一份、为什么带编码
 *
 * 长按删除走 librime 的 tombstone（`delete_candidate_on_current_page`），只对用户词典生效——
 * 方案词典里的词删不掉，自动造词的临时/固定词库也不受它管。这里在**显示层**统一屏蔽。
 *
 * 记录**词 + 删除时的编码**：同一词在多个编码下都可能出现（造词一词多码、引擎词与造词
 * 撞码），按「编码 → 词集合」粒度屏蔽，删掉某编码下的词不影响其它编码下的同一词。
 *
 * ## 存放位置与格式
 *
 * 与 `xime.yaml` 同级（`/storage/emulated/0/rime/xime/word_blacklist.txt`），
 * 每行 `词\t编码`（编码为空表示按词全局屏蔽，兼容旧数据）；可见目录不可用时回退私有目录。
 *
 * ## 手改合并
 *
 * 运行期内存表是唯一真源，但保存前会先做外部修改合并：用户手改内容合回内存表
 * （加词/删词都尊重，见 [UserDataMerge]），不会被下一次保存冲掉。
 */
internal object WordBlacklistStore {
    /** 黑名单文件名（与 xime.yaml 同级）。 */
    internal const val FILE_NAME = "word_blacklist.txt"
    private const val HEADER = "# 黑名单词库：长按候选删除的词（一行一词+编码，`词\t编码`），这些词不再出现在候选栏"

    // ── 纯逻辑（无 IO，直接单测） ──
    /** 解析黑名单：`词\t编码` 一行一条，`#` 开头与空行跳过；兼容旧格式「一行一词」（无编码归空）。 */
    fun parse(text: String): Map<String, Map<String, Int>> {
        val table = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            val word = parts[0].trim()
            if (word.isEmpty()) continue
            val code = if (parts.size >= 2) parts[1].trim() else ""
            table.getOrPut(code) { LinkedHashMap() }[word] = 1
        }
        return table
    }
    /** 序列化（按编码加入顺序，编码内按词加入顺序，稳定输出）。 */
    fun serialize(table: Map<String, Map<String, Int>>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for ((code, words) in table) {
            for (word in words.keys) {
                if (code.isEmpty()) sb.append(word).append('\n')
                else sb.append(word).append('\t').append(code).append('\n')
            }
        }
        return sb.toString()
    }
    /**
     * 加入一个 (编码, 词) 屏蔽条目。
     * [code] 为空表示按词全局屏蔽（兼容旧删除）；已存在时返回原表。
     */
    fun withBlocked(
        table: Map<String, Map<String, Int>>,
        word: String,
        code: String = "",
    ): Map<String, Map<String, Int>> {
        if (word.isEmpty()) return table
        val codeWords = table[code]
        if (codeWords?.containsKey(word) == true) return table
        val next = LinkedHashMap(table)
        val nextWords = LinkedHashMap(codeWords ?: emptyMap())
        nextWords[word] = 1
        next[code] = nextWords
        return next
    }
    /** 编码对应的词集合（空编码条目 = 全局屏蔽，任何编码都生效）。 */
    fun blockedFor(table: Map<String, Map<String, Int>>, code: String): Set<String> {
        if (code.isEmpty()) return table.values.flatMap { it.keys }.toSet()
        return (table[code]?.keys ?: emptySet()) + (table[""]?.keys ?: emptySet())
    }
    /**
     * 过滤候选：按当前编码屏蔽黑名单词，候选/注释/动作三者同步（下标必须保持对齐）。
     * 无需过滤时返回 null（null = 不干预，调用方沿用原三元组）。
     */
    fun filter(
        candidates: List<String>,
        comments: List<String>,
        actions: List<CandidateAction>,
        table: Map<String, Map<String, Int>>,
        code: String,
    ): CandidateOrderStore.Reordered? {
        if (candidates.isEmpty() || table.isEmpty()) return null
        val blocked = blockedFor(table, code)
        if (blocked.isEmpty()) return null
        val kept = candidates.indices.filter { candidates[it] !in blocked }
        if (kept.size == candidates.size) return null
        return CandidateOrderStore.Reordered(
            candidates = kept.map { candidates[it] },
            comments = kept.map { comments.getOrElse(it) { "" } },
            actions = kept.map { actions.getOrElse(it) { CandidateAction.engine(it) } },
        )
    }

    // ── IO ──
    /** 外部修改检测基线：上次 load 读到的可见文件文本 / 上次 save 写出的文本。 */
    private var baselineText: String? = null
    @Synchronized
    fun load(context: android.content.Context): Map<String, Map<String, Int>> {
        val visibleText = UserConfigDir.readText(context, FILE_NAME)
        val visibleStamp = UserConfigDir.stamp(context, FILE_NAME)
        val priv = privateFile(context)
        val privateText = if (priv.exists()) runCatching { priv.readText() }.getOrNull() else null
        val privateStamp = if (priv.exists()) priv.lastModified() else null
        baselineText = visibleText ?: ""
        val text = CandidateOrderStore.newerOf(visibleStamp, visibleText, privateStamp, privateText)
            ?: return emptyMap()
        return runCatching { parse(text) }.getOrDefault(emptyMap())
    }
    /** 写入黑名单，返回**实际写入的表**（调用方必须采纳）。 */
    @Synchronized
    fun save(context: android.content.Context, table: Map<String, Map<String, Int>>): Map<String, Map<String, Int>> =
        UserDataMerge.saveUserTable(
            context = context,
            name = FILE_NAME,
            privateFile = privateFile(context),
            baselineText = baselineText,
            memory = table,
            parse = ::parse,
            serialize = ::serialize,
            onBaseline = { baselineText = it },
        )
    /** 合并外部手改（不写盘）：返回合并后的表，调用方必须采纳。 */
    @Synchronized
    fun reconcile(context: android.content.Context, table: Map<String, Map<String, Int>>): Map<String, Map<String, Int>> =
        UserDataMerge.reconcileTable(
            baselineText,
            UserConfigDir.readText(context, FILE_NAME),
            table,
            ::parse,
        )
    /** 可见目录不可用时回退应用私有目录。 */
    private fun privateFile(context: android.content.Context): File =
        File(context.filesDir, FILE_NAME)
}
