package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.media.MediaPlayer
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ai.XimeAiApp
import com.kingzcheung.xime.ai.data.HistoryRecord
import com.kingzcheung.xime.ai.data.HistoryRetention
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 历史记录页：查看/复制/删除/清空录音文字记录，并选择保留时长。
 * 数据来自 [com.kingzcheung.xime.ai.data.AppRepository.history]（语音链路写入）。
 */
@Composable
fun AiHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { runCatching { XimeAiApp.repository }.getOrNull() }
    val history by (repo?.history?.collectAsState() ?: remember {
        androidx.compose.runtime.mutableStateOf<List<HistoryRecord>>(emptyList())
    })
    val settings by (repo?.settings?.collectAsState() ?: remember {
        androidx.compose.runtime.mutableStateOf<com.kingzcheung.xime.ai.data.AppSettings?>(null)
    })
    var confirmClear by remember { mutableStateOf(false) }

    SettingsScreenScaffold(title = "AI 历史记录", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (repo == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("AI 模块未初始化", color = MaterialTheme.colorScheme.outline)
                }
                return@Column
            }

            // 顶部：保留时长 + 清空
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "保留时长",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        RetentionPicker(
                            current = settings?.historyRetention ?: HistoryRetention.FOREVER,
                            onPick = { r -> repo.updateSettings { it.copy(historyRetention = r) } }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "共 ${history.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            enabled = history.isNotEmpty(),
                            onClick = { confirmClear = true }
                        ) { Text("清空") }
                    }
                }
            }

            if (history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无历史记录", color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "语音识别上屏后会自动记录在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(history, key = { it.id }) { rec ->
                        HistoryRow(
                            record = rec,
                            onCopy = { copyToClipboard(context, rec.displayText()) },
                            onDelete = { repo.deleteHistoryItem(rec.id) }
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空历史记录") },
            text = { Text("将删除全部 ${history.size} 条记录，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    repo?.clearHistory()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

private fun HistoryRecord.displayText(): String =
    polishedText.ifBlank { rawTranscript }

@Composable
private fun RetentionPicker(current: HistoryRetention, onPick: (HistoryRetention) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(current.label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            HistoryRetention.entries.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.label) },
                    onClick = { open = false; onPick(r) }
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(record: HistoryRecord, onCopy: () -> Unit, onDelete: () -> Unit) {
    val timeText = remember(record.timestamp) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(record.timestamp))
    }
    val meta = buildList {
        add(timeText)
        if (record.targetAppName.isNotBlank()) add(record.targetAppName)
        if (record.modeLabel.isNotBlank()) add(record.modeLabel)
        if (record.durationMs > 0) add("${record.durationMs / 1000}s")
    }.joinToString(" · ")

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            Text(record.displayText(), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val hasAudio = record.audioFilePath.isNotBlank() &&
                    runCatching { File(record.audioFilePath).exists() }.getOrDefault(false)
                if (hasAudio) {
                    var playing by remember { mutableStateOf(false) }
                    var player by remember { mutableStateOf<MediaPlayer?>(null) }
                    androidx.compose.runtime.DisposableEffect(record.id) {
                        onDispose { runCatching { player?.release() }; player = null }
                    }
                    TextButton(onClick = {
                        if (playing) {
                            runCatching { player?.stop(); player?.release() }
                            player = null
                            playing = false
                        } else {
                            runCatching {
                                val mp = MediaPlayer()
                                mp.setDataSource(record.audioFilePath)
                                mp.setOnCompletionListener { playing = false }
                                mp.prepare()
                                mp.start()
                                player = mp
                                playing = true
                            }.onFailure { playing = false }
                        }
                    }) { Text(if (playing) "停止" else "播放") }
                }
                TextButton(onClick = onCopy) { Text("复制") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("AI 历史", text))
    }
}
