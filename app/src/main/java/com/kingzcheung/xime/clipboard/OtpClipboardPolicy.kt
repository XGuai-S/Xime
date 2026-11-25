package com.kingzcheung.xime.clipboard

import com.kingzcheung.xime.clipboard.db.ClipboardEntry
import com.kingzcheung.xime.service.otp.OtpCollector

internal object OtpClipboardPolicy {
    fun isFresh(capturedAt: Long, nowMs: Long): Boolean =
        capturedAt > 0 && capturedAt <= nowMs && nowMs - capturedAt < OtpCollector.FRESH_WINDOW_MS

    /**
     * 云同步闸门：验证码与「含高风险凭据的敏感文本」都不外传。
     *
     * 敏感文本仍在本机入库（用户要能粘贴），只是不参与跨设备同步与备份。
     */
    fun canSync(type: String): Boolean =
        type != ClipboardEntry.TYPE_OTP && type != ClipboardEntry.TYPE_SENSITIVE

    /** 备份闸门：与 [canSync] 同口径，敏感内容不写进备份 zip（zip 是明文）。 */
    fun canBackup(type: String): Boolean =
        type != ClipboardEntry.TYPE_OTP && type != ClipboardEntry.TYPE_SENSITIVE
}
