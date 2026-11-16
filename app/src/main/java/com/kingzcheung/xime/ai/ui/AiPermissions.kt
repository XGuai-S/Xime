package com.kingzcheung.xime.ai.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/**
 * AI 功能的系统权限引导（移植自 TypeFree 的权限申请流程，按 Xime 入口重写）。
 *
 * 三类权限：
 * 1. **麦克风**（RECORD_AUDIO）：语音识别必需，运行时权限；
 * 2. **悬浮窗**（SYSTEM_ALERT_WINDOW）：悬浮语音胶囊必需，特殊权限需跳设置；
 * 3. **无障碍**：读屏上下文 / 截屏帮回必需，需用户在系统设置里手动启用。
 *
 * 设计原则：不主动弹窗骚扰。只在用户**第一次使用对应功能**时引导，
 * 且每次都给出「为什么需要」的说明。
 */
object AiPermissions {

    const val REQ_RECORD_AUDIO = 0xA101

    /** 是否已授予麦克风权限。 */
    fun hasMicrophone(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 是否已授予悬浮窗权限。 */
    fun canDrawOverlays(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

    /** 无障碍服务是否已启用（按服务名匹配，避免误判其他应用）。 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${context.packageName}.ai.bubble.XimeAccessibilityService"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * 跳系统设置开启悬浮窗权限。
     *
     * 三级降级（MIUI/HyperOS 对 ACTION_MANAGE_OVERLAY_PERMISSION 的处理各版本不同，
     * 只给包名参数时可能直接忽略或跳到空白页）：
     * 1. 带 package: 的 MANAGE_OVERLAY_PERMISSION
     * 2. 不带参数的 MANAGE_OVERLAY_PERMISSION（全局悬浮窗列表）
     * 3. 应用详情页（手动点"其他权限"）
     */
    fun requestOverlay(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
        for (i in attempts) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return
        }
        Toast.makeText(context, "无法打开悬浮窗设置，请到 设置→应用→曦码→其他权限 手动开启", Toast.LENGTH_LONG).show()
    }

    /**
     * 跳系统辅助功能设置开启读屏。
     *
     * 部分 ROM 的 ACCESSIBILITY_SETTINGS 需要 Intent 组件显式指定，
     * 失败时退回 ACTION_SETTINGS 让用户自己进。
     */
    fun requestAccessibility(context: Context) {
        val attempts = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in attempts) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return
        }
        Toast.makeText(context, "无法打开无障碍设置，请手动前往 设置→更多设置→无障碍", Toast.LENGTH_LONG).show()
    }

    /**
     * 请求麦克风权限。
     *
     * Xime 是输入法服务，本身不是 Activity，无法直接 requestPermissions。
     * 这里借道主应用 Activity（MainActivity 提供 requestPermissions 入口）。
     *
     * @return true 表示已授予或已发起请求
     */
    fun requestMicrophone(activity: Activity?) {
        if (activity == null) return
        runCatching {
            activity.requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_AUDIO
            )
        }
    }

    /** 缺失权限的说明文本（给设置页/面板提示用）。 */
    fun missingSummary(context: Context): String {
        val missing = buildList {
            if (!hasMicrophone(context)) add("麦克风")
            if (!canDrawOverlays(context)) add("悬浮窗")
            if (!isAccessibilityEnabled(context)) add("无障碍（读屏帮回）")
        }
        return if (missing.isEmpty()) "全部权限已就绪"
        else "未开启：" + missing.joinToString("、")
    }
}
