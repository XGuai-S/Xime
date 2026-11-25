package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约：备份**不再**依赖 librime 的 `*.userdb.txt` 文本快照。
 *
 * 旧流程每次备份前先 `SyncManager.syncNow()` 让 librime 导出快照，随整包上传，
 * 恢复时再交给 `UserDictManager::Restore` 按时间戳合并。那套机制有三个代价：
 * 1. 备份耗时受引擎 sync 影响（引擎没就绪就退化成"用旧快照"）；
 * 2. 快照是派生数据，包里体积大；
 * 3. 恢复多一步合并，出错时用户只看到"恢复成功但词是旧的"。
 *
 * 现在改成文件级增量：直接传 rime 目录里的原始文件，恢复时逐文件写回。
 * 本测试守住"别再把它加回来"——加回来会让备份重新依赖引擎状态。
 */
class BackupNoSnapshotTest {

    private fun source(name: String) =
        RepoFiles.mainKotlinSources.single { it.name == name }.readText()

    @Test
    fun `snapshot refresh helper is gone`() {
        val export = source("RimeExportManager.kt")
        assertFalse(
            "RimeExportManager 不应再有 refreshDictionarySnapshot（快照机制已移除）",
            export.contains("refreshDictionarySnapshot"),
        )
    }

    @Test
    fun `cloud backup does not trigger engine sync before packing`() {
        val cloud = source("BackupManager.kt")
            .substringAfter("suspend fun backupNow(")
            .substringBefore("private suspend fun pruneRemoteBackups(")
        assertFalse("云备份不得先跑引擎 sync 导快照", cloud.contains("refreshDictionarySnapshot"))
        assertFalse("云备份不得直接调 SyncManager.syncNow", cloud.contains("SyncManager.syncNow"))
        // 增量优先：backupNow 里必须出现 IncrementalBackup，否则整包路径成了唯一实现
        assertTrue("云备份应先尝试文件级增量", cloud.contains("IncrementalBackup.backup("))
    }

    @Test
    fun `local export does not trigger engine sync`() {
        val export = source("RimeExportManager.kt")
        val body = export.substringAfter("suspend fun exportArchive(")
            .substringBefore("fun buildArchiveToFile(")
        assertFalse("本机导出不得再跑引擎 sync", body.contains("SyncManager.syncNow"))
    }

    @Test
    fun `restore still goes through the shared restore path`() {
        val cloud = source("BackupManager.kt")
        // 文件级恢复必须是「重建整包 → 走原恢复逻辑」，不能另写一套写盘路径
        val restore = cloud.substringAfter("suspend fun restore(context: Context, plugin: BackupPlugin, id: String)")
            .substringBefore("private suspend fun")
        assertTrue(
            "文件级恢复应复用 restoreFromArchive（格式与整包一致，零格式风险）",
            restore.contains("restoreFromArchive(") || restore.contains("BackupManager.restoreArchive("),
        )
    }
}
