package com.kingzcheung.xime.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Prepare and validate the new service before disabling the previous one. */
internal suspend fun <T : Any> activateBackupService(
    selectedId: String,
    installedIds: List<String>,
    setEnabled: suspend (String, Boolean) -> Boolean,
    launch: suspend (String) -> T?,
    commit: (T) -> Unit,
    restore: suspend () -> Unit,
): Result<T> {
    if (selectedId !in installedIds) {
        return Result.failure(IllegalArgumentException("备份插件未安装"))
    }
    return try {
        check(setEnabled(selectedId, true)) { "无法保存备份插件启用状态" }
        val service = checkNotNull(launch(selectedId)) { "备份插件启动失败，请到插件中心查看错误" }
        for (id in installedIds.distinct()) {
            if (id != selectedId) {
                check(setEnabled(id, false)) { "无法停用其他备份插件" }
            }
        }
        commit(service)
        Result.success(service)
    } catch (e: Exception) {
        val rollbackError = withContext(NonCancellable) {
            try {
                restore()
                null
            } catch (rollback: Exception) {
                rollback
            }
        }
        if (e is CancellationException) {
            rollbackError?.let { e.addSuppressed(it) }
            throw e
        }
        if (rollbackError == null) Result.failure(e)
        else Result.failure(IllegalStateException("${e.message}；恢复原备份服务失败", e).apply {
            addSuppressed(rollbackError)
        })
    }
}
