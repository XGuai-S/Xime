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
/**
 * 候选排序表最多容纳的编码数。
 *
 * 一条编码对应一整组全序权重，4096 条已覆盖重度用户的全部常用码；
 * 超出后不再收录新编码，保证 candidate_order.txt 体积有界（见 withFullOrder）。
 */
private const val MAX_CODES = 4096
object CandidateOrderStore {

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
        // 兼容 dict.yaml 头（3.3.14 yaml 单源）
        for (rawLine in UserDataMerge.stripDictYamlHeader(text).lineSequence()) {
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
        /**
         * [candidates] 中属于临时词库（未晋升的自动造词）的下标集合。
         * 渲染层据此加「◌」前缀区分；空集 = 不标记（黑名单/词频重排等路径不涉及造词）。
         */
        val autoWordTempIndices: Set<Int> = emptySet(),
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
        // 编码数上限：只对**新编码**设卡。
        //
        // 拖动候选排序会为每个编码写一整组全序（不是单条），长期使用后
        // candidate_order.txt 会随「敲过的不同编码」无限膨胀——文件越大启动解析越慢。
        // 已达上限时拒绝新增编码，但**允许更新已有编码**（否则用户就没法再调整
        // 现已排序过的那些词了）。
        if (code !in table && table.size >= MAX_CODES) {
            // 静默丢弃会让用户觉得「拖了又弹回去」，至少留下可诊断的日志。
            android.util.Log.w(
                "CandidateOrderStore",
                "withFullOrder: code='$code' rejected, MAX_CODES=$MAX_CODES reached",
            )
            return table
        }
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
        // 3.3.14「yaml 单源」：优先 dict.yaml（镜像 → 私有 rime），退回历史 txt（迁移期）。
        val mirrorDict = java.io.File(
            com.kingzcheung.xime.settings.RimeUserFileSync.mirrorDir(context), RIME_DICT_FILE_NAME,
        )
        val privateDict = java.io.File(
            com.kingzcheung.xime.settings.SchemaManager.getRimeDir(context), RIME_DICT_FILE_NAME,
        )
        val legacyVisible = UserConfigDir.readText(context, FILE_NAME)
        val legacyStamp = UserConfigDir.stamp(context, FILE_NAME)
        // 读失败要回退到另一份（镜像不可读时私有副本往往还在），且记录实际命中的
        // 文件：时间比较若固定拿 mirrorDict（不存在时 mtime=0）会把历史 txt 判成更新。
        var dictSource: java.io.File? = null
        var dictText: String? = null
        for (candidate in listOf(mirrorDict, privateDict)) {
            if (!candidate.isFile) continue
            val t = runCatching { candidate.readText() }.getOrNull() ?: continue
            dictSource = candidate
            dictText = t
            break
        }
        val text = if (dictText != null) {
            if (legacyVisible != null && legacyStamp != null && dictSource != null &&
                legacyStamp.first > dictSource.lastModified() / 1000L
            ) legacyVisible else dictText
        } else {
            legacyVisible ?: return emptyMap()
        }
        baselineText = text
        return runCatching { parse(text) }.getOrElse { emptyMap() }
    }

    /**
     * 写入词频表，返回**实际写入的表**（调用方必须采纳）。
     *
     * 保存前先做外部修改合并：用户刚在配置目录里手改过文件时，手改内容合进返回表
     * （新增/修改/删除都尊重，见 [UserDataMerge]）——不采纳的话下次保存又会冲掉。
     * 表为空且没被外部改过时删除文件，不留表头空壳。
     */
    /**
     * 候选排序表对应的 rime 可编译词典文件名。
     *
     * XGuai_flypy 等方案的 `import_tables` 引用了 `candidate_order`，librime 部署时
     * 必须能读到该词典文件，否则方案词库编译失败、build 无 .bin、候选打不出字。
     */
    internal const val RIME_DICT_FILE_NAME = "candidate_order.dict.yaml"

    @Synchronized
    fun save(
        context: android.content.Context,
        table: Map<String, Map<String, Int>>,
    ): Map<String, Map<String, Int>> {
        // 3.3.14「yaml 单源」：直接写 candidate_order.dict.yaml。
        val saved = UserDataMerge.saveUserDictYaml(
            context = context,
            name = FILE_NAME,
            dictName = "candidate_order",
            comment = "候选排序：由 Xime 自动生成（源即本文件），用户拖动候选后写入",
            mirrorDir = com.kingzcheung.xime.settings.RimeUserFileSync.mirrorDir(context),
            privateRimeDir = com.kingzcheung.xime.settings.SchemaManager.getRimeDir(context),
            baselineText = baselineText,
            memory = table,
            parse = ::parse,
            serializeBody = { t, sb ->
                for ((code, words) in t.toSortedMap()) {
                    for ((word, weight) in words.entries.sortedByDescending { it.value }) {
                        sb.append(word).append('\t').append(code).append('\t').append(weight).append('\n')
                    }
                }
            },
            onBaseline = { baselineText = it },
        )
        return saved
    }

    /**
     * 生成/同步 rime 可编译词典 `candidate_order.dict.yaml`。
     *
     * 同时写两处（与 [WordFreqStore.ensureRimeDict] 完全一致的策略）：
     * - 私有 rime 工作目录 `files/rime/`（librime 部署时实际读取的位置）
     * - 可见 rime 目录根 `/storage/emulated/0/rime/`（与 word_freq.dict.yaml、
     *   Flypy.dict.yaml 同级，用户可直接查看/编辑/备份）
     *
     * 旧位置 `rime/xime/` 下的历史同名文件一并清理，避免同一词典出现两份互相遮蔽。
     *
     * 内容与磁盘一致时不落盘（避免无意义 IO）；表空时删除文件，不留只有表头的空壳，
     * 否则 librime 会把空词典编进 table.bin。失败静默降级（排序表是辅助功能，
     * 不能因它影响主流程）。
     */
    /** @return true 表示词典内容有变化需要重新部署（3.3.16：供 reconcile 判改动用）。 */
    @Synchronized
    fun ensureRimeDict(
        context: android.content.Context,
        table: Map<String, Map<String, Int>>,
    ): Boolean {
        return runCatching {
            val dictFile = File(
                com.kingzcheung.xime.settings.SchemaManager.getRimeDir(context),
                RIME_DICT_FILE_NAME,
            )
            val visible = File(
                com.kingzcheung.xime.settings.RimeUserFileSync.mirrorDir(context),
                RIME_DICT_FILE_NAME,
            )
            val legacy = File(
                com.kingzcheung.xime.settings.UserConfigDir.absoluteDir,
                RIME_DICT_FILE_NAME,
            )

            if (table.isEmpty()) {
                val existed = dictFile.exists() || visible.exists()
                if (dictFile.exists()) dictFile.delete()
                if (visible.exists()) visible.delete()
                if (legacy.exists()) legacy.delete()
                return@runCatching existed
            }

            // 词典正文必须带 rime 词典头（name/version/sort），否则 librime 无法识别，
            // import_tables 引用它时整份词典会被当成空内容。
            // sort: by_weight → rime 原生按权重降序，与手排序意图一致。
            val text = buildString {
                append("# Rime dictionary\n")
                append("# encoding: utf-8\n")
                append("# 候选排序字典：由 Xime 自动生成，权重越大候选越靠前（rime 原生 by_weight 排序）\n")
                append("---\n")
                append("name: candidate_order\n")
                append("version: \"1\"\n")
                append("sort: by_weight\n")
                append("use_preset_vocabulary: false\n")
                append("...\n")
                for ((code, words) in table.toSortedMap()) {
                    for ((word, weight) in words.entries.sortedByDescending { it.value }) {
                        append(word).append('\t').append(code).append('\t').append(weight).append('\n')
                    }
                }
            }

            val changed = !dictFile.exists() || dictFile.readText() != text
            if (changed) {
                dictFile.parentFile?.mkdirs()
                dictFile.writeText(text)
                runCatching {
                    visible.parentFile?.mkdirs()
                    visible.writeText(text)
                }
            }
            // 旧位置残留无论如何都清掉（迁移遗留，不影响内容判定）
            if (legacy.exists()) runCatching { legacy.delete() }
            changed
        }.getOrDefault(false)
    }

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
        // 3.3.17：源文件已是 dict.yaml（txt 已在 yaml 单源迁移中删除），
        // 必须读 dict.yaml 才能检测外部改动，否则拖动排序会被旧基线覆盖、
        // 重载永远判定「无改动」。
        val current = UserDataMerge.readDictYamlText(context, "candidate_order", FILE_NAME)
            ?: return table
        if (!UserDataMerge.externalChanged(baselineText, current)) return table
        val merged = UserDataMerge.reconcileTable(baselineText, current, table, ::parse)
        baselineText = current
        return merged
    }
}
