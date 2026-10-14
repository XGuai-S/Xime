package com.kingzcheung.xime.settings

/** 1 KiB 的字节数。 */
const val BYTES_PER_KB = 1024L

/** 1 MiB 的字节数。 */
const val BYTES_PER_MB = 1024L * 1024L

/**
 * 把字节数格式化为人类可读的 B / KB / MB 文本。
 *
 * 合并自两处逐字等价的私有实现：MarketHubScreen 的 modelFormatSize 与
 * RimeFileBrowserScreen 的 formatSize。二者分支阈值、单位与精度完全一致，
 * 仅字符串模板写法不同（`"${x}".format()` 与 `"x".format()`），此处取后者。
 *
 * 行为保持不变：
 * - 小于 1 KiB 显示整数 B；
 * - 小于 1 MiB 显示整数 KB（向下取整）；
 * - 其余显示一位小数的 MB。
 */
fun formatByteSize(bytes: Long): String {
    return when {
        bytes < BYTES_PER_KB -> "$bytes B"
        bytes < BYTES_PER_MB -> "${bytes / BYTES_PER_KB} KB"
        else -> "%.1f MB".format(bytes.toDouble() / BYTES_PER_MB)
    }
}

/**
 * 把索引/市场接口里以 " MB" 结尾的下载体积文本换算成字节数。
 *
 * 合并自三处逐字相同的回退估算：XimeIndexSource.resolvedSizeBytes 与
 * MarketHubScreen 的两处 downloadUrls.sumOf 累加。原文案统一假设单位为 MB，
 * 解析失败一律回退 0L，语义不变。
 */
fun parseMegabyteTextToBytes(text: String?): Long {
    return text
        ?.removeSuffix(" MB")
        ?.trim()
        ?.toDoubleOrNull()
        ?.let { (it * BYTES_PER_MB).toLong() }
        ?: 0L
}
