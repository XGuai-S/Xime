package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.UserConfigDir
import java.io.File

/**
 * 黑名单词库：长按候选「删除」的词记在这里，之后不再出现在候选栏。
 *
 * ## 为什么要单独一份
 *
 * 长按删除走的是 librime 的 tombstone（`delete_candidate_on_current_page`），只对用户词典
 * 生效——方案词典里的词删不掉，自动造词的临时/固定词库也不受它管，删除后照样会被合并进
 * 候选栏。这里在**显示层**统一屏蔽：不管词来自引擎、方案词典还是自造词库，只要在黑名单
 * 里就不进候选。
 *
 * ## 存放位置
 *
 * 与 `xime.yaml` 同级（`/storage/emulated/0/rime/xime/`），用户可直接查看/编辑；
 * 可见目录不可用时回退应用私有目录。
 *
 * ## 手改合并
 *
 * 运行期内存集合是唯一真源，但保存前会先做外部修改合并：用户刚在配置目录里手改过
 * 文件时，手改内容合回内存（加词/删词都尊重，见 [UserDataMerge]），不会被下一次
 * 保存冲掉。
 */
internal object WordBlacklistStore {

    /** 黑名单文件名（与 xime.yaml 同级）。 */
    internal const val FILE_NAME = "word_blacklist.txt"

    private const val HEADER = "# 黑名单词库：长按候选删除的词（一行一词），这些词不再出现在候选栏"

    /** 黑名单上限：防御异常写入把文件撑爆。 */
    private const val MAX_WORDS = 4096

    // ── 纯逻辑（无 IO，直接单测） ──

    /** 解析黑名单：一行一词，`#` 开头与空行跳过。 */
    fun parse(text: String): Set<String> {
        val words = LinkedHashSet<String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            words += line
        }
        return words
    }

    /** 序列化（按加入顺序，稳定输出）。 */
    fun serialize(words: Set<String>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (word in words) sb.append(word).append('\n')
        return sb.toString()
    }

    /**
     * 加入一个词。已达上限或词已存在时返回原集合（调用方据此判断是否需要写盘）。
     */
    fun withBlocked(words: Set<String>, word: String): Set<String> {
        if (word.isEmpty() || word in words || words.size >= MAX_WORDS) return words
        return LinkedHashSet(words).apply { add(word) }
    }

    /**
     * 过滤候选：黑名单里的词整条剔除，候选/注释/动作三者同步（下标必须保持对齐）。
     *
     * 无需过滤时返回 null（null = 不干预，调用方沿用原三元组）。
     */
    fun filter(
        candidates: List<String>,
        comments: List<String>,
        actions: List<CandidateAction>,
        blocked: Set<String>,
    ): CandidateOrderStore.Reordered? {
        if (candidates.isEmpty() || blocked.isEmpty()) return null
        val kept = candidates.indices.filter { candidates[it] !in blocked }
        if (kept.size == candidates.size) return null
        return CandidateOrderStore.Reordered(
            candidates = kept.map { candidates[it] },
            comments = kept.map { comments.getOrElse(it) { "" } },
            actions = kept.map { actions.getOrElse(it) { CandidateAction.engine(it) } },
        )
    }

    // ── IO ──

    /**
     * 外部修改检测基线：上次 load 读到的可见文件文本 / 上次 save 写出的文本。
     * null = 本进程还没读过也没写过（无从判断外部修改，保存时不合并直接写）。
     */
    private var baselineText: String? = null

    @Synchronized
    fun load(context: android.content.Context): Set<String> {
        val visibleText = UserConfigDir.readText(context, FILE_NAME)
        val visibleStamp = UserConfigDir.stamp(context, FILE_NAME)
        val priv = privateFile(context)
        val privateText = if (priv.exists()) runCatching { priv.readText() }.getOrNull() else null
        val privateStamp = if (priv.exists()) priv.lastModified() else null
        // 基线锚定**可见文件**的文本（保存前比对的就是它），与内存取自哪一侧无关
        baselineText = visibleText ?: ""
        val text = CandidateOrderStore.newerOf(visibleStamp, visibleText, privateStamp, privateText)
            ?: return emptySet()
        return runCatching { parse(text) }.getOrDefault(emptySet())
    }

    /**
     * 写入黑名单，返回**实际写入的集合**（调用方必须采纳）。
     *
     * 保存前先做外部修改合并：用户刚在配置目录里手改过文件时，手改内容合进返回集合
     * （加词/删词都尊重，见 [UserDataMerge]）——不采纳的话下次保存又会冲掉。
     * 集合为空且没被外部改过时删除文件，不留表头空壳。
     */
    @Synchronized
    fun save(context: android.content.Context, words: Set<String>): Set<String> =
        UserDataMerge.saveUserSet(
            context = context,
            name = FILE_NAME,
            privateFile = privateFile(context),
            baselineText = baselineText,
            memory = words,
            parse = ::parse,
            serialize = ::serialize,
            onBaseline = { baselineText = it },
        )

    /**
     * 合并外部手改（不写盘）：返回合并后的集合，调用方必须采纳。
     *
     * 内存集合随即包含文件里的全部词条，下次保存自然落盘；基线记为当前文件内容，
     * 之后保存不会把用户的删词写回冲掉。文件读不到或没被改过 → 原样返回。
     */
    @Synchronized
    fun reconcile(context: android.content.Context, words: Set<String>): Set<String> {
        val current = UserConfigDir.readText(context, FILE_NAME) ?: return words
        if (!UserDataMerge.externalChanged(baselineText, current)) return words
        val merged = UserDataMerge.reconcileSet(baselineText, current, words, ::parse)
        baselineText = current
        return merged
    }

    /** 可见目录不可用时回退应用私有目录。 */
    private fun privateFile(context: android.content.Context): File =
        File(context.filesDir, FILE_NAME)
}
