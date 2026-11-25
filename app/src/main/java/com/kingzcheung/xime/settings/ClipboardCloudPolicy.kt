package com.kingzcheung.xime.settings

/** 统一完整备份的剪贴板纳入策略；与旧实时同步开关完全独立。 */
internal object ClipboardCloudPolicy {
    const val DEFAULT_BACKUP_ENABLED = true
    const val LEGACY_REALTIME_SYNC_ENABLED = false
    const val SKIPPED_BACKUP_LOG = "剪贴板备份已关闭，跳过剪贴板数据"

    fun isLegacyRealtimeSyncEnabled(savedEnabled: Boolean): Boolean =
        LEGACY_REALTIME_SYNC_ENABLED && savedEnabled

    fun shouldIncludeInBackup(enabled: Boolean): Boolean = enabled

    fun backupSkipMessage(enabled: Boolean): String? =
        if (enabled) null else "剪贴板备份已关闭，跳过剪贴板数据"

    /** 关闭时不执行 writer，确保不会触碰 Room/图片资源。 */
    inline fun <T : Any> withBackupEnabled(enabled: Boolean, writer: () -> T): T? =
        if (shouldIncludeInBackup(enabled)) writer() else null
}
