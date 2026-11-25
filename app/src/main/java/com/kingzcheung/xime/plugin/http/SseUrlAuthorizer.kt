package com.kingzcheung.xime.plugin.http

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginNetworkAuthHelper
import com.kingzcheung.xime.plugin.core.js.ws.NetworkPolicy
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * SSE 建连前的域名白名单校验（可注入，便于单测绕过 PluginManager/SharedPreferences）。
 *
 * @return null = 放行；非 null = 拒绝原因（同时负责记录错误与引导用户授权）
 */
fun interface SseUrlAuthorizer {
    fun check(url: String): String?
}

/** 生产实现：与 [HttpHostApiImpl] 同一套 [NetworkPolicy] + 用户授权口径。 */
class DefaultSseUrlAuthorizer(
    private val context: Context,
    private val pluginId: String
) : SseUrlAuthorizer {

    override fun check(url: String): String? {
        val pluginInfo = PluginManager.getAllInstallPlugins().firstOrNull { it.id == pluginId }
        val declaredHosts = pluginInfo?.declaredHosts ?: emptyList()
        val authorizedHosts = SettingsPreferences.getPluginAuthorizedHosts(context, pluginId)
        val customHosts = ExtensionManager.getConfiguredNetworkHosts(context, pluginId).toSet()

        val reason = NetworkPolicy.check(url, emptySet(), declaredHosts, authorizedHosts, customHosts)
            ?: return null
        Log.w("SseHostApi", "[$pluginId] 联网被拒绝: $reason")
        PluginErrorLog.logError(
            pluginId,
            "联网被拒绝",
            reason,
            category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.NETWORK_DENIED
        )
        PluginNetworkAuthHelper.onNetworkDenied(
            context, pluginId, pluginInfo?.name,
            NetworkPolicy.extractHost(url), reason
        )
        return reason
    }
}
