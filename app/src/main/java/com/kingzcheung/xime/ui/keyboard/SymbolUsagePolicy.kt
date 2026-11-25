package com.kingzcheung.xime.ui.keyboard

/**
 * 运算符列的**按使用频率自适应排序**。
 *
 * ## 为什么
 *
 * 运算符列可滚动后容纳了 32 个符号，但绝大多数人日常只用得到其中几个。
 * 固定顺序意味着常用符号可能藏在下面要滑动才够得到。这里记录每个符号的点击次数，
 * 越常用的排越前，**点完立刻生效**（下一次渲染即为新顺序）。
 *
 * ## 顺序规则（纯函数，可单测）
 *
 * 1. 四则运算 `+ - * /` **永远固定在最前**，不参与排序：
 *    它们是肌肉记忆位置，跟着频率跳会让上手位置失稳。
 * 2. 其余符号按 `次数降序`，同次数按**默认表的原始顺序**（稳定排序）：
 *    没用过的符号保持新手友好的原始顺序，不会因为零次而乱序。
 *
 * ## 持久化
 *
 * 与 xime.yaml 同级（`symbol_usage.txt`），每行 `符号\t次数`。进程重启后保留习惯。
 */
internal object SymbolUsagePolicy {

    /** 四则运算：位置固定，不参与频率排序。 */
    internal val PINNED = listOf("+", "-", "*", "/")

    /** 默认符号表。`/` 之后按「日常使用频率从高到低」作为初始顺序（新装用户）。 */
    internal val DEFAULT_SYMBOLS = listOf(
        "+", "-", "*", "/",
        "=", "%", ".", ",",
        ":", ";", "(", ")",
        "!", "?", "[", "]",
        "{", "}", "<", ">",
        "_", "&", "#", "@",
        "|", "~", "^", "°",
        "±", "×", "÷", "\\",
    )

    internal const val FILE_NAME = "symbol_usage.txt"
    internal const val HEADER = "# 符号使用次数：一行一条 `符号\t次数`，用于运算符列自适应排序"

    /**
     * 计算显示顺序。
     *
     * @param counts 符号 → 累计点击次数（缺失视为 0 次）
     * @return 四则在前（固定顺序），其余按次数降序、同次数保持 [DEFAULT_SYMBOLS] 原序
     */
    fun orderedSymbols(counts: Map<String, Int>): List<String> {
        val rest = DEFAULT_SYMBOLS.filter { it !in PINNED }
        // sortedWith 是稳定排序：同次数时保留默认表顺序
        val sortedRest = rest.sortedWith(
            compareByDescending<String> { counts[it] ?: 0 }
        )
        return PINNED + sortedRest
    }

    /** 累计一次点击，返回新表（不改入参）。 */
    fun withIncrement(counts: Map<String, Int>, symbol: String): Map<String, Int> {
        if (symbol.isEmpty()) return counts
        val next = LinkedHashMap(counts)
        next[symbol] = (next[symbol] ?: 0) + 1
        return next
    }

    /** 解析持久化文本；格式不符或次数非正的行跳过。 */
    fun parse(text: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val symbol = parts[0]
            if (symbol.isEmpty()) continue
            val count = parts[1].trim().toIntOrNull() ?: continue
            if (count > 0) out[symbol] = count
        }
        return out
    }

    /** 序列化：按次数降序输出，便于人工查看哪些符号常用。 */
    fun serialize(counts: Map<String, Int>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        counts.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .forEach { (symbol, count) -> sb.append(symbol).append('\t').append(count).append('\n') }
        return sb.toString()
    }
}