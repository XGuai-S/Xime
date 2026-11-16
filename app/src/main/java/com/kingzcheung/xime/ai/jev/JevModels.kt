package com.kingzcheung.xime.ai.jev

/**
 * Semantic tension tier mapped from the `Lv.1` .. `Lv.9` subtext radar score.
 * Rendered ONLY on the `6.dp` status dot inside the `25.dp` radar bar:
 * - `Lv.1–3` -> [SAFE_GREEN] (`#10B981` Emerald)
 * - `Lv.4–6` -> [PROBE_AMBER] (`#F59E0B` Amber)
 * - `Lv.7–9` -> [DANGER_ROSE] (`#F43F5E` Rose)
 */
enum class JevTensionTier(val label: String) {
    SAFE_GREEN("安全顺风"),
    PROBE_AMBER("拉扯试探"),
    DANGER_ROSE("高危防御")
}

/**
 * 3-State Role Topology Mode for `✦ Jev 军师` to distinguish 1v1 private chat,
 * group chat involved party, and group chat 3rd-party bystander.
 */
enum class JevRoleMode(val label: String) {
    PRIVATE_1V1("1v1私聊对象"),
    GROUP_INVOLVED_PARTY("群聊当事人"),
    GROUP_BYSTANDER("群聊第三方旁观者")
}

/**
 * Conversation topology extracted from top-bar group features, multi-speaker nickname pairing,
 * and recent user turn / `@` mention signals.
 */
data class ChatTopology(
    val isGroupChat: Boolean = false,
    val groupMemberCount: Int = 0,
    val distinctOtherSenders: List<String> = emptyList(),
    val recentSelfTurns: Int = 0,
    val hasExplicitMentionMe: Boolean = false,
    val roleMode: JevRoleMode = JevRoleMode.PRIVATE_1V1
)

/**
 * A single selectable strategy option card in `✦ Jev 军师`.
 *
 * @property label Short 2–6 character tactical tag (e.g. `太极推挡`, `锚定底价`, `神评造梗`)
 * @property replyText Pure reply draft ready to be previewed/injected into the input field
 * @property probability Normalized recommendation weight percentage (`0..100`, 3 options sum to `100`)
 */
data class JevStrategyOption(
    val label: String,
    val replyText: String,
    val probability: Int = 0
)

/**
 * Structured output of a `✦ Jev 军师` tactical analysis and 3-strategy generation run.
 */
data class JevCopilotResult(
    val level: Int,
    val tag: String,
    val subtext: String,
    val options: List<JevStrategyOption>,
    val usedVision: Boolean = false,
    val redactionCount: Int = 0
) {
    val clampedLevel: Int
        get() = level.coerceIn(1, 9)

    val tensionTier: JevTensionTier
        get() = when {
            clampedLevel <= 3 -> JevTensionTier.SAFE_GREEN
            clampedLevel <= 6 -> JevTensionTier.PROBE_AMBER
            else -> JevTensionTier.DANGER_ROSE
        }
}

/**
 * 5 All-Scene Tactical Domain Profiles automatically matched from the host app package name.
 */
enum class JevDomainProfile(
    val badgeTitle: String,
    val promptDirective: String
) {
    IM_CHAT(
        badgeTitle = "私聊社交",
        promptDirective = "场景：熟人/职场/情感私聊。重点解码多轮关系温度、微表情（如[微笑]/🙂反话）与隐性情绪边界；严禁客服腔与油腻画饼，提供给台阶破冰、温和设限、松弛接梗三类真人感话术。"
    ),
    COMMERCE_BARGAIN(
        badgeTitle = "交易博弈",
        promptDirective = "场景：二手闲鱼/电商砍价或售后维权。重点识别探底压价、到手刀、推卸运费或售后拖延；平视博弈不卑微，提供锚定底价、条件置换、体面送客/维权施压三类高筹码话术。"
    ),
    CAREER_NEGOTIATION(
        badgeTitle = "职场谈薪",
        promptDirective = "场景：招聘求职与职场晋升谈薪（BOSS直聘/脉脉/猎聘）。重点识别压薪试探、画饼预期、离职原因盘问；提供价值锚定、区间博弈、体面缓兵三类专业平视话术。"
    ),
    SOCIAL_COMMENT(
        badgeTitle = "高赞互动",
        promptDirective = "场景：小红书/抖音/知乎/微博/B站公开评论区或私信互动。重点捕捉网感梗点、争议杠精或种草咨询；拒绝AI说教感，提供神评造梗、松弛共情、降维控评三类高赞回复。"
    ),
    FORMAL_WORKFLOW(
        badgeTitle = "商务协同",
        promptDirective = "场景：邮件往来、协同文档或通用办公沟通。重点识别权责归属、排期推进与跨部门边界；提供周全对齐、温和挡责、锚定闭环三类干练得体回复。"
    );

    companion object {
        private val IM_PACKAGES = setOf(
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
            "org.thunderdog.challegram",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.discord",
            "com.Slack",
            "jp.naver.line.android",
            "com.facebook.orca",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )

        private val COMMERCE_PACKAGES = setOf(
            "com.taobao.idlefish",
            "com.wuba.zhuanzhuan",
            "com.taobao.taobao",
            "com.tmall.wireless",
            "com.xunmeng.pinduoduo",
            "com.jingdong.app.mall",
            "com.shizhuang.duapp",
            "com.sankuai.meituan",
            "me.ele",
            "com.achievo.vipshop",
            "com.alibaba.wireless"
        )

        private val CAREER_PACKAGES = setOf(
            "com.hpbr.bosszhipin",
            "com.taou.maimai",
            "com.lietou.mishu",
            "com.job.android",
            "com.zhaopin.social",
            "com.linkedin.android",
            "com.kanzhun.kzapp",
            "com.lagou.lagou2"
        )

        private val SOCIAL_PACKAGES = setOf(
            "com.xingin.xhs",
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.smile.gifmaker",
            "com.kuaishou.nebula",
            "com.sina.weibo",
            "com.weico.international",
            "com.zhihu.android",
            "tv.danmaku.bili",
            "com.bilibili.app.in",
            "com.twitter.android",
            "com.instagram.android",
            "com.instagram.barcelona",
            "com.douban.frodo",
            "com.coolapk.market",
            "com.hupu.games",
            "com.tieba.baidu",
            "com.baidu.tieba"
        )

        fun fromPackage(packageName: String): JevDomainProfile {
            val clean = packageName.trim()
            if (clean.isEmpty()) return FORMAL_WORKFLOW
            val lower = clean.lowercase()
            return when {
                clean in IM_PACKAGES || lower in IM_PACKAGES ||
                    lower.contains("dingtalk") || lower.contains("aliding") ||
                    lower.contains("rimet") || lower.contains("wework") ||
                    lower.contains("lark") || lower.contains("feishu") -> IM_CHAT
                clean in COMMERCE_PACKAGES || lower in COMMERCE_PACKAGES ||
                    lower.contains("idlefish") || lower.contains("zhuanzhuan") ||
                    lower.contains("pinduoduo") || lower.contains("taobao") ||
                    lower.contains("jingdong") -> COMMERCE_BARGAIN
                clean in CAREER_PACKAGES || lower in CAREER_PACKAGES ||
                    lower.contains("bosszhipin") || lower.contains("maimai") ||
                    lower.contains("lietou") || lower.contains("zhaopin") ||
                    lower.contains("linkedin") -> CAREER_NEGOTIATION
                clean in SOCIAL_PACKAGES || lower in SOCIAL_PACKAGES ||
                    lower.contains("xingin.xhs") || lower.contains("aweme") ||
                    lower.contains("gifmaker") || lower.contains("weibo") ||
                    lower.contains("zhihu") || lower.contains("danmaku.bili") -> SOCIAL_COMMENT
                else -> FORMAL_WORKFLOW
            }
        }
    }
}
