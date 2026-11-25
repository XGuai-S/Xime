package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.MarketUpdateChecker
import com.kingzcheung.xime.settings.ImportManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PluginsUiState(
    val extensions: List<PluginInfo> = emptyList(),
    val icons: Map<String, PluginIcon> = emptyMap(),
    val isLoading: Boolean = true,
    val errorMsg: String? = null
)

class PluginsSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val TAG = "PluginsSettingsViewModel"
    
    private val _uiState = MutableStateFlow(PluginsUiState())
    val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()
    
    val loadedPlugins = PluginManager.loadedPluginsFlow

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    init {
        refreshPlugins()
    }

    fun installPluginFromUri(uri: Uri) {
        viewModelScope.launch {
            val displayName = queryDisplayName(uri)
            if (displayName == null || !ImportManager.isPluginFile(displayName)) {
                _importMessage.value = "请选择有效的插件文件"
                return@launch
            }
            when (val result = ImportManager.import(context, uri)) {
                is ImportManager.ImportResult.Plugin -> {
                    PluginManager.loadEnabledPlugins()
                    refreshPlugins()
                    _importMessage.value = "插件「${result.pluginInfo?.name ?: ""}」安装成功"
                }
                is ImportManager.ImportResult.Failed -> {
                    _importMessage.value = "安装失败：${result.reason}"
                }
                else -> {
                    _importMessage.value = "安装失败：未知错误"
                }
            }
        }
    }

    fun consumeImportMessage() {
        _importMessage.value = null
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryDisplayName failed", e)
            null
        }
    }
    
    fun refreshPlugins() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMsg = null) }
            
            try {
                withContext(Dispatchers.IO) {
                    PluginManager.loadEnabledPlugins()
                }
                
                val extensions = PluginManager.getAllInstallPlugins()

                // 解析每个已加载插件的图标（文字或已提取的资源文件）
                val instances = PluginManager.getAllPluginInstances()
                val icons = mutableMapOf<String, PluginIcon>()
                extensions.forEach { info ->
                    val instance = instances[info.id]
                    if (instance != null) {
                        try {
                            ExtensionManager.extractPluginIcon(context, info.id, instance, info)
                                ?.let { icons[info.id] = it }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to resolve icon for ${info.name}", e)
                        }
                    }
                }

                _uiState.update { it.copy(
                    extensions = extensions,
                    icons = icons,
                    isLoading = false
                )}
            } catch (e: Exception) {
                Log.e(TAG, "Error loading plugins", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    errorMsg = e.message
                )}
            }
        }
    }
    
    fun isPluginEnabled(pluginId: String): Boolean {
        return SettingsPreferences.isPluginEnabled(context, pluginId)
    }

    /** 插件是否兼容当前主应用版本。 */
    fun isHostCompatible(extension: PluginInfo): Boolean {
        return try {
            PluginManager.isPluginHostCompatible(extension)
        } catch (e: Exception) {
            true
        }
    }
    
    /**
     * 启用 / 禁用插件。
     *
     * 必须**同时**写 registry 与 prefs，且 registry 才是权威源：
     * `PluginLifecycleManager.loadEnabledPlugins()` 启动时只读 `registry.enabled`，
     * 而旧实现这里只写了 `SettingsPreferences`（prefs 里的 `plugin_enabled_<id>`），
     * 于是「在插件中心关掉插件 → 重启应用」之后插件**仍会被实例化并执行 JS**
     * （建 WS/SSE、后台定时器、按 manifest 读剪贴板），只是业务调用被上层过滤掉 ——
     * 用户以为自己关掉了，实际代码还在跑。
     *
     * prefs 那份保留：其它读取路径（如按能力取插件、备份设置页的单选）仍在用它。
     */
    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        // prefs 同步写：UI 立即反映，且其它读取路径不受影响
        SettingsPreferences.setPluginEnabled(context, pluginId, enabled)

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // registry 是启动加载的唯一权威源，必须同步更新（内部含 flushToDisk）
                runCatching { PluginManager.setPluginEnabled(pluginId, enabled) }
                    .onFailure {
                        com.kingzcheung.xime.util.FileLogger.e(
                            "PluginsVM",
                            "写插件注册表失败 id=$pluginId enabled=$enabled（重启后该状态会丢失）",
                            it
                        )
                    }
                if (enabled) {
                    PluginManager.launchPlugin(pluginId)
                } else {
                    PluginManager.unloadPlugin(pluginId)
                }
            }
        }
    }
    
    fun uninstallPlugin(pluginId: String) {
        if (SettingsPreferences.getSttOnlinePluginId(context) == pluginId) {
            SettingsPreferences.setSttOnlinePluginId(context, "")
        }
        if (SettingsPreferences.getClipboardSyncPluginId(context) == pluginId) {
            SettingsPreferences.setClipboardSyncPluginId(context, "")
        }
        
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                PluginManager.unloadPlugin(pluginId)
                val ok = PluginManager.installerManager.uninstallPlugin(pluginId)
                // 只有真的卸载成功才清持久化痕迹：清早了而目录没删掉，
                // 插件还在列表里但配置/授权没了，用户看到的是「功能突然坏了」。
                if (ok) SettingsPreferences.clearPluginPersistence(context, pluginId)
                ok
            }
            if (!removed) {
                // 卸载失败：目录与注册项未动，不能从 UI 列表里摘掉
                return@launch
            }

            _uiState.update { state ->
                state.copy(extensions = state.extensions.filter { it.id != pluginId })
            }
            // 卸载后该项不再计入可更新，立即重算角标
            MarketUpdateChecker.refreshNow(context)
        }
    }
}