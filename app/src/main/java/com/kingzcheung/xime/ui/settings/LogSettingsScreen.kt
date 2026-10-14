package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Environment
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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kingzcheung.xime.settings.LogLevel
import com.kingzcheung.xime.settings.LogLevelResolver
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.logStatLine
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 「日志」设置页：开关 + 查看 + 导出 + 清理。
 *
 * 开关默认**开**，且对 release 正式包同样生效（旧实现把 v/d/i 写文件卡在 `BuildConfig.DEBUG`
 * 上，导致正式包既没有开关也拿不到日志）。关闭后仍保留 W/E 级：异常与崩溃必须有据可查，
 * 否则用户报障时无从下手。
 *
 * 导出是**汇总**所有来源（APP 日志 + RIME 引擎日志 + 插件错误）成一个文件再走系统分享：
 * 用户反馈时要的是完整上下文，分多个文件发过来反而拼不回现场。
 */
@Composable
fun LogSettingsContent(
    onBack: () -> Unit,
    onOpenLogViewer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 与 FileLogger/IME 用同一套解析（界面覆盖 → xime.yaml → 默认 ERROR）
    var level by remember { mutableStateOf(LogLevelResolver.resolve(context)) }
    // 当前级别是否来自 xime.yaml（而非界面覆盖）。用于文案与「改用配置文件」项是否可点。
    var levelSourceIsConfig by remember {
        mutableStateOf(SettingsPreferences.getLogLevelOverride(context) == null)
    }
    var showLevelDialog by remember { mutableStateOf(false) }
    var fileCount by remember { mutableStateOf(0) }
    var totalSize by remember { mutableLongStateOf(0L) }
    var exporting by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    suspend fun refreshStats() {
        withContext(Dispatchers.IO) {
            val files = FileLogger.getAllLogFiles()
            fileCount = files.size
            totalSize = files.sumOf { it.length() }
        }
    }

    LaunchedEffect(Unit) { refreshStats() }

    if (showLevelDialog) {
        LogLevelDialog(
            current = level,
            onDismiss = { showLevelDialog = false },
            onSelect = { picked ->
                level = picked
                levelSourceIsConfig = false
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
                    icon = Icons.Default.Tune,
                    title = "在 xime.yaml 中配置",
                    subtitle = if (levelSourceIsConfig) {
                        "当前取自配置文件的 logging.level"
                    } else {
                        "点此改用配置文件（写 logging: { level: warn }）"
                    },
                    onClick = {
                        // 清除界面覆盖值，让 xime.yaml 的 logging.level 重新生效
                        SettingsPreferences.clearLogLevelOverride(context)
                        val fromConfig = LogLevelResolver.resolve(context)
                        level = fromConfig
                        levelSourceIsConfig = true
                        FileLogger.setLogLevel(fromConfig)
                        Toast.makeText(
                            context,
                            "已改用 xime.yaml：${fromConfig.label}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    showArrow = false,
                )
            }

            SettingsSection(title = "查看与导出") {
                SettingsItem(
                    icon = Icons.Default.Visibility,
                    title = "日志查看器",
                    subtitle = "按文件查看内容，支持单文件分享与保存",
                    onClick = onOpenLogViewer,
                    showArrow = true,
                )
                SettingsItem(
                    icon = Icons.Default.IosShare,
                    title = if (exporting) "正在导出…" else "导出全部日志",
                    subtitle = "汇总 APP、RIME 与插件日志为一个文件，可发送给开发者",
                    onClick = {
                        // 导出中重复点击会并发写同一个汇总目录，直接忽略
                        if (!exporting) {
                            exporting = true
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    FileLogger.exportAllLogs(context)
                                }
                                exporting = false
                                if (file == null) {
                                    Toast.makeText(context, "导出失败：日志目录不可用", Toast.LENGTH_LONG)
                                        .show()
                                } else {
                                    shareExportedLog(context, file)
                                }
                            }
                        }
                    },
                    showArrow = true,
                )
            }

            SettingsSection(title = "存储与清理") {
                SettingsItem(
                    icon = Icons.Default.Refresh,
                    title = "刷新统计",
                    subtitle = logStatLine(fileCount, totalSize),
                    onClick = { scope.launch { refreshStats() } },
                )
                SettingsItem(
                    icon = Icons.Default.DeleteSweep,
                    title = "清空全部日志",
                    subtitle = "删除所有历史日志文件（保留当日文件占位）",
                    onClick = { showClearDialog = true },
                )
            }

            Text(
                text = "日志保存在应用私有目录，卸载应用会一并清除。" +
                    "「导出全部日志」会把汇总文件交给系统分享面板，" +
                    "你也可以直接选微信、QQ 等应用发出去。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空全部日志？") },
            text = { Text("将删除所有历史日志（保留当日文件占位）。该操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) { FileLogger.clearAllLogs() }
                            refreshStats()
                            Toast.makeText(context, "已清空日志", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 日志级别选择对话框。
 *
 * 用 M3 的单选列表（`RadioButton` + `selectable`）而不是下拉菜单：六个级别各带一句
 * 说明，需要足够行宽展示；单选列表也能一眼看出当前生效的是哪一档。
 */
@Composable
private fun LogLevelDialog(
    current: LogLevel,
    onDismiss: () -> Unit,
    onSelect: (LogLevel) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
        title = { Text("日志级别") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LogLevel.entries.forEach { option ->
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

/** 通过系统分享面板把汇总文件发出去（微信/QQ/邮件等由用户自己选）。 */
private fun shareExportedLog(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Xime 日志 - ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "导出日志"))
    } catch (t: Throwable) {
        // 退化路径：FileProvider 未覆盖导出目录（filepaths.xml 缺条目）或个别 ROM
        // 不支持时，直接写入下载目录，至少让用户拿到文件。
        //
        // 必须记日志：这条 fallback 会「假装成功」（用户仍拿到文件，只是没弹分享面板），
        // 是最容易长期潜伏的失败模式——曾经就因为 filepaths.xml 漏声明 log_export/
        // 而永远走不到分享面板。记下来后能在日志查看器里直接看到原因。
        FileLogger.w(TAG, "分享日志失败，退化为保存到 Download：${t.message}", t)
        runCatching {
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                file.name,
            )
            file.copyTo(dest, overwrite = true)
            Toast.makeText(context, "已保存到 Download/${file.name}", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}

private const val TAG = "LogSettings"
