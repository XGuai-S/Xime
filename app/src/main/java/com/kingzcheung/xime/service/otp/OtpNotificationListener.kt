package com.kingzcheung.xime.service.otp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

/**
 * 验证码通知捕获（方案 A）：绕过国内 ROM 对 `SMS_RECEIVED` 广播的过滤。
 *
 * 背景：MIUI/HyperOS 的 `BroadcastQueueInjector` 只把短信广播发给默认短信应用，
 * 第三方应用即使有 RECEIVE_SMS 权限也收不到（真机日志 `MIUILOG- Sms Filter ...`）。
 * 但**通知**不受此限制，因此改为监听通知栏。
 *
 * 覆盖两类来源（比短信更广）：
 * 1. 短信通知（ROM 会发通知，但广播被拦）；
 * 2. App 内验证码通知（Google、银行、微信等）。
 *
 * **常驻能力**：本服务由系统绑定运行，**键盘不弹出时也能收到验证码**，
 * 满足「后台也要能用」的需求。
 *
 * 授权：用户需在 `设置 → 通知与状态栏 → 通知使用权` 里勾选「曦码」。
 */
class OtpNotificationListener : NotificationListenerService() {

    /**
     * Listener 连上（开机 / 授权后 / 被系统重建）时，**补扫当前通知栏**。
     *
     * `onNotificationPosted` 只推送「新」通知，连接前已有的通知不会重放。
     * 验证码短信往往在 listener 就绪前就到了（真机实测：短信 07:02:23，
     * listener 07:01:22 已连但通知仍在栏里却未补收），这里主动扫一遍吞掉差集。
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            val active = activeNotifications ?: return@runCatching
            FileLogger.i(TAG, "listener connected, scanning ${active.size} active notifications")
            active.forEach { onNotificationPosted(it) }
        }.onFailure {
            FileLogger.e(TAG, "scan active notifications failed", it)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        // 默认关闭是隐私设计：读设置失败时必须保持关闭，不能退化成"读不到就采集"。
        if (!runCatching { SettingsPreferences.isOtpAutofillEnabled(this) }.getOrDefault(false)) return
        // 聊天类应用不采集：微信/QQ 里随便说一句「验证码 123456」就会被当验证码，
        // 还会把用户手头的剪贴板换掉（用户 2026-11 反馈的误报来源）。
        val pkg = sbn?.packageName.orEmpty()
        if (pkg in IM_PACKAGES) {
            FileLogger.d(TAG, "skip otp from IM package=$pkg")
            return
        }

        runCatching {
            val extras = notification.extras ?: return@runCatching
            // 验证码可能出现在任意字段，全部拼起来再匹配。
            //
            // **tickerText 必取**：MIUI 的短信通知会把 EXTRA_TEXT 截断成
            // 「…证码：911751，5分钟内有效…」——「验证」两字被吃掉，
            // 导致关键词「验证码」匹配失败（真机实测 matched=false 的根因）。
            // 完整短信在 tickerText（以及 BigTextStyle 的 EXTRA_BIG_TEXT）里。
            val body = listOf(
                notification.tickerText?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            ).filter { it.isNotBlank() }.joinToString(" ")

            if (body.isBlank()) {
                // 关键诊断点：MIUI 的短信通知若取不到 title/text/bigText，会走到这里。
                // 原来静默 return，导致「明明有通知却零日志」无法定位。
                FileLogger.d(TAG, "notif from=${sbn.packageName} body blank, keys=${extras.keySet().size}")
                return@runCatching
            }
            // 捕获时刻取通知的**发布时间**，不是"现在"。
            // onListenerConnected 会补扫通知栏里已有的历史通知（listener 重建、
            // 重新授权、开机后），用当前时间会把几小时前的旧验证码当成刚收到，
            // 进而被自动填进输入框（用户 2026-11 修复）。
            val extracted = OtpExtractor.extract(body)
            val postedAt = sbn?.postTime ?: 0L
            val candidate = extracted?.copy(
                capturedAt = postedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
            // 诊断：记录收到的通知来源与是否命中（不记正文，避免泄露验证码）
            FileLogger.d(TAG, "notif from=${sbn.packageName} len=${body.length} matched=${candidate != null}")
            if (candidate == null) return@runCatching

            val isNew = OtpCollector.offer(candidate, source = "notif:${sbn.packageName}")
            if (!isNew) return@runCatching

            // 1) 应用内剪贴板历史（键盘剪贴板面板可查看/粘贴）
            runCatching {
                ClipboardManager.getInstance(this).addItem(candidate.primary)
            }.onFailure {
                FileLogger.w(TAG, "write otp to app clipboard history failed", it)
            }
            // 2) 不再写系统剪贴板：原来会**静默替换**用户手头的剪贴板内容
            //    （任何前台应用都能读到），属于越权副作用。验证码现在只进应用内
            //    剪贴板历史（键盘剪贴板面板可粘贴），候选栏也能直接点选上屏。
        }.onFailure {
            FileLogger.e(TAG, "handle notification otp failed", it)
        }
    }

    private companion object {
        const val TAG = "OtpNotificationListener"

        /** 聊天/IM 类包名：它们的通知里出现「验证码 + 数字」通常是聊天内容，不是验证码。 */
        val IM_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "com.ss.android.lark",
            "com.alibaba.android.rimet",
            "com.tencent.wework",
            "com.immomo.momo",
            "com.sina.weibo",
        )
    }
}
