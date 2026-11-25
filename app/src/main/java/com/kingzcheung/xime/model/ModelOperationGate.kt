package com.kingzcheung.xime.model

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 模型存储操作的进程级互斥域（H11）。
 *
 * 与 ViewModel 的 activeDownloads 不同：这里按「规范化模型目录」划分串行域，
 * 同一模型的下载事务、删除、旧路径迁移互斥；不同模型互不影响。
 * 调用方（ViewModel）只负责显示，互斥以这里为准。
 *
 * 取消语义：下载登记的是**执行下载的那个协程的 Job**，删除入口取消它即可让
 * 下载事务在分片/解密边界退出，`join()` 则保证等它彻底结束（含临时目录清理）。
 */
internal object ModelOperationGate {

    private class Domain {
        val mutex = Mutex()

        /** 当前下载协程（只有下载可被删除入口取消） */
        @Volatile
        var download: Job? = null
    }

    private val domains = ConcurrentHashMap<String, Domain>()

    /** 规范化模型目录即串行域 key；路径不可用时退回模型 id，保证映射恒定。 */
    private fun domainKey(context: Context, modelId: String): String =
        runCatching { ModelStorage.getModelDir(context, modelId).canonicalPath }
            .getOrElse { "model-id:$modelId" }

    private fun domain(context: Context, modelId: String): Domain =
        domains.getOrPut(domainKey(context, modelId)) { Domain() }

    /** 该模型是否有下载正在执行（高频非挂起路径用：不加锁）。 */
    fun isDownloading(context: Context, modelId: String): Boolean =
        domain(context, modelId).download?.isActive == true

    /** 活动下载协程句柄（删除入口取消 + 等待它结束）。 */
    fun activeDownload(context: Context, modelId: String): Job? =
        domain(context, modelId).download?.takeIf { it.isActive }

    /**
     * 在模型的串行域内执行 [block]。
     * @param download 为真时把当前协程登记为可取消的下载任务
     */
    suspend fun <T> runExclusive(
        context: Context,
        modelId: String,
        download: Boolean = false,
        block: suspend () -> T,
    ): T {
        val target = domain(context, modelId)
        return target.mutex.withLock {
            if (download) target.download = currentCoroutineContext()[Job]
            try {
                block()
            } finally {
                if (download) target.download = null
            }
        }
    }

    /**
     * 非阻塞占用串行域（迁移等高频非挂起路径）：域被下载/删除占用时返回 null，
     * 由调用方放弃本次操作，避免把旧文件搬进正在提交的模型目录。
     */
    fun <T> tryIfIdle(context: Context, modelId: String, block: () -> T): T? {
        val target = domain(context, modelId)
        if (!target.mutex.tryLock()) return null
        return try {
            block()
        } finally {
            target.mutex.unlock()
        }
    }
}
