package com.kingzcheung.xime.ai.ime.flypy

import com.kingzcheung.xime.ai.data.SnippetEntry
import com.kingzcheung.xime.ai.data.SnippetMatcher
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed interface FlypyAction {
    /** Keystroke updated or cleared composing state without committing text to the host editor. */
    data object ConsumedComposing : FlypyAction

    /** Commits [committed] to the host editor; if [remainComposing] is true, a new code is already active. */
    data class CommitText(
        val committed: String,
        val remainComposing: Boolean = false
    ) : FlypyAction
}

/**
 * Pure Kotlin state machine implementing authentic Xiaohe Yinxing (小鹤音形 / Flypy) rules:
 * - 4-code unique auto-commit (`四码唯一自动上屏`, `^\w{4}$`)
 * - 5th-code push-commit (`第五码顶字上屏`) & `auto_clear: max_length` on empty 4-codes
 * - `;x` quick-symbol 2-code auto-commit (`^;.$`, e.g. `;q -> ：“`, `;w -> ？`) & `;f` repeat last commit
 * - `;xx` / alpha custom snippet expansion (`口令短语展开`, e.g. `;sd`, `;yx`, `;dz`) with push-commit preservation
 * - `,` / `.` secondary & tertiary candidate quick-select (`次选 / 三选`) or punctuation top-up
 * - `` ` `` wildcard bidirectional reverse lookup (`知音查形 / 知形查音` with 4-code hints)
 * - Dynamic Lua-equivalent commands: `orq` (date), `ouj` (time), `oxq` (weekday)
 * - Shift clear-code (`有码清码`) & Enter raw-code commit (`回车上屏编码`)
 */
class FlypyEngine(
    initialDictionary: FlypyDictionary = FlypyDictionary.empty()
) {

    var dictionary: FlypyDictionary = initialDictionary
        private set

    private var customSnippets: List<SnippetEntry> = emptyList()

    var composingCode: String = ""
        private set

    var candidates: List<FlypyCandidate> = emptyList()
        private set

    var lastCommittedText: String = ""
        private set

    val isComposing: Boolean
        get() = composingCode.isNotEmpty()

    val isEmptyCode: Boolean
        get() = composingCode.isNotEmpty() &&
            candidates.isEmpty() &&
            !composingCode.all { it == '`' }

    fun updateDictionary(newDict: FlypyDictionary, nowMillis: Long = System.currentTimeMillis()) {
        dictionary = newDict
        if (composingCode.isNotEmpty()) {
            candidates = resolveCandidates(composingCode, nowMillis)
        }
    }

    fun updateSnippets(snippets: List<SnippetEntry>, nowMillis: Long = System.currentTimeMillis()) {
        customSnippets = snippets.mapNotNull { entry ->
            if (!entry.enabled || entry.content.isBlank()) return@mapNotNull null
            val normCode = SnippetMatcher.normalizeFlypyCode(entry.flypyCode)
            if (normCode.isEmpty()) null else entry.copy(flypyCode = normCode)
        }
        if (composingCode.isNotEmpty()) {
            candidates = resolveCandidates(composingCode, nowMillis)
        }
    }

    private fun hasCustomSnippetContinuation(prefix: String): Boolean {
        if (prefix.isEmpty()) return false
        return customSnippets.any { it.flypyCode.length > prefix.length && it.flypyCode.startsWith(prefix) }
    }

    fun resetComposing() {
        composingCode = ""
        candidates = emptyList()
    }

    fun clearComposing(): Boolean {
        if (!isComposing) return false
        resetComposing()
        return true
    }

    fun recordExternalCommit(text: String) {
        if (text.isNotBlank()) {
            lastCommittedText = text
        }
    }

    fun selectCandidate(index: Int): FlypyAction {
        val picked = candidates.getOrNull(index)?.text ?: return FlypyAction.ConsumedComposing
        resetComposing()
        lastCommittedText = picked
        return FlypyAction.CommitText(picked, remainComposing = false)
    }

    fun onBackspace(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isComposing) return false
        composingCode = composingCode.dropLast(1)
        candidates = if (composingCode.isEmpty()) {
            emptyList()
        } else {
            resolveCandidates(composingCode, nowMillis)
        }
        return true
    }

    fun onSpace(): FlypyAction {
        if (!isComposing) {
            return FlypyAction.CommitText(" ", remainComposing = false)
        }
        val top = candidates.firstOrNull()?.text
        resetComposing()
        return if (top != null) {
            lastCommittedText = top
            FlypyAction.CommitText(top, remainComposing = false)
        } else {
            // Empty code (`空码`): clear composing without inserting an unwanted space
            FlypyAction.ConsumedComposing
        }
    }

    /**
     * When composing, Enter commits the raw ASCII code (`回车上屏编码`); when idle, returns null
     * so the IME performs the standard editor action / newline.
     */
    fun onEnterWhenComposing(): String? {
        if (!isComposing) return null
        val raw = composingCode
        resetComposing()
        lastCommittedText = raw
        return raw
    }

    /**
     * Handles bottom-row `,` and `.` keys in Chinese (`鹤`) mode:
     * - Idle: outputs `，` or `。`
     * - Composing with [commaPeriodQuickSelect] = true: `,` selects 2nd candidate, `.` selects 3rd candidate
     *   (gracefully falling back to top-up `1st + ，/。` when only 1 candidate exists)
     * - Composing with [commaPeriodQuickSelect] = false: commits `1st + ，/。` (`标点顶屏`)
     */
    fun onCommaOrPeriod(isComma: Boolean, commaPeriodQuickSelect: Boolean): FlypyAction {
        val cnPunct = if (isComma) "，" else "。"
        if (!isComposing) {
            lastCommittedText = cnPunct
            return FlypyAction.CommitText(cnPunct, remainComposing = false)
        }

        if (candidates.isEmpty()) {
            resetComposing()
            return FlypyAction.ConsumedComposing
        }

        if (commaPeriodQuickSelect) {
            if (isComma && candidates.size >= 2) {
                val second = candidates[1].text
                resetComposing()
                lastCommittedText = second
                return FlypyAction.CommitText(second, remainComposing = false)
            }
            if (!isComma && candidates.size >= 3) {
                val third = candidates[2].text
                resetComposing()
                lastCommittedText = third
                return FlypyAction.CommitText(third, remainComposing = false)
            }
        }

        val topUp = candidates[0].text + cnPunct
        resetComposing()
        lastCommittedText = topUp
        return FlypyAction.CommitText(topUp, remainComposing = false)
    }

    /**
     * Flushes the 1st candidate if currently composing (used e.g. before switching to `123` layer or `EN`).
     */
    fun flushTopCandidateOrClear(): String {
        if (!isComposing) return ""
        val top = candidates.firstOrNull()?.text.orEmpty()
        resetComposing()
        if (top.isNotEmpty()) {
            lastCommittedText = top
        }
        return top
    }

    fun onCharInput(ch: Char, nowMillis: Long = System.currentTimeMillis()): FlypyAction {
        // 1. Semicolon (`;`) — Quick-symbol prefix when idle, secondary select (`分号次选`) when menu active
        if (ch == ';') {
            return when {
                composingCode.isEmpty() -> {
                    composingCode = ";"
                    candidates = resolveCandidates(";", nowMillis)
                    FlypyAction.ConsumedComposing
                }
                composingCode == ";" -> {
                    val semiSecond = candidates.getOrNull(1)?.text ?: "；"
                    resetComposing()
                    lastCommittedText = semiSecond
                    FlypyAction.CommitText(semiSecond, remainComposing = false)
                }
                candidates.size >= 2 -> {
                    val second = candidates[1].text
                    resetComposing()
                    lastCommittedText = second
                    FlypyAction.CommitText(second, remainComposing = false)
                }
                else -> {
                    val pushed = candidates.firstOrNull()?.text.orEmpty()
                    composingCode = ";"
                    candidates = resolveCandidates(";", nowMillis)
                    if (pushed.isNotEmpty()) {
                        lastCommittedText = pushed
                        FlypyAction.CommitText(pushed, remainComposing = true)
                    } else {
                        FlypyAction.ConsumedComposing
                    }
                }
            }
        }

        // 2. Backtick wildcard (`` ` `` via Long-Press Z) — Reverse lookup (`知音查形 / 知形查音`)
        if (ch == '`') {
            if (composingCode.startsWith(";")) {
                val pushed = candidates.firstOrNull()?.text.orEmpty()
                composingCode = "`"
                candidates = resolveCandidates("`", nowMillis)
                return if (pushed.isNotEmpty()) {
                    lastCommittedText = pushed
                    FlypyAction.CommitText(pushed, remainComposing = true)
                } else {
                    FlypyAction.ConsumedComposing
                }
            }
            if (composingCode.length < 4) {
                composingCode += '`'
                candidates = resolveCandidates(composingCode, nowMillis)
                return FlypyAction.ConsumedComposing
            }
            val pushed = candidates.firstOrNull()?.text.orEmpty()
            composingCode = "`"
            candidates = resolveCandidates("`", nowMillis)
            return if (pushed.isNotEmpty()) {
                lastCommittedText = pushed
                FlypyAction.CommitText(pushed, remainComposing = true)
            } else {
                FlypyAction.ConsumedComposing
            }
        }

        // 3. Uppercase letters (`Shift` / `Caps Lock`): commit English directly (`flypy.schema.yaml` `uppercase: ""`)
        if (ch in 'A'..'Z') {
            val pushed = flushTopCandidateOrClear()
            val out = pushed + ch
            lastCommittedText = out
            return FlypyAction.CommitText(out, remainComposing = false)
        }

        // 4. Non-alpha characters: flush any active candidate + commit character directly
        if (ch !in 'a'..'z') {
            val pushed = flushTopCandidateOrClear()
            val out = pushed + ch
            if (out.isNotBlank()) {
                lastCommittedText = out
            }
            return FlypyAction.CommitText(out, remainComposing = false)
        }

        // 5. `;x` Quick Symbol, `;f` Repeat & `;xx` Custom Snippets (`;sd`, `;yx`, `;dz`)
        if (composingCode.startsWith(";")) {
            if (composingCode.length >= 6) {
                val pushed = flushTopCandidateOrClear()
                val follow = onCharInput(ch, nowMillis)
                return mergePushedWithFollowUp(pushed, follow)
            }
            val nextCode = composingCode + ch
            val nextCands = resolveCandidates(nextCode, nowMillis)
            val hasMoreSnippet = hasCustomSnippetContinuation(nextCode)
            return when {
                nextCands.size == 1 && !hasMoreSnippet -> {
                    val committed = nextCands[0].text
                    resetComposing()
                    lastCommittedText = committed
                    FlypyAction.CommitText(committed, remainComposing = false)
                }
                nextCands.isEmpty() && !hasMoreSnippet -> {
                    if (composingCode == ";") {
                        resetComposing()
                        FlypyAction.ConsumedComposing
                    } else {
                        val pushed = flushTopCandidateOrClear()
                        val follow = onCharInput(ch, nowMillis)
                        mergePushedWithFollowUp(pushed, follow)
                    }
                }
                else -> {
                    composingCode = nextCode
                    candidates = nextCands
                    FlypyAction.ConsumedComposing
                }
            }
        }

        // 6. Wildcard Reverse-Lookup Composing (`composingCode` contains `` ` ``)
        if (composingCode.contains('`')) {
            if (composingCode.length < 4) {
                composingCode += ch
                candidates = resolveCandidates(composingCode, nowMillis)
                return FlypyAction.ConsumedComposing
            }
            // 5th key after 4-char wildcard pushes 1st reverse-lookup char and starts new code
            val pushed = candidates.firstOrNull()?.text.orEmpty()
            resetComposing()
            if (pushed.isNotEmpty()) {
                lastCommittedText = pushed
            }
            val follow = onCharInput(ch, nowMillis)
            return mergePushedWithFollowUp(pushed, follow)
        }

        // 7. Standard `a-z` Xiaohe Yinxing Composing (`max_code_length = 4`, plus `os...` & custom snippets)
        val canExtend = composingCode.length < 4 ||
            (composingCode.length < 6 && (
                dictionary.hasExtendedOsContinuation(composingCode) ||
                    hasCustomSnippetContinuation(composingCode)
                ))

        if (canExtend) {
            val nextCode = composingCode + ch
            val nextCands = resolveCandidates(nextCode, nowMillis)

            // 4-Code Unique Auto-Commit (`四码唯一自动上屏`)
            if (
                nextCode.length == 4 &&
                nextCands.size == 1 &&
                !dictionary.hasExtendedOsContinuation(nextCode) &&
                !hasCustomSnippetContinuation(nextCode)
            ) {
                val text = nextCands[0].text
                resetComposing()
                lastCommittedText = text
                return FlypyAction.CommitText(text, remainComposing = false)
            }

            // Custom alpha snippet unique auto-commit at 5-6 chars
            if (
                nextCode.length > 4 &&
                nextCands.size == 1 &&
                !dictionary.hasExtendedOsContinuation(nextCode) &&
                !hasCustomSnippetContinuation(nextCode)
            ) {
                val text = nextCands[0].text
                resetComposing()
                lastCommittedText = text
                return FlypyAction.CommitText(text, remainComposing = false)
            }

            composingCode = nextCode
            candidates = nextCands
            return FlypyAction.ConsumedComposing
        }

        // 8. 5th-Code Push-Commit (`第五码顶字上屏`) + `auto_clear: max_length` on empty 4-code
        val pushed = candidates.firstOrNull()?.text.orEmpty()
        resetComposing()
        if (pushed.isNotEmpty()) {
            lastCommittedText = pushed
        }
        val follow = onCharInput(ch, nowMillis)
        return mergePushedWithFollowUp(pushed, follow)
    }

    private fun mergePushedWithFollowUp(pushed: String, follow: FlypyAction): FlypyAction {
        return when (follow) {
            is FlypyAction.CommitText -> {
                FlypyAction.CommitText(
                    committed = pushed + follow.committed,
                    remainComposing = follow.remainComposing
                )
            }
            FlypyAction.ConsumedComposing -> {
                if (pushed.isNotEmpty()) {
                    FlypyAction.CommitText(committed = pushed, remainComposing = isComposing)
                } else {
                    FlypyAction.ConsumedComposing
                }
            }
        }
    }

    private fun resolveCandidates(code: String, nowMillis: Long): List<FlypyCandidate> {
        if (code.contains('`')) {
            return dictionary.queryWildcard(code)
        }

        val exactSnippets = customSnippets
            .filter { it.flypyCode == code }
            .map { entry ->
                val hint = entry.title.trim().take(6).let { if (it.isNotEmpty()) "⚡$it" else "⚡短语" }
                FlypyCandidate(text = entry.content, codeHint = hint)
            }

        val prefixSnippets = if (code.startsWith(";") && code.length >= 2) {
            customSnippets
                .filter { it.flypyCode.length > code.length && it.flypyCode.startsWith(code) }
                .map { entry ->
                    FlypyCandidate(text = entry.content, codeHint = entry.flypyCode)
                }
        } else {
            emptyList()
        }

        if (code == ";f") {
            val repeatList = if (lastCommittedText.isNotEmpty()) {
                listOf(FlypyCandidate(text = lastCommittedText))
            } else {
                emptyList()
            }
            return exactSnippets + repeatList + prefixSnippets
        }

        if (code == "orq") {
            val date = Date(nowMillis)
            val cnFmt = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(date)
            val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
            val compactFmt = SimpleDateFormat("yyyyMMdd", Locale.US).format(date)
            val dynamic = listOf(
                FlypyCandidate(cnFmt),
                FlypyCandidate(isoFmt),
                FlypyCandidate(compactFmt)
            )
            return exactSnippets + dynamic + dictionary.lookupExact(code)
        }

        if (code == "ouj") {
            val date = Date(nowMillis)
            val hm = SimpleDateFormat("HH:mm", Locale.US).format(date)
            val hms = SimpleDateFormat("HH:mm:ss", Locale.US).format(date)
            val dynamic = listOf(
                FlypyCandidate(hm),
                FlypyCandidate(hms)
            )
            return exactSnippets + dynamic + dictionary.lookupExact(code)
        }

        if (code == "oxq") {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val dayIdx = cal.get(Calendar.DAY_OF_WEEK)
            val suffix = when (dayIdx) {
                Calendar.MONDAY -> "一"
                Calendar.TUESDAY -> "二"
                Calendar.WEDNESDAY -> "三"
                Calendar.THURSDAY -> "四"
                Calendar.FRIDAY -> "五"
                Calendar.SATURDAY -> "六"
                else -> "日"
            }
            val dynamic = listOf(
                FlypyCandidate("星期$suffix"),
                FlypyCandidate("周$suffix")
            )
            return exactSnippets + dynamic + dictionary.lookupExact(code)
        }

        if (code.startsWith("os") && code.length >= 3) {
            return exactSnippets + dictionary.lookupWithExtendedOs(code)
        }

        return exactSnippets + dictionary.lookupExact(code) + prefixSnippets
    }
}
