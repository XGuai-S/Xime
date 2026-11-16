package com.kingzcheung.xime.ai.ime.clipboard

import com.kingzcheung.xime.ai.data.ClipboardEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ClipboardFilterTab(val label: String) {
    ALL("全部"),
    PINNED("📌 常用"),
    SMART("✨ 提取")
}

enum class SmartClipKind(val badgePrefix: String) {
    OTP_CODE("🔢 验证码"),
    PICKUP_CODE("📦 取件码"),
    TRACKING_NUMBER("🚚 快递单号"),
    URL("🔗 链接"),
    PHONE("📱 号码"),
    EMAIL("✉️ 邮箱"),
    ADDRESS("📍 地址")
}

data class SmartClipExtraction(
    val kind: SmartClipKind,
    val extractedValue: String,
    val chipLabel: String
)

/**
 * Pure Kotlin smart entity extractor & formatter for the built-in IME Clipboard Manager (v3.0 Smart Clipboard 2.0).
 * Recognizes verification codes (OTP), Cainiao/Fengchao pickup codes (including hyphenated shelf codes like 3-2-1048),
 * express tracking numbers (SF/JD/YT/ZTO/JT), URLs, emails, phone numbers, and shipping addresses inside copied text
 * so users can paste individual entities or the full text in a single tap.
 */
object ClipboardSmartExtractor {

    private val OTP_KEYWORDS_REGEX = Regex(
        "(验证码|校验码|动态码|确认码|授权码|安全码|verification|passcode|otp|auth\\s*code|login\\s*code)",
        RegexOption.IGNORE_CASE
    )
    private val PICKUP_KEYWORDS_REGEX = Regex(
        "(取件码|提货码|取货码|菜鸟驿站|丰巢|妈妈驿站|兔喜|快宝|快递柜|丰巢柜|驿站|凭码|请凭)",
        RegexOption.IGNORE_CASE
    )
    // Hyphenated shelf pickup codes: e.g. 3-2-1048, A05-2019, 12-4088, B2-1-809
    private val HYPHENATED_PICKUP_REGEX = Regex(
        "(?<![A-Za-z0-9\\-])([A-Za-z0-9]{1,4}(?:-[A-Za-z0-9]{1,6}){1,2})(?![A-Za-z0-9\\-])"
    )
    // Alphanumeric or 4-8 digit cabinet pickup code: e.g. 425666, A2048, 884216
    private val CABINET_PICKUP_REGEX = Regex(
        "(?:取件码|提货码|取货码|凭码|请凭)[:：\\s]*([A-Za-z]?\\d{4,8}|\\d{4,8})(?!\\d)"
    )
    private val OTP_CANDIDATE_REGEX = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    // Express tracking numbers: SF (顺丰), JD (京东), YT (圆通), JT (极兔), EMS, or explicit 单号 context
    private val EXPLICIT_CARRIER_TRACKING_REGEX = Regex(
        "(?<![A-Za-z0-9])(SF\\d{12,15}|JD[A-Z0-9]{11,15}|YT\\d{13,15}|JT\\d{13,15}|E[A-Z]\\d{9}CN)(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE
    )
    private val CONTEXT_TRACKING_REGEX = Regex(
        "(?:快递单号|运单号|物流单号|单号|中通|韵达|申通|圆通|顺丰|京东快递|极兔|邮政)[:：\\s]*([A-Za-z0-9]{10,20})"
    )

    private val URL_REGEX = Regex("https?://[^\\s<>\"'，。！？；：）】\\]]+", RegexOption.IGNORE_CASE)
    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val CN_MOBILE_REGEX = Regex("(?<!\\d)(?:\\+?86[-\\s]?)?(1[3-9]\\d[-\\s]?\\d{4}[-\\s]?\\d{4})(?!\\d)")

    private val ADDRESS_HINT_REGEX = Regex("(收货地址|收件地址|详细地址|寄件地址|地址[:：]|收件人|联系电话|手机[:：])")
    private val CN_ADDRESS_SEGMENT_REGEX = Regex(
        "((?:北京|上海|天津|重庆|[\\u4e00-\\u9fa5]{2,5}(?:省|自治区|特别行政区))?[\\u4e00-\\u9fa5]{2,7}(?:市|州|盟|区|县)[\\u4e00-\\u9fa50-9A-Za-z\\-号弄幢栋单元室楼层路街巷道院村镇乡大厦广场中心苑园城湾]{4,45})"
    )

    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Extracts all distinct actionable entities from [rawText] in priority order.
     */
    fun extractAll(rawText: String): List<SmartClipExtraction> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val results = mutableListOf<SmartClipExtraction>()
        val seenValues = mutableSetOf<String>()

        fun addUnique(kind: SmartClipKind, value: String, chipLabel: String) {
            val clean = value.trim()
            if (clean.isNotEmpty() && seenValues.add(clean)) {
                results.add(
                    SmartClipExtraction(
                        kind = kind,
                        extractedValue = clean,
                        chipLabel = chipLabel
                    )
                )
            }
        }

        // 1. Express / Cabinet Pickup Codes (Cainiao 3-2-1048, Fengchao 425666, etc.)
        if (PICKUP_KEYWORDS_REGEX.containsMatchIn(trimmed)) {
            HYPHENATED_PICKUP_REGEX.findAll(trimmed).forEach { matchResult ->
                val candidate = matchResult.groupValues[1]
                // Must contain at least one digit and not look like a date (2026-11-10)
                val looksLikeDate = Regex("^20\\d{2}-\\d{1,2}-\\d{1,2}$").matches(candidate)
                if (candidate.any { it.isDigit() } && !looksLikeDate) {
                    addUnique(
                        kind = SmartClipKind.PICKUP_CODE,
                        value = candidate,
                        chipLabel = "📦 取件码 $candidate"
                    )
                }
            }
            CABINET_PICKUP_REGEX.findAll(trimmed).forEach { matchResult ->
                val candidate = matchResult.groupValues[1]
                if (candidate.isNotBlank()) {
                    addUnique(
                        kind = SmartClipKind.PICKUP_CODE,
                        value = candidate,
                        chipLabel = "📦 取件码 $candidate"
                    )
                }
            }
            if (results.none { it.kind == SmartClipKind.PICKUP_CODE }) {
                val fallbackDigitCode = OTP_CANDIDATE_REGEX.findAll(trimmed)
                    .map { it.groupValues[1] }
                    .firstOrNull { candidate ->
                        !(candidate.length == 4 && candidate.toIntOrNull() in 2020..2035)
                    }
                if (fallbackDigitCode != null) {
                    addUnique(
                        kind = SmartClipKind.PICKUP_CODE,
                        value = fallbackDigitCode,
                        chipLabel = "📦 取件码 $fallbackDigitCode"
                    )
                }
            }
        }

        // 2. Verification Code (OTP 4-8 digits when message contains OTP context keywords)
        if (OTP_KEYWORDS_REGEX.containsMatchIn(trimmed)) {
            val match = OTP_CANDIDATE_REGEX.findAll(trimmed)
                .map { it.groupValues[1] }
                .firstOrNull { candidate ->
                    // Avoid matching 4-digit current years like 2024..2030 when a real 6-digit code follows
                    !(candidate.length == 4 && candidate.toIntOrNull() in 2020..2035) &&
                        !seenValues.contains(candidate)
                }
            if (match != null) {
                addUnique(
                    kind = SmartClipKind.OTP_CODE,
                    value = match,
                    chipLabel = "🔢 仅填 $match"
                )
            }
        }

        // 3. Express Tracking Numbers (SF/JD/YT/JT/EMS or explicit context)
        EXPLICIT_CARRIER_TRACKING_REGEX.findAll(trimmed).forEach { matchResult ->
            val tracking = matchResult.groupValues[1].uppercase(Locale.US)
            val shortLabel = if (tracking.length > 12) tracking.take(10) + "…" else tracking
            addUnique(
                kind = SmartClipKind.TRACKING_NUMBER,
                value = tracking,
                chipLabel = "🚚 单号 $shortLabel"
            )
        }
        CONTEXT_TRACKING_REGEX.findAll(trimmed).forEach { matchResult ->
            val tracking = matchResult.groupValues[1]
            // Avoid mistaking 11-digit mobile numbers for tracking numbers
            val isPhone = tracking.length == 11 && tracking.startsWith("1") && tracking.all { it.isDigit() }
            if (!isPhone && tracking.count { it.isDigit() } >= 8) {
                val shortLabel = if (tracking.length > 12) tracking.take(10) + "…" else tracking
                addUnique(
                    kind = SmartClipKind.TRACKING_NUMBER,
                    value = tracking,
                    chipLabel = "🚚 单号 $shortLabel"
                )
            }
        }

        // 4. HTTP / HTTPS URLs
        URL_REGEX.findAll(trimmed).forEach { matchResult ->
            val url = matchResult.value.trimEnd('.', ',', ';', ')', ']', '，', '。', '！', '？', '；', '：', '）', '】')
            if (url.length >= 10) {
                val host = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
                val shortHost = if (host.length > 16) host.take(15) + "…" else host
                addUnique(
                    kind = SmartClipKind.URL,
                    value = url,
                    chipLabel = "🔗 $shortHost"
                )
            }
        }

        // 5. Email Addresses
        EMAIL_REGEX.findAll(trimmed).forEach { matchResult ->
            val email = matchResult.value
            val shortEmail = if (email.length > 18) email.take(17) + "…" else email
            addUnique(
                kind = SmartClipKind.EMAIL,
                value = email,
                chipLabel = "✉️ $shortEmail"
            )
        }

        // 6. Mobile Phone Numbers
        CN_MOBILE_REGEX.findAll(trimmed).forEach { matchResult ->
            val rawPhone = matchResult.groupValues.getOrNull(1).orEmpty()
            val digitsOnly = rawPhone.filter { it.isDigit() }
            if (digitsOnly.length == 11) {
                addUnique(
                    kind = SmartClipKind.PHONE,
                    value = digitsOnly,
                    chipLabel = "📱 $digitsOnly"
                )
            }
        }

        // 7. Recipient / Shipping Address (extracted when embedded in multi-field contact/shipping info)
        val hasPhoneOrHint = results.any { it.kind == SmartClipKind.PHONE } || ADDRESS_HINT_REGEX.containsMatchIn(trimmed)
        if (hasPhoneOrHint) {
            CN_ADDRESS_SEGMENT_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { addr ->
                val cleanAddr = addr.trim()
                if (cleanAddr.length >= 8 && cleanAddr != trimmed) {
                    val shortAddr = if (cleanAddr.length > 12) cleanAddr.take(11) + "…" else cleanAddr
                    addUnique(
                        kind = SmartClipKind.ADDRESS,
                        value = cleanAddr,
                        chipLabel = "📍 $shortAddr"
                    )
                }
            }
        }

        return results
    }

    /**
     * Extracts the highest-priority actionable token from [rawText], or null if none is present.
     */
    fun extractPrimary(rawText: String): SmartClipExtraction? {
        return extractAll(rawText).firstOrNull()
    }

    /**
     * Filters [items] according to the active [ClipboardFilterTab].
     */
    fun filterItems(
        items: List<ClipboardEntry>,
        tab: ClipboardFilterTab
    ): List<ClipboardEntry> {
        return when (tab) {
            ClipboardFilterTab.ALL -> items
            ClipboardFilterTab.PINNED -> items.filter { it.isPinned }
            ClipboardFilterTab.SMART -> items.filter { extractPrimary(it.text) != null }
        }
    }

    /**
     * Formats compact single-line preview for top-bar suggestion capsules.
     */
    fun formatSingleLinePreview(text: String, maxChars: Int = 12): String {
        val clean = text.trim().replace(WHITESPACE_REGEX, " ")
        if (clean.length <= maxChars) return clean
        return clean.take(maxChars.coerceAtLeast(1)).trimEnd() + "…"
    }

    /**
     * Formats human-friendly relative timestamp (`刚刚`, `5分钟前`, `2小时前`, `MM-dd HH:mm`).
     */
    fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffSec = ((nowMs - timestampMs).coerceAtLeast(0L)) / 1000L
        return when {
            diffSec < 45L -> "刚刚"
            diffSec < 3600L -> "${diffSec / 60L}分钟前"
            diffSec < 86_400L -> "${diffSec / 3600L}小时前"
            diffSec < 172_800L -> "昨天"
            else -> SimpleDateFormat("MM-dd", Locale.US).format(Date(timestampMs))
        }
    }
}
