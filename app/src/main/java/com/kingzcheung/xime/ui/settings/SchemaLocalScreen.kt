package com.kingzcheung.xime.ui.settings

import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.viewmodel.SchemaLocalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaLocalContent(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SchemaLocalViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    if (uiState.conflictPackageId != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelConflictInstall() },
            title = { Text("文件冲突") },
            text = {
                Text("需要先卸载冲突方案：${uiState.conflictingSchemeIds.joinToString("、")}，是否继续？")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmInstallWithUninstall() }) {
                    Text("确认卸载并安装")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelConflictInstall() }) {
                    Text("取消")
                }
            },
        )
    }

    SettingsScreenScaffold(
        title = "本地方案",
        onBack = onBack,
        containerColor = MaterialTheme.colorScheme.background,
        actions = {
            IconButton(onClick = { viewModel.loadLocalPackages() }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.packages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (uiState.isLoading) "加载中…" else "暂无本地方案",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val installedCount = uiState.packages.count { it.installed }
                            val downloadedCount = uiState.packages.count { it.downloaded }
                            Text(
                                "共 ${uiState.packages.size} 个（已安装 $installedCount，可安装 ${downloadedCount - installedCount}）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    items(uiState.packages, key = { it.packageId }) { item ->
                        LocalPackageCard(
                            item = item,
                            installing = uiState.installingId == item.packageId,
                            onInstall = { viewModel.installPackage(item) },
                            onDelete = { viewModel.deleteDownloaded(item) },
                            onUninstall = { viewModel.uninstall(item) },
                        )
                    }
                }
            }
        }
    }
}
