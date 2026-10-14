package com.kingzcheung.xime.settings

import java.util.Locale

/**
 * 日志体积与统计的文案格式化。
 *
 * 抽到 UI 之外的独立文件：这两个函数是纯计算，放在 Compose 文件里既无法被单测直接覆盖，
 * 也会让「日志页」承担与界面无关的职责。
 *
 * 单位自适应而非固定 MB：日志少时显示 `0.00 MB` 会让用户以为日志是空的，
 * 而实际已有内容——单位必须跟着量级走。
 */
internal fun formatLogSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 日志统计文案：无文件时给出明确说明，而不是「0 个文件 · 0 B」。 */
internal fun logStatLine(fileCount: Int, totalBytes: Long): String = when {
    fileCount <= 0 -> "暂无日志文件"
    else -> "$fileCount 个文件 · ${formatLogSize(totalBytes)}"
}
