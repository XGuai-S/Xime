package com.kingzcheung.xime.ai.bubble

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.kingzcheung.xime.ai.XimeAiApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FocusedInputSnapshot(
    val hasEditableFocus: Boolean = false,
    val isSensitivePassword: Boolean = false,
    val packageName: String = "",
    val inputType: Int = 0,
    val selectedText: String = "",
    val surroundingBeforeCursor: String = ""
)

object XimeBubbleController {

    private val _focusedInput = MutableStateFlow(FocusedInputSnapshot())
    val focusedInput: StateFlow<FocusedInputSnapshot> = _focusedInput.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isBubbleServiceRunning = MutableStateFlow(false)
    val isBubbleServiceRunning: StateFlow<Boolean> = _isBubbleServiceRunning.asStateFlow()

    private val _forcePreviewVisible = MutableStateFlow(false)
    val forcePreviewVisible: StateFlow<Boolean> = _forcePreviewVisible.asStateFlow()

    internal fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
        if (!connected) {
            _focusedInput.value = FocusedInputSnapshot()
        }
    }

    internal fun setBubbleServiceRunning(running: Boolean) {
        _isBubbleServiceRunning.value = running
    }

    internal fun updateFocusedInput(snapshot: FocusedInputSnapshot) {
        _focusedInput.value = snapshot
    }

    fun setForcePreviewVisible(visible: Boolean) {
        _forcePreviewVisible.value = visible
    }

    fun hasRecordAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        if (_isAccessibilityConnected.value) return true
        return try {
            val expected = ComponentName(context, XimeAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabledServices.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == expected
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 跳悬浮窗权限页。
     *
     * 原实现只有两级（带包名 → 不带包名），且第二次 startActivity 在 catch 里，
     * 一旦它也失败就静默什么也不发生。MIUI/HyperOS 对带包名的
     * ACTION_MANAGE_OVERLAY_PERMISSION 时有忽略，故补齐为三级并最终兜底到应用详情页，
     * 保证用户一定能到达可操作的位置。
     */
    fun openOverlayPermissionSettings(context: Context) {
        val attempts = listOf(
            // MIUI/HyperOS 专用：直接打开本应用的权限编辑页（「其他权限」在里面）。
            // 日志实测系统用的是 miui.intent.action.APP_PERM_EDITOR_PRIVATE，
            // 走这个能直达，避免 ACTION_MANAGE_OVERLAY_PERMISSION 被忽略。
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                .putExtra("extra_pkgname", context.packageName),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            // 兜底：应用详情页 →「其他权限」里可开悬浮窗
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        // 注意：用户关掉 Xime 的通知权限后，系统会拦掉本应用的 Toast
        // （NotificationService: Suppressing toast ... by user request），
        // 所以这里同时写日志，便于排查"点了没反应"。
        com.kingzcheung.xime.util.FileLogger.e(
            "XimeBubbleController",
            "openOverlayPermissionSettings: all intents failed"
        )
    }

    /**
     * 跳无障碍设置页。
     *
     * 原实现 catch 内为空，失败时用户完全收不到反馈。改为逐级降级 + 明确提示。
     */
    fun openAccessibilitySettings(context: Context) {
        val attempts = listOf(
            // MIUI 无障碍设置页（不同于 AOSP 的 ACTION_ACCESSIBILITY_SETTINGS）
            Intent("miui.intent.action.ACCESSIBILITY_SETTINGS"),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        com.kingzcheung.xime.util.FileLogger.e(
            "XimeBubbleController",
            "openAccessibilitySettings: all intents failed"
        )
    }

    /**
     * 跳系统应用权限页开启麦克风。
     *
     * 用于「永久拒绝」（USER_FIXED，用户勾了“不再询问”）之后：此时再调
     * requestPermissions 系统不会弹任何对话框，只会立刻返回 denied，表现为
     * 「点去开启没反应」。这里直接打开本应用的权限编辑页，让用户手动打开麦克风。
     */
    fun openMicrophonePermissionSettings(context: Context) {
        val attempts = listOf(
            // MIUI/HyperOS：应用权限编辑页，含「麦克风」单项可切
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                .putExtra("extra_pkgname", context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        com.kingzcheung.xime.util.FileLogger.e(
            "XimeBubbleController",
            "openMicrophonePermissionSettings: all intents failed"
        )
    }

    fun syncBubbleService(context: Context) {
        val appContext = context.applicationContext
        // 无障碍服务可能早于 AI 模块初始化就回调到这里；确保 init 后再读设置，
        // 否则 XimeAiApp.repository 会抛 "未初始化" 直接把进程干掉。
        if (!XimeAiApp.ensureInit(appContext)) return
        val settings = runCatching { XimeAiApp.repository.settings.value }.getOrNull() ?: return
        val intent = Intent(appContext, XimeBubbleService::class.java)
        if (settings.bubbleEnabled && hasOverlayPermission(appContext)) {
            try {
                // XimeBubbleService 是前台服务（microphone 类型）。从无障碍服务、
                // 后台等非前台上下文必须用 startForegroundService，否则 Android 8+
                // 会直接抛 IllegalStateException。
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (t: Exception) {
                // API 31+ 后台启动 microphone 前台服务会被拒绝；记录以便诊断（用户可回设置页手动开启）。
                com.kingzcheung.xime.util.FileLogger.w("XimeBubbleController", "start bubble service failed", t)
            }
        } else {
            try {
                appContext.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }
}