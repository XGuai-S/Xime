package com.kingzcheung.xime.ai.ime.clipboard

data class BigBangToken(
    val index: Int,
    val text: String,
    val isPunctuation: Boolean = false,
    val isEntity: Boolean = false
)

/**
 * Zero-bloat local "BigBang (大爆炸)" word & entity tokenizer for the IME Clipboard Panel.
 *
 * Splits any clipboard entry into clickable word/entity chips:
 * 1. Intact entities (URLs, emails, hyphenated pickup codes like `3-2-1048`, phone numbers, alphanumeric words)
 *    are preserved as single tokens (`isEntity = true`).
 * 2. Chinese text spans are segmented via Maximum Forward Matching (up to 4 chars) using both the built-in
 *    common vocabulary and an optional caller-supplied lexicon lookup (e.g. `FlypyDictionary` / user phrases).
 * 3. Punctuation marks are tagged with `isPunctuation = true` so users can toggle or skip punctuation in one tap.
 */
object ClipboardBigBangTokenizer {

    private val ENTITY_SPAN_REGEX = Regex(
        "https?://[^\\s<>\"'，。！？；：）】\\]]+|" +
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}|" +
            "[A-Za-z0-9]{1,4}(?:-[A-Za-z0-9]{1,6}){1,2}|" +
            "[A-Za-z0-9]+(?:[._\\-][A-Za-z0-9]+)*"
    )

    private val COMMON_CN_WORDS: Set<String> = setOf(
        // 快递 / 取件 / 地址常见词
        "菜鸟驿站", "丰巢柜", "快递柜", "快递员", "取件码", "提货码", "收发室", "验证码", "运单号", "快递单号",
        "丰巢", "菜鸟", "驿站", "顺丰", "京东", "中通", "圆通", "韵达", "申通", "极兔", "邮政",
        "凭取件码", "取件", "包裹", "快递", "到了", "南门", "北门", "东门", "西门", "正门", "后门",
        "小区", "大厦", "广场", "中心", "软件园", "单元", "楼层", "收件人", "联系电话", "收货地址", "详细地址",
        // 沟通 / 办公 / 时间常见词
        "今天", "明天", "后天", "昨天", "上午", "中午", "下午", "晚上", "现在", "稍后", "马上", "尽快",
        "已经", "没有", "可以", "需要", "不用", "没问题", "好的", "收到", "谢谢", "麻烦", "请问", "联系",
        "有疑问", "疑问", "提醒", "发我", "给我", "一下", "什么", "怎么", "为什么", "因为", "所以", "但是",
        "目前", "发现", "各个", "房间", "面板", "程序", "更新", "天气", "中央空调", "强电箱", "管家", "升级",
        "手机", "联网", "一直", "回我", "管理员", "群主", "业主群", "交流群", "会议", "议程", "评审", "文档",
        "账号", "密码", "链接", "地址", "电话", "邮箱", "微信", "支付宝", "银行卡", "发票", "报销", "合同"
    )

    /**
     * Tokenizes [rawText] into clickable [BigBangToken] items.
     *
     * @param rawText The clipboard text to segment.
     * @param wordLookup Optional dictionary predicate (returns true if a 2..4 char Chinese string is a known word).
     */
    fun tokenize(
        rawText: String,
        wordLookup: ((String) -> Boolean)? = null
    ): List<BigBangToken> {
        val text = rawText.trim()
        if (text.isEmpty()) return emptyList()

        val rawTokens = mutableListOf<Triple<String, Boolean, Boolean>>() // (token, isPunct, isEntity)
        var cursor = 0

        for (match in ENTITY_SPAN_REGEX.findAll(text)) {
            if (match.range.first > cursor) {
                segmentNonEntitySpan(
                    span = text.substring(cursor, match.range.first),
                    wordLookup = wordLookup,
                    out = rawTokens
                )
            }
            val entityToken = match.value
            rawTokens.add(Triple(entityToken, false, true))
            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            segmentNonEntitySpan(
                span = text.substring(cursor),
                wordLookup = wordLookup,
                out = rawTokens
            )
        }

        return rawTokens.mapIndexed { idx, (tok, isPunct, isEntity) ->
            BigBangToken(
                index = idx,
                text = tok,
                isPunctuation = isPunct,
                isEntity = isEntity
            )
        }
    }

    private fun segmentNonEntitySpan(
        span: String,
        wordLookup: ((String) -> Boolean)?,
        out: MutableList<Triple<String, Boolean, Boolean>>
    ) {
        var i = 0
        val n = span.length
        while (i < n) {
            val ch = span[i]
            if (ch.isWhitespace()) {
                i++
                continue
            }
            if (isChineseChar(ch)) {
                var matchedLen = 1
                val maxLen = minOf(4, n - i)
                for (len in maxLen downTo 2) {
                    val candidate = span.substring(i, i + len)
                    if ( candidate.all { isChineseChar(it) } &&
                        (COMMON_CN_WORDS.contains(candidate) || wordLookup?.invoke(candidate) == true)
                    ) {
                        matchedLen = len
                        break
                    }
                }
                out.add(Triple(span.substring(i, i + matchedLen), false, false))
                i += matchedLen
            } else {
                // Punctuation or symbol
                out.add(Triple(ch.toString(), true, false))
                i++
            }
        }
    }

    private fun isChineseChar(c: Char): Boolean {
        return c.code in 0x4E00..0x9FFF
    }

    /**
     * Joins the selected token indices in ascending order into a clean output string.
     */
    fun joinSelectedTokens(
        tokens: List<BigBangToken>,
        selectedIndices: Set<Int>
    ): String {
        if (selectedIndices.isEmpty()) return ""
        val ordered = tokens.filter { selectedIndices.contains(it.index) }.sortedBy { it.index }
        val sb = StringBuilder()
        for (k in ordered.indices) {
            val cur = ordered[k]
            if (k > 0) {
                val prev = ordered[k - 1]
                // Insert a space between two consecutive ASCII/entity words if neither is Chinese or punctuation
                if (prev.isEntity && cur.isEntity &&
                    prev.text.lastOrNull()?.isLetterOrDigit() == true &&
                    cur.text.firstOrNull()?.isLetterOrDigit() == true
                ) {
                    sb.append(' ')
                }
            }
            sb.append(cur.text)
        }
        return sb.toString()
    }
}
