package com.kingzcheung.xime.plugin.core.model

import java.io.Serializable

data class PluginCrashInfo(
    val throwable: Throwable,
    val culpritPluginId: String?,
    val defaultMessage: String
) : Serializable