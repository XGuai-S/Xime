package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.UserConfigDir
import java.io.File

/**
 * 自动造词的词库分级管理：临时词库 → 固定词库。
 *
 * ## 规则
 *
 * 每次上屏的词先进**临时词库**（[TEMP_FILE_NAME]，带首次上屏时间与累计次数）；
 * 临时词库与固定词库的词都会在输入对应编码时**直接合并进候选栏**（不经 rime、
 * 不占用快捷短语 custom_phrase.txt），被用户选中上屏即计数；
 * 同一词被选中满 2 次晋升到**固定词库**（[FIXED_FILE_NAME]）。
 * 超过 7 天没再被选中的词在 [prune] / [promote] 中淘汰，不会无限堆积；
 * 临时词库文件**不限大小**（不设条数/字节上限），体积完全靠 7 天过期控制——
 * 既不担心文件撑爆，也不会出现满额后新词学不进来的问题。
 *
 * ## 与 [WordFreqStore] 的分工
 *
 * [WordFreqStore] 统计**所有**上屏词的频数（用于候选排序，长期累积、不清除）；
 * 本类只管**造词晋升**：临时库是候选池，固定库是「学成的词」。
 *
 * ## 存放位置
 *
 * 两个文件都与 `xime.yaml` 同级（`/storage/emulated/0/rime/xime/`），用户可直接查看/编辑；
 * 可见目录不可用时回退应用私有目录。
 *
 * ## 手改合并
 *
 * 运行期内存表是唯一真源，但保存前会先做外部修改合并：用户刚在配置目录里手改过文件时，
 * 手改内容合回内存表（新增/修改保留、删除也尊重，见 [UserDataMerge]），不会被下一次
 * 保存冲掉。
 */
internal object AutoWordStore {

    /** 临时词库文件名（与 xime.yaml 同级）。 */
    internal const val TEMP_FILE_NAME = "auto_word_temp.txt"

    /** 固定词库文件名（与 xime.yaml 同级）。 */
    internal const val FIXED_FILE_NAME = "auto_word.txt"

    /** 晋升考察窗口 / 临时词库过期窗口：7 天。窗口内未满晋升条件即删除。 */
    internal const val PROMOTE_WINDOW_DAYS = 7

    /** 窗口内需达到的上屏次数。 */
    internal const val PROMOTE_MIN_COUNT = 2

    /** 前缀命中时最多补进候选栏的词数：短编码命中面宽，不封顶会把候选栏尾部塞满。 */
    private const val MAX_PREFIX_CANDIDATES = 8

    /** 前缀扫描的最短输入长度（字母数）：1 个字母时命中面太宽，等第二个键再开始补。 */
    private const val PREFIX_MIN_INPUT = 2

    /** 组合生词默认字数下限（含）。 */
    internal const val COMBO_MIN_CHARS = 2

    /** 组合生词默认字数上限（含）。 */
    internal const val COMBO_MAX_CHARS = 4

    private const val TEMP_HEADER =
        "# 临时词库：词\t编码\t首次上屏时间\t被选中次数\t最后上屏时间（被选中满 2 次晋升固定词库；超过 7 天没再被选中则删除）"

    private const val FIXED_HEADER = "# 固定词库：词\t编码\t权重（由临时词库晋升，输入时合并进候选栏出候选）"

    /** 临时词库条目。 */
    data class Entry(
        val word: String,
        val code: String,
        /** 首次上屏时间（epoch 毫秒）：晋升窗口的起点。 */
        val firstSeen: Long,
        /** 被选中次数（在候选栏点选或按空格选中该词上屏各算一次）。 */
        val count: Int,
        /** 最后上屏时间（epoch 毫秒）：淘汰窗口的起点，超过窗口天数没再上屏即删除。 */
        val lastSeen: Long,
    )

    /** 晋升结果：新的临时表、新的固定表、本次晋升的词。 */
    data class Promotion(
        val temp: Map<String, Map<String, Entry>>,
        val fixed: Map<String, Map<String, Int>>,
        val promoted: List<Entry>,
    )

    // ── 纯逻辑（无 IO，直接单测） ──

    /**
     * 组合生词：把相邻两次上屏的词拼成一个新词。
     *
     * 规则（用户需求「上屏词可前后组合生词，长度可自定义」）：
     * - 前一词与后一词都非空才组合；
     * - 只组合汉字词（[isComboWord]）——英文、数字、标点参与会把噪音粘进词库；
     * - 拼接结果字数须落在 `[minChars, maxChars]` 内（按 Unicode 码点数），
     *   默认 2–4 字：太短（单字碎片）与太长（整句）都不收录。
     *
     * 编码不在这里拼：按启用方案的**满四码单字**与三个总规则生成，
     * 见 [SchemeCodeIndex.composeCodes]。
     *
     * @return 组合出的新词；不满足条件返回 null。
     */
    fun comboWord(
        prevWord: String,
        word: String,
        minChars: Int = COMBO_MIN_CHARS,
        maxChars: Int = COMBO_MAX_CHARS,
    ): String? {
        if (prevWord.isEmpty() || word.isEmpty()) return null
        if (!isComboWord(prevWord) || !isComboWord(word)) return null
        val combined = prevWord + word
        val length = combined.codePointCount(0, combined.length)
        if (length < minChars || length > maxChars) return null
        return combined
    }

    /**
     * 词是否适合参与组合：全部为汉字（CJK 统一表意文字及其扩展）。
     *
     * 只挑汉字是为了不把英文单词、数字、标点粘进词库——它们在候选里也没有组合意义，
     * 反而会让固定词库塞满噪音。
     */
    fun isComboWord(word: String): Boolean {
        if (word.isEmpty()) return false
        // 按码点遍历：增补平面汉字（如扩展 B 区）占两个 char，逐个 char 判脚本会得到
        // UNKNOWN 而被误判成非汉字，组合链因此断掉
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /**
     * 造词库收录门槛：≥2 个汉字（组合词/普通上屏词都要求），区别于词频表
     * （[WordFreqStore.isCountableWord] 收单字）。单字方案里本身就能打，无需学。
     */
    private fun isLearnedWord(word: String): Boolean {
        if (word.length < 2) return false
        return isComboWord(word)
    }

    /**
     * 解析临时词库。每行 `词\t编码\t首次时间\t次数\t最后时间`；格式错的只丢该行。
     *
     * 次数为 0 是**合法**值：组合生词刚拼出来还没被用户选中过就是 0 次，
     * 必须读回来才能出现在候选栏等用户选中；只有负数（人工改坏的残行）才丢。
     */
    fun parseTemp(text: String): Map<String, Map<String, Entry>> {
        val table = LinkedHashMap<String, LinkedHashMap<String, Entry>>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 5) continue
            val word = parts[0].trim()
            val code = parts[1].trim()
            val firstSeen = parts[2].trim().toLongOrNull() ?: continue
            val count = parts[3].trim().toIntOrNull() ?: continue
            val lastSeen = parts[4].trim().toLongOrNull() ?: continue
            if (word.isEmpty() || code.isEmpty() || count < 0) continue
            table.getOrPut(code) { LinkedHashMap() }[word] =
                Entry(word = word, code = code, firstSeen = firstSeen, count = count, lastSeen = lastSeen)
        }
        return table
    }

    /** 序列化临时词库（按编码首次出现顺序，编码内按最后上屏时间降序）。 */
    fun serializeTemp(table: Map<String, Map<String, Entry>>): String {
        val sb = StringBuilder()
        sb.append(TEMP_HEADER).append('\n')
        for ((code, words) in table) {
            for ((_, entry) in words.entries.sortedByDescending { it.value.lastSeen }) {
                sb.append(entry.word).append('\t')
                    .append(entry.code).append('\t')
                    .append(entry.firstSeen).append('\t')
                    .append(entry.count).append('\t')
                    .append(entry.lastSeen).append('\n')
            }
        }
        return sb.toString()
    }

    /** 解析固定词库。每行 `词\t编码\t权重`；格式错的只丢该行。 */
    fun parseFixed(text: String): Map<String, Map<String, Int>> {
        val table = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val word = parts[0].trim()
            val code = parts[1].trim()
            val weight = parts[2].trim().toIntOrNull() ?: continue
            if (word.isEmpty() || code.isEmpty() || weight <= 0) continue
            table.getOrPut(code) { LinkedHashMap() }[word] = weight
        }
        return table
    }

    /** 序列化固定词库（按编码首次出现顺序，编码内按权重降序）。 */
    fun serializeFixed(table: Map<String, Map<String, Int>>): String {
        val sb = StringBuilder()
        sb.append(FIXED_HEADER).append('\n')
        for ((code, words) in table) {
            for ((word, weight) in words.entries.sortedByDescending { it.value }) {
                sb.append(word).append('\t').append(code).append('\t').append(weight).append('\n')
            }
        }
        return sb.toString()
    }
    /**
     * 收录一个词条（普通上屏或组合生词），返回新表（无变化时返回原表引用）。
     *
     * [initialCount] 决定语义：
     * - `1`（默认）= **普通上屏**：用户从候选栏完整选中该词并上屏。已存在则计数 +1、
     *   刷新最后上屏时间——这是晋升统计的「用户选中次数」。
     * - `0` = **组合生词**：相邻两次上屏自动拼出来的候选，用户还没选过它。已存在时
     *   **不计数、不刷新时间**（只是又拼了一次，不是用户选中）；仅词库中还没有时才
     *   以 0 次插入，供候选栏展示等用户选中。
     *
     * 这条区分很关键：否则用户连打「虾苗」两次，组合逻辑自动拼出的「虾苗虾苗」
     * 会被误当成用户选中它，计数被反复刷高甚至提前晋升——而用户从没选过这个词。
     */
    fun record(
        temp: Map<String, Map<String, Entry>>,
        code: String,
        word: String,
        now: Long,
        initialCount: Int = 1,
    ): Map<String, Map<String, Entry>> {
        // 造词收录门槛与词频表（WordFreqStore.isCountableWord）不同：词频表收单字
        // （上屏历史镜像），但造词库只学「≥2 字可组合的词」——单字方案里本身就能打，
        // 学进造词库只会污染候选（合并时固定库权重会把它顶到前面）。
        if (code.isEmpty() || !isLearnedWord(word)) return temp
        // 临时词库**不限大小**：不设条数/编码/字节上限（体积由 7 天过期窗口控制），
        // 任何词都能学进来——以前组合生词把 4096 个编码额度占满后新词被拒收的问题不再存在。
        val codeWords = temp[code]
        val existing = codeWords?.get(word)
        val entry = if (existing == null) {
            Entry(
                word = word, code = code, firstSeen = now,
                count = initialCount.coerceAtLeast(0), lastSeen = now,
            )
        } else if (initialCount > 0) {
            // 普通上屏：用户选中计数 +1，刷新最后上屏时间
            existing.copy(count = existing.count + 1, lastSeen = now)
        } else {
            // 组合生词已存在：仅自动拼接过一次，不是用户选中 → 原样返回，不计数不刷新
            return temp
        }
        val next = LinkedHashMap(temp)
        val words = LinkedHashMap(codeWords ?: emptyMap())
        words[word] = entry
        next[code] = words
        return next
    }

    /**
     * 晋升与淘汰：
     * - 首次上屏起窗口（7 天）内上屏满 2 次的词 → 移入固定词库（权重按次数换算，供 custom_phrase 使用）；
     * - 超过窗口天数没再上屏的词 → 从临时库淘汰。
     *
     * 两个窗口都取 [windowDays]（默认 7 天，可在 xime.yaml 的 `auto_word.promote_days` 调整）：
     * 晋升从**首次**上屏算起，淘汰从**最后**上屏算起——常用的词不会被误删。
     *
     * 每次上屏后调用，幂等：无待晋升/待淘汰时返回原表。
     */
    fun promote(
        temp: Map<String, Map<String, Entry>>,
        fixed: Map<String, Map<String, Int>>,
        now: Long,
        windowDays: Int = PROMOTE_WINDOW_DAYS,
        minCount: Int = PROMOTE_MIN_COUNT,
    ): Promotion {
        if (temp.isEmpty()) return Promotion(temp, fixed, emptyList())
        val limit = windowMillis(windowDays)
        val needCount = minCount.coerceAtLeast(1)
        val promoted = mutableListOf<Entry>()
        val nextTemp = LinkedHashMap<String, LinkedHashMap<String, Entry>>()
        for ((code, words) in temp) {
            for ((word, entry) in words) {
                val windowPassed = now - entry.firstSeen > limit
                val stale = now - entry.lastSeen > limit
                when {
                    // 窗口内攒够次数：晋升固定词库
                    !windowPassed && entry.count >= needCount -> promoted += entry
                    // 超过窗口没再上屏：淘汰
                    stale -> Unit
                    // 仍在窗口内：留在临时库继续观察
                    else -> nextTemp.getOrPut(code) { LinkedHashMap() }[word] = entry
                }
            }
        }
        if (promoted.isEmpty() && nextTemp == temp) return Promotion(temp, fixed, emptyList())

        val nextFixed = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for ((code, words) in fixed) nextFixed[code] = LinkedHashMap(words)
        for (entry in promoted) {
            nextFixed.getOrPut(entry.code) { LinkedHashMap() }[entry.word] = promoteWeight(entry.count)
        }
        return Promotion(nextTemp, nextFixed, promoted)
    }

    /**
     * 清理临时词库中超过窗口天数没再上屏的词（只淘汰，不晋升）。
     *
     * 服务启动加载后调用：用户可能长时间不开输入法，过期词不必等到下次上屏才清。
     *
     * @return 清理后的表；无变化时返回原表（引用相同，便于调用方判空跳过落盘）
     */
    fun prune(
        temp: Map<String, Map<String, Entry>>,
        now: Long,
        windowDays: Int = PROMOTE_WINDOW_DAYS,
    ): Map<String, Map<String, Entry>> {
        if (temp.isEmpty()) return temp
        val limit = windowMillis(windowDays)
        val next = LinkedHashMap<String, LinkedHashMap<String, Entry>>()
        var changed = false
        for ((code, words) in temp) {
            for ((word, entry) in words) {
                if (now - entry.lastSeen > limit) {
                    changed = true
                    continue
                }
                next.getOrPut(code) { LinkedHashMap() }[word] = entry
            }
        }
        return if (changed) next else temp
    }

    /** 观察窗口毫秒数（天数下限 1，防御 0/负数配置导致词立即过期）。 */
    private fun windowMillis(windowDays: Int): Long =
        windowDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L

    /**
     * 取当前编码下可出候选的造词：**完整编码命中优先**（固定词库按权重降序、临时词库
     * 垫底）；[prefixMatch] 为 true 时，编码还没打完再做**前缀命中**（固定库权重降序 +
     * 临时库最近上屏降序，总量封顶 [MAX_PREFIX_CANDIDATES]）。
     *
     * [prefixMatch] 默认 false（完整命中）：必须敲满词库中的完整编码才出词，避免
     * 编码没按够就提前出现。置 true 恢复旧行为：输入 >= [PREFIX_MIN_INPUT] 个字母时
     * 以当前输入作词库编码前缀提前出词（引擎码表也是边输边出词）。
     *
     * [known] 为引擎已出候选（这些词不再重复合并）。供候选栏合并使用。
     */
    fun candidatesFor(
        temp: Map<String, Map<String, Entry>>,
        fixed: Map<String, Map<String, Int>>,
        code: String,
        known: Set<String>,
        prefixMatch: Boolean = false,
    ): List<String> {
        if (code.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        // 1) 完整编码命中：固定库按权重降序，临时库垫底
        fixed[code]?.entries?.sortedByDescending { it.value }?.forEach { (word, _) ->
            if (seen.add(word)) out += word
        }
        temp[code]?.forEach { (word, _) -> if (seen.add(word)) out += word }
        // 2) 前缀命中（仅 prefixMatch=true 时）：输入是某个已录编码的前缀（且没敲完）时也让词可见
        if (prefixMatch && code.length >= PREFIX_MIN_INPUT) {
            var added = 0
            val prefixFixed = ArrayList<Pair<String, Int>>()
            for ((c, words) in fixed) {
                if (c.length > code.length && c.startsWith(code)) {
                    for ((word, weight) in words) prefixFixed += word to weight
                }
            }
            prefixFixed.sortByDescending { it.second }
            for ((word, _) in prefixFixed) {
                if (added >= MAX_PREFIX_CANDIDATES) break
                if (word in known || !seen.add(word)) continue
                out += word
                added++
            }
            if (added < MAX_PREFIX_CANDIDATES) {
                val prefixTemp = ArrayList<Pair<String, Long>>()
                for ((c, words) in temp) {
                    if (c.length > code.length && c.startsWith(code)) {
                        for ((word, entry) in words) prefixTemp += word to entry.lastSeen
                    }
                }
                prefixTemp.sortByDescending { it.second }
                for ((word, _) in prefixTemp) {
                    if (added >= MAX_PREFIX_CANDIDATES) break
                    if (word in known || !seen.add(word)) continue
                    out += word
                    added++
                }
            }
        }
        return out.filter { it !in known }
    }

    /** 晋升权重：次数越多权重越高（与 [CandidateOrderStore.fullOrderWeights] 同一量级）。 */
    fun promoteWeight(count: Int): Int = count.coerceAtLeast(PROMOTE_MIN_COUNT) * 10

    // ── 文件 IO ──

    /**
     * 外部修改检测基线（临时/固定各一份）：上次 load 读到的可见文件文本 /
     * 上次 save 写出的文本。null = 本进程还没读过也没写过（不合并直接写）。
     */
    private var tempBaselineText: String? = null
    private var fixedBaselineText: String? = null

    private fun tempPrivateFile(context: android.content.Context): File =
        File(context.filesDir, TEMP_FILE_NAME)

    private fun fixedPrivateFile(context: android.content.Context): File =
        File(context.filesDir, FIXED_FILE_NAME)

    /** 读临时词库；不存在或读失败返回空表。 */
    @Synchronized
    fun loadTemp(context: android.content.Context): Map<String, Map<String, Entry>> {
        val visibleText = UserConfigDir.readText(context, TEMP_FILE_NAME)
        val visibleStamp = UserConfigDir.stamp(context, TEMP_FILE_NAME)
        // 基线锚定可见文件文本（保存前比对的就是它），与内存取自哪一侧无关
        tempBaselineText = visibleText ?: ""
        val text = readPreferNewer(
            context,
            TEMP_FILE_NAME,
            visibleText,
            visibleStamp,
            tempPrivateFile(context),
        ) ?: return emptyMap()
        return runCatching { parseTemp(text) }.getOrElse { emptyMap() }
    }

    /** 读固定词库；不存在或读失败返回空表。 */
    @Synchronized
    fun loadFixed(context: android.content.Context): Map<String, Map<String, Int>> {
        val visibleText = UserConfigDir.readText(context, FIXED_FILE_NAME)
        val visibleStamp = UserConfigDir.stamp(context, FIXED_FILE_NAME)
        fixedBaselineText = visibleText ?: ""
        val text = readPreferNewer(
            context,
            FIXED_FILE_NAME,
            visibleText,
            visibleStamp,
            fixedPrivateFile(context),
        ) ?: return emptyMap()
        return runCatching { parseFixed(text) }.getOrElse { emptyMap() }
    }

    /**
     * 可见目录与私有目录取较新者（与 [CandidateOrderStore.newerOf] 同一策略，
     * 避免可见目录里的旧副本遮蔽私有目录的新数据）。
     */
    private fun readPreferNewer(
        context: android.content.Context,
        name: String,
        visibleText: String?,
        visibleStamp: Pair<Long, Long>?,
        priv: File,
    ): String? {
        val privateText = if (priv.exists()) runCatching { priv.readText() }.getOrNull() else null
        val privateStamp = if (priv.exists()) priv.lastModified() else null
        return WordFreqStore.newerOf(visibleStamp, visibleText, privateStamp, privateText)
    }

    /**
     * 写临时词库，返回**实际写入的表**（调用方必须采纳）。
     *
     * 保存前先做外部修改合并：用户刚在配置目录里手改过文件时，手改内容合进返回表
     * （新增/修改/删除都尊重，见 [UserDataMerge]）——不采纳的话下次保存又会冲掉。
     * 表为空且没被外部改过时删除文件，不留空壳。
     */
    @Synchronized
    fun saveTemp(
        context: android.content.Context,
        table: Map<String, Map<String, Entry>>,
    ): Map<String, Map<String, Entry>> =
        UserDataMerge.saveUserTable(
            context = context,
            name = TEMP_FILE_NAME,
            privateFile = tempPrivateFile(context),
            baselineText = tempBaselineText,
            memory = table,
            parse = ::parseTemp,
            serialize = ::serializeTemp,
            onBaseline = { tempBaselineText = it },
        )

    /** 写固定词库，返回**实际写入的表**（调用方必须采纳）；合并语义同 [saveTemp]。 */
    @Synchronized
    fun saveFixed(
        context: android.content.Context,
        table: Map<String, Map<String, Int>>,
    ): Map<String, Map<String, Int>> =
        UserDataMerge.saveUserTable(
            context = context,
            name = FIXED_FILE_NAME,
            privateFile = fixedPrivateFile(context),
            baselineText = fixedBaselineText,
            memory = table,
            parse = ::parseFixed,
            serialize = ::serializeFixed,
            onBaseline = { fixedBaselineText = it },
        )

    /**
     * 合并临时词库的外部手改（不写盘）：返回合并后的表，调用方必须采纳。
     *
     * 内存表随即包含文件里的全部有效条目，下次保存自然落盘；基线记为当前文件内容，
     * 之后保存不会把用户的删除写回冲掉。文件读不到或没被改过 → 原样返回。
     */
    @Synchronized
    fun reconcileTemp(
        context: android.content.Context,
        table: Map<String, Map<String, Entry>>,
    ): Map<String, Map<String, Entry>> {
        val current = UserConfigDir.readText(context, TEMP_FILE_NAME) ?: return table
        if (!UserDataMerge.externalChanged(tempBaselineText, current)) return table
        val merged = UserDataMerge.reconcileTable(tempBaselineText, current, table, ::parseTemp)
        tempBaselineText = current
        return merged
    }

    /** 合并固定词库的外部手改（不写盘），语义同 [reconcileTemp]。 */
    @Synchronized
    fun reconcileFixed(
        context: android.content.Context,
        table: Map<String, Map<String, Int>>,
    ): Map<String, Map<String, Int>> {
        val current = UserConfigDir.readText(context, FIXED_FILE_NAME) ?: return table
        if (!UserDataMerge.externalChanged(fixedBaselineText, current)) return table
        val merged = UserDataMerge.reconcileTable(fixedBaselineText, current, table, ::parseFixed)
        fixedBaselineText = current
        return merged
    }
}