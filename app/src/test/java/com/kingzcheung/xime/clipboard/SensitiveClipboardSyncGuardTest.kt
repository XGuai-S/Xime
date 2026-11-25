package com.kingzcheung.xime.clipboard

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感剪贴板条目不得被云同步外传。
 *
 * 缺陷：`addItem` 在入库时正确算出 `TYPE_SENSITIVE`，但发射 `clipboardChanged` 事件时
 * 构造的 `ClipboardItem` **没带 type**，于是 `item.type` 退回默认 `TYPE_TEXT`。
 * 同步桥按 `OtpClipboardPolicy.canSync(item.type)` 放行 → 密码/证件号/银行卡/密钥
 * 被推送到 WebDAV / S3 / ximed 等远端。
 *
 * 这里钉住两件事：事件必须带真实类型；同步闸门必须排除敏感类型。
 */
class SensitiveClipboardSyncGuardTest {

    private val manager by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/clipboard/ClipboardManager.kt").readText()
    }
    private val policy by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/clipboard/OtpClipboardPolicy.kt").readText()
    }

    /** 截取 addItem 方法体。 */
    private fun addItemBody(): String {
        val start = manager.indexOf("fun addItem(")
        assertTrue("找不到 addItem", start >= 0)
        val rest = manager.substring(start)
        val end = rest.indexOf("\n    fun ", 1).let { if (it < 0) rest.length else it }
        return rest.substring(0, end)
    }

    @Test
    fun `同步事件必须携带真实条目类型`() {
        val body = addItemBody()
        assertTrue(
            "addItem 发射事件时必须带 type=entryType，否则同步桥会当普通文本外传",
            "type = entryType" in body,
        )
    }

    @Test
    fun `敏感判定在事件发射前完成`() {
        val body = addItemBody()
        val judge = body.indexOf("containsHighRiskCredential(text)")
        val emit = body.indexOf("_clipboardChanged.emit")
        assertTrue("必须做敏感判定", judge >= 0)
        assertTrue("判定必须先于事件发射", judge < emit)
    }

    @Test
    fun `同步闸门排除敏感类型`() {
        assertTrue(
            "canSync 必须排除 TYPE_SENSITIVE",
            "type != ClipboardEntry.TYPE_SENSITIVE" in policy,
        )
        assertTrue("canSync 仍须排除 OTP", "type != ClipboardEntry.TYPE_OTP" in policy)
    }

    @Test
    fun `备份闸门同样排除敏感类型`() {
        val backupFn = policy.substring(policy.indexOf("fun canBackup"))
        assertTrue("canBackup 必须排除 TYPE_SENSITIVE", "type != ClipboardEntry.TYPE_SENSITIVE" in backupFn)
    }
}
