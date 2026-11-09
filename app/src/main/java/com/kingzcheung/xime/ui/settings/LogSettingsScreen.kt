package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.LogLevel
import com.kingzcheung.xime.settings.LogLevelResolver
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

/**
 * 「日志」设置页：只保留**记录级别**与**日志查看器**。
 *
 * 记录级别收敛为三档：关闭 / 仅错误 / 调试。日常只需"仅错误"留档报障；
 * 排查问题切"调试"看全过程；"关闭"连错误也不落盘（仅 logcat 可见）。
 * 点选后立即生效，无需重启输入法。
 */
@Composable
fun LogSettingsContent(
    onBack: () -> Unit,
    onOpenLogViewer: () -> Unit,
) {
    val context = LocalContext.current

    // 与 FileLogger/IME 用同一套解析（界面覆盖 → xime.yaml → 默认 ERROR）
    var level by remember { mutableStateOf(LogLevelResolver.resolve(context)) }
    var showLevelDialog by remember { mutableStateOf(false) }

    if (showLevelDialog) {
        LogLevelDialog(
            current = level,
            onDismiss = { showLevelDialog = false },
            onSelect = { picked ->
                level = picked
                showLevelDialog = false
                // 写入界面覆盖值并立即生效，无需重启输入法
                SettingsPreferences.setLogLevelOverride(context, picked)
                FileLogger.setLogLevel(picked)
                Toast.makeText(context, "日志级别：${picked.label}", Toast.LENGTH_SHORT).show()
            },
        )
    }

    SettingsScreenScaffold(title = "日志", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(title = "日志记录") {
                SettingsItem(
                    icon = Icons.Default.BugReport,
                    title = "记录级别",
                    subtitle = "${level.label} · ${level.description}",
                    onClick = { showLevelDialog = true },
                    showArrow = true,
                )
                SettingsItem(
                    icon = Icons.Default.Visibility,
                    title = "日志查看器",
                    subtitle = "按文件查看内容，支持单文件分享与保存",
                    onClick = onOpenLogViewer,
                    showArrow = true,
                )
            }

            Text(
                text = "日志保存在应用私有目录，卸载应用会一并清除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * 记录级别选择对话框。
 *
 * 只暴露三档：关闭 / 仅错误 / 调试。用 M3 单选列表展示，每档一句说明，
 * 一眼看出当前生效的是哪一档。
 */
@Composable
private fun LogLevelDialog(
    current: LogLevel,
    onDismiss: () -> Unit,
    onSelect: (LogLevel) -> Unit,
) {
    // UI 只暴露三档；内部 LogLevel 仍保留完整枚举供引擎/文件日志按级别过滤。
    val options = listOf(LogLevel.OFF, LogLevel.ERROR, LogLevel.DEBUG)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
        title = { Text("记录级别") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = option == current,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
