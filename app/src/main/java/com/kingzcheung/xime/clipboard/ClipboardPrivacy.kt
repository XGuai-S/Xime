package com.kingzcheung.xime.clipboard

import android.content.ClipData
import android.content.ClipDescription

internal object ClipboardPrivacy {
    // 同名 extras 在 minSdk 28 可读，不依赖 API 33 的系统常量字段。
    const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

    fun isSensitive(description: ClipDescription?): Boolean {
        if (description == null) return true
        return try {
            val extras = description.extras
            // 字段存在但类型错误时也按敏感处理。
            extras != null && extras.containsKey(SENSITIVE_EXTRA) &&
                extras.getBoolean(SENSITIVE_EXTRA, true)
        } catch (_: Exception) {
            true
        }
    }

    fun readText(readClip: () -> ClipData?): String? = try {
        val clip = readClip()
        if (clip == null || isSensitive(clip.description)) null else rawText(clip)
    } catch (_: Exception) {
        null
    }

    /** 仅用于内部写入去重，返回布尔值，不把敏感内容暴露给读取调用方。 */
    fun isNoOpWrite(text: String, readClip: () -> ClipData?): Boolean = try {
        ClipboardSelfWritePolicy.isNoOpWrite(rawText(readClip()), text)
    } catch (_: Exception) {
        false
    }

    private fun rawText(clip: ClipData?): String? =
        if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() else null
}
