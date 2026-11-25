package com.kingzcheung.xime.clipboard

import android.content.Context
import com.kingzcheung.xime.clipboard.db.ClipboardDatabase
import com.kingzcheung.xime.clipboard.db.ClipboardEntry
import java.io.File
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock

/** 完整备份专用入口：直接使用 Room，不经过会写系统剪贴板/发同步事件的 Manager。 */
object ClipboardBackup {
    fun writeArchive(context: Context, zip: ZipOutputStream, metaPrefix: String): ClipboardBackupCounts = runBlocking(Dispatchers.IO) {
        ClipboardImageStore.mutationMutex.withLock {
            val records = ClipboardDatabase.getInstance(context).clipboardDao().backupEntries().map { it.toBackupRecord() }
            ClipboardBackupCodec.writeArchive(context.filesDir, records, zip, metaPrefix)
        }
    }

    fun restore(context: Context, records: List<ClipboardBackupRecord>, staging: File) = runBlocking(Dispatchers.IO) {
        ClipboardImageStore.mutationMutex.withLock {
            val dao = ClipboardDatabase.getInstance(context).clipboardDao()
            ClipboardBackupCodec.restoreImages(context.filesDir, records, staging)
            // 敏感重识别只对最近的若干条做：识别器有 20+ 个正则，备份包上限 20001 条，
            // 全量重扫要跑几十万次正则（实测单条 ~0.4ms → 最坏约 8 秒），
            // 会明显拖慢恢复。取最近 RESCAN_LIMIT 条覆盖实际需要补标的场景。
            val rescanBudget = java.util.concurrent.atomic.AtomicInteger(RESCAN_LIMIT)
            dao.mergeBackup(ClipboardBackupCodec.mergeInsertionOrder(records).map { r ->
                ClipboardEntry(
                    text = r.text, code = r.code, timestamp = r.timestamp, isPinned = r.isPinned,
                    isQuickSend = r.isQuickSend, consumed = r.consumed,
                    // 重新识别敏感内容：备份包里带的是**备份时**的 type，
                    // 3.4.14 之前生成的旧包没有 sensitive 标记，直接照搬会把密码/卡号
                    // 恢复成普通文本，再次进备份与云同步。这里以当前识别结果为准。
                    type = if (r.type == ClipboardEntry.TYPE_TEXT &&
                        rescanBudget.getAndDecrement() > 0 &&
                        com.kingzcheung.xime.clipboard.ClipboardSensitivity
                            .containsHighRiskCredential(r.text)
                    ) {
                        ClipboardEntry.TYPE_SENSITIVE
                    } else {
                        r.type
                    },
                    imagePath = r.imagePath, imageHash = r.imageHash, mimeType = r.mimeType,
                    sizeBytes = r.sizeBytes, width = r.width, height = r.height,
                )
            })
        }
    }

    /**
     * 恢复时重识别敏感内容的条目预算（见 restore 里的说明）。
     *
     * 取 2000：实测单条识别约 0.4ms（24 个正则），2000 条约 0.8 秒——
     * 恢复本身是一次性操作，这个开销可接受；而 200 条会漏标「备份包里第 201 条之后的
     * 历史密码」，那些恢复后仍会进下一次备份与云同步，等于漏洞只修了一半。
     */
    private const val RESCAN_LIMIT = 2000

    private fun ClipboardEntry.toBackupRecord() = ClipboardBackupRecord(
        text = text, code = code, timestamp = timestamp, isPinned = isPinned,
        isQuickSend = isQuickSend, consumed = consumed, type = type, imagePath = imagePath,
        imageHash = imageHash, mimeType = mimeType, sizeBytes = sizeBytes, width = width, height = height,
    )
}
