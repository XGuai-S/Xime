package com.kingzcheung.xime.plugin.core.security.crash

import com.kingzcheung.xime.plugin.core.model.PluginCrashInfo

interface IPluginCrashCallback {
    fun onClassCastException(info: PluginCrashInfo): Boolean = false

    fun onDependencyException(info: PluginCrashInfo): Boolean = false

    fun onResourceNotFoundException(info: PluginCrashInfo): Boolean = false

    fun onApiIncompatibleException(info: PluginCrashInfo): Boolean = false

    fun onOtherPluginException(info: PluginCrashInfo): Boolean = false
}