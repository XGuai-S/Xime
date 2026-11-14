package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.UserConfigDir
import java.io.File

/**
 * 上屏词频表：统计「哪些词被上屏过、各上屏多少次」。
 *
 * ## 与 [CandidateOrderStore] 的分工
 *
 * [CandidateOrderStore] 记录的是**用户手动拖动**排定的候选顺序（全序权重）；
 * 本表记录的是**自动累积**的上屏频数。两者互不干扰：手动排序优先，
 * 未手动排过的编码按本表频数降序显示（见 [reorder]）。
 *
 * ## 表结构
 *
 * 键是**编码**（如 `orq`、`jt`），值是「词 → 上屏次数」。全部编码共用同一个文件。
 * 每上屏一次该词，计数 +1；计数只增不减，反映长期使用偏好。
 *
 * ## 存放位置
 *
 * 与 `xime.yaml` 同级（`/storage/emulated/0/rime/xime/`），用户可直接查看/编辑；
 * 可见目录不可用时回退应用私有目录。
 *
 * ## 手改合并
 *
 * 运行期内存表是唯一真源，但保存前会先做外部修改合并：用户刚在配置目录里手改过文件时，
 * 手改内容合回内存表（新增/修改保留、删除也尊重，见 [UserDataMerge]），不会被下一次
 * 保存冲掉。
 */
internal object WordFreqStore {

    /**
     * 词频文件名（可见配置目录与 xime.yaml 同级）。
     *
     * 本表随备份打包与否由 xime.yaml `backup.word_freq` 控制（默认开启）。关闭时
     * 换机或恢复后词频从空表重新累积，排序退化为引擎默认顺序，其它数据不受影响。
     */
    internal const val FILE_NAME = "word_freq.txt"

    /** 表头注释行；解析时以 `#` 开头一律跳过。 */
    private const val HEADER = "# 上屏词频：词\t编码\t频数（每次上屏自动 +1）"

    /**
     * 是否计入词频的词。
     *
     * 候选上屏的**汉字**一律计入，不设任何限制（单字、词组、任意长度的汉字连续串
     * 都记——词频表是「上屏历史」的镜像，只按字面记录）。仅排除空串与非汉字
     * （英文、数字、标点、空白及混合内容）——它们记进来只会污染排序。
     */
    fun isCountableWord(word: String): Boolean {
        if (word.isEmpty()) return false
        // 按码点遍历：增补平面汉字（如扩展 B 区）占两个 char，逐个 char 判脚本会得到
        // UNKNOWN 而被误判成非汉字
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return false
            i += Character.charCount(cp)
        }
        return true
    }

    // ── 纯逻辑（无 IO，直接单测） ──

    /**
     * 解析词频表。格式为每行 `词\t编码\t频数`；`#` 开头与空行跳过。
     *
     * 容错取向：任何一行格式不对只丢那一行，不让整张表失效——手工编辑过或跨版本
     * 残留时，宁可少几条统计也不能连候选都出不来。同一 (编码, 词) 重复时后者覆盖前者。
     */
    fun parse(text: String): Map<String, Map<String, Int>> {
        val table = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        // 兼容 dict.yaml 头（3.3.14 yaml 单源）：跳过 --- / name: x / ... 等行
        for (rawLine in com.kingzcheung.xime.service.UserDataMerge
            .stripDictYamlHeader(text).lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val word = parts[0].trim()
            val code = parts[1].trim()
            val count = parts[2].trim().toIntOrNull() ?: continue
            if (code.isEmpty() || word.isEmpty() || count <= 0) continue
            table.getOrPut(code) { LinkedHashMap() }[word] = count
        }
        return table
    }

    /** 序列化为文本（稳定顺序：按编码字母序，编码内按频数降序）。 */
    fun serialize(table: Map<String, Map<String, Int>>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for ((code, words) in table.toSortedMap()) {
            for ((word, count) in words.entries.sortedByDescending { it.value }) {
                sb.append(word).append('\t').append(code).append('\t').append(count).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * 记一次上屏：该 (编码, 词) 频数 +1。
     *
     * 返回原表表示无需变更（词不可计入或编码为空）。
     */
    fun withCommit(
        table: Map<String, Map<String, Int>>,
        code: String,
        word: String,
    ): Map<String, Map<String, Int>> {
        if (code.isEmpty() || !isCountableWord(word)) return table
        val codeWords = table[code]
        val next = LinkedHashMap(table)
        val words = LinkedHashMap(codeWords ?: emptyMap())
        words[word] = (words[word] ?: 0) + 1
        next[code] = words
        return next
    }

    /** 重排结果复用 [CandidateOrderStore.Reordered]（候选、注释、动作三者平行）。 */
    typealias Reordered = CandidateOrderStore.Reordered

    /**
     * 按上屏频数重排显示候选：频数高的在前，未统计到的词保持引擎原顺序排在其后。
     *
     * 该编码无统计记录、或顺序本就无需变化时返回 null（null = 不干预，调用方原样渲染）。
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

    // ── 文件 IO ──

    /**
     * 外部修改检测基线：上次 load 读到的可见文件文本 / 上次 save 写出的文本。
     * null = 本进程还没读过也没写过（无从判断外部修改，保存时不合并直接写）。
     */
    private var baselineText: String? = null

    /** 回退用的私有目录文件（可见目录不可用/写入失败时）。 */
    private fun privateFile(context: android.content.Context): File =
        File(context.filesDir, FILE_NAME)

    /**
     * 两处候选文本中取较新者（与 [CandidateOrderStore.newerOf] 同一策略）。
     * 返回 null 表示两处都没有可读内容。
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

    /** 读取词频表；文件不存在或读失败时返回空表（统计功能退化为不干预排序）。 */
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
     *
     * 写主文件 `word_freq.txt` 后，同步生成两份内容一致的派生文件：
     * - 私有 rime 编译词典 `files/rime/word_freq.dict.yaml`（librime 部署用）
     * - 可见目录副本 `rime/xime/word_freq.dict.yaml`（与 word_freq.txt 同级，供查看/编辑/备份）
     * 不再生成 `word_freq.yaml`（旧镜像已废弃）；派生文件写入失败只记日志，不影响主文件。
     */
    /** rime 可编译词典文件名（私有 rime 工作目录 files/rime/ 下）。 */
    internal const val RIME_DICT_FILE_NAME = "word_freq.dict.yaml"

    /**
     * 生成/删除 rime 可编译词典 `word_freq.dict.yaml`（私有 rime 工作目录）。
     *
     * XGuai_flypy 等方案的 `import_tables` 引用了 `word_freq`，librime 部署时
     * 必须能读到该词典文件，否则方案词库编译失败、build 无 .bin、候选打不出字。
     * 词条按频数降序 + 权重=频数（`sort: by_weight` → rime 原生按词频排序候选）。
     *
     * 幂等：表空时删除文件（不留空壳），有内容时整体重写。失败静默降级
     * （只记日志不抛错——词频是辅助表，不能因它影响主流程）。
     */
    @Synchronized
    /**
     * 生成 rime 可编译词典 `word_freq.dict.yaml`。
     *
     * 同时写两处：私有 rime 工作目录（librime 编译用）与可见 rime 目录根
     *（`/storage/emulated/0/rime/`，与 Flypy.dict.yaml 等同级，方便用户查看/编辑/备份）。
     * 旧位置 `rime/xime/` 下的历史副本一并清理，避免同一词典出现两份。
     *
     * @return true 表示词典内容相对磁盘已有版本发生变化，调用方据此决定
     *         是否触发增量部署（见 [save] 与 saveWordData）；false 表示无需重编译。
     */
    fun ensureRimeDict(
        context: android.content.Context,
        table: Map<String, Map<String, Int>>,
    ): Boolean = runCatching {
        val rimeDir = com.kingzcheung.xime.settings.SchemaManager.getRimeDir(context)
        val dictFile = File(rimeDir, RIME_DICT_FILE_NAME)
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

        val text = buildString {
            append("# Rime dictionary\n")
            append("# encoding: utf-8\n")
            append("# 上屏词频字典：由 Xime 自动生成，词频越高权重越大（rime 原生 by_weight 排序）\n")
            append("---\n")
            append("name: word_freq\n")
            append("version: \"1\"\n")
            append("sort: by_weight\n")
            append("use_preset_vocabulary: false\n")
            append("...\n")
            for ((code, words) in table.toSortedMap()) {
                for ((word, count) in words.entries.sortedByDescending { it.value }) {
                    append(word).append('\t').append(code).append('\t').append(count).append('\n')
                }
            }
        }

        // 内容没变就不落盘、不触发重编译（上屏极频繁，避免无意义 IO 与编译）
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

@Synchronized
    fun save(
        context: android.content.Context,
        table: Map<String, Map<String, Int>>,
    ): Map<String, Map<String, Int>> {
        // 3.3.14「yaml 单源」：直接写 word_freq.dict.yaml，不再维护中间层 txt。
        // 源文件 = librime 编译的那份，不存在「改了源、派生没跟上」的断层；
        // 同时省掉一次序列化 + 一次写盘（txt 30KB + dict 30KB → 只写 30KB）。
        val saved = UserDataMerge.saveUserDictYaml(
            context = context,
            name = FILE_NAME,
            dictName = "word_freq",
            comment = "上屏词频：由 Xime 自动生成（源即本文件），词频越高权重越大",
            mirrorDir = com.kingzcheung.xime.settings.RimeUserFileSync.mirrorDir(context),
            privateRimeDir = com.kingzcheung.xime.settings.SchemaManager.getRimeDir(context),
            baselineText = baselineText,
            memory = table,
            parse = ::parse,
            serializeBody = { t, sb ->
                for ((code, words) in t.toSortedMap()) {
                    for ((word, count) in words.entries.sortedByDescending { it.value }) {
                        sb.append(word).append('\t').append(code).append('\t').append(count).append('\n')
                    }
                }
            },
            onBaseline = { baselineText = it },
        )
        // dict.yaml 已由 saveUserDictYaml 直接落盘（镜像 + 私有 rime 目录）。
        return saved
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
        val current = UserDataMerge.readDictYamlText(context, "word_freq", FILE_NAME)
            ?: return table
        if (!UserDataMerge.externalChanged(baselineText, current)) return table
        val merged = UserDataMerge.reconcileTable(baselineText, current, table, ::parse)
        baselineText = current
        return merged
    }
}
