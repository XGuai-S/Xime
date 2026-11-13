package com.kingzcheung.xime.service.otp

import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一条被捕获的验证码。
 *
 * @param primary   首选显示形式：纯数字（如 `344204`）
 * @param secondary 次选显示形式：保留原始来源格式（如 Google 短信的 `G-344204`），可为空
 */
data class OtpCandidate(
    val primary: String,
    val secondary: String? = null,
    /** 捕获时刻，用于「新鲜度」判断（只自动填入刚收到的验证码）。 */
    val capturedAt: Long = System.currentTimeMillis(),
) {
    /** 候选栏展示用：首选在前，次选在后。 */
    fun toList(): List<String> = if (secondary.isNullOrEmpty()) listOf(primary) else listOf(primary, secondary)
}

/**
 * 从短信正文里提取验证码。
 *
 * 设计原则：**宁可漏，不可错**——误把订单号/金额/快递单号当验证码填进输入框，
 * 比漏掉一个验证码更烦人。因此要求正文含关键词，并对噪声短信直接放弃。
 */
object OtpExtractor {

    /** Google 短信格式：`G-123456`（带词边界，避免误配长数字串中的片段）。 */
    private val GOOGLE = Regex("""\bG-(\d{4,8})\b""", RegexOption.IGNORE_CASE)

    /** 通用：独立的 4~8 位数字（前后不能紧邻其它数字）。 */
    private val GENERIC = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    /** 可信关键词：正文必须含其一，才认为是验证码短信。 */
    private val KEYWORDS = Regex(
        // 「证码」单独列出：MIUI 会把「验证码」截断成「…证码」，只匹配完整词会漏
        "验证码|证码|校验码|确认码|动态码|安全码|激活码|动态密码|验证身份|口令|" +
            "verification code|verify code|your code|one-time|otp|passcode|security code|" +
            "auth code|login code",
        RegexOption.IGNORE_CASE,
    )

    /** 含噪声词时，验证码数字与关键词的最大字符距离（超过就认为那是订单号/金额）。 */
    private const val NOISE_KEYWORD_MAX_DISTANCE = 12

    /** 噪声特征：命中则不认（金额、订单、快递、余额等场景常含 4~8 位数字）。 */
    private val NOISE = Regex("""订单|金额|余额|支付|付款|退款|快递|物流|单号|尾号|积分|优惠|券|￥|¥|USD|CNY""")

    /**
     * @return 提取到的验证码；不构成验证码时返回 null。
     */
    fun extract(smsBody: String): OtpCandidate? {
        if (smsBody.isBlank()) return null
        if (!KEYWORDS.containsMatchIn(smsBody)) return null

        // Google 优先：G-344204 → 首选 344204，次选 G-344204
        GOOGLE.find(smsBody)?.let { m ->
            val code = m.groupValues[1]
            return OtpCandidate(primary = code, secondary = "G-$code")
        }

        val numberMatch = GENERIC.find(smsBody) ?: return null
        // 噪声词不再整条否决：银行/电商的验证码短信普遍带「金额/支付/尾号」等词，
        // 原来一律丢弃会系统性漏判。改为：含噪声词时，只有数字**紧挨关键词**才认，
        // 避免把订单号/金额当验证码（验证码一般就在关键词后 3~10 个字符内）。
        if (NOISE.containsMatchIn(smsBody)) {
            val keywordMatch = KEYWORDS.find(smsBody) ?: return null
            val distance = kotlin.math.abs(numberMatch.range.first - keywordMatch.range.first)
            if (distance > NOISE_KEYWORD_MAX_DISTANCE) return null
        }
        return OtpCandidate(primary = numberMatch.groupValues[1])
    }

    /**
     * 从任意文本（如剪贴板内容）提取验证码。
     * 与短信提取的区别：文本通常更短更干净，但仍要求含关键词或**整段就是一个数字**。
     */
    fun extractFromClipboard(text: String): OtpCandidate? {
        val t = text.trim()
        if (t.isEmpty()) return null
        GOOGLE.find(t)?.let { m ->
            val code = m.groupValues[1]
            return OtpCandidate(primary = code, secondary = "G-$code")
        }
        // 整段就是 4~8 位数字
        if (GENERIC.matches(t)) return OtpCandidate(primary = t)
        return extract(t)
    }
}

/**
 * 验证码是否仍应出现在候选栏。
 *
 * 三条同时成立才显示：未过期（[freshWindowMs] 内捕获）、且捕获时刻**晚于最后一次键盘按键点击**
 * ——用户 2026-11 规则：只要发生键盘点击事件，此前收到的验证码就不再在候选栏显示
 * （新收到的验证码 capturedAt 更新，会重新出现）。
 */
internal fun isOtpDisplayable(
    capturedAt: Long,
    nowMs: Long,
    lastKeyPressAtMs: Long,
    freshWindowMs: Long,
): Boolean {
    if (nowMs - capturedAt > freshWindowMs) return false
    return capturedAt > lastKeyPressAtMs
}

/**
 * 键盘点击时应从候选栏摘除的验证码文本：**忽略**「按键」这条过滤，取所有未过期条目。
 *
 * 与 [isOtpDisplayable] 的分工不同：
 * - [isOtpDisplayable] 决定这条验证码**还能不能被注入**候选栏；
 * - 本函数决定**已经挂在候选栏上的**要不要摘掉。
 *
 * 按键时两者都要做，否则只要有一次漏摘（滑动输入、迟到的一帧、别的路径先写了
 * associationCandidates），旧验证码就会一直挂在候选栏直到过期。
 */
internal fun otpTextsForDismissal(
    pending: List<OtpCandidate>,
    nowMs: Long,
    freshWindowMs: Long,
): List<String> = pending
    .filter { nowMs - it.capturedAt <= freshWindowMs }
    .flatMap { it.toList() }
    .distinct()

/**
 * 进程级验证码暂存器。
 *
 * 键盘不弹出时（后台收到短信）也会收集；键盘再次弹出时由 [displayList] 提供给候选栏。
 * 同时写入剪贴板历史（见 [SmsOtpReceiver]），两条路都能取用。
 */
object OtpCollector {

    private const val TAG = "OtpCollector"

    /** 最多保留多少条，避免长期累积。 */
    private const val MAX_KEEP = 5

    /** 验证码有效期：超过则不显示、不自动填、自动清理（用户要求 2 分钟）。 */
    const val FRESH_WINDOW_MS = 2 * 60_000L

    private val _pending = MutableStateFlow<List<OtpCandidate>>(emptyList())

    /**
     * 是否有**任何**待处理验证码（不看新鲜度）。
     *
     * 供按键热路径做快速短路：绝大多数时间这里都是空的，`handleKeyPress` 里
     * 三次验证码相关调用（displayList / allDisplayTexts / onKeyboardKeyPress）
     * 都可直接跳过，省掉每键 3 次 System.currentTimeMillis() + 列表分配。
     */
    fun hasAnyPending(): Boolean = _pending.value.isNotEmpty()

    /**
     * 所有 [_pending] 的读改写都在这把锁里。
     *
     * 写入方有两个线程：NotificationListenerService 的 binder 线程（[offer]）与主线程
     * （[consume] / [clear]）。`StateFlow.value = …` 只保证引用赋值原子，
     * 「读-改-写」整段不原子——并发时会丢新码，或把已上屏消费掉的码写回去。
     */
    private val pendingLock = Any()

    /** 最后一次「键盘按键点击」的时刻；此前捕获的验证码不再进候选栏（见 [isOtpDisplayable]）。 */
    @Volatile
    private var lastKeyPressAtMs: Long = 0L

    /** 待用验证码（新→旧），供候选栏/UI 观察。 */
    val pending: StateFlow<List<OtpCandidate>> = _pending.asStateFlow()

    /** 由设置项驱动；关闭后不再收集（已收集的保留）。 */
    @Volatile
    var enabled: Boolean = true

    /**
     * 记录一条验证码。
     *
     * @param candidate 提取结果
     * @param source    来源标识（日志用，如 `sms:+8613800138000`）
     * @return true 表示这是一条**新的**（此前未出现过）
     */
    fun offer(candidate: OtpCandidate, source: String): Boolean {
        if (!enabled) return false
        val now = System.currentTimeMillis()
        val accepted = synchronized(pendingLock) {
            val cur = _pending.value
            // **只和未过期的条目判重**：同一条短信过 2 分钟再重发时，旧条目已经失效，
            // 若仍按 primary 判重就会把新码吞掉（且不刷新 capturedAt），
            // 结果是这个验证码永远不再显示（用户 2026-11 修复）。
            val fresh = cur.filter { now - it.capturedAt <= FRESH_WINDOW_MS }
            if (fresh.any { it.primary == candidate.primary }) return@synchronized false
            // 只保留**最新一条** + 未过期项：新码替换旧的，顺手清掉过期废码
            _pending.value = listOf(candidate) + fresh
            true
        }
        if (!accepted) return false
        // 安全：**绝不**把验证码本身写进日志（日志文件可能被导出/分享）
        FileLogger.i(
            TAG,
            "captured otp from=$source len=${candidate.primary.length}" +
                if (candidate.secondary != null) " hasSecondary=true" else "",
        )
        return true
    }

    /**
     * 候选栏展示用的扁平列表：每条验证码展开成「首选、次选」两个候选字符串，
     * 例如 Google 短信 → `[344204, G-344204]`。
     */
    fun displayList(): List<String> {
        val now = System.currentTimeMillis()
        val lastKeyPress = lastKeyPressAtMs
        return _pending.value
            .filter { isOtpDisplayable(it.capturedAt, now, lastKeyPress, FRESH_WINDOW_MS) }
            .flatMap { it.toList() }
            .distinct()
    }

    /**
     * 候选栏上**可能挂着**的全部验证码文本（不按「按键」过滤），用于按键时摘除。
     *
     * 与 [displayList] 的区别：那个只返回「还该显示」的，本函数返回「还该撤下」的。
     * 按键时一律用本函数，保证按过键后候选栏里一条验证码都不剩。
     */
    fun allDisplayTexts(): List<String> =
        otpTextsForDismissal(_pending.value, System.currentTimeMillis(), FRESH_WINDOW_MS)

    /**
     * 键盘上任意按键被点击：此后此前捕获的验证码不再进候选栏（用户 2026-11 规则）。
     *
     * 只影响候选栏展示，不影响「点进数字框自动填充」（那条走 [takeFresh]）——
     * 自动填充是用户主动进入验证码框的意图，不该被之前随手按过的键抵消。
     */
    fun onKeyboardKeyPress(nowMs: Long = System.currentTimeMillis()) {
        lastKeyPressAtMs = nowMs
    }

    /**
     * 取一条**足够新**的验证码，用于「点进验证码框自动填入」。
     *
     * 窗口与候选栏展示统一为 [FRESH_WINDOW_MS]（2 分钟）：之前这里是 5 分钟，
     * 导致「候选栏已经撤下（看着像过期）」却仍会被自动填入，设置页文案也写的是 2 分钟。
     */
    fun takeFresh(windowMs: Long = FRESH_WINDOW_MS): String? {
        val now = System.currentTimeMillis()
        return _pending.value.firstOrNull { now - it.capturedAt <= windowMs }?.primary
    }

    /** 用户已取用某个验证码（点选/上屏）后移除，避免候选栏一直挂着。 */
    fun consume(codeOrDisplay: String) {
        if (codeOrDisplay.isEmpty()) return
        val removed = synchronized(pendingLock) {
            val before = _pending.value
            val after = before.filterNot {
                it.primary == codeOrDisplay || it.secondary == codeOrDisplay
            }
            if (after.size == before.size) return
            _pending.value = after
            before.size - after.size
        }
        // 安全：只记长度，绝不把验证码明文写进日志文件
        FileLogger.d(TAG, "consumed otp len=${codeOrDisplay.length}, removed=$removed")
    }

    /** 清空（设置页「清空已验证码」/切换会话时使用）。 */
    fun clear() {
        synchronized(pendingLock) { _pending.value = emptyList() }
    }
}
