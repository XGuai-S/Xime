package com.kingzcheung.xime.service

import android.content.Context
import com.kingzcheung.xime.settings.UserConfigDir
import java.io.File

/**
 * 用户数据文件（词频 / 自动造词 / 候选排序 / 黑名单）的「外部修改合并」。
 *
 * ## 为什么需要
 *
 * 这些文件放在用户可见配置目录 `/storage/emulated/0/rime/xime/`（与 `xime.yaml` 同级），
 * 用户可以直接手改（文件管理器、电脑 USB、应用内文件编辑器都行）；但运行期**内存表才是
 * 唯一真源**，每次保存都把内存表全量覆盖写回文件。不检测外部修改的话，用户手改的内容会在
 * 下一次保存时被静默冲掉——而且「重载」只重载 rime 与键盘配置，词库表根本不重读，
 * 手改就像「变了又变回去」。
 *
 * ## 合并语义（三方合并）
 *
 * base = 上次读取/写入时的文件内容，ours = 当前内存表，theirs = 用户手改后的文件内容。
 * 以 (编码, 词) 条目为粒度判定：
 *
 * - 用户新增（theirs 有、base 无）→ 保留用户版；
 * - 用户改过（theirs 与 base 不同）→ 取用户版，用户优先；
 * - 用户删了（base 有、theirs 无）→ 尊重删除，不保留；
 * - 应用期间新增（ours 有、base/theirs 无）→ 保留；
 * - 应用改了计数/权重（theirs 与 base 相同、ours 不同）→ 取内存版（用户没动它，统计继续）；
 * - 双方都改同一词 → 取用户版。
 *
 * 已知边界：「用户把值改回与上次写入完全相同」在文本层面无法与「没改」区分，按没改处理
 * （取应用统计）。真想固定某个值，先让应用保存一次（基线刷新）再改。
 *
 * 黑名单是集合，同一套语义：theirs ∪ (ours - base)。
 *
 * ## 外部修改怎么判定
 *
 * 靠**基线文本比对**：store 在 load 时记下可见文件的文本，每次写入后更新为写出的文本；
 * 保存前重读文件，与基线不同即视为外部修改。文件不存在或读不了 → 不合并——删掉整个
 * 文件不该被解读成「清空整张表」，误删时保住数据比兑现删除更重要。
 *
 * ## 手改的边界
 *
 * 合并以条目为粒度：格式正确的行才进得来；表头注释由应用生成，用户自己加的注释行不会
 * 保留；行格式改坏的行按解析失败丢弃（与启动时加载是同一套容错）。
 */
internal object UserDataMerge {

    /** 外部是否改过文件。[baselineText] 为 null 表示本进程还没读过也没写过，无从判断。 */
    fun externalChanged(baselineText: String?, currentText: String?): Boolean =
        baselineText != null && currentText != null && currentText != baselineText

    /**
     * 词表合并：无外部修改时原样返回 [memory]（同一实例，调用方可据此跳过）。
     *
     * @param parse 文件文本 → 表（各 store 的解析器；坏行容错丢弃）
     */
    fun <V> reconcileTable(
        baselineText: String?,
        currentText: String?,
        memory: Map<String, Map<String, V>>,
        parse: (String) -> Map<String, Map<String, V>>,
    ): Map<String, Map<String, V>> {
        if (!externalChanged(baselineText, currentText)) return memory
        val base = runCatching { parse(baselineText!!) }.getOrElse { emptyMap() }
        val theirs = runCatching { parse(currentText!!) }.getOrElse { emptyMap() }
        return mergeTable(base, memory, theirs)
    }

    /** 黑名单集合合并：无外部修改时原样返回 [memory]。 */
    fun reconcileSet(
        baselineText: String?,
        currentText: String?,
        memory: Set<String>,
        parse: (String) -> Set<String>,
    ): Set<String> {
        if (!externalChanged(baselineText, currentText)) return memory
        val base = runCatching { parse(baselineText!!) }.getOrElse { emptySet() }
        val theirs = runCatching { parse(currentText!!) }.getOrElse { emptySet() }
        return mergeSet(base, memory, theirs)
    }

    /**
     * 词表三方合并（纯函数）：编码 → 词 → 值。
     *
     * 输出编码顺序 = ours 的编码顺序 + theirs 独有的编码（顺序稳定，便于序列化后 diff）。
     */
    fun <V> mergeTable(
        base: Map<String, Map<String, V>>,
        ours: Map<String, Map<String, V>>,
        theirs: Map<String, Map<String, V>>,
    ): Map<String, Map<String, V>> {
        val out = LinkedHashMap<String, Map<String, V>>()
        for (code in ours.keys + theirs.keys) {
            val o = ours[code]
            val t = theirs[code]
            val b = base[code]
            val words = LinkedHashMap<String, V>()
            for (word in (o?.keys ?: emptySet()) + (t?.keys ?: emptySet())) {
                val ov = o?.get(word)
                val tv = t?.get(word)
                val bv = b?.get(word)
                when {
                    // 用户新增或改过（与 base 不同即算改过，含 base 里没有）→ 取用户版
                    tv != null && bv != tv -> words[word] = tv
                    // 用户没动：应用改过统计/排序 → 取内存版；用户没动且应用删了 → 不保留
                    ov != null && (tv != null || bv == null) -> words[word] = ov
                    // 其余（用户删了 / 应用删了）→ 不保留
                }
            }
            if (words.isNotEmpty()) out[code] = words
        }
        return out
    }

    /**
     * 集合三方合并（纯函数，黑名单用）：
     * 用户加的（theirs）+ 应用期间加的（ours - base）；用户删掉的（base - theirs）不保留。
     */
    fun mergeSet(base: Set<String>, ours: Set<String>, theirs: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        out += theirs
        out += ours - base
        return out
    }

    /**
     * 保存用户数据词表文件：先合并外部手改，再写盘，并通过 [onBaseline] 维护基线。
     *
     * @param baselineText 上次读取/写入的文本（外部修改基线；null = 本进程没读过也没写过，
     *   此时不合并、直接写，兼容测试与首次保存）
     * @param onBaseline 基线更新回调（写成功记写出的文本；保留用户原文记原文；删文件记当前内容）
     * @return 实际写入的表；调用方**必须采纳**——不采纳的话下次保存又会把用户手改冲掉
     */
    fun <V> saveUserTable(
        context: Context,
        name: String,
        privateFile: File,
        baselineText: String?,
        memory: Map<String, Map<String, V>>,
        parse: (String) -> Map<String, Map<String, V>>,
        serialize: (Map<String, Map<String, V>>) -> String,
        onBaseline: (String?) -> Unit,
    ): Map<String, Map<String, V>> {
        val currentText = UserConfigDir.readText(context, name)
        val external = externalChanged(baselineText, currentText)
        val merged = reconcileTable(baselineText, currentText, memory, parse)
        if (merged.isEmpty()) {
            // 空表：没被外部改过才删文件（清掉自己留下的表头空壳）；
            // 用户手写了内容但解析不出条目（只有注释/坏行）时原样保留，不删用户的东西
            if (!external) {
                UserConfigDir.delete(context, name)
                runCatching { if (privateFile.exists()) privateFile.delete() }
            }
            onBaseline(currentText)
            return merged
        }
        val text = serialize(merged)
        return if (UserConfigDir.writeText(context, name, text)) {
            runCatching { if (privateFile.exists()) privateFile.delete() }
            onBaseline(text)
            merged
        } else {
            // 可见目录写不进：退回私有目录。可见文件没被动过，基线维持原样
            runCatching { privateFile.writeText(text) }
            merged
        }
    }

    /** 保存用户数据集合文件（黑名单用），语义同 [saveUserTable]。 */
    fun saveUserSet(
        context: Context,
        name: String,
        privateFile: File,
        baselineText: String?,
        memory: Set<String>,
        parse: (String) -> Set<String>,
        serialize: (Set<String>) -> String,
        onBaseline: (String?) -> Unit,
    ): Set<String> {
        val currentText = UserConfigDir.readText(context, name)
        val external = externalChanged(baselineText, currentText)
        val merged = reconcileSet(baselineText, currentText, memory, parse)
        if (merged.isEmpty()) {
            if (!external) {
                UserConfigDir.delete(context, name)
                runCatching { if (privateFile.exists()) privateFile.delete() }
            }
            onBaseline(currentText)
            return merged
        }
        val text = serialize(merged)
        return if (UserConfigDir.writeText(context, name, text)) {
            runCatching { if (privateFile.exists()) privateFile.delete() }
            onBaseline(text)
            merged
        } else {
            runCatching { privateFile.writeText(text) }
            merged
        }
    }
}
