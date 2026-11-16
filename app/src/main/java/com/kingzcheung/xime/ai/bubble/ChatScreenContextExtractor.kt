package com.kingzcheung.xime.ai.bubble

import android.content.Context
import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.AppToneRule
import com.kingzcheung.xime.ai.data.DefaultPresets
import com.kingzcheung.xime.ai.jev.ChatTopology
import com.kingzcheung.xime.ai.jev.JevDomainProfile
import com.kingzcheung.xime.ai.jev.JevRoleMode
import com.kingzcheung.xime.ai.privacy.LocalPiiRedactor
import com.kingzcheung.xime.ai.privacy.RedactionSession
import kotlin.math.abs

enum class ChatSpeaker(val tag: String) {
    OTHER("[对方]"),
    SELF("[我]"),
    UNKNOWN("[未知]")
}

data class RawScreenTextNode(
    val text: String,
    val boundsTop: Int,
    val boundsBottom: Int,
    val boundsLeft: Int,
    val boundsRight: Int,
    val isEditable: Boolean = false,
    val isClickable: Boolean = false,
    val viewIdResourceName: String = "",
    val className: String = "",
    val isPassword: Boolean = false,
    val isVisibleToUser: Boolean = true
)

data class ExtractedChatMessage(
    val speaker: ChatSpeaker,
    val text: String,
    val boundsTop: Int,
    val senderAlias: String = ""
) {
    val formattedWithSpeaker: String
        get() = if (speaker == ChatSpeaker.OTHER && senderAlias.isNotBlank()) {
            "[$senderAlias] $text"
        } else {
            "${speaker.tag} $text"
        }
}

data class CapturedChatContext(
    val packageName: String = "",
    val appName: String = "",
    val messages: List<ExtractedChatMessage> = emptyList(),
    val latestMessagePreview: String = "",
    /**
     * In-memory cropped JPEG Base64 of the chat area above the keyboard (Android 11+ `takeScreenshot`).
     * Never persisted to disk, history, or preferences; cleared immediately on panel close.
     */
    val screenshotJpegBase64: String = "",
    val screenshotWidth: Int = 0,
    val screenshotHeight: Int = 0,
    val capturedAtMs: Long = 0L,
    val redactionSession: RedactionSession = RedactionSession.EMPTY,
    val redactionCount: Int = redactionSession.redactionCount,
    val hasHighRiskRedaction: Boolean = redactionSession.hasHighRiskCredentials,
    val domainProfile: JevDomainProfile = JevDomainProfile.fromPackage(packageName),
    val topology: ChatTopology = ChatTopology()
) {
    val effectiveBadgeTitle: String
        get() = when {
            ChatScreenContextExtractor.isEnterpriseWorkplaceImPackage(packageName) ->
                if (topology.isGroupChat) "群聊协同" else "商务协同"
            topology.isGroupChat && domainProfile == JevDomainProfile.IM_CHAT ->
                "群聊局势"
            else ->
                domainProfile.badgeTitle
        }

    val hasScreenshot: Boolean
        get() = screenshotJpegBase64.isNotBlank()

    val hasContext: Boolean
        get() = messages.isNotEmpty() || hasScreenshot

    val hasMessages: Boolean
        get() = messages.isNotEmpty()

    val messageCount: Int
        get() = messages.size

    val formattedMessages: List<String>
        get() = messages.map { it.formattedWithSpeaker }

    val formattedLines: List<String>
        get() = formattedMessages

    val latestIncomingMessage: String
        get() = (messages.lastOrNull { it.speaker == ChatSpeaker.OTHER } ?: messages.lastOrNull())?.text.orEmpty()

    fun withViewportOutcome(
        outcome: ViewportCaptureOutcome,
        fallbackPackage: String = packageName,
        fallbackAppName: String = appName
    ): CapturedChatContext {
        val resolvedPkg = packageName.ifBlank { fallbackPackage }
        val resolvedApp = appName.ifBlank { fallbackAppName }
        val updatedMessages = if (outcome.mergedMessages.isNotEmpty()) {
            outcome.mergedMessages.mapIndexed { idx, line ->
                ChatScreenContextExtractor.parseFormattedChatMessage(line, fallbackTop = (idx + 1) * 100)
            }
        } else {
            messages
        }
        val updatedSession = if (
            outcome.mergedMessages.isNotEmpty() ||
            outcome.redactionSession.redactionCount > redactionSession.redactionCount ||
            outcome.redactionSession.hasHighRiskCredentials
        ) {
            outcome.redactionSession
        } else {
            redactionSession
        }
        val safeScreenshot = if (updatedSession.hasHighRiskCredentials) "" else outcome.screenshotJpegBase64
        val previewTarget = updatedMessages.lastOrNull { it.speaker == ChatSpeaker.OTHER }
            ?: updatedMessages.lastOrNull()
        val updatedPreview = when {
            previewTarget != null -> {
                val body = if (previewTarget.text.length > 32) {
                    "…" + previewTarget.text.takeLast(30)
                } else {
                    previewTarget.text
                }
                val prefixTag = if (previewTarget.speaker == ChatSpeaker.OTHER && previewTarget.senderAlias.isNotBlank()) {
                    "[${previewTarget.senderAlias}]"
                } else {
                    previewTarget.speaker.tag
                }
                "$prefixTag $body"
            }
            safeScreenshot.isNotBlank() -> latestMessagePreview.ifBlank { "📸 已截屏捕获聊天画面与图片" }
            else -> latestMessagePreview
        }
        val inferredSenders = updatedMessages
            .mapNotNull { msg -> msg.senderAlias.takeIf { msg.speaker == ChatSpeaker.OTHER && it.isNotBlank() } }
            .distinct()
        val mergedSenders = when {
            inferredSenders.isNotEmpty() -> inferredSenders
            outcome.topology.distinctOtherSenders.isNotEmpty() -> outcome.topology.distinctOtherSenders
            else -> topology.distinctOtherSenders
        }
        val mergedIsGroup = topology.isGroupChat ||
            outcome.topology.isGroupChat ||
            mergedSenders.isNotEmpty()
        val mergedMemberCount = maxOf(topology.groupMemberCount, outcome.topology.groupMemberCount)
        val mergedMentionMe = topology.hasExplicitMentionMe || outcome.topology.hasExplicitMentionMe
        val mergedRecentSelfTurns = if (updatedMessages.isNotEmpty()) {
            updatedMessages.takeLast(5).count { it.speaker == ChatSpeaker.SELF }
        } else {
            maxOf(topology.recentSelfTurns, outcome.topology.recentSelfTurns)
        }
        val mergedTopology = ChatTopology(
            isGroupChat = mergedIsGroup,
            groupMemberCount = mergedMemberCount,
            distinctOtherSenders = mergedSenders,
            recentSelfTurns = mergedRecentSelfTurns,
            hasExplicitMentionMe = mergedMentionMe,
            roleMode = ChatScreenContextExtractor.resolveRoleMode(
                isGroupChat = mergedIsGroup,
                distinctOtherSendersCount = mergedSenders.size,
                recentSelfTurns = mergedRecentSelfTurns,
                hasExplicitMentionMe = mergedMentionMe
            )
        )
        return copy(
            packageName = resolvedPkg,
            appName = resolvedApp,
            messages = updatedMessages,
            latestMessagePreview = updatedPreview,
            screenshotJpegBase64 = safeScreenshot,
            screenshotWidth = if (safeScreenshot.isNotBlank()) outcome.width else 0,
            screenshotHeight = if (safeScreenshot.isNotBlank()) outcome.height else 0,
            redactionSession = updatedSession,
            redactionCount = updatedSession.redactionCount,
            hasHighRiskRedaction = updatedSession.hasHighRiskCredentials,
            domainProfile = if (resolvedPkg.isNotBlank()) JevDomainProfile.fromPackage(resolvedPkg) else domainProfile,
            topology = mergedTopology
        )
    }

    companion object {
        val EMPTY = CapturedChatContext()
    }
}

data class ViewportCaptureOutcome(
    val screenshotJpegBase64: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val mergedMessages: List<String> = emptyList(),
    val redactionSession: RedactionSession = RedactionSession.EMPTY,
    val topology: ChatTopology = ChatTopology()
)

/**
 * Pure geometric heuristic extractor, screenshot crop calculator, and privacy gatekeeper
 * for on-demand "AI 帮回" (`VoiceSubPanel.HELP_ME_WRITE`).
 *
 * Security & privacy guarantees:
 * 1. Never runs in background accessibility event streams; invoked strictly on-demand when
 *    the user taps `✍️` in a supported messaging app.
 * 2. Requires explicit user opt-in (`chatContextReplyEnabled && chatContextDisclosureAccepted`),
 *    non-incognito mode, non-password field, and exact package match (`activeWindowPackage == targetInputPackage`).
 * 3. Blocks banking, payment, password manager, and system settings packages unconditionally.
 * 4. Classifies message speaker (`[对方]` / `[我]` / `[未知]`) using horizontal bubble geometry.
 * 5. Escapes XML boundary tags inside untrusted chat messages to prevent prompt injection breakout.
 */
object ChatScreenContextExtractor {

    private val SENSITIVE_PACKAGE_EXACT_BLACKLIST = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.kingzcheung.xime.ai",
        // Payment & Banking
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "com.icbc",
        "com.chinamworld.main",
        "com.chinamworld.bocmbci",
        "com.android.bankabc",
        "cmb.pb",
        "com.cmbchina.ccd.pluto.cmbActivity",
        "com.pingan.paces.ccms",
        "com.paypal.android.p2pmobile",
        // Password Managers & Authenticators
        "com.x8bit.bitwarden",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "com.keepassdroid",
        "keepass2android.keepass2android",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator"
    )

    private val SENSITIVE_PACKAGE_KEYWORD_BLACKLIST = listOf(
        "bank",
        "alipay",
        "wallet",
        "pay",
        "keepass",
        "bitwarden",
        "1password",
        "onepassword",
        "authenticator",
        "keychain",
        "password"
    )

    private val DEFAULT_MESSAGING_PACKAGES = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.tencent.qqlite",
        "com.ss.android.lark",
        "com.larksuite.suite",
        "com.alibaba.android.rimet",
        "com.alibaba.aliding",
        "com.alibaba.work",
        "com.tencent.wework",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "tw.nekomimi.nekogram",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.Slack",
        "com.xingin.xhs",
        "com.discord",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging"
    )

    private val ENTERPRISE_WORKPLACE_IM_PACKAGES = setOf(
        "com.ss.android.lark",
        "com.larksuite.suite",
        "com.alibaba.android.rimet",
        "com.alibaba.aliding",
        "com.alibaba.work",
        "com.tencent.wework",
        "com.Slack"
    )

    private val ENTERPRISE_IM_KEYWORDS = listOf(
        "dingtalk",
        "aliding",
        "rimet",
        "wework",
        "lark",
        "feishu",
        "slack"
    )

    private val MESSAGING_STYLE_IDS = setOf("casual_chat", "work_collab")

    private val UI_CHROME_NOISE_EXACT = setOf(
        "发送",
        "按住 说话",
        "按住说话",
        "松开 结束",
        "切换到语音",
        "切换到键盘",
        "表情",
        "动画表情",
        "贴纸",
        "查看原图",
        "双击查看",
        "更多功能按钮",
        "更多",
        "返回",
        "语音通话",
        "视频通话",
        "拍摄",
        "相册",
        "文件",
        "红包",
        "转账",
        "位置",
        "已读",
        "未读",
        "送达",
        "以下为新消息",
        "以下是新消息",
        "你撤回了一条消息",
        "对方撤回了一条消息",
        "以上是打招呼的内容",
        "对方正在输入...",
        "正在输入...",
        // Group chat role badges & generic accessibility labels
        "管理员",
        "[管理员]",
        "【管理员】",
        "群主",
        "[群主]",
        "【群主】",
        "群管理员",
        "活跃",
        "龙王",
        "学霸",
        "翰林",
        "潜水",
        "冒泡",
        "头像",
        "图片",
        "视频",
        "语音",
        "语音消息",
        "小程序",
        "个人名片",
        "群公告",
        "置顶",
        "Send",
        "Message",
        "Write a message...",
        "Type a message",
        "Hold to talk",
        "Unread Messages",
        "+"
    )

    private val PURE_TIME_OR_DATE_REGEX = Regex(
        """^(?:(?:昨天|今天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|Yesterday|Today)\s*)?(?:上午|下午|凌晨|晚上)?\s*\d{1,2}:\d{2}(?::\d{2})?(?:\s*(?:AM|PM|am|pm))?$"""
    )

    private val FULL_DATE_HEADER_REGEX = Regex(
        """^(?:\d{4}[年/-])?\d{1,2}[月/-]\d{1,2}日?(?:\s+\d{1,2}:\d{2})?$"""
    )

    private val SENDER_AND_TIME_HEADER_REGEX = Regex(
        """^.{1,16}\s*[·•]\s*\d{1,2}:\d{2}$"""
    )

    private val UNREAD_BADGE_REGEX = Regex(
        """^(?:\d{1,4}|\d{1,3}\+|99\+|·)$"""
    )

    /**
     * Matches standalone property/community room number nicknames such as `2-102`, `3-602`, `12-1-301`, `5栋202`.
     */
    private val BUILDING_UNIT_NICKNAME_REGEX = Regex(
        """^\d{1,3}\s*[-—–栋号单元]\s*(?:\d{1,3}\s*[-—–单元]\s*)?\d{2,4}[A-Za-z]?$"""
    )

    /**
     * Matches top-bar group chat titles with member counts such as `未成年成长交流群(16)` or `金泰和序-【真】业主群(435)`.
     */
    private val GROUP_TITLE_MEMBER_COUNT_REGEX = Regex(
        """^.{1,30}\s*[(（]\d{1,5}[)）]$"""
    )

    private val GROUP_TITLE_MEMBER_COUNT_CAPTURE_REGEX = Regex(
        """^(.{1,30}?)\s*[(（](\d{1,5})[)）]$"""
    )

    private val MENTION_ME_SIGNAL_REGEX = Regex(
        """(?:\[\s*有人@我\s*]|有人@我|\[\s*@我\s*]|(?:^|[\s\u2005\u2004\u2006，,。？！：；:;.?!（(【\[])@(?:我|所有人|全体成员|All)(?=[\s\u2005\u2004\u2006，,。？！：；:;.?!）)】\]]|$))""",
        RegexOption.IGNORE_CASE
    )

    private val NON_IM_MENTION_WHITESPACE_REGEX = Regex("""[^\S\u2005]+""")
    private val IM_MENTION_SPACE_VARIANTS_REGEX = Regex("""[\u2004\u2006]""")
    private val EXPLICIT_U2005_MENTION_REGEX = Regex(
        """(^|[\s，,。？！：；:;.?!（(【\[])@([^\u2005\u2004\u2006\n@\[\]]{1,42})[\u2005\u2004\u2006]+\s*"""
    )
    private val LEADING_GROUP_MENTION_FALLBACK_REGEX = Regex(
        """^\s*@([^\s@\u2005\[\]]{1,28}(?:[，,·•_-][^\s@\u2005\[\]]{1,22}){0,2})\s+(?=\S)"""
    )
    private val QUOTED_SENDER_TIME_CAPTURE_REGEX = Regex(
        """^(.{1,36}?)\s+\d{1,2}:\d{2}(?::\d{2})?$"""
    )
    private val ASCII_HANDLE_HEADER_REGEX = Regex(
        """^[A-Za-z][A-Za-z0-9_.-]{1,22}$"""
    )
    private val PROTECTED_MENTION_HANDLES = setOf(
        "我", "所有人", "all", "全体成员", "全体", "here", "channel"
    )

    private val STANDALONE_MENTION_BANNER_REGEX = Regex(
        """^(?:[↑↓]\s*)?[\[【(（]?\s*(?:有人@我|@我|\d+\s*条?新消息)\s*[\]】)）]?$"""
    )

    /**
     * Matches level/badge tags in QQ/forum groups such as `LV.12`, `Lv8`, `VIP3`.
     */
    private val LEVEL_BADGE_REGEX = Regex(
        """^(?:LV|Lv|lv|VIP|vip|SVIP|svip)\.?\s*\d{1,3}$"""
    )

    /**
     * Matches Mobile QQ / WeChat avatar accessibility labels such as `"玄冰是仓鼠 资料卡"`, `"张三的头像"`.
     */
    private val AVATAR_PROFILE_CARD_REGEX = Regex(
        """^(.{1,28}?)\s*(?:的)?(?:资料卡|个人资料卡|头像|个人主页|名片)$"""
    )

    /**
     * Matches DingTalk / WeWork enterprise group affiliation tags such as `"归属于 蚂蚁集团"`, `"内部群"`.
     */
    private val GROUP_AFFILIATION_HEADER_REGEX = Regex(
        """^(?:归属于\s*.+|内部群|外部群|全员群|部门群)$"""
    )

    /**
     * Matches bracketed or plain group role badges such as `"[管理员]"`, `"【群主】"`.
     */
    private val ROLE_BADGE_REGEX = Regex(
        """^[\[【(（]?\s*(?:管理员|群主|喦主|群王|群管理员|活跃|龙王|学霸|翰林|潜水|冒泡|群机器人|机器人)\s*[\]】)）]?$"""
    )

    /**
     * Matches OCR-merged role/level badge + group nickname lines such as
     * `"[群主]我一直在寻找，有下一顿饭钱的世界在哪里"`, `"群主 我一直在寻找，有下一顿饭钱的世界在哪里"`,
     * `"喦主 我一直在寻找..."`, `"LV.18 花猫"`, or `"[管理员] 玄冰是仓鼠"`.
     */
    private val ROLE_BADGE_PREFIXED_NICK_REGEX = Regex(
        """^(?:[\[【(（]\s*(?:管理员|群主|喦主|群王|群管理员|活跃|龙王|学霸|翰林|潜水|冒泡|群机器人|机器人|(?:LV|Lv|lv|VIP|vip|SVIP|svip)\.?\s*\d{1,3})\s*[\]】)）]\s*|(?:管理员|群主|喦主|群王|群管理员|活跃|龙王|学霸|翰林|潜水|冒泡|群机器人|机器人|(?:LV|Lv|lv|VIP|vip|SVIP|svip)\.?\s*\d{1,3})\s+)(.{1,42})$"""
    )

    private val SPEAKER_PREFIX_REGEX = Regex("""^\s*\[(?:对方|我|未知|群友[A-Z](?:\([^\n]{1,32}\))?)]\s*:?\s*""")
    private val GROUP_SENDER_PREFIX_CAPTURE_REGEX = Regex("""^\s*\[(群友[A-Z](?:\([^\n]{1,32}\))?)]\s*:?\s*(.*)$""")
    private val NORMALIZE_PUNCT_SPACE_REGEX = Regex("""[\s\p{P}\p{S}]+""")

    private val TAG_ESCAPE_REGEX = Regex(
        """<(/?\s*(?:chat_context|writing_request|system|user|assistant|cursor_before_context|personal_dictionary|app_and_tone|jev|domain_profile|conversation_topology|user_bottom_line|excluded_previous_replies|opt|subtext|lv|tag|weights)\b[^>]*)>""",
        RegexOption.IGNORE_CASE
    )

    fun isSensitiveBlacklistedPackage(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return true
        if (pkg in SENSITIVE_PACKAGE_EXACT_BLACKLIST) return true
        val lower = pkg.lowercase()
        return SENSITIVE_PACKAGE_KEYWORD_BLACKLIST.any { keyword -> lower.contains(keyword) }
    }

    fun isEnterpriseWorkplaceImPackage(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || isSensitiveBlacklistedPackage(pkg)) return false
        if (pkg in ENTERPRISE_WORKPLACE_IM_PACKAGES) return true
        val lower = pkg.lowercase()
        return ENTERPRISE_IM_KEYWORDS.any { lower.contains(it) }
    }

    fun isSupportedMessagingApp(
        packageName: String,
        appRules: List<AppToneRule> = DefaultPresets.defaultAppRules()
    ): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || isSensitiveBlacklistedPackage(pkg)) return false
        if (pkg in DEFAULT_MESSAGING_PACKAGES) return true
        val lower = pkg.lowercase()
        if (ENTERPRISE_IM_KEYWORDS.any { lower.contains(it) }) return true
        val matchedRule = appRules.firstOrNull { it.packageName == pkg }
        return matchedRule != null && matchedRule.styleId in MESSAGING_STYLE_IDS
    }

    fun hasAnyVisiblePasswordNode(nodes: List<RawScreenTextNode>): Boolean {
        return nodes.any { it.isVisibleToUser && it.isPassword }
    }

    fun shouldPromptFirstTimeDisclosure(
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        targetInputPackage: String,
        hasVisiblePasswordNode: Boolean = false
    ): Boolean {
        if (settings.chatContextDisclosureAccepted || settings.chatContextDisclosureDismissed) return false
        if (isIncognitoMode || settings.manualIncognitoMode || isPasswordInput || hasVisiblePasswordNode) return false
        if (isSensitiveBlacklistedPackage(targetInputPackage)) return false
        return isSupportedMessagingApp(targetInputPackage, settings.appRules)
    }

    fun shouldAllowChatContextCapture(
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean,
        activeWindowPackage: String,
        targetInputPackage: String,
        hasVisiblePasswordNode: Boolean = false
    ): Boolean {
        if (!settings.chatContextReplyEnabled || !settings.chatContextDisclosureAccepted) return false
        if (isIncognitoMode || settings.manualIncognitoMode || isPasswordInput || hasVisiblePasswordNode || ignoreChatContextForTurn) return false
        val activePkg = activeWindowPackage.trim()
        val targetPkg = targetInputPackage.trim()
        if (activePkg.isEmpty() || targetPkg.isEmpty() || activePkg != targetPkg) return false
        if (isSensitiveBlacklistedPackage(targetPkg)) return false
        return isSupportedMessagingApp(targetPkg, settings.appRules)
    }

    /**
     * All-Scene Jev Copilot first-time disclosure gate: unlocked across all non-blacklisted apps
     * (IM, Xianyu/Commerce, BossZhipin/Career, Xiaohongshu/Social, Mail/Docs/Web) when `✦ Jev 军师`
     * is enabled and not in incognito/password state.
     */
    fun shouldPromptJevFirstTimeDisclosure(
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        targetInputPackage: String,
        hasVisiblePasswordNode: Boolean = false
    ): Boolean {
        if (!settings.jevCopilotEnabled) return false
        if (settings.chatContextDisclosureAccepted || settings.chatContextDisclosureDismissed) return false
        if (isIncognitoMode || settings.manualIncognitoMode || isPasswordInput || hasVisiblePasswordNode) return false
        val pkg = targetInputPackage.trim()
        if (pkg.isEmpty() || isSensitiveBlacklistedPackage(pkg)) return false
        return true
    }

    /**
     * All-Scene Jev Copilot context capture gate: allows on-demand screen reading in ANY non-blacklisted
     * host app once the user has accepted first-time disclosure, while enforcing hard circuit-breakers
     * on password inputs, visible password nodes anywhere on screen, incognito mode, and sensitive
     * finance/payment/authenticator/password-manager apps.
     */
    fun shouldAllowJevContextCapture(
        settings: AppSettings,
        isIncognitoMode: Boolean,
        isPasswordInput: Boolean,
        ignoreChatContextForTurn: Boolean,
        activeWindowPackage: String,
        targetInputPackage: String,
        hasVisiblePasswordNode: Boolean = false
    ): Boolean {
        if (!settings.jevCopilotEnabled) return false
        if (!settings.chatContextReplyEnabled || !settings.chatContextDisclosureAccepted) return false
        if (isIncognitoMode || settings.manualIncognitoMode || isPasswordInput || hasVisiblePasswordNode || ignoreChatContextForTurn) return false
        val activePkg = activeWindowPackage.trim()
        val targetPkg = targetInputPackage.trim()
        if (activePkg.isEmpty() || targetPkg.isEmpty() || activePkg != targetPkg) return false
        if (isSensitiveBlacklistedPackage(targetPkg)) return false
        return true
    }

    fun sanitizeChatContextTagEscape(text: String): String {
        if (text.isEmpty()) return ""
        return TAG_ESCAPE_REGEX.replace(text) { matchResult ->
            "＜${matchResult.groupValues[1]}＞"
        }
    }

    fun classifySpeaker(
        node: RawScreenTextNode,
        screenWidth: Int
    ): ChatSpeaker {
        if (screenWidth <= 0) return ChatSpeaker.UNKNOWN
        val left = node.boundsLeft.coerceAtLeast(0)
        val right = node.boundsRight.coerceAtMost(screenWidth)
        if (right <= left) return ChatSpeaker.UNKNOWN

        val centerX = (left + right) / 2.0
        val midX = screenWidth / 2.0
        val leftMargin = left.toDouble()
        val rightMargin = (screenWidth - right).toDouble()
        val width = (right - left).toDouble()

        // Full-width banner or symmetrically centered system hint -> UNKNOWN
        if (width >= screenWidth * 0.86) return ChatSpeaker.UNKNOWN
        if (abs(centerX - midX) <= screenWidth * 0.035 &&
            abs(leftMargin - rightMargin) <= screenWidth * 0.025
        ) {
            return ChatSpeaker.UNKNOWN
        }

        // Right-aligned bubble -> SELF ([我])
        if (centerX >= screenWidth * 0.525 ||
            (rightMargin + screenWidth * 0.05 < leftMargin && left >= screenWidth * 0.14)
        ) {
            return ChatSpeaker.SELF
        }

        // Left-aligned bubble -> OTHER ([对方])
        if (centerX <= screenWidth * 0.475 ||
            (leftMargin < rightMargin && right <= screenWidth * 0.88)
        ) {
            return ChatSpeaker.OTHER
        }

        return ChatSpeaker.UNKNOWN
    }

    fun isNoiseText(rawText: String): Boolean {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed in UI_CHROME_NOISE_EXACT) return true
        if (STANDALONE_MENTION_BANNER_REGEX.matches(trimmed)) return true
        if (ROLE_BADGE_REGEX.matches(trimmed)) return true
        if (AVATAR_PROFILE_CARD_REGEX.matches(trimmed)) return true
        if (GROUP_AFFILIATION_HEADER_REGEX.matches(trimmed)) return true
        if (UNREAD_BADGE_REGEX.matches(trimmed)) return true
        if (PURE_TIME_OR_DATE_REGEX.matches(trimmed)) return true
        if (FULL_DATE_HEADER_REGEX.matches(trimmed)) return true
        if (SENDER_AND_TIME_HEADER_REGEX.matches(trimmed)) return true
        if (BUILDING_UNIT_NICKNAME_REGEX.matches(trimmed)) return true
        if (GROUP_TITLE_MEMBER_COUNT_REGEX.matches(trimmed)) return true
        if (LEVEL_BADGE_REGEX.matches(trimmed)) return true
        return false
    }

    fun isLikelyAvatarImageNode(
        node: RawScreenTextNode,
        screenWidth: Int = 1080
    ): Boolean {
        if (!node.className.contains("Image", ignoreCase = true)) return false
        val width = node.boundsRight - node.boundsLeft
        val height = node.boundsBottom - node.boundsTop
        if (width !in 24..180 || height !in 24..180 || abs(width - height) > 24) return false
        val edgeLimit = if (screenWidth > 0) (screenWidth * 0.22).toInt().coerceAtLeast(180) else 180
        return node.boundsLeft <= edgeLimit || (screenWidth > 0 && node.boundsRight >= screenWidth - edgeLimit)
    }

    internal fun normalizeMessageForDedup(msg: String): String {
        val withoutSpeaker = msg
            .replace(SPEAKER_PREFIX_REGEX, "")
            .replace(Regex("""^\s*\[@群友[A-Z]?]\s*"""), "")
            .trim()
        return withoutSpeaker
            .lowercase()
            .replace(NORMALIZE_PUNCT_SPACE_REGEX, "")
    }

    private fun collapseSpacesPreservingImMentionDelimiter(raw: String): String {
        if (raw.isEmpty()) return ""
        return raw
            .replace(IM_MENTION_SPACE_VARIANTS_REGEX, "\u2005")
            .replace(NON_IM_MENTION_WHITESPACE_REGEX, " ")
            .trim { it <= ' ' }
    }

    private fun isProtectedMentionHandle(handle: String): Boolean {
        val norm = handle.trim().lowercase()
        return norm.isEmpty() ||
            norm in PROTECTED_MENTION_HANDLES ||
            norm.startsWith("[") ||
            norm.startsWith("群友")
    }

    /**
     * Replaces `@<群友原昵称>` mentions inside chat message bodies (including quoted reply lines)
     * with structured symbolic tokens (`[@群友A]`, `[@群友B]`, or `[@群友]`) while preserving
     * `@我` / `@所有人` / `@All`, stripping the literal semantic wording of long sentence-like
     * handles (e.g. `@我一直在寻找，有下一顿饭钱的世界在哪里 这是什么，能干啥` -> `[@群友A] 这是什么，能干啥`).
     */
    fun normalizeGroupMentionsInMessage(
        rawText: String,
        isGroupChat: Boolean = true,
        nicknameToBaseAlias: Map<String, String> = emptyMap()
    ): String {
        if (rawText.isBlank()) return rawText.trim()
        if (!rawText.contains('@') && !rawText.contains('＠')) {
            return rawText.replace('\u2005', ' ').replace(Regex("""\s+"""), " ").trim()
        }

        var current = rawText.replace('＠', '@')

        // Layer 1: Exact known group-member nicknames sorted longest-first
        if (nicknameToBaseAlias.isNotEmpty()) {
            val sortedEntries = nicknameToBaseAlias.entries
                .filter { (nick, alias) ->
                    nick.isNotBlank() && alias.isNotBlank() && !isProtectedMentionHandle(nick)
                }
                .sortedByDescending { it.key.length }

            for ((nick, baseAlias) in sortedEntries) {
                val escapedNick = Regex.escape(nick)
                val knownMentionRegex = Regex(
                    """(^|[\s，,。？！：；:;.?!（(【\[])@$escapedNick(?:[\u2005\u2004\u2006]+\s*|\s+|(?=[，,。？！：；:;.?!）)】\]]|$))""",
                    RegexOption.IGNORE_CASE
                )
                current = knownMentionRegex.replace(current) { match ->
                    val prefix = match.groupValues[1]
                    val fullMatch = match.value
                    val lastChar = fullMatch.last()
                    val trailingSpace = if (lastChar.isWhitespace() || lastChar == '\u2005') " " else ""
                    "$prefix[@$baseAlias]$trailingSpace"
                }
            }
        }

        // Layer 2: Explicit IM four-per-em space (`\u2005`) mention delimiter inserted by WeChat/QQ/Feishu `@` picker
        current = EXPLICIT_U2005_MENTION_REGEX.replace(current) { match ->
            val prefix = match.groupValues[1]
            val handle = match.groupValues[2].trim()
            if (isProtectedMentionHandle(handle)) {
                "$prefix@$handle "
            } else {
                "$prefix[@群友] "
            }
        }

        // Layer 3: Leading `@<handle> <question>` fallback when OCR flattened `\u2005` to plain space
        // and the mentioned member's own bubbles have scrolled off-screen
        if (isGroupChat) {
            current = LEADING_GROUP_MENTION_FALLBACK_REGEX.replace(current) { match ->
                val handle = match.groupValues[1].trim()
                if (handle.length > 36 || isProtectedMentionHandle(handle)) {
                    match.value
                } else {
                    "[@群友] "
                }
            }
        }

        return current
            .replace('\u2005', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun parseFormattedChatMessage(
        formatted: String,
        fallbackTop: Int = 0
    ): ExtractedChatMessage {
        val trimmed = formatted.trim()
        val groupMatch = GROUP_SENDER_PREFIX_CAPTURE_REGEX.matchEntire(trimmed)
        if (groupMatch != null) {
            return ExtractedChatMessage(
                speaker = ChatSpeaker.OTHER,
                text = groupMatch.groupValues[2].trim(),
                boundsTop = fallbackTop,
                senderAlias = groupMatch.groupValues[1].trim()
            )
        }
        return when {
            trimmed.startsWith(ChatSpeaker.OTHER.tag) -> ExtractedChatMessage(
                speaker = ChatSpeaker.OTHER,
                text = trimmed.removePrefix(ChatSpeaker.OTHER.tag).trim(),
                boundsTop = fallbackTop
            )
            trimmed.startsWith(ChatSpeaker.SELF.tag) -> ExtractedChatMessage(
                speaker = ChatSpeaker.SELF,
                text = trimmed.removePrefix(ChatSpeaker.SELF.tag).trim(),
                boundsTop = fallbackTop
            )
            trimmed.startsWith(ChatSpeaker.UNKNOWN.tag) -> ExtractedChatMessage(
                speaker = ChatSpeaker.UNKNOWN,
                text = trimmed.removePrefix(ChatSpeaker.UNKNOWN.tag).trim(),
                boundsTop = fallbackTop
            )
            else -> ExtractedChatMessage(
                speaker = ChatSpeaker.OTHER,
                text = trimmed,
                boundsTop = fallbackTop
            )
        }
    }

    fun resolveRoleMode(
        isGroupChat: Boolean,
        distinctOtherSendersCount: Int,
        recentSelfTurns: Int,
        hasExplicitMentionMe: Boolean
    ): JevRoleMode {
        if (!isGroupChat && distinctOtherSendersCount < 2) {
            return JevRoleMode.PRIVATE_1V1
        }
        return if (hasExplicitMentionMe || recentSelfTurns > 0) {
            JevRoleMode.GROUP_INVOLVED_PARTY
        } else {
            JevRoleMode.GROUP_BYSTANDER
        }
    }

    fun inferTopologyFromFormattedMessages(
        messages: List<String>,
        existingTopology: ChatTopology = ChatTopology()
    ): ChatTopology {
        if (messages.isEmpty()) return existingTopology
        val parsed = messages.mapIndexed { idx, line ->
            parseFormattedChatMessage(line, fallbackTop = idx * 100)
        }
        val senders = parsed
            .mapNotNull { m -> m.senderAlias.takeIf { m.speaker == ChatSpeaker.OTHER && it.isNotBlank() } }
            .distinct()
        val finalSenders = senders.ifEmpty { existingTopology.distinctOtherSenders }
        val isGroup = existingTopology.isGroupChat || finalSenders.isNotEmpty()
        val recentSelf = parsed.takeLast(5).count { it.speaker == ChatSpeaker.SELF }
        val mentionMe = existingTopology.hasExplicitMentionMe ||
            parsed.any { it.speaker == ChatSpeaker.OTHER && MENTION_ME_SIGNAL_REGEX.containsMatchIn(it.text) }
        return ChatTopology(
            isGroupChat = isGroup,
            groupMemberCount = existingTopology.groupMemberCount,
            distinctOtherSenders = finalSenders,
            recentSelfTurns = recentSelf,
            hasExplicitMentionMe = mentionMe,
            roleMode = resolveRoleMode(
                isGroupChat = isGroup,
                distinctOtherSendersCount = finalSenders.size,
                recentSelfTurns = recentSelf,
                hasExplicitMentionMe = mentionMe
            )
        )
    }

    fun shouldTriggerOcrForCustomCanvas(
        ctx: CapturedChatContext,
        baseMessages: List<String> = ctx.formattedMessages
    ): Boolean {
        if (baseMessages.size < 2) return true
        val allShortUnpairedLabels = ctx.messages.isNotEmpty() && ctx.messages.all { msg ->
            msg.speaker == ChatSpeaker.OTHER &&
                msg.senderAlias.isBlank() &&
                msg.text.length <= 14 &&
                !msg.text.any { it in "，。？！：；,.?!～~" }
        }
        return allShortUnpairedLabels
    }

    /**
     * Merges Accessibility nodes ([a11yNodes]) and on-device OCR nodes ([ocrNodes]) in 2D screen
     * coordinate space before running [extractChatContext], so group nickname headers from A11y
     * (e.g. `凉意` at `top=310..348`) geometrically pair with custom-canvas chat bubbles
     * recognized by OCR immediately below them (`不好玩` at `top=358..410`).
     */
    fun mergeCoordinateNodes(
        a11yNodes: List<RawScreenTextNode>,
        ocrNodes: List<RawScreenTextNode>
    ): List<RawScreenTextNode> {
        if (ocrNodes.isEmpty()) return a11yNodes
        if (a11yNodes.isEmpty()) return ocrNodes
        val merged = ArrayList<RawScreenTextNode>(a11yNodes.size + ocrNodes.size)
        merged.addAll(a11yNodes)
        for (ocr in ocrNodes) {
            val normOcr = normalizeMessageForDedup(ocr.text)
            if (normOcr.isEmpty()) continue
            val duplicateIdx = merged.indexOfFirst { existing ->
                if (!existing.isVisibleToUser || existing.text.isBlank()) return@indexOfFirst false
                val verticalOverlap = minOf(existing.boundsBottom, ocr.boundsBottom) - maxOf(existing.boundsTop, ocr.boundsTop)
                val closeY = verticalOverlap >= -16 || abs(existing.boundsTop - ocr.boundsTop) <= 28
                if (!closeY) return@indexOfFirst false
                val normExisting = normalizeMessageForDedup(existing.text)
                normExisting.isNotEmpty() && (
                    normExisting == normOcr ||
                        (normOcr.length >= 2 && normExisting.contains(normOcr)) ||
                        (normExisting.length >= 2 && normOcr.contains(normExisting))
                    )
            }
            if (duplicateIdx >= 0) {
                val existing = merged[duplicateIdx]
                val normExisting = normalizeMessageForDedup(existing.text)
                if (normOcr.length > normExisting.length + 4 && normOcr.contains(normExisting)) {
                    merged[duplicateIdx] = existing.copy(text = ocr.text)
                }
            } else {
                merged.add(ocr)
            }
        }
        return merged
    }

    /**
     * Merges and deduplicates messages extracted from the Accessibility tree ([a11yMessages])
     * with messages recognized by on-device viewport OCR ([ocrMessages]):
     * - When [a11yMessages] is empty (e.g. Mobile QQ NT / WeChat custom-canvas bubbles), directly
     *   uses [ocrMessages].
     * - When both are non-empty, deduplicates overlapping messages by normalized body text
     *   (stripping `[对方]`/`[我]`/`[群友A]` prefixes, whitespace, and punctuation) while appending
     *   custom-drawn bubbles or inline image text found only by OCR, keeping the last [maxMessages].
     */
    fun mergeAccessibilityAndOcrMessages(
        a11yMessages: List<String>,
        ocrMessages: List<String>,
        maxMessages: Int = 10
    ): List<String> {
        val limit = maxMessages.coerceAtLeast(1)
        val cleanA11y = a11yMessages.map { it.trim() }.filter { it.isNotEmpty() }
        val cleanOcr = ocrMessages.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanA11y.isEmpty()) {
            return cleanOcr.takeLast(limit)
        }
        if (cleanOcr.isEmpty()) {
            return cleanA11y.takeLast(limit)
        }

        val merged = ArrayList<String>(cleanA11y.size + cleanOcr.size)
        val normalizedMerged = ArrayList<String>(cleanA11y.size + cleanOcr.size)

        for (msg in cleanA11y) {
            val norm = normalizeMessageForDedup(msg)
            if (norm.isNotEmpty()) {
                merged.add(msg)
                normalizedMerged.add(norm)
            }
        }

        for (ocrMsg in cleanOcr) {
            val normOcr = normalizeMessageForDedup(ocrMsg)
            if (normOcr.isEmpty()) continue
            val existingIdx = normalizedMerged.indexOfFirst { normExisting ->
                normExisting == normOcr ||
                    (normOcr.length >= 2 && normExisting.contains(normOcr)) ||
                    (normExisting.length >= 2 && normOcr.contains(normExisting))
            }
            if (existingIdx >= 0) {
                // If OCR captured a significantly fuller line or group sender prefix, upgrade it
                val existingNorm = normalizedMerged[existingIdx]
                val existingRaw = merged[existingIdx]
                val ocrHasGroupAlias = GROUP_SENDER_PREFIX_CAPTURE_REGEX.matches(ocrMsg) &&
                    !GROUP_SENDER_PREFIX_CAPTURE_REGEX.matches(existingRaw)
                if ((normOcr.length > existingNorm.length + 4 && normOcr.contains(existingNorm)) || ocrHasGroupAlias) {
                    merged[existingIdx] = ocrMsg
                    normalizedMerged[existingIdx] = normOcr
                }
            } else {
                merged.add(ocrMsg)
                normalizedMerged.add(normOcr)
            }
        }

        return merged.takeLast(limit)
    }

    const val MAX_SAFE_VIEWPORT_BOTTOM_RATIO = 0.76f
    const val MAX_FULL_SCREEN_PEEK_BOTTOM_RATIO = 0.92f
    const val DEFAULT_FULL_SCREEN_PEEK_BOTTOM_RATIO = 0.88f

    /**
     * Computes the crop rectangle `[left, top, width, height]` of the chat message viewport
     * above the input box / soft keyboard, excluding the top status bar (`0.052f`) and the bottom keyboard,
     * while preserving the top app/group title bar (`大鸽子交流群(8)`).
     * - When [allowFullScreenViewport] is `false` (default), enforces [MAX_SAFE_VIEWPORT_BOTTOM_RATIO] (`0.76f`)
     *   and falls back to `0.58f` so stale pre-`adjustResize` bottom coordinates never include the soft keyboard.
     * - When [allowFullScreenViewport] is `true` (240ms full-screen peek with keyboard hidden), relaxes
     *   the safe bottom ratio to [MAX_FULL_SCREEN_PEEK_BOTTOM_RATIO] (`0.92f`) and default bottom to `0.88f`
     *   so the newly exposed lower-half screen messages above the bottom input bar (`~2120px` on `2400px`) are preserved.
     */
    fun computeChatRegionCropRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        inputBoxTop: Int = -1,
        allowFullScreenViewport: Boolean = false
    ): IntArray {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            return intArrayOf(0, 0, 0, 0)
        }
        val cropTop = (bitmapHeight * 0.052f).toInt().coerceIn(0, bitmapHeight - 1)
        val defaultBottomRatio = if (allowFullScreenViewport) {
            DEFAULT_FULL_SCREEN_PEEK_BOTTOM_RATIO
        } else {
            0.58f
        }
        val maxSafeBottomRatio = if (allowFullScreenViewport) {
            MAX_FULL_SCREEN_PEEK_BOTTOM_RATIO
        } else {
            MAX_SAFE_VIEWPORT_BOTTOM_RATIO
        }
        val defaultBottom = (bitmapHeight * defaultBottomRatio).toInt().coerceIn(cropTop + 1, bitmapHeight)
        val maxSafeBottom = (bitmapHeight * maxSafeBottomRatio)
            .toInt()
            .coerceIn(defaultBottom, bitmapHeight)
        val cropBottom = if (inputBoxTop in (cropTop + 100)..maxSafeBottom) {
            inputBoxTop
        } else {
            defaultBottom
        }
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(1)
        return intArrayOf(0, cropTop, bitmapWidth, cropHeight)
    }

    private data class PendingPairedMessage(
        val speaker: ChatSpeaker,
        val text: String,
        val boundsTop: Int,
        val rawNickname: String = "",
        val omitRawNickInAlias: Boolean = false
    )

    fun extractChatContext(
        nodes: List<RawScreenTextNode>,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        inputBoxTop: Int = -1,
        packageName: String = "",
        appName: String = "",
        maxMessages: Int = 5,
        maxTotalChars: Int = 500,
        nowMs: Long = System.currentTimeMillis(),
        redactPii: Boolean = true,
        context: Context? = null
    ): CapturedChatContext {
        if (nodes.isEmpty()) {
            return CapturedChatContext(packageName = packageName, appName = appName, capturedAtMs = nowMs)
        }
        // Wall 2 Hard Circuit-Breaker: Any visible password node on screen or blacklisted app aborts immediately
        if (hasAnyVisiblePasswordNode(nodes) ||
            (packageName.isNotBlank() && isSensitiveBlacklistedPackage(packageName))
        ) {
            return CapturedChatContext(packageName = packageName, appName = appName, capturedAtMs = nowMs)
        }

        val topBarThreshold = if (screenHeight > 0) (screenHeight * 0.11).toInt() else 160
        val bottomFallbackThreshold = if (screenHeight > 0) (screenHeight * 0.89).toInt() else Int.MAX_VALUE
        val effectiveBottomLimit = if (inputBoxTop > topBarThreshold) {
            inputBoxTop - 4
        } else {
            bottomFallbackThreshold
        }

        // Pre-extract top-bar group title & memberCount (`大鸽子交流群(8)`), enterprise group tags,
        // `@我` / `[有人@我]` signals, avatar profile cards, avatar rects, and role badge rects BEFORE top-bar filtering.
        var isGroupChat = false
        var groupMemberCount = 0
        var hasExplicitMentionMe = false
        val groupHeaderMaxTop = if (screenHeight > 0) (screenHeight * 0.16).toInt().coerceAtLeast(260) else 260

        val knownAvatarNicknames = HashSet<String>()
        val viewportKnownGroupNicknames = LinkedHashSet<String>()
        val roleBadgeRects = ArrayList<RawScreenTextNode>(8)
        val leftAvatarRects = ArrayList<RawScreenTextNode>(8)
        val rightAvatarRects = ArrayList<RawScreenTextNode>(8)
        val midScreenX = if (screenWidth > 0) screenWidth / 2 else 540
        for (node in nodes) {
            if (!node.isVisibleToUser) continue
            if (isLikelyAvatarImageNode(node, screenWidth)) {
                val centerX = (node.boundsLeft + node.boundsRight) / 2
                if (centerX <= midScreenX) {
                    leftAvatarRects.add(node)
                } else {
                    rightAvatarRects.add(node)
                }
            }
            val t = node.text.replace(Regex("""\s+"""), " ").trim()
            if (t.isEmpty()) continue
            if (node.boundsTop <= groupHeaderMaxTop) {
                val groupMatch = GROUP_TITLE_MEMBER_COUNT_CAPTURE_REGEX.matchEntire(t)
                if (groupMatch != null) {
                    val count = groupMatch.groupValues[2].toIntOrNull() ?: 0
                    if (count >= 2) {
                        isGroupChat = true
                        if (count > groupMemberCount) {
                            groupMemberCount = count
                        }
                    }
                }
            }
            val preScanRolePrefix = ROLE_BADGE_PREFIXED_NICK_REGEX.matchEntire(t)
            if (preScanRolePrefix != null) {
                preScanRolePrefix.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    viewportKnownGroupNicknames.add(it)
                }
            }
            if (GROUP_AFFILIATION_HEADER_REGEX.matches(t) ||
                ROLE_BADGE_REGEX.matches(t) ||
                preScanRolePrefix != null ||
                t in setOf("群公告", "群主", "管理员", "群管理员")
            ) {
                isGroupChat = true
            }
            if (!node.isEditable && MENTION_ME_SIGNAL_REGEX.containsMatchIn(t)) {
                hasExplicitMentionMe = true
            }
            AVATAR_PROFILE_CARD_REGEX.matchEntire(t)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                knownAvatarNicknames.add(it)
                viewportKnownGroupNicknames.add(it)
            }
            if (ROLE_BADGE_REGEX.matches(t) || LEVEL_BADGE_REGEX.matches(t) || t in listOf("管理员", "群主", "群管理员")) {
                roleBadgeRects.add(node)
            }
        }

        fun buildEmptyCapturedContext(): CapturedChatContext {
            val emptyNodeSession = if (redactPii) {
                LocalPiiRedactor.redactText("", context = context, sourceNodes = nodes)
            } else {
                RedactionSession.EMPTY
            }
            val emptyTopology = ChatTopology(
                isGroupChat = isGroupChat,
                groupMemberCount = groupMemberCount,
                hasExplicitMentionMe = hasExplicitMentionMe,
                roleMode = resolveRoleMode(
                    isGroupChat = isGroupChat,
                    distinctOtherSendersCount = 0,
                    recentSelfTurns = 0,
                    hasExplicitMentionMe = hasExplicitMentionMe
                )
            )
            return CapturedChatContext(
                packageName = packageName,
                appName = appName,
                capturedAtMs = nowMs,
                redactionSession = emptyNodeSession,
                topology = emptyTopology
            )
        }

        val rawCandidates = nodes
            .asSequence()
            .filter { node ->
                node.isVisibleToUser &&
                    !node.isEditable &&
                    !node.isPassword &&
                    !isLikelyAvatarImageNode(node, screenWidth) &&
                    node.boundsBottom > topBarThreshold &&
                    node.boundsTop < effectiveBottomLimit &&
                    node.boundsBottom > node.boundsTop &&
                    node.boundsRight > node.boundsLeft
            }
            .mapNotNull { node ->
                val cleanWithImSpace = collapseSpacesPreservingImMentionDelimiter(node.text)
                val plainClean = cleanWithImSpace.replace('\u2005', ' ').replace(Regex("""\s+"""), " ").trim()
                val height = node.boundsBottom - node.boundsTop
                val isAvatarNickname = plainClean in knownAvatarNicknames && height <= 80
                if (isNoiseText(plainClean) || isAvatarNickname) {
                    null
                } else {
                    node.copy(text = sanitizeChatContextTagEscape(cleanWithImSpace))
                }
            }
            .sortedBy { it.boundsTop }
            .toList()

        if (rawCandidates.isEmpty()) {
            return buildEmptyCapturedContext()
        }

        // Adaptive 1080p / 1.5K / 2K thresholds for group sender nickname -> bubble pairing
        val maxNickHeight = if (screenHeight > 0) (screenHeight * 0.032).toInt().coerceAtLeast(68) else 68
        val captionFontMaxHeight = if (screenHeight > 0) (screenHeight * 0.019).toInt().coerceAtLeast(44) else 45
        val maxVerticalGap = if (screenHeight > 0) (screenHeight * 0.032).toInt().coerceAtLeast(68) else 68
        val maxCaptionGap = if (screenHeight > 0) (screenHeight * 0.019).toInt().coerceAtLeast(42) else 44
        val maxLeftDelta = if (screenWidth > 0) (screenWidth * 0.12).toInt().coerceAtLeast(96) else 96
        val leftNickColumnLimit = if (screenWidth > 0) (screenWidth * 0.36).toInt() else 388
        val rightNickColumnMinRight = if (screenWidth > 0) (screenWidth * 0.62).toInt() else 670

        val leftShortTextFreq = HashMap<String, Int>()
        val rightShortTextFreq = HashMap<String, Int>()
        for (node in rawCandidates) {
            val h = node.boundsBottom - node.boundsTop
            if (h <= maxNickHeight &&
                node.text.length in 1..14 &&
                !node.text.any { it in "，。？！：；,.?!～~" }
            ) {
                if (node.boundsLeft <= leftNickColumnLimit && classifySpeaker(node, screenWidth) == ChatSpeaker.OTHER) {
                    leftShortTextFreq[node.text] = (leftShortTextFreq[node.text] ?: 0) + 1
                } else if (node.boundsRight >= rightNickColumnMinRight && classifySpeaker(node, screenWidth) == ChatSpeaker.SELF) {
                    rightShortTextFreq[node.text] = (rightShortTextFreq[node.text] ?: 0) + 1
                }
            }
        }
        if (leftShortTextFreq.values.any { it >= 2 }) {
            isGroupChat = true
        }

        // Pre-strip quoted-reply sender+time headers (`terwer 13:49`) and any immediately attached
        // gray quoted echo of an earlier visible message BEFORE sender->bubble pairing, so the replier's
        // nickname header pairs directly with their actual reply line rather than with `terwer 13:49`!
        val strippedQuoteSpans = ArrayList<Pair<Int, Int>>(4)
        val candidates = ArrayList<RawScreenTextNode>(rawCandidates.size)
        var rawIdx = 0
        while (rawIdx < rawCandidates.size) {
            val cand = rawCandidates[rawIdx]
            val plainCand = cand.text.replace('\u2005', ' ').trim()
            val quoteMatch = QUOTED_SENDER_TIME_CAPTURE_REGEX.matchEntire(plainCand)
            val quoteSender = quoteMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val isQuotedSenderTimeHeader = quoteSender.isNotEmpty() && (
                quoteSender in viewportKnownGroupNicknames ||
                    leftShortTextFreq.containsKey(quoteSender) ||
                    ASCII_HANDLE_HEADER_REGEX.matches(quoteSender)
                )
            if (isQuotedSenderTimeHeader) {
                var spanBottom = cand.boundsBottom
                val maybeEcho = rawCandidates.getOrNull(rawIdx + 1)
                if (maybeEcho != null && (maybeEcho.boundsTop - cand.boundsBottom) in -10..52) {
                    val normEcho = normalizeMessageForDedup(maybeEcho.text)
                    val matchesEarlier = normEcho.isNotEmpty() && candidates.any { earlier ->
                        val normEarlier = normalizeMessageForDedup(earlier.text)
                        normEarlier.isNotEmpty() && (normEarlier == normEcho || normEarlier.startsWith(normEcho))
                    }
                    if (matchesEarlier) {
                        spanBottom = maybeEcho.boundsBottom
                        strippedQuoteSpans.add(cand.boundsTop to spanBottom)
                        rawIdx += 2
                        continue
                    }
                }
                strippedQuoteSpans.add(cand.boundsTop to spanBottom)
                rawIdx += 1
                continue
            }
            candidates.add(cand)
            rawIdx += 1
        }
        if (candidates.isEmpty()) {
            return buildEmptyCapturedContext()
        }

        // 5-Signal Geometric Pairing & Stripping Loop (`curr` -> `next`):
        // Pairs left-side group sender nicknames (including 19+ char / punctuated nicknames that extend past
        // screenWidth/2, role-badge rows `[群主] 昵称`, and OCR-merged `"群主 昵称"`) with the bubble below,
        // drops standalone role-badge nickname headers above stickers/images, and strips right-side `[我]`
        // self group nicknames (`Balthazar`) above `[我]`'s own bubble.
        val pairedCandidates = ArrayList<PendingPairedMessage>(candidates.size)
        var idx = 0
        while (idx < candidates.size) {
            val curr = candidates[idx]
            val next = candidates.getOrNull(idx + 1)
            val currHeight = curr.boundsBottom - curr.boundsTop
            val currSpeaker = classifySpeaker(curr, screenWidth)
            var pairedWithNext = false

            val sameRowAsRoleBadge = currHeight <= maxNickHeight && roleBadgeRects.any { b ->
                abs(curr.boundsTop - b.boundsTop) <= 26 &&
                    (curr.boundsLeft >= b.boundsLeft - 24 || curr.boundsRight <= b.boundsRight + 24)
            }
            val rolePrefixMatch = if (currHeight <= maxNickHeight) {
                ROLE_BADGE_PREFIXED_NICK_REGEX.matchEntire(curr.text)
            } else {
                null
            }
            val sameRowAsLeftRoleBadge = sameRowAsRoleBadge && curr.boundsLeft <= (screenWidth * 0.48).toInt()
            val sameRowAsRightRoleBadge = sameRowAsRoleBadge && curr.boundsRight >= rightNickColumnMinRight

            if (next != null) {
                val nextSpeaker = classifySpeaker(next, screenWidth)
                val nextHeight = next.boundsBottom - next.boundsTop
                val verticalGap = next.boundsTop - curr.boundsBottom
                val hasStrippedQuoteBetween = strippedQuoteSpans.any { (qTop, qBottom) ->
                    qTop >= curr.boundsBottom - 16 && qBottom <= next.boundsTop + 16
                }
                val quoteGapAllowance = if (hasStrippedQuoteBetween) {
                    (screenHeight * 0.085).toInt().coerceAtLeast(180)
                } else {
                    0
                }
                val effectiveMaxVerticalGap = maxVerticalGap + quoteGapAllowance
                val effectiveMaxCaptionGap = maxCaptionGap + quoteGapAllowance

                // Case A: Left-side sender nickname header above `[对方]`/`[群友X]` bubble.
                // Note: Do NOT require `currSpeaker == ChatSpeaker.OTHER` because a 19-char left-anchored
                // nickname (`left=242..888` on 1080p) has `centerX > 540`!
                val isLeftHeaderCandidate = curr.boundsLeft <= leftNickColumnLimit ||
                    sameRowAsLeftRoleBadge ||
                    (rolePrefixMatch != null && curr.boundsLeft <= (screenWidth * 0.48).toInt())
                val leftColumnAligned = (curr.boundsLeft - next.boundsLeft) in -maxLeftDelta..(maxLeftDelta + 132)
                if (nextSpeaker == ChatSpeaker.OTHER &&
                    isLeftHeaderCandidate &&
                    leftColumnAligned &&
                    verticalGap in -12..effectiveMaxVerticalGap
                ) {
                    val alignedWithLeftAvatarSpan = currHeight <= maxNickHeight && leftAvatarRects.any { av ->
                        abs(curr.boundsTop - av.boundsTop) <= 28 &&
                            curr.boundsLeft >= av.boundsRight - 24 &&
                            next.boundsTop > curr.boundsTop + 18 &&
                            next.boundsTop <= av.boundsBottom + 8 + quoteGapAllowance
                    }
                    val hasClassicShortShape = currSpeaker == ChatSpeaker.OTHER &&
                        curr.boundsLeft <= leftNickColumnLimit &&
                        abs(curr.boundsLeft - next.boundsLeft) <= maxLeftDelta &&
                        curr.text.length in 1..14 &&
                        !curr.text.any { it in "，。？！：；,.?!～~" } &&
                        currHeight <= maxNickHeight &&
                        (
                            nextHeight >= (currHeight * 1.18).toInt() ||
                                (leftShortTextFreq[curr.text] ?: 0) >= 2 ||
                                (isGroupChat && nextHeight >= (currHeight * 0.85).toInt())
                            )
                    val hasGroupCaptionFontShape = isGroupChat &&
                        currHeight <= captionFontMaxHeight &&
                        curr.text.length in 1..40 &&
                        verticalGap <= effectiveMaxCaptionGap &&
                        nextHeight >= (currHeight * 1.18).toInt()

                    if (sameRowAsLeftRoleBadge ||
                        rolePrefixMatch != null ||
                        alignedWithLeftAvatarSpan ||
                        hasClassicShortShape ||
                        hasGroupCaptionFontShape
                    ) {
                        val extractedNick = (
                            rolePrefixMatch?.groupValues?.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() }
                                ?: curr.text
                            ).replace('\u2005', ' ').trim()
                        if (extractedNick.isNotEmpty()) {
                            viewportKnownGroupNicknames.add(extractedNick)
                        }
                        val hideRawNick = sameRowAsLeftRoleBadge || rolePrefixMatch != null
                        pairedCandidates.add(
                            PendingPairedMessage(
                                speaker = ChatSpeaker.OTHER,
                                text = next.text,
                                boundsTop = next.boundsTop,
                                rawNickname = extractedNick,
                                omitRawNickInAlias = hideRawNick
                            )
                        )
                        idx += 2
                        pairedWithNext = true
                    }
                }

                // Case B: Right-side self group nickname header (`Balthazar`) above `[我]`'s own bubble.
                if (!pairedWithNext &&
                    nextSpeaker == ChatSpeaker.SELF &&
                    curr.boundsRight >= rightNickColumnMinRight &&
                    abs(curr.boundsRight - next.boundsRight) <= maxLeftDelta &&
                    verticalGap in -12..maxVerticalGap &&
                    currHeight <= maxNickHeight
                ) {
                    val alignedWithRightAvatarSpan = rightAvatarRects.any { av ->
                        abs(curr.boundsTop - av.boundsTop) <= 28 &&
                            curr.boundsRight <= av.boundsLeft + 24 &&
                            next.boundsTop > curr.boundsTop + 18 &&
                            next.boundsTop <= av.boundsBottom + 8
                    }
                    val hasRightGroupCaptionShape = isGroupChat &&
                        currHeight <= captionFontMaxHeight &&
                        curr.text.length in 1..32 &&
                        verticalGap <= maxCaptionGap &&
                        nextHeight >= (currHeight * 1.18).toInt()
                    val isRepeatedRightHeader = isGroupChat &&
                        curr.text.length in 1..16 &&
                        !curr.text.any { it in "，。？！：；,.?!～~" } &&
                        (rightShortTextFreq[curr.text] ?: 0) >= 2 &&
                        nextHeight >= (currHeight * 1.12).toInt()

                    if (sameRowAsRightRoleBadge ||
                        rolePrefixMatch != null ||
                        alignedWithRightAvatarSpan ||
                        hasRightGroupCaptionShape ||
                        isRepeatedRightHeader
                    ) {
                        pairedCandidates.add(
                            PendingPairedMessage(
                                speaker = ChatSpeaker.SELF,
                                text = next.text,
                                boundsTop = next.boundsTop,
                                rawNickname = ""
                            )
                        )
                        idx += 2
                        pairedWithNext = true
                    }
                }
            }

            // Standalone role-badge nickname row above a sticker/image (with no text bubble below):
            // never emit a `[群主] 昵称` / `[管理员] 昵称` header as a spoken chat message,
            // while still recording the nickname for `@mention` symbolic normalization!
            if (!pairedWithNext && (sameRowAsRoleBadge || rolePrefixMatch != null)) {
                val standaloneNick = (
                    rolePrefixMatch?.groupValues?.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: curr.text
                    ).replace('\u2005', ' ').trim()
                if (standaloneNick.isNotEmpty()) {
                    viewportKnownGroupNicknames.add(standaloneNick)
                }
                idx += 1
                continue
            }

            if (!pairedWithNext) {
                val plainCurr = curr.text.replace('\u2005', ' ').trim()
                val quoteHeaderMatch = QUOTED_SENDER_TIME_CAPTURE_REGEX.matchEntire(plainCurr)
                val quoteSender = quoteHeaderMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val isQuotedSenderTimeHeader = quoteSender.isNotEmpty() && (
                    quoteSender in viewportKnownGroupNicknames ||
                        leftShortTextFreq.containsKey(quoteSender) ||
                        ASCII_HANDLE_HEADER_REGEX.matches(quoteSender)
                    )
                if (isQuotedSenderTimeHeader) {
                    idx += 1
                    continue
                }
                pairedCandidates.add(
                    PendingPairedMessage(
                        speaker = currSpeaker,
                        text = curr.text,
                        boundsTop = curr.boundsTop,
                        rawNickname = ""
                    )
                )
                idx += 1
            }
        }

        if (pairedCandidates.isEmpty()) {
            return buildEmptyCapturedContext()
        }

        // Deduplicate adjacent identical or substring-overlapping nodes at almost identical Y positions
        val deduplicated = mutableListOf<PendingPairedMessage>()
        for (msg in pairedCandidates) {
            val prev = deduplicated.lastOrNull()
            if (prev != null && abs(msg.boundsTop - prev.boundsTop) <= 24) {
                if (prev.text == msg.text || prev.text.contains(msg.text)) {
                    if (prev.rawNickname.isBlank() && msg.rawNickname.isNotBlank()) {
                        deduplicated[deduplicated.lastIndex] = prev.copy(
                            rawNickname = msg.rawNickname,
                            omitRawNickInAlias = msg.omitRawNickInAlias
                        )
                    }
                    continue
                }
                if (msg.text.contains(prev.text)) {
                    deduplicated[deduplicated.lastIndex] = if (msg.rawNickname.isBlank() && prev.rawNickname.isNotBlank()) {
                        msg.copy(
                            rawNickname = prev.rawNickname,
                            omitRawNickInAlias = prev.omitRawNickInAlias
                        )
                    } else {
                        msg
                    }
                    continue
                }
            }
            deduplicated.add(msg)
        }

        // Take last `maxMessages` and enforce `maxTotalChars` budget from bottom (most recent) up
        val tailWindow = deduplicated.takeLast(maxMessages.coerceAtLeast(1))
        val selectedReversed = mutableListOf<PendingPairedMessage>()
        var charCount = 0
        for (msg in tailWindow.asReversed()) {
            val clippedText = if (msg.text.length > 180) {
                msg.text.take(180) + "…"
            } else {
                msg.text
            }
            if (charCount + clippedText.length > maxTotalChars && selectedReversed.isNotEmpty()) {
                break
            }
            selectedReversed.add(msg.copy(text = clippedText))
            charCount += clippedText.length
        }

        val rawFinalMessages = selectedReversed.asReversed()
        if (rawFinalMessages.any { it.rawNickname.isNotBlank() } || viewportKnownGroupNicknames.size >= 2) {
            isGroupChat = true
        }

        // Pre-assign ordered group aliases (`群友A` / `群友B(terwer)`) and pure base aliases (`群友A` / `群友B`)
        // across both active speakers and viewport-visible members (e.g. image-only senders above),
        // then normalize `@<群友原昵称>` inside message bodies BEFORE PII redaction.
        val nicknameToGroupAlias = LinkedHashMap<String, String>()
        val rawNickToDisplayAlias = LinkedHashMap<String, String>()
        val nicknameToBaseAlias = LinkedHashMap<String, String>()
        var nextAliasOrdinal = 0

        for (msg in rawFinalMessages) {
            if (msg.speaker == ChatSpeaker.OTHER && msg.rawNickname.isNotBlank()) {
                val rawKey = msg.rawNickname.replace('\u2005', ' ').replace(Regex("""\s+"""), " ").trim()
                if (rawKey.isEmpty()) continue
                if (!rawNickToDisplayAlias.containsKey(rawKey)) {
                    val sanitizedKey = (
                        if (redactPii) {
                            LocalPiiRedactor.redactText(rawKey, context = context).redactedText.trim()
                        } else {
                            rawKey
                        }
                        )
                        .replace('[', '‹')
                        .replace(']', '›')
                    val shouldOmitRawNick = msg.omitRawNickInAlias ||
                        sanitizedKey.length > 8 ||
                        sanitizedKey.any { it in "，。？！：；,.?!～~" }
                    val existingDisplay = nicknameToGroupAlias[sanitizedKey]
                    if (existingDisplay != null) {
                        rawNickToDisplayAlias[rawKey] = existingDisplay
                        val base = existingDisplay.substringBefore('(')
                        nicknameToBaseAlias[rawKey] = base
                        nicknameToBaseAlias[sanitizedKey] = base
                    } else {
                        val letter = ('A' + nextAliasOrdinal.coerceAtMost(25))
                        nextAliasOrdinal += 1
                        val base = "群友$letter"
                        val display = if (shouldOmitRawNick) base else "$base($sanitizedKey)"
                        nicknameToGroupAlias[sanitizedKey] = display
                        rawNickToDisplayAlias[rawKey] = display
                        nicknameToBaseAlias[rawKey] = base
                        nicknameToBaseAlias[sanitizedKey] = base
                    }
                }
            }
        }

        for (knownNick in viewportKnownGroupNicknames) {
            val cleanKnown = knownNick.replace('\u2005', ' ').replace(Regex("""\s+"""), " ").trim()
            if (cleanKnown.isNotEmpty() && !nicknameToBaseAlias.containsKey(cleanKnown)) {
                val letter = ('A' + nextAliasOrdinal.coerceAtMost(25))
                nextAliasOrdinal += 1
                nicknameToBaseAlias[cleanKnown] = "群友$letter"
            }
        }

        val mentionNormalizedMessages = rawFinalMessages.map { msg ->
            val normalizedText = normalizeGroupMentionsInMessage(
                rawText = msg.text,
                isGroupChat = isGroupChat || nicknameToBaseAlias.isNotEmpty(),
                nicknameToBaseAlias = nicknameToBaseAlias
            )
            msg.copy(text = normalizedText)
        }
        if (mentionNormalizedMessages.any { it.text.contains("[@群友") }) {
            isGroupChat = true
        }

        val redactionSession = if (redactPii && mentionNormalizedMessages.isNotEmpty()) {
            val joinedText = mentionNormalizedMessages.joinToString("\n") { it.text }
            LocalPiiRedactor.redactText(
                rawText = joinedText,
                context = context,
                sourceNodes = nodes
            )
        } else if (redactPii) {
            LocalPiiRedactor.redactText("", context = context, sourceNodes = nodes)
        } else {
            RedactionSession.EMPTY
        }

        val redactedBodyLines = if (redactPii && redactionSession.redactionCount > 0) {
            redactionSession.redactedText.split("\n")
        } else {
            emptyList()
        }

        val finalMessages = mentionNormalizedMessages.mapIndexed { i, msg ->
            val cleanText = if (redactedBodyLines.isNotEmpty()) {
                redactedBodyLines.getOrElse(i) { msg.text }
            } else {
                msg.text
            }
            val rawKey = msg.rawNickname.replace('\u2005', ' ').replace(Regex("""\s+"""), " ").trim()
            val alias = if (msg.speaker == ChatSpeaker.OTHER && rawKey.isNotEmpty()) {
                rawNickToDisplayAlias[rawKey].orEmpty()
            } else {
                ""
            }
            ExtractedChatMessage(
                speaker = msg.speaker,
                text = cleanText,
                boundsTop = msg.boundsTop,
                senderAlias = alias
            )
        }

        if (finalMessages.any { it.speaker == ChatSpeaker.OTHER && MENTION_ME_SIGNAL_REGEX.containsMatchIn(it.text) }) {
            hasExplicitMentionMe = true
        }
        val distinctOtherSenders = nicknameToGroupAlias.values.toList()
        val recentSelfTurns = finalMessages.takeLast(5).count { it.speaker == ChatSpeaker.SELF }
        val finalTopology = ChatTopology(
            isGroupChat = isGroupChat,
            groupMemberCount = groupMemberCount,
            distinctOtherSenders = distinctOtherSenders,
            recentSelfTurns = recentSelfTurns,
            hasExplicitMentionMe = hasExplicitMentionMe,
            roleMode = resolveRoleMode(
                isGroupChat = isGroupChat,
                distinctOtherSendersCount = distinctOtherSenders.size,
                recentSelfTurns = recentSelfTurns,
                hasExplicitMentionMe = hasExplicitMentionMe
            )
        )

        val previewTarget = finalMessages.lastOrNull { it.speaker == ChatSpeaker.OTHER }
            ?: finalMessages.lastOrNull()

        val previewText = if (previewTarget != null) {
            val body = if (previewTarget.text.length > 32) {
                "…" + previewTarget.text.takeLast(30)
            } else {
                previewTarget.text
            }
            val prefixTag = if (previewTarget.speaker == ChatSpeaker.OTHER && previewTarget.senderAlias.isNotBlank()) {
                "[${previewTarget.senderAlias}]"
            } else {
                previewTarget.speaker.tag
            }
            "$prefixTag $body"
        } else {
            ""
        }

        return CapturedChatContext(
            packageName = packageName,
            appName = appName,
            messages = finalMessages,
            latestMessagePreview = previewText,
            capturedAtMs = nowMs,
            redactionSession = redactionSession,
            topology = finalTopology
        )
    }
}
