package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.ui.theme.KeyboardColorScheme
import com.kingzcheung.xime.viewmodel.ThemeSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsContent(
    onBack: () -> Unit,
    onThemeChanged: () -> Unit = {},
) {
    val viewModel: ThemeSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    SettingsScreenScaffold(title = "主题与定制", onBack = onBack) { paddingValues ->
        var previewTheme by remember { mutableStateOf<KeyboardColorScheme?>(null) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "显示模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            item {
                val currentTheme = uiState.colorThemes.firstOrNull { it.id == uiState.colorTheme }
                    ?: uiState.colorThemes.first()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KeyboardThemeCard(
                        theme = currentTheme,
                        isSelected = uiState.darkMode == 2,
                        onClick = {
                            viewModel.setDarkMode(2)
                            onThemeChanged()
                        },
                        modifier = Modifier.weight(1f),
                        title = "跟随系统"
                    )
                    ThemeCard(
                        title = "浅色",
                        isSelected = uiState.darkMode == 0,
                        isDark = false,
                        accentColor = currentTheme.accentLight,
                        keyBgColor = currentTheme.keyBgLight,
                        onClick = {
                            viewModel.setDarkMode(0)
                            onThemeChanged()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeCard(
                        title = "深色",
                        isSelected = uiState.darkMode == 1,
                        isDark = true,
                        accentColor = currentTheme.accentDark,
                        keyBgColor = currentTheme.keyBgDark,
                        onClick = {
                            viewModel.setDarkMode(1)
                            onThemeChanged()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "配色方案",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            
            item {
                Text(
                    text = "点击配色预览完整键盘效果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            
            uiState.colorThemes.chunked(3).forEach { rowThemes ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowThemes.forEach { theme ->
                            KeyboardThemeCard(
                                theme = theme,
                                isSelected = uiState.colorTheme == theme.id,
                                onClick = {
                                    previewTheme = theme
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowThemes.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(1.dp))
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "提示: 配色切换后设置页面立即生效，键盘需重启输入法",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        previewTheme?.let { theme ->
            ThemePreviewSheet(
                theme = theme,
                onApply = {
                    viewModel.setColorTheme(theme.id)
                    previewTheme = null
                    onThemeChanged()
                },
                onDismiss = { previewTheme = null },
            )
        }
    }
}

/** 打开本应用的系统设置详情页（授权入口的兜底）。 */
internal fun openAppDetails(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "请在系统设置中为本应用开启存储权限", Toast.LENGTH_LONG).show()
        }
}
