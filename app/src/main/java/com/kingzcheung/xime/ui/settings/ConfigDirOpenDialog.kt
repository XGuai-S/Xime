package com.kingzcheung.xime.ui.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kingzcheung.xime.settings.ConfigDirTarget
import com.kingzcheung.xime.settings.UserConfigDir

/**
 * 「用其他应用打开配置目录」的选择弹窗：把 [com.kingzcheung.xime.settings.ConfigDirOpener]
 * 在手机上搜到的所有打开方式列出来，由用户挑一个。
 *
 * 做成弹窗而不是直接 `startActivity` 的原因：目录没有标准 MIME，系统选择器只会展示
 * 声明了对应类型的应用，经常只剩系统「文件」一个；这里把探测到的结果合并展示，
 * 用户能看到自己装的第三方管理器，也能看到兜底的系统选择器。
 *
 * 另外固定提供「复制路径」：某些管理器（实测 MT管理器）既不接受 SAF 目录树也不接受
 * `ACTION_VIEW` + 目录 MIME，其私有 scheme 又无公开文档，**任何程序化跳转都无法保证成功**。
 * 复制路径是唯一不依赖第三方实现的方式，作为兜底出口保留。
 */
@Composable
fun ConfigDirOpenDialog(
    targets: List<ConfigDirTarget>,
    onPick: (ConfigDirTarget) -> Unit,
    onDismiss: () -> Unit,
    onCopyPath: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
        title = { Text("用其他应用打开") },
        text = {
            Column {
                Text(
                    text = if (targets.isEmpty()) {
                        "没有找到可以打开 ${UserConfigDir.DISPLAY_PATH} 的应用"
                    } else {
                        "在手机上找到 ${targets.size} 个可打开 ${UserConfigDir.DISPLAY_PATH} 的方式"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (targets.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(targets, key = { it.packageName + "\u0000" + it.label }) { target ->
                            OpenTargetRow(target = target, onClick = { onPick(target) })
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // 兜底出口：不依赖任何第三方实现
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCopyPath)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "复制配置目录路径",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "粘贴到任意文件管理器的地址栏即可直达",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun OpenTargetRow(target: ConfigDirTarget, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(target.packageName) { loadAppIcon(context, target.packageName) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = target.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (target.packageName.isNotEmpty()) {
                Text(
                    text = target.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 取应用图标；包名为空或取不到返回 null，调用方退回通用图标。 */
private fun loadAppIcon(context: Context, packageName: String): ImageBitmap? {
    if (packageName.isEmpty()) return null
    return try {
        context.packageManager.getApplicationIcon(packageName).toBitmap(48, 48).asImageBitmap()
    } catch (t: Throwable) {
        null
    }
}
