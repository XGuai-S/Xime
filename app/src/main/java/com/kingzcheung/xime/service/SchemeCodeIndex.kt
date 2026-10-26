package com.kingzcheung.xime.service

/**
 * 组合生词的编码生成：按**启用方案自身的单字编码规则**给新词编一个打得出来的码。
 *
 * ## 为什么不能直接拼两个词的编码
 *
 * 「现在」=xmzd、「目录」=mulu，硬拼成 xmzdmulu 在该方案里既超长
 * （max_code_length=4）也没有任何意义；按方案规则编出的 xzml 才符合输入习惯。
 *
 * ## 造词基底：单字必须**满四码**
 *
 * 方案的单字条目里同时存在简码与全码（如 现 = `xm`/`xmw`/`xmwe`、在 = `z`/`zdut`、
 * 一 = `y`/`ob`/`yiaa`）。**只有满四码的条目才拿来做组合基底**——词库里的真实词条
 * 正是用满四码拼出来的：现 xmwe→xm + 在 zdut→zd = `xmzd`、目 muka→mu + 录 luvu→lu = `mulu`，
 * 输入法 `urf`/`urfa` 也全部命中。简码只是打单字时的省键输入，拿它当基底会编出
 * 用户实际打不出来的码（旧实现挑最短的 `ob`/`z` → 造出 `objm`、`xmz`，于是
 * 「一键」按 y+jmj 打永远匹配不上，表现为"组合生词失效"）。
 *
 * 任一字在方案里**没有满四码条目**时，整个词不造：编不出能打出来的码就不该进词库。
 *
 * ## 取码规则（三个总规则，与具体方案无关，任何码表方案通用）
 *
 * 按词长拼码，每个字从自己的满四码集合里各取一个，取尽所有组合：
 * - 2 字词：各取前 2 码（2+2，如「现在」→ xm + zd = xmzd）；
 * - 3 字词：两个编码都要——各字首码（1+1+1）与前两字首码 + 第三字前 2 码（1+1+2）；
 * - 4 字及以上：前三字首码 + 尾字首码（1+1+1+1）。
 *
 * 规则来自小鹤音形码表的统计验证（只用满四码的单字条目即可与词条真实编码吻合：
 * 现在→xmzd、目录→mulu、输入法→urf/urfa 均命中），换用其它码表方案时同样适用。
 */
internal object SchemeCodeIndex {

    /** 单字满四码才做组合基底：方案的单字全码长度。 */
    private const val FULL_CODE_LENGTH = 4

    /** 二字词反推单字码时，每字取前 2 码。 */
    private const val PER_CHAR_CODE_LENGTH = 2

    /**
     * 单字满四码集合上限：防御码表异常（一个字几十个满四码）导致组合爆炸。
     *
     * 真实方案里一个字通常只有 1–3 个满四码（多音/音形分码），8 已经非常宽松。
     */
    private const val MAX_FULL_CODES_PER_CHAR = 8

    /**
     * 单个词的组合编码上限。
     *
     * 笛卡尔积会迅速膨胀（4 字词每字 8 个码就是 4096 个组合），而造词词库对编码条数
     * 若不限，候选匹配会扫描过多编码，因此每个词只取最实用的前 [MAX_CODES_PER_WORD] 个。
     * 截断前按「短码优先」排序：短码既更接近用户实际敲的键数，也让 [composeCodes] 的
     * firstOrNull 直接就是最实用的那个码（词频兜底取它）。
     */
    private const val MAX_CODES_PER_WORD = 16

    /** 编码允许出现的字符：方案码表的字母表（小写字母 + 少量符号）。 */
    private fun isCodeLike(code: String): Boolean =
        code.isNotEmpty() && code.all { it in 'a'..'z' || it == ';' || it == '\'' }

    /**
     * 从码表的**单字条目**建「单字 → 该字全部满四码」索引。
     *
     * 只认单字条目（key 长度 1），**不借用多字词的编码**——词条的码是整词拼好的，
     * 拿它反推会让新词跟着旧词的编排走，而不是跟着方案的单字规则走。
     *
     * 每字只保留**满 [FULL_CODE_LENGTH] 码**的条目（去重、按字典序稳定排序，截上限
     * [MAX_FULL_CODES_PER_CHAR]）。满四码才是方案的完整单字编码，也是词库真实词条的
     * 拼接基底；简码（1–3 码）只是省键输入，不参与造词。
     *
     * @param entries 词条序列（词, 码），顺序无关
     * @return 只有满四码的字才会出现在结果里；没有满四码的字 = 不能参与组合造词
     */
    fun buildCharCodes(entries: Sequence<Pair<String, String>>): Map<String, List<String>> {
        val singles = HashMap<String, MutableSet<String>>()
        for ((word, code) in entries) {
            // 单字按码点数判定：增补平面汉字占两个 char，用 length 判会漏掉它们
            if (word.codePointCount(0, word.length) != 1) continue
            if (!isCodeLike(code) || code.length != FULL_CODE_LENGTH) continue
            singles.getOrPut(word) { mutableSetOf() }.add(code)
        }
        val result = HashMap<String, List<String>>(singles.size)
        for ((ch, codes) in singles) {
            result[ch] = codes.sorted().take(MAX_FULL_CODES_PER_CHAR)
        }
        return result
    }

    /**
     * 按 [charCodes] 给 [word] 拼出全部可用编码；任一字没有满四码条目时返回空
     * （编不出能打出来的码就不该造这个词）。
     *
     * 组合总规则（三档，按词长分）：
     * - 2 字：各取前 2 码（2+2）；
     * - 3 字：**两个编码都要**——各字首码（1+1+1）与前两字首码 + 第三字前 2 码（1+1+2）；
     * - 4 字及以上：前三字首码 + 尾字首码（1+1+1+1）。
     *
     * 每个字用它在方案里的**全部满四码**参与组合（笛卡尔积），因此一个词会得到
     * 多个编码——多音字按哪个全码打都能命中（「输入法」→ urf/urfa）。
     * 组合总数按 [MAX_CODES_PER_WORD] 截断，防止码表异常时炸开。
     */
    fun composeCodes(word: String, charCodes: Map<String, List<String>>): List<String> {
        if (word.isEmpty()) return emptyList()
        val chars = codePointStrings(word)
        // 组合生词至少两字：单字本身不需要造（调用方的词频/造词门槛也是 ≥2 字）
        if (chars.size < 2) return emptyList()
        val perCharCodes: List<List<String>> = chars.map { ch ->
            charCodes[ch]?.takeIf { it.isNotEmpty() } ?: return emptyList()
        }
        val result: List<String> = when (chars.size) {
            2 -> combine(perCharCodes[0].map { it.take(PER_CHAR_CODE_LENGTH) },
                         perCharCodes[1].map { it.take(PER_CHAR_CODE_LENGTH) })
            3 -> combine(
                perCharCodes[0].map { it.take(1) },
                perCharCodes[1].map { it.take(1) },
                perCharCodes[2].map { it.take(1) },
            ) + combine(
                perCharCodes[0].map { it.take(1) },
                perCharCodes[1].map { it.take(1) },
                perCharCodes[2].map { it.take(PER_CHAR_CODE_LENGTH) },
            )
            // 4 字及以上：前三字首码 + 尾字首码（中间的字不取）
            else -> combine(perCharCodes[0].map { it.take(1) },
                            perCharCodes[1].map { it.take(1) },
                            perCharCodes[2].map { it.take(1) },
                            perCharCodes.last().map { it.take(1) })
        }
        return result
            .filter { it.isNotEmpty() }
            .distinct()
            // 短码优先，超上限时截断：既限制词库编码条数，也让 firstOrNull 是最实用的码
            .sortedWith(compareBy<String> { it.length }.thenBy { it })
            .take(MAX_CODES_PER_WORD)
    }

    /** 多组编码做笛卡尔积拼接（2/3/4 组），空组直接导致空结果。 */
    private fun combine(vararg groups: List<String>): List<String> {
        var acc = listOf("")
        for (group in groups) {
            if (group.isEmpty()) return emptyList()
            acc = acc.flatMap { prefix -> group.map { prefix + it } }
        }
        return acc
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