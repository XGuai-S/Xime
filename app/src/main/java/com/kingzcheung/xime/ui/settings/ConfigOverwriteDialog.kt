package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.UnifiedConfigFile
import com.kingzcheung.xime.settings.UserConfigDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「新安装 / 升级后是否覆盖已有配置」启动弹窗。
 *
 * 背景：`/storage/emulated/0/rime/xime/xime.yaml` 是用户可直接编辑的**完整快照**，首次释放后
 * [UnifiedConfigFile.ensureGenerated] 永不覆盖它（保护用户编辑，这是对的）。代价是：装上新
 * 版本后，新版的内置默认值（如新的按键宽度、新增手势、新配色）不会出现在这份快照里，
 * 用户会以为「升级了却没生效」——这正是「安装后新配置为什么没覆盖」的原因。
 *
 * **判据用内容指纹，不用 versionCode**：本项目的 `versionCode` 是硬编码常量
 * （`app/build.gradle.kts` 的 `20261005`，仅 nightly 用 `-PversionCode` 覆盖），
 * 每次装的包都一样。曾用「versionCode 与上次不同」作判据，导致覆盖安装后判据恒为
 * 「同版本已问过」而永不弹窗。现在比对**快照里记录的基线指纹**与当前 `assets/xime.yaml`
 * 的指纹：内置配置一改，判据必成立；没有指纹的旧快照也视为落后。
 *
 * 行为：
 * - 仅在「已授权」且「快照确实落后」时弹窗，并尊重用户上次在同一指纹下的选择：
 *   选了「保留」就不再重复打扰，直到内置基线**再次**变化。
 * - 「覆盖为内置默认」：按当前内置配置重新生成快照（会丢失用户在该文件里的修改）并立即重载。
 * - 「保留现有配置」：原样不动，继续沿用用户自己的配置。
 * - 点弹窗外部视为「还没决定」，不记录选择，下次启动继续提醒
 *   （与 [StoragePermissionDialog] 的「稍后」语义一致）。
 */
@Composable
fun ConfigOverwriteDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        pending = withContext(Dispatchers.IO) {
            val current = UnifiedConfigFile.baselineHash(context) ?: return@withContext false
            // 已就这份内置基线做过决定（选了「保留」或「覆盖」）→ 不再打扰
            if (SettingsPreferences.getConfigBaseline(context) == current) return@withContext false
            // 快照确实落后（指纹不符，或是没有指纹的旧快照）
            UnifiedConfigFile.isSnapshotStale(context)
        }
    }

    if (!pending) return

    AlertDialog(
        onDismissRequest = { pending = false },
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text("配置可以更新到当前版本") },
        text = {
            Text(
                "目录里已存在一份旧版本的配置：\n" +
                    "${UserConfigDir.DISPLAY_PATH}/${UserConfigDir.UNIFIED_CONFIG_FILE}\n\n" +
                    "当前版本的内置默认值（按键宽度、手势等）已有变化，但这份文件仍是你原来的内容。\n\n" +
                    "「覆盖」会用当前版本的内置配置重新生成，你在该文件里的修改会丢失；\n" +
                    "「保留」则继续使用你现有的配置（以后同一版本不再询问）。",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    pending = false
                    scope.launch {
                        val hash = withContext(Dispatchers.IO) {
                            UnifiedConfigFile.baselineHash(context)
                        }
                        val ok = withContext(Dispatchers.IO) {
                            UnifiedConfigFile.generate(context)
                        }
                        // 重新解析，让覆盖后的配置立刻生效，不必再手动部署一次
                        if (ok) withContext(Dispatchers.IO) { KeysConfigHelper.loadConfig(context) }
                        hash?.let { SettingsPreferences.setConfigBaseline(context, it) }
                        Toast.makeText(
                            context,
                            if (ok) "已覆盖为内置默认配置" else "覆盖失败，已保留现有配置",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            ) { Text("覆盖为内置默认") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    pending = false
                    scope.launch {
                        // 记录「已就这份基线做过决定」，避免同一份内置配置反复弹窗
                        val hash = withContext(Dispatchers.IO) {
                            UnifiedConfigFile.baselineHash(context)
                        }
                        hash?.let { SettingsPreferences.setConfigBaseline(context, it) }
                    }
                },
            ) { Text("保留现有配置") }
        },
    )
}
