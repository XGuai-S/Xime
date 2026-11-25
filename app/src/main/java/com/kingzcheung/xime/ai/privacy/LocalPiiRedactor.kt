package com.kingzcheung.xime.ai.privacy

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import com.kingzcheung.xime.ai.bubble.RawScreenTextNode

/**
 * Categories of sensitive Personal Identifiable Information (PII) and credentials.
 *
 * - Contact categories ([restorable] = true) are replaced with numbered placeholders before leaving
 *   the device and can be restored locally into the user's draft via [RedactionSession.restoreSafePlaceholders].
 * - High-risk credential categories ([restorable] = false) are permanently masked for the session,
 *   trigger automatic screenshot dropping, and are NEVER auto-restored.
 */
enum class PiiCategory(
    val placeholderPrefix: String,
    val restorable: Boolean
) {
    // Reversible contact placeholders (restorable locally before draft injection)
    PHONE("手机号", restorable = true),
    EMAIL("邮箱", restorable = true),
    ADDRESS("地址", restorable = true),
    PICKUP_CODE("取件码", restorable = true),

    // High-risk credentials (NEVER auto-restored; triggers screenshot drop)
    PASSWORD_SECRET("敏感口令", restorable = false),
    OTP_CODE("验证码", restorable = false),
    ID_CARD("证件号", restorable = false),
    BANK_CARD("银行卡", restorable = false),
    API_KEY("密钥", restorable = false)
}

data class RedactedSpan(
    val category: PiiCategory,
    val placeholder: String,
    val originalText: String,
    val startIndex: Int,
    val endIndex: Int
)

/**
 * Ephemeral in-memory redaction session for a single on-demand screen read.
 * Never persisted to SharedPreferences, disk, or logs.
 */
data class RedactionSession(
    val redactedText: String,
    val placeholderToOriginal: Map<String, Pair<PiiCategory, String>>,
    val hasHighRiskCredentials: Boolean,
    val redactionCount: Int,
    val redactedNodeBounds: List<IntArray> = emptyList()
) {
    /**
     * Restores only reversible contact placeholders (`restorable == true`) in [text].
     * High-risk credentials (`[敏感口令_N]`, `[验证码_N]`, `[证件号_N]`, `[银行卡_N]`, `[密钥_N]`)
     * are strictly preserved as placeholders and never back-filled.
     */
    fun restoreSafePlaceholders(text: String): String {
        if (text.isEmpty() || placeholderToOriginal.isEmpty()) return text
        var result = text
        for ((placeholder, entry) in placeholderToOriginal) {
            val (category, original) = entry
            if (category.restorable && original.isNotEmpty() && result.contains(placeholder)) {
                result = result.replace(placeholder, original)
            }
        }
        return result
    }

    companion object {
        val EMPTY = RedactionSession(
            redactedText = "",
            placeholderToOriginal = emptyMap(),
            hasHighRiskCredentials = false,
            redactionCount = 0,
            redactedNodeBounds = emptyList()
        )
    }
}

/**
 * 3-in-1 On-Device PII & Credential Redaction Engine:
 * 1. Android API 29+ native offline `TextClassificationManager` (`TextLinks`) entity extraction
 *    (with graceful pure-JVM fallback for unit tests).
 * 2. Open-source `Gitleaks` (MIT) high-confidence secret/token/password patterns +
 *    `ClipboardSmartExtractor` OTP & pickup code patterns.
 * 3. Mathematical checksum verification:
 *    - 18-digit Mainland China ID card (`GB 11643-1999` / ISO 7064 Mod 11-2 check digit)
 *    - 13..19-digit bank card (`Luhn` mod-10 check digit with timestamp/order-ID exclusion)
 *    - Mainland mobile / landline phone numbers, emails, and structured Chinese addresses.
 */
object LocalPiiRedactor {

    // GB 11643-1999 (ISO 7064 Mod 11-2) weights & check characters for 18-digit ID cards
    private val ID_CARD_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private val ID_CARD_CHECK_CHARS = charArrayOf('1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2')
    private val VALID_PROVINCE_CODES = setOf(
        11, 12, 13, 14, 15,
        21, 22, 23,
        31, 32, 33, 34, 35, 36, 37,
        41, 42, 43, 44, 45, 46,
        50, 51, 52, 53, 54,
        61, 62, 63, 64, 65,
        71, 81, 82, 91
    )

    // 1. Gitleaks (MIT) high-confidence API keys, cloud credentials & bearer/JWT tokens
    private val API_KEY_PATTERNS = listOf(
        // OpenAI / DeepSeek / SiliconFlow / Moonshot / Anthropic sk- keys
        Regex("""\bsk-(?:proj-|ant-)?[A-Za-z0-9_-]{16,}\b"""),
        // Google Cloud / Gemini API Key
        Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""),
        // AWS Access Key ID
        Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        // Alibaba Cloud AccessKey ID
        Regex("""\bLTAI[A-Za-z0-9]{12,24}\b"""),
        // Tencent Cloud SecretId
        Regex("""\bAKID[A-Za-z0-9]{13,32}\b"""),
        // GitHub PAT / OAuth / App Token
        Regex("""\bgh[pousr]_[A-Za-z0-9_]{20,}\b"""),
        // Slack Token
        Regex("""\bxox[baprs]-[0-9A-Za-z-]{10,}\b"""),
        // JWT (3 base64url segments starting with eyJ)
        Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"""),
        // Bearer token
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{16,}\b""")
    )

    // 2. Context-triggered password / secret assignment
    // 支持 JSON / YAML / env 三种常见形态：
    //   {"password":"xxx"}   password: xxx   PASSWORD=xxx
    // 旧写法在冒号后直接取非空白串，JSON 的 `"` 会被吃进值里、导致判定与脱敏位置错位。
    private val PASSWORD_CONTEXT_REGEX = Regex(
        """(?i)(?:密码|口令|登录密码|支付密码|交易密码|password|passwd|pwd|secret|api[_-]?key|access[_-]?token|private[_-]?key)\s*["']?\s*(?:[:：=]|是|为)\s*["']?([^\s,，。；;！!？?、"'\n\r]{4,64})"""
    )

    // 3. OTP verification code context & digits
    private val OTP_CONTEXT_KEYWORD_REGEX = Regex(
        """(?i)(验证码|校验码|动态码|确认码|安全码|随机码|提货码|verification|verify|otp|passcode|security\s*code|auth\s*code)"""
    )
    private val OTP_INLINE_REGEX = Regex(
        """(?i)(?:验证码|校验码|动态码|确认码|安全码|随机码|verification\s*code|otp|passcode)\s*(?:[:：是为]|为)?\s*([0-9]{4,8})(?!\d)"""
    )
    private val FOUR_TO_EIGHT_DIGITS_REGEX = Regex("""(?<!\d)([0-9]{4,8})(?!\d)""")

    // 4. Express / parcel pickup code
    private val PICKUP_CONTEXT_KEYWORD_REGEX = Regex(
        """(取件|取货|提货|驿站|丰巢|快递|包裹|菜鸟|柜|货架)"""
    )
    private val PICKUP_HYPHEN_CODE_REGEX = Regex(
        """(?<![A-Za-z0-9])([A-Za-z0-9]{1,4}\s*[-—–]\s*\d{1,4}\s*[-—–]\s*\d{2,6})(?!\d)"""
    )
    private val PICKUP_LABELED_REGEX = Regex(
        """(?:取件码|提货码|取货码)\s*(?:[:：是为])?\s*([A-Za-z0-9-]{4,14})"""
    )

    // 5. 18-digit Mainland ID card candidate
    private val CN_ID_CARD_18_REGEX = Regex(
        """(?<!\d)([1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx])(?!\d)"""
    )

    // 6. 13..19 digit bank card candidate (allows spaces or hyphens between groups)
    //
    // 旧写法 `(\d(?:[ -]?\d){12,18})` 是贪婪的：`4111111111111111 12/26` 会被吞成
    // `4111111111111111 12`（18 位），Luhn 必然失败 → 「卡号+有效期」这种最典型的
    // 信用卡粘贴形态全部漏判。同理 `4111 1111 1111 1111 123` 被吞成 19 位也失败。
    //
    // 改为「组边界」匹配：每组 4~6 位数字，组间只允许单个空格/连字符，且**后面不能再跟
    // 数字或数字组**。这样 `4111 1111 1111 1111 123` 只会匹配到前 16 位（4 组），
    // 末尾的 `123` 因为组数超限被自然排除，Luhn 就能通过。
    private val BANK_CARD_CANDIDATE_REGEX = Regex(
        """(?<![\d-])(\d{4}(?:[ -]?\d{4}){2,3}|\d{13,19})(?![\d-])"""
    )

    // 7. Mainland mobile phone & landline
    private val CN_MOBILE_PHONE_REGEX = Regex(
        """(?<!\d)((?:\+?86[- ]?)?1[3-9]\d[- ]?\d{4}[- ]?\d{4})(?!\d)"""
    )
    private val CN_LANDLINE_PHONE_REGEX = Regex(
        """(?<!\d)(0\d{2,3}-\d{7,8})(?!\d)"""
    )

    // 8. Email address
    private val EMAIL_REGEX = Regex(
        """(?<![A-Za-z0-9._%+-])([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})(?![A-Za-z0-9._%+-])"""
    )

    // 9. Structured Chinese address (province/city/district + street/road/building/room)
    private val CN_STRUCTURED_ADDRESS_REGEX = Regex(
        """(?:[\u4e00-\u9fa5]{2,7}(?:省|自治区|特别行政区))?(?:[\u4e00-\u9fa5]{2,7}(?:市|自治州|地区|盟))?(?:[\u4e00-\u9fa5]{2,7}(?:区|县|旗))(?:[\u4e00-\u9fa5A-Za-z0-9\-—#号楼栋单元室层座弄巷路街大道胡同院小区大厦广场中心园]{4,42})"""
    )
    private val CN_STREET_BUILDING_ADDRESS_REGEX = Regex(
        """[\u4e00-\u9fa5]{2,12}(?:路|街|大道|胡同|弄|巷)\s*\d{1,5}\s*号(?:[\u4e00-\u9fa5A-Za-z0-9\-—#号楼栋单元室层座小区大厦广场中心园]{2,32})?"""
    )

    /**
     * Validates an 18-digit Mainland China ID card using GB 11643-1999 (ISO 7064 Mod 11-2).
     */
    fun isValidCnIdCard18(raw: String): Boolean {
        val clean = raw.trim()
        if (clean.length != 18) return false
        val province = clean.substring(0, 2).toIntOrNull() ?: return false
        if (province !in VALID_PROVINCE_CODES) return false

        val year = clean.substring(6, 10).toIntOrNull() ?: return false
        val month = clean.substring(10, 12).toIntOrNull() ?: return false
        val day = clean.substring(12, 14).toIntOrNull() ?: return false
        if (year !in 1900..2099 || month !in 1..12 || day !in 1..31) return false

        var sum = 0
        for (i in 0 until 17) {
            val ch = clean[i]
            if (!ch.isDigit()) return false
            sum += (ch - '0') * ID_CARD_WEIGHTS[i]
        }
        val expectedCheck = ID_CARD_CHECK_CHARS[sum % 11]
        return clean[17].uppercaseChar() == expectedCheck
    }

    /**
     * Validates a 13..19-digit bank card number using the Luhn mod-10 checksum algorithm,
     * while excluding 18-digit Mainland ID cards and Unix millisecond timestamps.
     */
    fun isValidLuhnBankCard(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.length !in 13..19) return false
        // Standard ISO/IEC 7812 banking/financial Major Industry Identifiers start with 3, 4, 5, or 6
        if (digits[0] !in '3'..'6') return false
        // Exclude all-identical digits (e.g. 0000000000000000)
        if (digits.all { it == digits[0] }) return false
        // Exclude 13-digit Unix millisecond timestamps (e.g. 16xxxxxxxxxxx / 17xxxxxxxxxxx / 18xxxxxxxxxxx)
        if (digits.length == 13 && (digits.startsWith("16") || digits.startsWith("17") || digits.startsWith("18"))) {
            return false
        }
        // Exclude valid 18-digit Mainland ID cards (handled by ID_CARD)
        if (digits.length == 18 && isValidCnIdCard18(digits)) {
            return false
        }

        var sum = 0
        var shouldDouble = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (shouldDouble) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            shouldDouble = !shouldDouble
        }
        return sum > 0 && (sum % 10 == 0)
    }

    private data class CandidateMatch(
        val category: PiiCategory,
        val startIndex: Int,
        val endIndex: Int,
        val originalText: String,
        val priority: Int
    )

    /**
     * Redacts PII and high-risk credentials in [rawText] and returns an in-memory [RedactionSession].
     */
    fun redactText(
        rawText: String,
        context: Context? = null,
        sourceNodes: List<RawScreenTextNode> = emptyList()
    ): RedactionSession {
        if (rawText.isBlank() && sourceNodes.isEmpty()) {
            return RedactionSession.EMPTY.copy(redactedText = rawText)
        }

        val candidates = collectCandidates(rawText, context)
        val nonOverlapping = resolveNonOverlappingMatches(candidates)

        val categoryCounters = mutableMapOf<PiiCategory, Int>()
        val originalToPlaceholder = mutableMapOf<Pair<PiiCategory, String>, String>()
        val placeholderToOriginal = LinkedHashMap<String, Pair<PiiCategory, String>>()
        var hasHighRisk = false

        val sb = StringBuilder(rawText.length)
        var cursor = 0
        var totalRedactions = 0

        for (match in nonOverlapping) {
            if (match.startIndex > cursor) {
                sb.append(rawText, cursor, match.startIndex)
            }
            val normKey = match.category to match.originalText.trim()
            val placeholder = originalToPlaceholder.getOrPut(normKey) {
                val nextIndex = (categoryCounters[match.category] ?: 0) + 1
                categoryCounters[match.category] = nextIndex
                "[${match.category.placeholderPrefix}_$nextIndex]"
            }
            placeholderToOriginal[placeholder] = match.category to match.originalText
            if (!match.category.restorable) {
                hasHighRisk = true
            }
            sb.append(placeholder)
            cursor = match.endIndex
            totalRedactions += 1
        }
        if (cursor < rawText.length) {
            sb.append(rawText, cursor, rawText.length)
        }

        // Also scan sourceNodes so any visible node containing PII contributes its screen bounds
        // for screenshot blackout, and any high-risk node triggers screenshot drop even if outside bubble list.
        // Pass context = null here because full-text TextClassifier already ran on rawText above,
        // avoiding 40-100 synchronous cross-process TextClassifier Binder IPC calls on the main thread.
        val redactedBounds = mutableListOf<IntArray>()
        if (sourceNodes.isNotEmpty()) {
            for (node in sourceNodes) {
                if (!node.isVisibleToUser || node.text.isBlank()) continue
                val nodeMatches = resolveNonOverlappingMatches(collectCandidates(node.text, context = null))
                if (nodeMatches.isNotEmpty()) {
                    if (nodeMatches.any { !it.category.restorable }) {
                        hasHighRisk = true
                    }
                    if (node.boundsRight > node.boundsLeft && node.boundsBottom > node.boundsTop) {
                        redactedBounds.add(
                            intArrayOf(
                                node.boundsLeft,
                                node.boundsTop,
                                node.boundsRight,
                                node.boundsBottom
                            )
                        )
                    }
                }
            }
        }

        return RedactionSession(
            redactedText = sb.toString(),
            placeholderToOriginal = placeholderToOriginal,
            hasHighRiskCredentials = hasHighRisk,
            redactionCount = totalRedactions,
            redactedNodeBounds = redactedBounds
        )
    }

    /**
     * Redacts a list of formatted or plain chat messages along with combined accessibility + OCR
     * [sourceNodes], returning `(redactedMessages, redactionSession)`.
     */
    fun redactMessages(
        messages: List<String>,
        context: Context? = null,
        sourceNodes: List<RawScreenTextNode> = emptyList()
    ): Pair<List<String>, RedactionSession> {
        if (messages.isEmpty()) {
            val session = redactText("", context = context, sourceNodes = sourceNodes)
            return emptyList<String>() to session
        }
        val joined = messages.joinToString("\n")
        val session = redactText(
            rawText = joined,
            context = context,
            sourceNodes = sourceNodes
        )
        val redactedLines = if (session.redactionCount > 0) {
            val split = session.redactedText.split("\n")
            messages.mapIndexed { idx, orig -> split.getOrElse(idx) { orig } }
        } else {
            messages
        }
        return redactedLines to session
    }

    /**
     * Checks whether [text] contains any high-risk credential (`!category.restorable`).
     */
    fun containsHighRiskCredentials(text: String, context: Context? = null): Boolean {
        if (text.isBlank()) return false
        return resolveNonOverlappingMatches(collectCandidates(text, context))
            .any { !it.category.restorable }
    }

    private fun collectCandidates(text: String, context: Context?): List<CandidateMatch> {
        if (text.isEmpty()) return emptyList()
        val list = mutableListOf<CandidateMatch>()

        // Priority 100: API keys & tokens (Gitleaks MIT patterns)
        for (regex in API_KEY_PATTERNS) {
            regex.findAll(text).forEach { m ->
                list.add(
                    CandidateMatch(
                        category = PiiCategory.API_KEY,
                        startIndex = m.range.first,
                        endIndex = m.range.last + 1,
                        originalText = m.value,
                        priority = 100
                    )
                )
            }
        }

        // Priority 95: Context passwords / secrets (replace the secret value group)
        PASSWORD_CONTEXT_REGEX.findAll(text).forEach { m ->
            val group = m.groups[1]
            if (group != null && group.value.isNotBlank() && !group.value.startsWith("[")) {
                list.add(
                    CandidateMatch(
                        category = PiiCategory.PASSWORD_SECRET,
                        startIndex = group.range.first,
                        endIndex = group.range.last + 1,
                        originalText = group.value,
                        priority = 95
                    )
                )
            }
        }

        // Priority 90: 18-digit Mainland China ID card (verified by GB 11643-1999 Mod 11-2)
        CN_ID_CARD_18_REGEX.findAll(text).forEach { m ->
            val candidate = m.value
            if (isValidCnIdCard18(candidate)) {
                list.add(
                    CandidateMatch(
                        category = PiiCategory.ID_CARD,
                        startIndex = m.range.first,
                        endIndex = m.range.last + 1,
                        originalText = candidate,
                        priority = 90
                    )
                )
            }
        }

        // Priority 85: 13..19 digit bank card (verified by Luhn mod-10 checksum)
        BANK_CARD_CANDIDATE_REGEX.findAll(text).forEach { m ->
            // 先验整段；失败则按「去掉最后一组数字」回退一次。
            // 正则已尽量避免吞并后续数字，但用户输入格式千奇百怪
            //（如 `4111-1111-1111-1111-1234`），回退能兜住尾部多带一段的情况。
            val candidate = m.value
            val matched = if (isValidLuhnBankCard(candidate)) candidate else {
                val trimmed = candidate.trimEnd { it.isDigit() || it == ' ' || it == '-' }
                val groups = candidate.split(' ', '-').filter { it.isNotBlank() }
                if (groups.size > 3) {
                    val shorter = groups.dropLast(1).joinToString(" ")
                    if (isValidLuhnBankCard(shorter)) shorter else null
                } else null
            }
            if (matched != null) {
                val start = m.range.first
                list.add(
                    CandidateMatch(
                        category = PiiCategory.BANK_CARD,
                        startIndex = start,
                        endIndex = start + matched.length,
                        originalText = matched,
                        priority = 85
                    )
                )
            }
        }

        // Priority 80: OTP verification codes
        OTP_INLINE_REGEX.findAll(text).forEach { m ->
            val group = m.groups[1]
            if (group != null) {
                list.add(
                    CandidateMatch(
                        category = PiiCategory.OTP_CODE,
                        startIndex = group.range.first,
                        endIndex = group.range.last + 1,
                        originalText = group.value,
                        priority = 80
                    )
                )
            }
        }
        // Line-based OTP fallback when line contains OTP keywords
        var lineStart = 0
        for (line in text.split('\n')) {
            val lineEnd = lineStart + line.length
            if (OTP_CONTEXT_KEYWORD_REGEX.containsMatchIn(line)) {
                FOUR_TO_EIGHT_DIGITS_REGEX.findAll(line).forEach { m ->
                    val code = m.value
                    val asInt = code.toIntOrNull()
                    // Avoid mistaking standalone years like 2024..2029 unless explicitly matched by OTP_INLINE_REGEX
                    val isLikelyYear = code.length == 4 && asInt != null && asInt in 1990..2035
                    if (!isLikelyYear) {
                        list.add(
                            CandidateMatch(
                                category = PiiCategory.OTP_CODE,
                                startIndex = lineStart + m.range.first,
                                endIndex = lineStart + m.range.last + 1,
                                originalText = code,
                                priority = 78
                            )
                        )
                    }
                }
            }
            if (PICKUP_CONTEXT_KEYWORD_REGEX.containsMatchIn(line)) {
                PICKUP_HYPHEN_CODE_REGEX.findAll(line).forEach { m ->
                    val group = m.groups[1]
                    val matchRange = group?.range ?: m.range
                    val matchValue = group?.value ?: m.value
                    list.add(
                        CandidateMatch(
                            category = PiiCategory.PICKUP_CODE,
                            startIndex = lineStart + matchRange.first,
                            endIndex = lineStart + matchRange.last + 1,
                            originalText = matchValue,
                            priority = 75
                        )
                    )
                }
            }
            lineStart = lineEnd + 1
        }
        PICKUP_LABELED_REGEX.findAll(text).forEach { m ->
            val group = m.groups[1]
            if (group != null) {
                list.add(
                    CandidateMatch(
                        category = PiiCategory.PICKUP_CODE,
                        startIndex = group.range.first,
                        endIndex = group.range.last + 1,
                        originalText = group.value,
                        priority = 76
                    )
                )
            }
        }

        // Priority 70: Mainland mobile & landline phone numbers
        CN_MOBILE_PHONE_REGEX.findAll(text).forEach { m ->
            list.add(
                CandidateMatch(
                    category = PiiCategory.PHONE,
                    startIndex = m.range.first,
                    endIndex = m.range.last + 1,
                    originalText = m.value,
                    priority = 70
                )
            )
        }
        CN_LANDLINE_PHONE_REGEX.findAll(text).forEach { m ->
            list.add(
                CandidateMatch(
                    category = PiiCategory.PHONE,
                    startIndex = m.range.first,
                    endIndex = m.range.last + 1,
                    originalText = m.value,
                    priority = 68
                )
            )
        }

        // Priority 65: Email addresses
        EMAIL_REGEX.findAll(text).forEach { m ->
            list.add(
                CandidateMatch(
                    category = PiiCategory.EMAIL,
                    startIndex = m.range.first,
                    endIndex = m.range.last + 1,
                    originalText = m.value,
                    priority = 65
                )
            )
        }

        // Priority 60: Structured Chinese physical addresses
        CN_STRUCTURED_ADDRESS_REGEX.findAll(text).forEach { m ->
            list.add(
                CandidateMatch(
                    category = PiiCategory.ADDRESS,
                    startIndex = m.range.first,
                    endIndex = m.range.last + 1,
                    originalText = m.value,
                    priority = 60
                )
            )
        }
        CN_STREET_BUILDING_ADDRESS_REGEX.findAll(text).forEach { m ->
            list.add(
                CandidateMatch(
                    category = PiiCategory.ADDRESS,
                    startIndex = m.range.first,
                    endIndex = m.range.last + 1,
                    originalText = m.value,
                    priority = 58
                )
            )
        }

        // Priority 50: Android API 29+ native offline TextClassificationManager (TextLinks) supplement
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && text.length <= 4000) {
            runCatching {
                val tcm = context.getSystemService(TextClassificationManager::class.java)
                val classifier = tcm?.textClassifier
                if (classifier != null && !classifier.isDestroyed) {
                    val entityConfig = TextClassifier.EntityConfig.Builder()
                        .setIncludedTypes(
                            listOf(
                                TextClassifier.TYPE_ADDRESS,
                                TextClassifier.TYPE_PHONE,
                                TextClassifier.TYPE_EMAIL
                            )
                        )
                        .build()
                    val request = TextLinks.Request.Builder(text)
                        .setEntityConfig(entityConfig)
                        .build()
                    val links = classifier.generateLinks(request)
                    for (link in links.links) {
                        val start = link.start.coerceIn(0, text.length)
                        val end = link.end.coerceIn(start, text.length)
                        if (end <= start) continue
                        val spanText = text.substring(start, end)
                        val category = when {
                            link.getConfidenceScore(TextClassifier.TYPE_ADDRESS) >= 0.7f -> PiiCategory.ADDRESS
                            link.getConfidenceScore(TextClassifier.TYPE_PHONE) >= 0.75f -> PiiCategory.PHONE
                            link.getConfidenceScore(TextClassifier.TYPE_EMAIL) >= 0.75f -> PiiCategory.EMAIL
                            else -> null
                        }
                        if (category != null) {
                            list.add(
                                CandidateMatch(
                                    category = category,
                                    startIndex = start,
                                    endIndex = end,
                                    originalText = spanText,
                                    priority = 50
                                )
                            )
                        }
                    }
                }
            }
        }

        return list
    }

    private fun resolveNonOverlappingMatches(candidates: List<CandidateMatch>): List<CandidateMatch> {
        if (candidates.isEmpty()) return emptyList()
        // Sort by priority DESC, then length DESC, then startIndex ASC
        val sorted = candidates.sortedWith(
            compareByDescending<CandidateMatch> { it.priority }
                .thenByDescending { it.endIndex - it.startIndex }
                .thenBy { it.startIndex }
        )
        val accepted = mutableListOf<CandidateMatch>()
        for (c in sorted) {
            if (c.startIndex >= c.endIndex) continue
            val overlaps = accepted.any { existing ->
                c.startIndex < existing.endIndex && c.endIndex > existing.startIndex
            }
            if (!overlaps) {
                accepted.add(c)
            }
        }
        return accepted.sortedBy { it.startIndex }
    }
}
