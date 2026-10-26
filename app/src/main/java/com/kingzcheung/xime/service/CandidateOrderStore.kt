package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.UserConfigDir
import java.io.File

/**
 * 候选词手动排序的本地词频表。
 *
 * ## 为什么需要它
 *
 * 引擎的候选顺序是固定的（词库频次 + 用户词典学习），但同一个编码下「谁是第一候选」
 * 因人而异：打 `ni` 的人可能永远只想要「你」，另一个人要的是「尼」。用户手动拖动一次
 * 就能把这件事一次性说清，不必每回都往下翻。
 *
 * ## 表结构
 *
 * 键是**编码**（如 `ni`、`zhongguo`），值是「词 → 权重」。全部编码共用同一个文件，
 * 按编码分键：没排过序的编码在这个表里查不到，也就完全不受影响。
 *
 * 用户拖完一次会把该编码下**可见候选的全序**写进来（位置越靠前权重越大），
 * 于是下次同样的编码出候选时，按权重降序即可复原用户排定的顺序。
 *
 * ## 存放位置
 *
 * 词频表写在用户可见配置目录（`/storage/emulated/0/rime/xime/`，与 `xime.yaml` 同级），
 * 用户在文件管理器 / 电脑 USB 里可直接查看、备份。可见目录不可用（未授权等）时
 * 回退应用私有目录，功能不丢。
 *
 * ## 与引擎的关系
 *
 * 只改**显示顺序**，不动引擎候选本体。重排后每个显示位置都附带一条
 * [CandidateAction.engine] 指回它原本的引擎下标，点选因此仍然落在正确的词上
 * （见 [ImeKeyRouter.selectCandidateAsync] 对 pendingAction.engineIndex 的处理）。
 *
 * ## 手改合并
 *
 * 运行期内存表是唯一真源，但保存前会先做外部修改合并：用户刚在配置目录里手改过文件时，
 * 手改内容合回内存表（新增/修改保留、删除也尊重，见 [UserDataMerge]），不会被下一次
 * 保存冲掉。
 */
internal object CandidateOrderStore {

    /** 词频表文件名（可见配置目录与 xime.yaml 同级；回退时也用它。备份引擎按此名打包/恢复）。 */
    internal const val FILE_NAME = "candidate_order.txt"

    /** 表头注释行；解析时以 `#` 开头一律跳过。 */
    private const val HEADER = "# 候选排序词频：词\t编码\t权重（用户手动拖动候选后自动生成）"

    // ── 纯逻辑（无 IO，直接单测） ──

    /**
     * 解析词频表。格式为每行 `词\t编码\t权重`；`#` 开头与空行跳过。
     *
     * 容错取向：任何一行格式不对只丢那一行，不让整张表失效——用户词典文件被
     * 手工编辑过、或跨版本残留时，宁可少几条排序也不能连候选都出不来。
     * 同一 (编码, 词) 重复出现时后者覆盖前者。
     */
    fun parse(text: String): Map<String, Map<String, Int>> {
        val table = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val word = parts[0].trim()
            val code = parts[1].trim()
            val weight = parts[2].trim().toIntOrNull() ?: continue
            if (code.isEmpty() || word.isEmpty()) continue
            table.getOrPut(code) { LinkedHashMap() }[word] = weight
        }
        return table
    }

    /** 序列化为文本（稳定顺序：按编码首次出现顺序，编码内按权重降序；每行 `词\t编码\t权重`）。 */
    fun serialize(table: Map<String, Map<String, Int>>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for ((code, words) in table) {
            for ((word, weight) in words.entries.sortedByDescending { it.value }) {
                sb.append(word).append('\t').append(code).append('\t').append(weight).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * 把一次拖动后的**全序**换算成权重：位置越靠前权重越大。
     *
     * 用等距递减整数（而非 0/1 排序号）是为了留出手工调整的余地，
     * 也让「新词插入」有一个明确的落点：不在表里的词权重视为最低。
     */
    fun fullOrderWeights(ordered: List<String>): Map<String, Int> {
        val weights = LinkedHashMap<String, Int>()
        val top = ordered.size * 10
        for ((index, word) in ordered.withIndex()) {
            if (word.isEmpty()) continue
            weights[word] = top - index * 10
        }
        return weights
    }

    /** 重排结果：候选、注释与上屏动作三者平行。 */
    data class Reordered(
        val candidates: List<String>,
        val comments: List<String>,
        val actions: List<CandidateAction>,
    )

    /**
     * 按词频表重排显示候选；该编码没有排序记录、或顺序本就无需变化时返回 null
     * （null = 不干预，调用方原样渲染）。
     *
     * 排序规则：有记录的词按权重降序在前；没记录的词（新词）权重视为最低并
     * **保持引擎原有相对顺序**排在其后。整体稳定——这一步只应影响用户明确排过的词。
     *
     * [candidates] 的下标与 [comments] 一一对应，且必须等于引擎候选下标
     * （调用方保证：仅在非 ascii、非 T9、且无插件变换的纯引擎路径下调用）。
     */
    fun reorder(
        code: String,
        candidates: List<String>,
        comments: List<String>,
        table: Map<String, Map<String, Int>>,
    ): Reordered? {
        if (candidates.isEmpty() || code.isEmpty()) return null
        val frequency = table[code] ?: return null
        if (frequency.isEmpty()) return null

        val order = candidates.withIndex().sortedWith(
            compareByDescending<IndexedValue<String>> { frequency[it.value] ?: Int.MIN_VALUE }
                .thenBy { it.index }
        )
        if (order.map { it.index } == candidates.indices.toList()) return null

        return Reordered(
            candidates = order.map { it.value },
            comments = order.map { comments.getOrElse(it.index) { "" } },
            // 显示位置变了，点上屏必须回到原本的引擎下标
            actions = order.map { CandidateAction.engine(it.index) },
        )
    }

    /**
     * 把一次拖动结果并入表：该编码的词条**整体替换**为新的全序，其他编码不动。
     */
    fun withFullOrder(
        table: Map<String, Map<String, Int>>,
        code: String,
        ordered: List<String>,
    ): Map<String, Map<String, Int>> {
        if (code.isEmpty() || ordered.isEmpty()) return table
        val weights = fullOrderWeights(ordered)
        val next = LinkedHashMap(table)
        next[code] = weights
        return next
    }

    // ── 文件 IO ──

    /**
     * 外部修改检测基线：上次 load 读到的可见文件文本 / 上次 save 写出的文本。
     * null = 本进程还没读过也没写过（无从判断外部修改，保存时不合并直接写）。
     */
    private var baselineText: String? = null

    /**
     * 回退用的私有目录文件（可见目录不可用/写入失败时）。
     *
     * 读取时也会回退到这里：老版本把词频表写在私有目录，升级后应能读到旧数据。
     */
    private fun privateFile(context: android.content.Context): File =
        File(context.filesDir, FILE_NAME)

    /**
     * 两处候选文本中取较新者。
     *
     * 返回 null 表示两处都没有可读内容。
     *
     * 这是「回退遮蔽」修复的核心：`save` 在可见目录不可用时把新表写进私有目录，
     * 但可见目录里可能残留旧版文件；若 `load` 无条件优先可见目录，旧文件就会
     * 永远遮蔽私有目录里的新表（旧 v1 读到、新 v2 静默丢失）。
     * 这里按修改时间（秒）比较，晚写入的一侧胜出；时间相同取可见侧（正常路径已
     * 迁移至可见目录，相同时间戳下可见侧应为同一内容）。
     */
    internal fun newerOf(
        visibleStamp: Pair<Long, Long>?,
        visibleText: String?,
        privateStamp: Long?,
        privateText: String?,
    ): String? {
        if (visibleText != null && privateText != null) {
            val visibleTime = visibleStamp?.first ?: 0L
            val privateTime = (privateStamp ?: 0L) / 1000L
            return if (visibleTime >= privateTime) visibleText else privateText
        }
        return visibleText ?: privateText
    }

    /** 读取词频表；文件不存在或读失败时返回空表（排序功能退化为不干预）。 */
    @Synchronized
    fun load(context: android.content.Context): Map<String, Map<String, Int>> {
        // 优先可见配置目录（与 xime.yaml 同级，用户可直接查看/备份），
        // 与私有目录都存在时按修改时间取较新者，见 [newerOf]。
        val visibleText = UserConfigDir.readText(context, FILE_NAME)
        val visibleStamp = UserConfigDir.stamp(context, FILE_NAME)
        val priv = privateFile(context)
        val privateText = if (priv.exists()) runCatching { priv.readText() }.getOrNull() else null
        val privateStamp = if (priv.exists()) priv.lastModified() else null
        // 基线锚定**可见文件**的文本（保存前比对的就是它），与内存取自哪一侧无关
        baselineText = visibleText ?: ""
        val text = newerOf(visibleStamp, visibleText, privateStamp, privateText) ?: return emptyMap()
        return runCatching { parse(text) }.getOrElse { emptyMap() }
    }

    /**
     * 写入词频表，返回**实际写入的表**（调用方必须采纳）。
     *
     * 保存前先做外部修改合并：用户刚在配置目录里手改过文件时，手改内容合进返回表
     * （新增/修改/删除都尊重，见 [UserDataMerge]）——不采纳的话下次保存又会冲掉。
     * 表为空且没被外部改过时删除文件，不留表头空壳。
     */
    @Synchronized
    fun save(
        context: android.content.Context,
        table: Map<String, Map<String, Int>>,
    ): Map<String, Map<String, Int>> =
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

    /**
     * 合并外部手改（不写盘）：返回合并后的表，调用方必须采纳。
     *
     * 内存表随即包含文件里的全部有效条目，下次保存自然落盘；基线记为当前文件内容，
     * 之后保存不会把用户的删除写回冲掉。文件读不到或没被改过 → 原样返回。
     */
    @Synchronized
    fun reconcile(
        context: android.content.Context,
        table: Map<String, Map<String, Int>>,
    ): Map<String, Map<String, Int>> {
        val current = UserConfigDir.readText(context, FILE_NAME) ?: return table
        if (!UserDataMerge.externalChanged(baselineText, current)) return table
        val merged = UserDataMerge.reconcileTable(baselineText, current, table, ::parse)
        baselineText = current
        return merged
    }
}
