package com.kingzcheung.xime.service

/**
 * 组合生词的编码生成：按**启用方案自身的单字编码规则**给新词编一个打得出来的码。
 *
 * ## 为什么不能直接拼两个词的编码
 *
 * 「现在」=xz、「目录」=mulu，硬拼成 xzmulu 在该方案里既超长（max_code_length=4）
 * 也没有任何意义；按方案规则编出的 xzml 才符合输入习惯。
 *
 * ## 取码规则（与具体方案无关，任何码表方案通用）
 *
 * 先建「单字 → 该字的编码」索引：**只用码表的单字条目**（key 长度 1），
 * 不借用多字词的编码；每个字优先取最短的、长度 ≥ 2 的码（如 在 = `z`/`zdut`
 * → 取 `zdut`，截前 2 位正是 `zd`；录 = `lue`/`lueu`/`luvu` → `lue` → `lu`），
 * 没有长度 ≥ 2 的码时才退到最短码。
 *
 * 再按词长拼码：
 * - 2 字词：各取前 2 码（2+2，如「现在」→ xm + zd = xmzd）；
 * - 3 字词：两个编码都生成——各字首码（1+1+1）与前两字首码 + 第三字前 2 码（1+1+2）；
 * - 4 字及以上：前三字首码 + 尾字首码（1+1+1+1）。
 *
 * 规则来自小鹤音形码表的统计验证（只用单字条目即可与词条真实编码吻合：
 * 现在→xmzd、目录→mulu、输入法→urf/urfa 均命中），换用其它码表方案时同样适用。
 */
internal object SchemeCodeIndex {

    /** 二字词反推单字码时，每字取前 2 码。 */
    private const val PER_CHAR_CODE_LENGTH = 2

    /** 编码允许出现的字符：方案码表的字母表（小写字母 + 少量符号）。 */
    private fun isCodeLike(code: String): Boolean =
        code.isNotEmpty() && code.all { it in 'a'..'z' || it == ';' || it == '\'' }

    /**
     * 从码表的**单字条目**建「单字 → 编码」索引。
     *
     * 只认单字条目（key 长度 1），**不借用多字词的编码**——词条的码是整词拼好的，
     * 拿它反推会让新词跟着旧词的编排走，而不是跟着方案的单字规则走。
     *
     * 取码规则：优先**最短的、长度 ≥ 2 的码**（如 在 = `z`/`zdut` → 取 `zdut`，
     * 截前 2 位正是 `zd`；可 = `k`/`kedk` → `kedk` → `ke`），
     * 没有长度 ≥ 2 的码时才退到最短码。
     *
     * @param entries 词条序列（词, 码），顺序无关
     */
    fun buildCharCodes(entries: Sequence<Pair<String, String>>): Map<String, String> {
        val singles = HashMap<String, MutableList<String>>()
        for ((word, code) in entries) {
            // 单字按码点数判定：增补平面汉字占两个 char，用 length 判会漏掉它们
            if (word.codePointCount(0, word.length) != 1 || !isCodeLike(code)) continue
            singles.getOrPut(word) { mutableListOf() }.add(code)
        }
        val result = HashMap<String, String>(singles.size)
        for ((ch, codes) in singles) {
            val long = codes.filter { it.length >= PER_CHAR_CODE_LENGTH }.minByOrNull { it.length }
            result[ch] = long ?: codes.minByOrNull { it.length } ?: continue
        }
        return result
    }

    /**
     * 按 [charCodes] 给 [word] 拼出全部可用编码；任一字取不到码时返回空
     * （编不出可输入的码就不该造这个词）。
     *
     * - 1 字：该字本身的码；
     * - 2 字：各取前 2 码（2+2）；
     * - 3 字：**两个编码都要**——各字首码（1+1+1）与前两字首码 + 第三字前 2 码（1+1+2），
     *   让用户按哪种习惯打都能出词；
     * - 4 字及以上：前三字首码 + 尾字首码（1+1+1+1）。
     */
    fun composeCodes(word: String, charCodes: Map<String, String>): List<String> {
        if (word.isEmpty()) return emptyList()
        val chars = codePointStrings(word)
        val codes = chars.map { charCodes[it] ?: return emptyList() }
        val result = when (chars.size) {
            1 -> listOf(codes[0])
            2 -> listOf(codes[0].take(PER_CHAR_CODE_LENGTH) + codes[1].take(PER_CHAR_CODE_LENGTH))
            3 -> listOf(
                codes.joinToString("") { it.take(1) },
                codes[0].take(1) + codes[1].take(1) + codes[2].take(PER_CHAR_CODE_LENGTH),
            )
            // 4 字及以上：前三字首码 + 尾字首码（中间的字不取）
            else -> listOf(codes[0].take(1) + codes[1].take(1) + codes[2].take(1) + codes.last().take(1))
        }
        return result.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 按码点切分字符串（增补平面字符占两个 char，不能按 char 切）。
     * 手写遍历而非 `codePoints()`：后者是 Java 9 的 String API，低版本 Android 上没有。
     */
    private fun codePointStrings(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out += String(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        return out
    }
}
