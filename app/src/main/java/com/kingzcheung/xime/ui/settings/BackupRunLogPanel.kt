package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.BackupLogLevel
import com.kingzcheung.xime.settings.BackupRunState
import com.kingzcheung.xime.settings.BackupRunOutcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only renders the safe, bounded events produced by the backup pipeline. */
@Composable
internal fun BackupRunLogPanel(state: BackupRunState, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LaunchedEffect(state.runId, state.entries.size, scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "备份运行记录",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = when {
                        state.running -> "运行中"
                        state.entries.isEmpty() -> "尚未开始"
                        state.outcome == BackupRunOutcome.SUCCESS -> "成功"
                        state.outcome == BackupRunOutcome.CANCELLED -> "已取消"
                        state.outcome != null -> "失败"
                        else -> "已结束"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onClose) { Text("收起") }
            }
            if (state.entries.isEmpty()) {
                Text(
                    text = "完整备份开始后，将在这里显示各阶段、备份内容数量和结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.entries.forEach { entry ->
                            Text(
                                text = "${timeFormat.format(Date(entry.timestamp))}  ${entry.message}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = when (entry.level) {
                                    BackupLogLevel.ERROR -> MaterialTheme.colorScheme.error
                                    BackupLogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                                    BackupLogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
                                    BackupLogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
            Text(
                text = "仅记录类别、数量与结果，不显示剪贴板正文、快捷码或账号信息。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
