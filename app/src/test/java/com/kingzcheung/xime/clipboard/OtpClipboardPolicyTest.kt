package com.kingzcheung.xime.clipboard

import com.kingzcheung.xime.clipboard.db.ClipboardEntry
import com.kingzcheung.xime.service.otp.OtpCollector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpClipboardPolicyTest {
    @Test
    fun `freshness uses capture time and expires at two minutes`() {
        val now = 1_000_000L
        val cutoff = now - OtpCollector.FRESH_WINDOW_MS
        assertTrue(OtpClipboardPolicy.isFresh(now, now))
        assertTrue(OtpClipboardPolicy.isFresh(cutoff + 1, now))
        assertFalse(OtpClipboardPolicy.isFresh(cutoff, now))
        assertFalse(OtpClipboardPolicy.isFresh(cutoff - 1, now))
        assertFalse(OtpClipboardPolicy.isFresh(now + 1, now))
        assertFalse(OtpClipboardPolicy.isFresh(0, now))
    }

    @Test
    fun `OTP entries are excluded from sync and backup`() {
        assertFalse(OtpClipboardPolicy.canSync(ClipboardEntry.TYPE_OTP))
        assertFalse(OtpClipboardPolicy.canBackup(ClipboardEntry.TYPE_OTP))
    }

    @Test
    fun `sensitive entries are excluded from sync and backup`() {
        // P1-3：含高风险凭据的文本（密码/证件号/银行卡/密钥）不进备份、不推云同步，
        // 但仍在本地入库供用户粘贴——外泄面才是要堵的地方。
        assertFalse(OtpClipboardPolicy.canSync(ClipboardEntry.TYPE_SENSITIVE))
        assertFalse(OtpClipboardPolicy.canBackup(ClipboardEntry.TYPE_SENSITIVE))
    }

    @Test
    fun `ordinary text and image entries still sync and back up`() {
        assertTrue(OtpClipboardPolicy.canSync(ClipboardEntry.TYPE_TEXT))
        assertTrue(OtpClipboardPolicy.canBackup(ClipboardEntry.TYPE_TEXT))
        assertTrue(OtpClipboardPolicy.canSync(ClipboardEntry.TYPE_IMAGE))
        assertTrue(OtpClipboardPolicy.canBackup(ClipboardEntry.TYPE_IMAGE))
    }
}
