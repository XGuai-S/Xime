package com.kingzcheung.xime.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Ballot
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Sms
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 「验证码自动填充」独立设置页。
 *
 * 与「语音转文本」同一套交互：主页只放一个开关 + 箭头，详细配置收在此页。
 *
 * 两级开关：
 * 1. 收集开关（通知使用权就绪后才允许打开）—— 决定是否从通知捕获验证码；
 * 2. 自动填入开关（从属项）—— 决定捕获后是否自动写进数字输入框，
 *    关掉它仍会显示在候选栏并写入剪贴板。
 */
@Composable
fun OtpSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current

    var otpEnabled by remember { mutableStateOf(SettingsPreferences.isOtpAutofillEnabled(context)) }
    var otpAutoFill by remember { mutableStateOf(SettingsPreferences.isOtpAutoFillEnabled(context)) }

    // 通知使用权：从系统授权页返回时必须刷新，仅靠重组不可靠（那是另一个 Activity）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var notifGranted by remember { mutableStateOf(otpNotifAccessGranted(context)) }
    // 用户点了开关但尚未授权 → 记下意图，授权回来自动打开
    var pendingEnable by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notifGranted = otpNotifAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(notifGranted, pendingEnable) {
        when {
            notifGranted && pendingEnable -> {
                otpEnabled = true
                SettingsPreferences.setOtpAutofillEnabled(context, true)
                pendingEnable = false
            }
            !notifGranted && otpEnabled -> {
                otpEnabled = false
                SettingsPreferences.setOtpAutofillEnabled(context, false)
            }
        }
    }

    SettingsScreenScaffold(title = "验证码自动填充", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                SettingsSection(content = {
                    if (notifGranted) {
                        SettingsItem(
                            icon = Icons.TwoTone.CheckCircle,
                            title = "通知使用权",
                            subtitle = "已授予，可从通知捕获验证码",
                            onClick = {},   // 已授权，无需跳转
                        )
                    } else {
                        SettingsItem(
                            icon = Icons.TwoTone.Warning,
                            title = "通知使用权（未授予）",
                            subtitle = "点击前往授权，在列表中勾选「曦码」",
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                    )
                                }.onFailure {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                android.net.Uri.fromParts(
                                                    "package", context.packageName, null,
                                                ),
                                            )
                                        )
                                    }
                                }
                            },
                            showArrow = true,
                        )
                    }
                })
            }

            item {
                SettingsSection(content = {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Sms,
                        title = "收集验证码",
                        subtitle = when {
                            !otpEnabled -> "关闭：不收集验证码"
                            notifGranted -> "从通知捕获，显示在候选栏并写入剪贴板"
                            else -> "开启前需先授予「通知使用权」"
                        },
                        checked = otpEnabled,
                        onCheckedChange = { wantOn ->
                            if (wantOn) {
                                if (notifGranted) {
                                    otpEnabled = true
                                    SettingsPreferences.setOtpAutofillEnabled(context, true)
                                } else {
                                    // 未授权：记意图 + 跳授权页，开关保持关闭
                                    pendingEnable = true
                                    android.widget.Toast.makeText(
                                        context,
                                        "请在列表里勾选「曦码」以授予通知使用权",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                    runCatching {
                                        context.startActivity(
                                            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                        )
                                    }
                                }
                            } else {
                                otpEnabled = false
                                SettingsPreferences.setOtpAutofillEnabled(context, false)
                            }
                        }
                    )
                    if (otpEnabled && notifGranted) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        SettingsToggleItem(
                            icon = Icons.TwoTone.Ballot,
                            title = "自动填入验证码",
                            subtitle = if (otpAutoFill) {
                                "点进数字输入框时自动写入；金额框与密码框不填"
                            } else {
                                "已关闭：验证码只在候选栏与剪贴板提供，不自动写入"
                            },
                            checked = otpAutoFill,
                            modifier = Modifier.padding(start = 32.dp),
                            onCheckedChange = { on ->
                                otpAutoFill = on
                                SettingsPreferences.setOtpAutoFillEnabled(context, on)
                            }
                        )
                    }
                })
            }

            item {
                SettingsSection(content = {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "• 验证码有效期 2 分钟：超过后不再显示、不再自动填入，并自动清理。\n" +
                                "• 按键盘任意键后，候选栏中的验证码会撤走，但不影响粘贴与自动填入。\n" +
                                "• 同时写入应用内剪贴板历史与系统剪贴板，长按粘贴即可得到最新验证码。\n" +
                                "• 需要「自启动」权限（系统应用管理里开启），否则通知监听无法绑定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                })
            }
        }
    }
}

/** 是否已授予本应用的「通知使用权」。 */
private fun otpNotifAccessGranted(context: android.content.Context): Boolean =
    (Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ) ?: "").contains(context.packageName)