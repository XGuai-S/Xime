package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.viewmodel.LocalPackageItem

/**
 * 本地方案包卡片（安装 / 卸载 / 删除已下载文件）。
 *
 * 原先 SchemaLocalScreen.LocalPackageCard 与 SchemaSettingsScreen.LocalPackageItemCard
 * 是两份逐字节相同的实现（各 104 行，含标签胶囊），抽到此处共用。
 */
@Composable
internal fun LocalPackageCard(
    item: LocalPackageItem,
    installing: Boolean,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                val tagColor = when {
                    item.installed -> MaterialTheme.colorScheme.primary
                    item.downloaded -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.error
                }
                TagChip(
                    if (item.installed) "已安装" else if (item.downloaded) "已下载" else "未知",
                    tagColor,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "ID: ${item.packageId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (item.version.isNotEmpty() || item.fileCount > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (item.version.isNotEmpty()) append("版本: ${item.version}")
                        if (item.fileCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${item.fileCount} 个文件")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    installing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("安装中…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    item.installed -> {
                        OutlinedButton(onClick = onUninstall) { Text("卸载") }
                    }
                    item.downloaded -> OutlinedButton(onClick = onInstall) { Text("安装") }
                }
                Spacer(Modifier.weight(1f))
                if (item.packageId != "builtin" && item.downloaded) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除下载文件",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
