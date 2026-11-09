package com.kingzcheung.xime.ui.settings

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.UserConfigDir

/**
 * 「需要存储权限」启动弹窗。
 *
 * 把配置释放到 `/storage/emulated/0/rime/xime/` 需要「所有文件访问」权限（API 28/29 为
 * `WRITE_EXTERNAL_STORAGE`）。该权限**没有运行时对话框**——Android 11+ 只能跳系统设置页手动开启，
 * 所以授权入口必须是显式引导，且不宜埋在设置列表里等用户自己找：这里在应用启动后直接弹窗询问。
 *
 * 行为：
 * - 仅在 [UserConfigDir.releaseState] 为 [UserConfigDir.ReleaseState.PERMISSION_REQUIRED] 时出现，
 *   即「平台支持但还没授权」；已授权、平台不支持、或写入被 ROM 拒绝都不会打扰用户。
 * - 「稍后」只关闭本次弹窗，不写任何标记：下次启动仍会提醒，避免用户彻底找不到入口。
 * - 授权成功后由 `MainActivity.onResume` 立刻重跑释放，无需重启应用。
 */
@Composable
fun StoragePermissionDialog() {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }

    // API 28/29：普通运行时权限，可直接弹系统授权框。
    // 授权成功后的释放交给 MainActivity.onResume（授权框关闭必然触发），此处不重复做文件 IO。
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "未授权，配置将保存在应用私有目录",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val state = UserConfigDir.releaseState
    if (state != UserConfigDir.ReleaseState.PERMISSION_REQUIRED || dismissed) return

    AlertDialog(
        onDismissRequest = { dismissed = true },
        icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
        title = { Text("把配置释放到本地目录") },
        text = {
            Text(
                "授予「所有文件访问」权限后，输入法配置会释放到\n" +
                    "${UserConfigDir.DISPLAY_PATH}\n\n" +
                    "这样你可以用任意文件管理器、电脑 USB 或第三方编辑器直接修改，" +
                    "改完在键盘菜单里点「部署方案」即可生效。\n\n" +
                    "不授权也能正常使用，配置会保存在应用私有目录里。",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    dismissed = true
                    val intent = UserConfigDir.allFilesAccessIntent(context)
                    if (intent != null) {
                        // 个别 ROM 没有「所有文件访问」设置页，退到应用详情页让用户手动开。
                        runCatching { context.startActivity(intent) }
                            .onFailure { openAppDetails(context) }
                    } else {
                        legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
            ) { Text("立即授权") }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text("稍后") }
        },
    )
}
