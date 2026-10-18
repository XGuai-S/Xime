package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.UnifiedConfigFile

/**
 * 「自动造词」设置页：上屏词自动收录 → 临时词库 → 晋升固定词库的总开关。
 *
 * 开关状态同步到 xime.yaml 的 `auto_word.enabled`（见 [UnifiedConfigFile.updateTopLevelBlock]）；
 * 关闭后不再收录/统计，已生成的词库文件原样保留。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoWordSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    var autoWordEnabled by remember {
        mutableStateOf(KeysConfigHelper.getAutoWordConfig().enabled)
    }

    SettingsScreenScaffold(title = "自动造词", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                SettingsSection(title = "功能开关", content = {
                    SettingsToggleItem(
                        icon = Icons.Default.AutoAwesome,
                        title = "启用自动造词",
                        subtitle = "上屏的字词自动收录进临时词库",
                        checked = autoWordEnabled,
                        onCheckedChange = { enabled ->
                            setAutoWordEnabled(context, enabled)
                            autoWordEnabled = enabled
                        }
                    )
                })
            }

            item {
                SettingsSection(title = "说明", content = {
                    val config = KeysConfigHelper.getAutoWordConfig()
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "• 上屏词频写入 word_freq.txt（与 xime.yaml 同级），输入时按上屏频数排列候选。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• 临时词库 auto_word_temp.txt：新上屏的词先进这里，输入对应编码时作为候选出现，被选中上屏才计数。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = "• 固定词库 auto_word.txt：被选中上屏满 ${config.promoteCount} 次的词晋升到这里；临时词库里超过 ${config.promoteDays} 天没再被选中的词自动删除（可在 xime.yaml 的 auto_word 段调整）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = "• 组合生词：相邻两次上屏的汉字词自动拼接进临时词库（如「现在」+「目录」→「现在目录」），只收 ${config.comboMinChars}–${config.comboMaxChars} 字；拼接不算选中次数。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = "• 造词编码按当前方案的**单字**规则生成：2 字各取前 2 码；3 字生成两种（各字首码、前两字首码+第三字前 2 码）；4 字及以上取前三字首码 + 尾字首码。方案词库已能打出或取不到码的词不收录。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                })
            }
        }
    }
}

/**
 * 把开关状态同步进 xime.yaml 的 `auto_word.enabled`：保留现有参数重写整个 auto_word 块。
 */
private fun setAutoWordEnabled(context: android.content.Context, enabled: Boolean) {
    val current = KeysConfigHelper.getAutoWordConfig()
    val block = """
        auto_word:
          # 自动造词：上屏词进临时词库，被选中上屏满 promote_count 次晋升固定词库；
          # 临时词库中超过 promote_days 天没被选中的词删除（文件与 xime.yaml 同级）。
          enabled: $enabled
          promote_days: ${current.promoteDays}
          promote_count: ${current.promoteCount}
          combo_enabled: ${current.comboEnabled}
          combo_min_chars: ${current.comboMinChars}
          combo_max_chars: ${current.comboMaxChars}
    """.trimIndent()
    if (!UnifiedConfigFile.updateTopLevelBlock(context, "auto_word", block)) return
    KeysConfigHelper.loadConfig(context)
}