package com.kingzcheung.xime

import android.content.Intent
import android.Manifest
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.ImportManager
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.UserConfigDir
import com.kingzcheung.xime.ui.settings.SettingsScreen
import com.kingzcheung.xime.ui.settings.SetupWizardScreen
import com.kingzcheung.xime.ui.settings.ConfigOverwriteDialog
import com.kingzcheung.xime.ui.settings.StoragePermissionDialog
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"

        /** 构造跳转到指定插件网络授权页的 Intent（IME 等后台场景调用）。 */
        fun buildPluginAuthIntent(context: android.content.Context, pluginId: String): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra("open_fragment", "plugins")
                putExtra("open_plugin_id", pluginId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun onDestroy() {
        prewarmScope.cancel()
        releaseScope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 用户从「所有文件访问」授权页返回后立刻重跑释放：否则刚授权完目录里仍是空的，
        // 用户会以为授权没生效（旧行为必须重启应用才释放）。
        // 判据是「清单里的文件是否全都在」，而不是上次的 releaseState：releaseState 只是
        // 进程内的一次结果快照，用它短路会让「授权被撤销后重新授权」「新版本新增配置文件」
        // 这类情况永远补不上文件。已齐全时直接跳过，避免每次回到前台都做无谓的目录扫描。
        if (UserConfigDir.allFilesReleased(this)) return
        releaseScope.launch {
            val ready = try {
                UserConfigDir.releaseUserConfig(this@MainActivity)
            } catch (t: Throwable) {
                Log.w(TAG, "release user config on resume failed", t)
                false
            }
            if (ready) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "配置已释放到 ${UserConfigDir.DISPLAY_PATH}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "麦克风权限已授权", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "麦克风权限被拒绝，无法使用语音输入", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
    
    private val prewarmScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 配置目录释放专用作用域：与预热分开，便于各自取消。 */
    private val releaseScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private fun prewarmRimeEngine() {
        if (RimeEngine.isInitialized()) return
        prewarmScope.launch {
            try {
                KeysConfigHelper.loadConfig(this@MainActivity)
                val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeDataAsync(this@MainActivity)
                RimeEngine.getInstance().initialize(userDataDir, sharedDataDir)
            } catch (e: Exception) {
                Log.w(TAG, "Rime engine pre-warm failed, will init on demand", e)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        prewarmRimeEngine()

        handleSharedIntent(intent)
        
        val requestPermission = intent?.getStringExtra("request_permission")
        if (requestPermission == PermissionHelper.PERMISSION_RECORD_AUDIO) {
            if (!PermissionHelper.hasRecordAudioPermission(this)) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                Toast.makeText(this, "麦克风权限已授权", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }
        
        val openFragment = intent?.getStringExtra("open_fragment")
        val openPluginId = intent?.getStringExtra("open_plugin_id")

        setContent {
            val context = this
            val setupCompleted = SettingsPreferences.isSetupCompleted(context)
            var showWizard by remember { mutableStateOf(!setupCompleted) }
            var wizardToSettings by remember { mutableStateOf(false) }
            var darkMode by remember { mutableIntStateOf(SettingsPreferences.getDarkMode(context)) }
            var keyboardTheme by remember { mutableStateOf(SettingsPreferences.getKeyboardTheme(context)) }
            
            val isDarkTheme = when (darkMode) {
                2 -> isSystemInDarkTheme()
                1 -> true
                else -> false
            }

            XimeTheme(darkTheme = isDarkTheme, themeId = keyboardTheme) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    DisposableEffect(darkMode) {
                        val window = (view.context as? ComponentActivity)?.window
                        if (window != null) {
                            val controller = WindowInsetsControllerCompat(window, view)
                            controller.isAppearanceLightStatusBars = !isDarkTheme
                            controller.isAppearanceLightNavigationBars = !isDarkTheme
                        }
                        onDispose { }
                    }
                }

                val density = LocalDensity.current
                val navBarHeight = with(density) {
                    WindowInsets.navigationBars.getBottom(density).toDp()
                }
                val scrimHeight = if (navBarHeight > 0.dp) navBarHeight else 32.dp

                Box(modifier = Modifier.fillMaxSize()) {
                    if (showWizard) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            SetupWizardScreen(
                                visible = !wizardToSettings,
                                onNavigateToSchemaSettings = { wizardToSettings = true },
                                onCompleted = { showWizard = false }
                            )
                            if (wizardToSettings) {
                                SettingsScreen(
                                    initialRoute = "schema",
                                    onThemeChanged = {
                                        darkMode = SettingsPreferences.getDarkMode(context)
                                        keyboardTheme = SettingsPreferences.getKeyboardTheme(context)
                                    },
                                    onWizardBack = { wizardToSettings = false }
                                )
                            }
                        }
                    } else {
                        SettingsScreen(
                            initialRoute = openFragment,
                            initialPluginId = openPluginId,
                            onThemeChanged = {
                                darkMode = SettingsPreferences.getDarkMode(context)
                                keyboardTheme = SettingsPreferences.getKeyboardTheme(context)
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(scrimHeight)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = if (isDarkTheme) {
                                        listOf(Color.Transparent, Color.White.copy(alpha = 0.10f))
                                    } else {
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f))
                                    }
                                )
                            )
                    )

                    // 存储权限引导弹窗：仅「平台支持但未授权」时出现。
                    // 放在根 Box 里，向导页与设置页都能覆盖到（首次启动正是最该提醒的时机）。
                    StoragePermissionDialog()

                    // 新安装/升级后询问是否覆盖已有配置：仅「目录里已有 xime.yaml」且
                    // 「当前版本还没问过」时出现；与存储权限弹窗互斥（未授权时不触发）。
                    ConfigOverwriteDialog()
                }
            }
        }
    }
    
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                val mime = intent.type
                if (uri != null) {
                    if (mime?.startsWith("image/") == true) {
                        importThemeImage(uri, mime)
                    } else {
                        importSchema(uri)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (uris != null) {
                    for (uri in uris) {
                        importSchema(uri)
                    }
                }
            }
        }
    }
    
    private fun importSchema(uri: android.net.Uri) {
        prewarmScope.launch {
            when (val result = ImportManager.import(this@MainActivity, uri)) {
                is ImportManager.ImportResult.Content -> {
                    launch(Dispatchers.Main) {
                        val msg = when {
                            !result.success -> "导入失败"
                            result.installedDirect -> "导入成功，已放入 rime 目录"
                            else -> "方案导入成功，请到「输入方案」页面部署"
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                is ImportManager.ImportResult.Plugin -> {
                    com.kingzcheung.xime.plugin.core.runtime.PluginManager.loadEnabledPlugins()
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "插件「${result.pluginInfo?.name}」安装成功",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                is ImportManager.ImportResult.Failed -> {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "导入失败：${result.reason}", Toast.LENGTH_LONG).show()
                    }
                }
                is ImportManager.ImportResult.Unsupported -> {
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "不支持的文件类型", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** 分享图片到 Xime：保存到 rime/themes/ 并提示用户如何引用。 */
    private fun importThemeImage(uri: android.net.Uri, mimeType: String) {
        prewarmScope.launch {
            val result = withContext(Dispatchers.IO) {
                val themesDir = File(SchemaManager.getRimeDir(this@MainActivity), "themes")
                themesDir.mkdirs()
                val ext = when (mimeType) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val name = "custom_${System.currentTimeMillis()}.$ext"
                val target = File(themesDir, name)
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    name
                } catch (e: Exception) {
                    Log.w(TAG, "importThemeImage failed", e)
                    null
                }
            }
            launch(Dispatchers.Main) {
                if (result == null) {
                    Toast.makeText(this@MainActivity, "背景图导入失败", Toast.LENGTH_LONG).show()
                } else {
                    copyThemeConfigTemplate(result)
                    Toast.makeText(
                        this@MainActivity,
                        "背景图已导入 rime/themes/$result，配置模板已复制到剪贴板",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** 生成可在 xime.custom.yaml 中使用的主题配置模板并复制到剪贴板。 */
    private fun copyThemeConfigTemplate(fileName: String) {
        val schemeId = fileName.substringBeforeLast('.')
        val template = """
            color_schemes:
              $schemeId:
                name: "自定义背景 ${schemeId.removePrefix("custom_")}"
                keyboard_background:
                  type: image
                  src: "themes/$fileName"
                  fit: cover
                  overlay_alpha: 0.15
                  overlay_alpha_dark: 0.30
                key_bg_color: 0x8cffffff
                key_bg_color_dark: 0x66000000
                key_text_color: 0x232323
                key_text_color_dark: 0xf2f2f2
                candidate_text_color: 0x232323
                candidate_text_color_dark: 0xf2f2f2
                candidate_selected_text_color: 0xffffff
                candidate_selected_text_color_dark: 0xffffff
        """.trimIndent()
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("xime theme", template))
    }
}
