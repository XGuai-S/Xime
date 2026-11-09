package com.kingzcheung.xime.settings

/**
 * 键盘布局标识
 */
enum class KeyboardLayout(val id: String, val displayName: String) {
    FULL("full", "全键盘"),
    NINEKEY("ninekey", "九键拼音");

    companion object {
        fun fromId(id: String): KeyboardLayout =
            entries.find { it.id == id } ?: FULL
    }
}

data class SchemaInfo(
    val schemaId: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val isDownloaded: Boolean = false,
    val needsUpdate: Boolean = false,
    /** 方案声明支持的键盘布局，默认只有全键盘 */
    val supportedLayouts: List<KeyboardLayout> = listOf(KeyboardLayout.FULL),
    /** 展开多布局时，标记此条目对应的布局 ID（null 表示单条目） */
    val displayLayoutId: String? = null
)