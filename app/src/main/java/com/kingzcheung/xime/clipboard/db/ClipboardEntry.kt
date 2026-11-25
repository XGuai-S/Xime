package com.kingzcheung.xime.clipboard.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "clipboard_entries",
    indices = [
        Index(value = ["text"]),
        Index(value = ["imageHash"]),
        // 面板列表 / 候选栏：WHERE isQuickSend = 0 ORDER BY timestamp DESC
        Index(value = ["isQuickSend", "timestamp"]),
        // 淘汰与置顶排序：WHERE isPinned = 0 ORDER BY timestamp ASC/DESC
        Index(value = ["isPinned", "timestamp"]),
        // 图片配额统计：WHERE isQuickSend = 0 AND type = 'image'
        Index(value = ["isQuickSend", "type"]),
    ]
)
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** 快捷发送触发编码（如 dh）：用户输入编码前缀命中后，对应快捷条目进入候选栏。 */
    @ColumnInfo(defaultValue = "") val code: String = "",
    @ColumnInfo(defaultValue = "0") val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isQuickSend: Boolean = false,
    @ColumnInfo(defaultValue = "0") val consumed: Boolean = false,
    /** 条目类型：[TYPE_TEXT] 文本 / [TYPE_IMAGE] 图片 / [TYPE_OTP] 临时验证码。 */
    @ColumnInfo(defaultValue = TYPE_TEXT) val type: String = TYPE_TEXT,
    /** 图片文件在 `files/` 下的相对路径（如 `clipboard_images/<sha256>.png`）；非图片为空串。 */
    @ColumnInfo(defaultValue = "") val imagePath: String = "",
    /** 图片内容 SHA-256（图片去重键）；非图片为空串。 */
    @ColumnInfo(defaultValue = "") val imageHash: String = "",
    /** 图片 MIME（如 image/png）；非图片为空串。 */
    @ColumnInfo(defaultValue = "") val mimeType: String = "",
    /** 图片字节大小（配额统计与淘汰用）。 */
    @ColumnInfo(defaultValue = "0") val sizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val width: Int = 0,
    @ColumnInfo(defaultValue = "0") val height: Int = 0,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_OTP = "otp"

        /**
         * 内容含高风险凭据（密码/证件号/银行卡/密钥）的文本条目。
         *
         * 与 [TYPE_TEXT] 的唯一区别是**排除在备份与云同步之外**：用户复制完要能粘回去，
         * 所以仍然入库；但这类内容不该随备份 zip 落到 Downloads、也不该推到云端。
         */
        const val TYPE_SENSITIVE = "sensitive"
    }
}