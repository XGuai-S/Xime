package com.kingzcheung.xime.ai.data

object SnippetMatcher {

    private val LEADING_POLITE_REGEX = Regex(
        "^(呃+|啊+|嗯+|那个|就是|请帮我|帮我|麻烦|请|立?即)+"
    )
    private val LEADING_ACTION_VERB_REGEX = Regex(
        "^(呃+|啊+|嗯+|那个|就是|请帮我|帮我|麻烦|请|立?即)*(输入|发送|插入|展开|打上|打出|粘贴|发)(一下)?"
    )
    private val PUNCT_AND_SPACE_REGEX = Regex("[\\s,，.。!！?？;；:：\"'“”‘’、~～·\\-_]+")

    /**
     * Matches a spoken dictation utterance against enabled [SnippetEntry] voice triggers.
     * Only triggers when the entire utterance (modulo polite prefix / action verb / punctuation)
     * matches a configured trigger phrase, so normal sentences mentioning "邮箱" are never hijacked.
     */
    fun matchVoiceTranscript(
        rawTranscript: String,
        snippets: List<SnippetEntry>
    ): SnippetEntry? {
        if (rawTranscript.isBlank() || snippets.isEmpty()) return null
        val trimmed = rawTranscript.trim()
        val exactNormalized = normalizePhrase(trimmed)
        if (exactNormalized.isEmpty()) return null

        val withoutPoliteNormalized = normalizePhrase(trimmed.replaceFirst(LEADING_POLITE_REGEX, ""))
        val withoutVerbNormalized = normalizePhrase(trimmed.replaceFirst(LEADING_ACTION_VERB_REGEX, ""))

        for (snippet in snippets) {
            if (!snippet.enabled || snippet.content.isBlank()) continue
            val triggers = parseTriggerList(snippet.voiceTriggers)
            for (trigger in triggers) {
                val normTrigger = normalizePhrase(trigger)
                if (normTrigger.length < 2) continue
                if (exactNormalized == normTrigger ||
                    (withoutPoliteNormalized.isNotEmpty() && withoutPoliteNormalized == normTrigger) ||
                    (withoutVerbNormalized.isNotEmpty() && withoutVerbNormalized == normTrigger)
                ) {
                    return snippet
                }
            }
        }
        return null
    }

    fun parseTriggerList(rawTriggers: String): List<String> {
        return rawTriggers
            .split(',', '，', '、', ';', '；', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun normalizeFlypyCode(rawCode: String): String {
        val clean = rawCode.trim().lowercase()
        if (clean.isEmpty()) return ""
        val filtered = buildString {
            clean.forEachIndexed { idx, c ->
                if ((idx == 0 && c == ';') || c in 'a'..'z') {
                    append(c)
                }
            }
        }
        return filtered.take(5)
    }

    /**
     * Parses inline `==` Ask-AI query in an input field (e.g. `==帮我用英文礼貌催一下合同进度`).
     * Returns the extracted query string when valid, or `null` if not an `==` command.
     */
    @Deprecated("Replaced in v3.5.0 by top bar [🔍 问AI] capsule and AskAiCardPanel; kept for unit test compatibility.")
    fun extractEqualEqualAskAiQuery(editorText: String): String? {
        val trimmed = editorText.trim()
        val body = when {
            trimmed.startsWith("==") -> trimmed.removePrefix("==")
            trimmed.startsWith("＝＝") -> trimmed.removePrefix("＝＝")
            trimmed.startsWith("=＝") -> trimmed.removePrefix("=＝")
            trimmed.startsWith("＝=") -> trimmed.removePrefix("＝=")
            else -> return null
        }.trim()
        // Avoid triggering on `===` divider lines or bare `==`
        if (body.length < 2 || body.all { it == '=' || it == '＝' }) return null
        return body
    }

    private fun normalizePhrase(input: String): String {
        return input.trim()
            .lowercase()
            .replace(PUNCT_AND_SPACE_REGEX, "")
    }
}
