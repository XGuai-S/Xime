package com.kingzcheung.xime.ai.jev

import com.google.gson.JsonParser
import com.kingzcheung.xime.ai.privacy.RedactionSession
import kotlin.math.roundToInt

/**
 * 3-Tier Anti-Fragile Parser (`JevResponseParser`) for `✦ Jev 军师`:
 * 1. Tier 1 (Primary): `<jev><lv>1-9</lv><tag>...</tag><subtext>...</subtext><opt label="..." prob="...">...</opt></jev>`
 *    XML tags — non-crossing inner `<opt>` matcher + hard residual tag stripping, 100% immune to
 *    unescaped quotes or nested `<opt><opt label="...">` hallucinations.
 * 2. Tier 2 (Secondary): First `{...}` JSON object fallback (compatible with `lv`/`level`,
 *    `tag`/`category`, `subtext`/`analysis`, `options`/`strategies`, `prob`/`probability`/`weight`).
 * 3. Tier 3 (Tertiary): Numbered / line-split plain text fallback with default `Lv.4 局势分析`,
 *    guaranteeing 1–3 selectable strategy cards on any custom API or small local `Ollama` model.
 * 4. Mathematical probability normalization (`sum == 100%`, sharpening uncalibrated high scores
 *    like `95/90/85`, fallback `56/28/16`) and descending sort so Card 1 is always the top pick.
 */
object JevResponseParser {

    private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val UNCLOSED_THINK_SUFFIX_REGEX = Regex("<think>[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val CODE_FENCE_REGEX = Regex("```(?:xml|json|text)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val UNCLOSED_CODE_FENCE_PREFIX_REGEX = Regex("^```(?:xml|json|text)?\\s*", RegexOption.IGNORE_CASE)

    private val LV_TAG_REGEX = Regex("<lv>\\s*(?:lv\\.?\\s*)?([1-9])\\s*</lv>", RegexOption.IGNORE_CASE)
    private val TAG_ELEM_REGEX = Regex("<tag>([\\s\\S]*?)</tag>", RegexOption.IGNORE_CASE)
    private val SUBTEXT_ELEM_REGEX = Regex("<subtext>([\\s\\S]*?)</subtext>", RegexOption.IGNORE_CASE)
    private val WEIGHTS_ELEM_REGEX = Regex("<weights>([\\s\\S]*?)</weights>", RegexOption.IGNORE_CASE)

    /**
     * Non-crossing inner `<opt>` matcher: forbids crossing any nested `<opt` or `</opt` boundary
     * so `<opt><opt label="主动承接">正文</opt></opt>` always matches the innermost `<opt>` cleanly.
     */
    private val OPT_ELEM_REGEX = Regex(
        """<opt\b([^>]*)>((?:(?!</?opt\b)[\s\S])*?)</opt>""",
        RegexOption.IGNORE_CASE
    )

    private val PRECEDING_OPEN_OPT_REGEX = Regex(
        """<opt\b([^>]*)>\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val LABEL_ATTR_REGEX = Regex(
        """\blabel\s*=\s*(?:"([^"]*)"|'([^']*)'|“([^”]*)”|‘([^’]*)’|「([^」]*)」|([^\s"'“”‘’「」>]+))""",
        RegexOption.IGNORE_CASE
    )

    private val PROB_ATTR_REGEX = Regex(
        """\b(?:prob|probability|weight)\s*=\s*(?:"([^"]*)"|'([^']*)'|“([^”]*)”|‘([^’]*)’|([^\s"'“”‘’>]+))""",
        RegexOption.IGNORE_CASE
    )

    private val RESIDUAL_JEV_XML_TAG_REGEX = Regex(
        """</?\s*(?:opt|jev|lv|tag|subtext|weights)\b[^>]*>?""",
        RegexOption.IGNORE_CASE
    )

    private val NUMBERED_LINE_REGEX = Regex(
        """^\s*(?:[1-3][.、)）]|①|②|③|[-•*])\s*(?:[\[【]([^\]】]{1,10})[\]】]\s*[:：]?\s*)?(.+)$"""
    )
    private val BRACKET_PREFIX_REGEX = Regex("""^\s*[\[【]([^\]】]{1,10})[\]】]\s*[:：]?\s*(.+)$""")

    private val DEFAULT_FALLBACK_LABELS = listOf("稳妥顺接", "温和设限", "高维反客")
    private val DEFAULT_PROB_3 = listOf(56, 28, 16)
    private val DEFAULT_PROB_2 = listOf(64, 36)

    fun parse(
        rawModelOutput: String,
        redactionSession: RedactionSession? = null,
        usedVision: Boolean = false,
        redactionCount: Int = redactionSession?.redactionCount ?: 0
    ): JevCopilotResult {
        val cleaned = sanitizeRawOutput(rawModelOutput)

        val rawResult = parseXmlTier1(cleaned)
            ?: parseJsonTier2(cleaned)
            ?: parsePlainTextTier3(cleaned)

        val cleanedOptions = rawResult.options.take(3).mapIndexed { index, opt ->
            val cleanLabel = opt.label
                .replace(RESIDUAL_JEV_XML_TAG_REGEX, "")
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("“", "”")
                .removeSurrounding("'")
                .trim()
                .ifEmpty {
                    DEFAULT_FALLBACK_LABELS.getOrElse(index) { "策略 ${index + 1}" }
                }
                .take(8)
            val cleanReply = cleanOptionText(opt.replyText)
            val restoredReply = if (redactionSession != null) {
                cleanOptionText(redactionSession.restoreSafePlaceholders(cleanReply))
            } else {
                cleanReply
            }
            JevStrategyOption(
                label = cleanLabel,
                replyText = restoredReply,
                probability = opt.probability
            )
        }

        val finalOptions = normalizeAndSortOptions(cleanedOptions)

        return rawResult.copy(
            level = rawResult.level.coerceIn(1, 9),
            tag = rawResult.tag.replace(RESIDUAL_JEV_XML_TAG_REGEX, "").trim().ifEmpty { "局势分析" }.take(12),
            subtext = rawResult.subtext.replace(RESIDUAL_JEV_XML_TAG_REGEX, "").trim().ifEmpty { "已结合当前上下文提炼高筹码回复方案" }.take(96),
            options = finalOptions,
            usedVision = usedVision,
            redactionCount = redactionCount
        )
    }

    fun parseStreamingPreview(
        rawPartialOutput: String,
        redactionSession: RedactionSession? = null,
        usedVision: Boolean = false,
        redactionCount: Int = redactionSession?.redactionCount ?: 0
    ): JevCopilotResult? {
        val cleaned = sanitizeRawOutput(rawPartialOutput)
        if (cleaned.isBlank()) return null

        val hasCompleteSubtext = SUBTEXT_ELEM_REGEX.containsMatchIn(cleaned)
        val optMatches = OPT_ELEM_REGEX.findAll(cleaned).toList()
        if (!hasCompleteSubtext && optMatches.isEmpty()) {
            return null
        }

        val level = LV_TAG_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 9) ?: 4
        val tag = TAG_ELEM_REGEX.find(cleaned)?.groupValues?.getOrNull(1)
            ?.replace("\n", " ")
            ?.replace(RESIDUAL_JEV_XML_TAG_REGEX, "")
            ?.trim()
            .orEmpty()
            .ifEmpty { "局势分析" }
            .take(12)
        val subtext = SUBTEXT_ELEM_REGEX.find(cleaned)?.groupValues?.getOrNull(1)
            ?.replace("\n", " ")
            ?.replace(RESIDUAL_JEV_XML_TAG_REGEX, "")
            ?.trim()
            .orEmpty()
            .ifEmpty { "正在拆解对方潜台词与策略..." }
            .take(96)

        val cleanedOptions = optMatches.take(3).mapIndexedNotNull { idx, m ->
            val innerAttrs = m.groupValues[1]
            val prefixBeforeMatch = if (m.range.first > 0) cleaned.substring(0, m.range.first) else ""
            val outerAttrs = PRECEDING_OPEN_OPT_REGEX.find(prefixBeforeMatch)?.groupValues?.getOrNull(1).orEmpty()

            val rawLabel = extractLabelFromAttrs(innerAttrs)
                ?: extractLabelFromAttrs(outerAttrs)
                ?: DEFAULT_FALLBACK_LABELS.getOrElse(idx) { "策略 ${idx + 1}" }
            val cleanLabel = rawLabel
                .replace(RESIDUAL_JEV_XML_TAG_REGEX, "")
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("“", "”")
                .removeSurrounding("'")
                .trim()
                .ifEmpty { DEFAULT_FALLBACK_LABELS.getOrElse(idx) { "策略 ${idx + 1}" } }
                .take(8)
            val prob = extractProbFromAttrs(innerAttrs).takeIf { it > 0 }
                ?: extractProbFromAttrs(outerAttrs)
            val body = cleanOptionText(m.groupValues[2])
            if (body.isBlank()) {
                null
            } else {
                val restoredBody = if (redactionSession != null) {
                    cleanOptionText(redactionSession.restoreSafePlaceholders(body))
                } else {
                    body
                }
                JevStrategyOption(label = cleanLabel, replyText = restoredBody, probability = prob)
            }
        }

        val postHocWeights = extractPostHocWeights(cleaned)
        val hasClosedWeights = WEIGHTS_ELEM_REGEX.containsMatchIn(cleaned)
        val finalOptions = if (hasClosedWeights && cleanedOptions.isNotEmpty()) {
            val weighted = cleanedOptions.mapIndexed { idx, opt ->
                val postHoc = postHocWeights.getOrNull(idx) ?: 0
                if (postHoc > 0) opt.copy(probability = postHoc) else opt
            }
            normalizeAndSortOptions(weighted)
        } else {
            cleanedOptions
        }

        return JevCopilotResult(
            level = level,
            tag = tag,
            subtext = subtext,
            options = finalOptions,
            usedVision = usedVision,
            redactionCount = redactionCount
        )
    }

    /**
     * Normalizes strategy recommendation weights so the returned options sum strictly to `100%`
     * and sorts them in descending order of [JevStrategyOption.probability]:
     * - When all weights are `<= 0` (omitted or Tier 3 fallback), assigns calibrated defaults
     *   (`56 / 28 / 16` for 3 options, `64 / 36` for 2 options, `100` for 1 option).
     * - When unnormalized high scores with flat spread are detected (`sum > 140 && maxW - minW <= 15`,
     *   e.g. `95 / 90 / 85`), sharpens via `(w_i - minW + 8)` before normalizing so the top card
     *   retains a clear decision gradient instead of collapsing to `35% / 33% / 32%`.
     * - Compensates rounding remainder on the highest-weight option so `sum == 100`.
     */
    fun normalizeAndSortOptions(options: List<JevStrategyOption>): List<JevStrategyOption> {
        val capped = options.take(3)
        if (capped.isEmpty()) return emptyList()
        if (capped.size == 1) {
            return listOf(capped[0].copy(probability = 100))
        }

        val defaults = if (capped.size == 2) DEFAULT_PROB_2 else DEFAULT_PROB_3
        val rawWeights = capped.map { it.probability.coerceAtLeast(0) }

        val effectiveWeights: List<Int> = when {
            rawWeights.all { it <= 0 } -> defaults
            rawWeights.any { it <= 0 } -> {
                val knownSum = rawWeights.filter { it > 0 }.sum()
                val missingIndices = rawWeights.indices.filter { rawWeights[it] <= 0 }
                val missingDefaultSum = missingIndices.sumOf { defaults.getOrElse(it) { 20 } }.coerceAtLeast(1)
                val remainingBudget = (100 - knownSum).coerceAtLeast(missingIndices.size * 12)
                rawWeights.mapIndexed { idx, w ->
                    if (w > 0) {
                        w
                    } else {
                        val slotDef = defaults.getOrElse(idx) { 20 }
                        ((remainingBudget.toDouble() * slotDef) / missingDefaultSum).roundToInt().coerceAtLeast(1)
                    }
                }
            }
            else -> {
                val sum = rawWeights.sum()
                val maxW = rawWeights.maxOrNull() ?: 0
                val minW = rawWeights.minOrNull() ?: 0
                if (maxW == minW) {
                    defaults
                } else if (sum > 140 && (maxW - minW) <= 15) {
                    rawWeights.map { w -> (w - minW + 8).coerceAtLeast(1) }
                } else {
                    rawWeights
                }
            }
        }

        val weightSum = effectiveWeights.sum().coerceAtLeast(1)
        val normalized = effectiveWeights.map { w ->
            ((w * 100.0) / weightSum).roundToInt().coerceAtLeast(1)
        }.toMutableList()

        val diff = 100 - normalized.sum()
        if (diff != 0) {
            val maxIdx = effectiveWeights.indices.maxByOrNull { effectiveWeights[it] } ?: 0
            normalized[maxIdx] = (normalized[maxIdx] + diff).coerceAtLeast(1)
        }

        return capped.mapIndexed { idx, opt ->
            opt.copy(probability = normalized[idx])
        }.sortedByDescending { it.probability }
    }

    private fun extractLabelFromAttrs(attrs: String): String? {
        if (attrs.isBlank()) return null
        val m = LABEL_ATTR_REGEX.find(attrs) ?: return null
        val raw = m.groups[1]?.value
            ?: m.groups[2]?.value
            ?: m.groups[3]?.value
            ?: m.groups[4]?.value
            ?: m.groups[5]?.value
            ?: m.groups[6]?.value
        return raw
            ?.replace(RESIDUAL_JEV_XML_TAG_REGEX, "")
            ?.trim()
            ?.removeSurrounding("“", "”")
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            ?.trim()
            ?.ifEmpty { null }
    }

    private fun extractProbFromAttrs(attrs: String): Int {
        if (attrs.isBlank()) return 0
        val m = PROB_ATTR_REGEX.find(attrs) ?: return 0
        val raw = m.groups[1]?.value
            ?: m.groups[2]?.value
            ?: m.groups[3]?.value
            ?: m.groups[4]?.value
            ?: m.groups[5]?.value
            ?: return 0
        val trimmed = raw.trim()
        // Guard against a weak model literally echoing template placeholder "P1"/"P2"/"P3" or "W1"/"W2"/"W3"
        if (trimmed.matches(Regex("^[pPwW][1-3]$"))) return 0
        val numStr = Regex("""(\d{1,3}(?:\.\d+)?)""").find(trimmed)?.groupValues?.getOrNull(1) ?: return 0
        return numStr.toDoubleOrNull()?.roundToInt()?.coerceAtLeast(0) ?: 0
    }

    private fun extractPostHocWeights(text: String): List<Int> {
        val content = WEIGHTS_ELEM_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (content.isEmpty()) return emptyList()
        return content.split(Regex("[,，/\\s|;；]+"))
            .mapNotNull { token ->
                val clean = token.trim().removeSuffix("%")
                if (clean.isEmpty() || clean.matches(Regex("^[wWpP][1-3]$"))) {
                    null
                } else {
                    Regex("""(\d{1,3}(?:\.\d+)?)""").find(clean)
                        ?.groupValues?.getOrNull(1)
                        ?.toDoubleOrNull()?.roundToInt()?.coerceAtLeast(0)
                }
            }
    }

    private fun sanitizeRawOutput(raw: String): String {
        var text = raw.replace(THINK_BLOCK_REGEX, "")
            .replace(UNCLOSED_THINK_SUFFIX_REGEX, "")
            .trim()
        val fenceMatch = CODE_FENCE_REGEX.find(text)
        if (fenceMatch != null && (text.startsWith("```") || text.contains("<jev") || text.contains("{"))) {
            val inner = fenceMatch.groupValues[1].trim()
            if (inner.isNotEmpty()) {
                text = inner
            }
        } else if (text.startsWith("```")) {
            text = text.replace(UNCLOSED_CODE_FENCE_PREFIX_REGEX, "")
        }
        return text.trim()
    }

    internal fun parseXmlTier1(text: String): JevCopilotResult? {
        val optMatches = OPT_ELEM_REGEX.findAll(text).toList()
        if (optMatches.isEmpty()) return null

        val level = LV_TAG_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 9) ?: 4
        val tag = TAG_ELEM_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.replace("\n", " ")
            .orEmpty()
            .ifEmpty { "局势分析" }
        val subtext = SUBTEXT_ELEM_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
            ?.replace("\n", " ")
            .orEmpty()
            .ifEmpty { "已拆解对方潜台词并生成差异化应对策略" }

        val postHocWeights = extractPostHocWeights(text)

        val rawOptions = optMatches.mapIndexedNotNull { idx, m ->
            val innerAttrs = m.groupValues[1]
            val prefixBeforeMatch = if (m.range.first > 0) text.substring(0, m.range.first) else ""
            val outerAttrs = PRECEDING_OPEN_OPT_REGEX.find(prefixBeforeMatch)?.groupValues?.getOrNull(1).orEmpty()

            val label = extractLabelFromAttrs(innerAttrs)
                ?: extractLabelFromAttrs(outerAttrs)
                ?: DEFAULT_FALLBACK_LABELS.getOrElse(idx) { "策略 ${idx + 1}" }
            val prob = extractProbFromAttrs(innerAttrs).takeIf { it > 0 }
                ?: extractProbFromAttrs(outerAttrs)
            val body = cleanOptionText(m.groupValues[2])
            if (body.isBlank()) {
                null
            } else {
                JevStrategyOption(label = label, replyText = body, probability = prob)
            }
        }

        if (rawOptions.isEmpty()) return null

        val options = rawOptions.mapIndexed { idx, opt ->
            val postHoc = postHocWeights.getOrNull(idx) ?: 0
            if (postHoc > 0) {
                opt.copy(probability = postHoc)
            } else {
                opt
            }
        }

        return JevCopilotResult(
            level = level,
            tag = tag,
            subtext = subtext,
            options = options.take(3)
        )
    }

    internal fun parseJsonTier2(text: String): JevCopilotResult? {
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) return null

        return try {
            val jsonCandidate = text.substring(firstBrace, lastBrace + 1)
            val obj = JsonParser.parseString(jsonCandidate).asJsonObject

            val rawLv = obj.get("lv") ?: obj.get("level")
            val level = when {
                rawLv == null || rawLv.isJsonNull -> 4
                rawLv.isJsonPrimitive && rawLv.asJsonPrimitive.isNumber -> rawLv.asInt
                rawLv.isJsonPrimitive -> {
                    Regex("([1-9])").find(rawLv.asString)?.groupValues?.get(1)?.toIntOrNull() ?: 4
                }
                else -> 4
            }.coerceIn(1, 9)

            val tag = (obj.get("tag") ?: obj.get("category") ?: obj.get("title"))
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                .ifEmpty { "局势分析" }

            val subtext = (obj.get("subtext") ?: obj.get("analysis") ?: obj.get("intent"))
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                .ifEmpty { "已拆解对方潜台词并生成差异化应对策略" }

            val arr = (obj.getAsJsonArray("options")
                ?: obj.getAsJsonArray("strategies")
                ?: obj.getAsJsonArray("replies"))
                ?: return null

            val options = mutableListOf<JevStrategyOption>()
            for (i in 0 until arr.size()) {
                val elem = arr[i]
                val defaultLabel = DEFAULT_FALLBACK_LABELS.getOrElse(i) { "策略 ${i + 1}" }
                if (elem.isJsonObject) {
                    val itemObj = elem.asJsonObject
                    val label = (itemObj.get("label") ?: itemObj.get("tag") ?: itemObj.get("title"))
                        ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                        .ifEmpty { defaultLabel }
                    val reply = (itemObj.get("reply")
                        ?: itemObj.get("text")
                        ?: itemObj.get("replyText")
                        ?: itemObj.get("content"))
                        ?.takeIf { it.isJsonPrimitive }?.asString?.let(::cleanOptionText).orEmpty()
                    val rawProb = itemObj.get("prob") ?: itemObj.get("probability") ?: itemObj.get("weight")
                    val prob = when {
                        rawProb == null || rawProb.isJsonNull -> 0
                        rawProb.isJsonPrimitive && rawProb.asJsonPrimitive.isNumber ->
                            rawProb.asDouble.roundToInt().coerceAtLeast(0)
                        rawProb.isJsonPrimitive ->
                            Regex("""(\d{1,3}(?:\.\d+)?)""").find(rawProb.asString)
                                ?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.roundToInt()?.coerceAtLeast(0) ?: 0
                        else -> 0
                    }
                    if (reply.isNotBlank()) {
                        options.add(JevStrategyOption(label = label, replyText = reply, probability = prob))
                    }
                } else if (elem.isJsonPrimitive) {
                    val rawLine = cleanOptionText(elem.asString)
                    if (rawLine.isNotBlank()) {
                        val bracket = BRACKET_PREFIX_REGEX.find(rawLine)
                        if (bracket != null) {
                            options.add(
                                JevStrategyOption(
                                    label = bracket.groupValues[1].trim(),
                                    replyText = cleanOptionText(bracket.groupValues[2])
                                )
                            )
                        } else {
                            options.add(JevStrategyOption(label = defaultLabel, replyText = rawLine))
                        }
                    }
                }
            }

            if (options.isEmpty()) return null
            JevCopilotResult(
                level = level,
                tag = tag,
                subtext = subtext,
                options = options.take(3)
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun parsePlainTextTier3(text: String): JevCopilotResult {
        val rawLines = text
            .replace(Regex("</?[a-zA-Z][^>]*>"), " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val options = mutableListOf<JevStrategyOption>()
        var detectedSubtext = ""

        for (line in rawLines) {
            val numbered = NUMBERED_LINE_REGEX.find(line)
            if (numbered != null) {
                val label = numbered.groupValues[1].trim().ifEmpty {
                    DEFAULT_FALLBACK_LABELS.getOrElse(options.size) { "策略 ${options.size + 1}" }
                }
                val body = cleanOptionText(numbered.groupValues[2])
                if (body.isNotBlank()) {
                    options.add(JevStrategyOption(label = label, replyText = body))
                }
                continue
            }
            val bracket = BRACKET_PREFIX_REGEX.find(line)
            if (bracket != null) {
                val label = bracket.groupValues[1].trim()
                val body = cleanOptionText(bracket.groupValues[2])
                if (body.isNotBlank()) {
                    options.add(JevStrategyOption(label = label, replyText = body))
                    continue
                }
            }
            if (options.isEmpty() && detectedSubtext.isEmpty() &&
                (line.startsWith("潜台词") || line.startsWith("局势") || line.startsWith("分析"))
            ) {
                detectedSubtext = line.replace(Regex("^(?:潜台词|局势|分析)\\s*[:：]?\\s*"), "").trim()
            } else {
                val cleanedLine = cleanOptionText(line)
                if (cleanedLine.isNotBlank()) {
                    options.add(
                        JevStrategyOption(
                            label = DEFAULT_FALLBACK_LABELS.getOrElse(options.size) { "策略 ${options.size + 1}" },
                            replyText = cleanedLine
                        )
                    )
                }
            }
        }

        if (options.isEmpty()) {
            val fallbackSingle = cleanOptionText(text).ifBlank { "收到，我先确认一下细节稍后回复你。" }
            options.add(
                JevStrategyOption(
                    label = DEFAULT_FALLBACK_LABELS[0],
                    replyText = fallbackSingle
                )
            )
        }

        return JevCopilotResult(
            level = 4,
            tag = "局势分析",
            subtext = detectedSubtext.ifBlank { "已根据当前上下文生成可直接点选的回复草稿" },
            options = options.take(3)
        )
    }

    internal fun cleanOptionText(raw: String): String {
        return raw
            .replace(WEIGHTS_ELEM_REGEX, "")
            .replace(RESIDUAL_JEV_XML_TAG_REGEX, "")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("“", "”")
            .removeSurrounding("「", "」")
            .replace(WEIGHTS_ELEM_REGEX, "")
            .replace(RESIDUAL_JEV_XML_TAG_REGEX, "")
            .trim()
    }
}
