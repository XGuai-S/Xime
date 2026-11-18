package com.kingzcheung.xime.plugin.http

import android.util.Log
import com.kingzcheung.xime.plugin.core.js.http.BlobStore
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [BlobStore] 的 app 层实现：临时文件落在 cacheDir/plugin-blobs，句柄为随机 UUID。
 *
 * - **id 不可猜**：UUID 随机串，且 [resolve] 校验登记方 pluginId，插件之间互不可见；
 * - **所有权**：`owned=true`（流式下载产物）在 [release] 时删除；`owned=false`
 *   （BackupManager 落盘的备份包）只注销，文件由原调用方清理；
 * - **进程残留**：init 时清理超过 [STALE_MILLIS] 的历史暂存文件（崩溃/被杀不会堆积），
 *   并限制单次下载体积上限（防恶意/异常服务器写满磁盘）；
 * - **总量与句柄数**：单次上限只能挡住「一个 512MB」，挡不住「插件反复下载不 release」。
 *   这里再加目录总量（[maxTotalBytes]）与同时存在的句柄数（[maxEntries]）两道闸：
 *   超总量时先按最旧优先回收**未登记**文件，仍超则直接拒绝新的落盘；句柄数超限则
 *   拒绝登记（等价于要求插件先 release）。
 *
 * @param maxTotalBytes 暂存目录总量上限（测试可注入小值）
 * @param maxEntries 同时存在的 blob 句柄上限（测试可注入小值）
 */
class PluginBlobStore(
    private val rootDir: File,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
) : BlobStore {

    companion object {
        private const val TAG = "PluginBlobStore"

        /** 残留暂存文件保留时长（超过即视为无人引用的垃圾）。 */
        private const val STALE_MILLIS = 24L * 60 * 60 * 1000

        /** 单次流式下载落盘上限（512MB，防写满磁盘）。 */
        const val MAX_BLOB_BYTES = 512L * 1024 * 1024

        /** 暂存目录总量上限（1GB，含未登记的半成品 .tmp）。 */
        const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024

        /** 同时存在的 blob 句柄上限。 */
        const val MAX_ENTRIES = 32
    }

    private data class Entry(val pluginId: String, val file: File, val owned: Boolean)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 容量判定与回收的互斥（插件线程与主线程都会触发登记）。 */
    private val capacityLock = Any()

    init {
        rootDir.mkdirs()
        synchronized(capacityLock) {
            sweepStaleLocked()
            shrinkToLimitLocked()
        }
    }

    override fun createTempFile(ownerPluginId: String): File {
        rootDir.mkdirs()
        synchronized(capacityLock) { ensureCapacityForNewBlobLocked() }
        return File(rootDir, "blob-${UUID.randomUUID()}.tmp")
    }

    override fun register(ownerPluginId: String, file: File, owned: Boolean): String {
        synchronized(capacityLock) {
            pruneDeadEntriesLocked()
            if (entries.size >= maxEntries) {
                throw IllegalStateException(
                    "blob 句柄已达上限（$maxEntries），请先 release 不再使用的句柄"
                )
            }
        }
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(ownerPluginId, file, owned)
        return id
    }

    override fun resolve(pluginId: String, id: String): File? {
        val entry = entries[id] ?: return null
        if (entry.pluginId != pluginId) {
            Log.w(TAG, "blob 句柄跨插件引用被拒绝: $id")
            return null
        }
        if (!entry.file.isFile) {
            entries.remove(id)
            return null
        }
        return entry.file
    }

    override fun release(id: String) {
        val entry = entries.remove(id) ?: return
        if (entry.owned && !entry.file.delete()) {
            Log.w(TAG, "blob 暂存文件删除失败: ${entry.file.name}")
        }
    }

    /** 当前目录占用字节（测试与日志用）。 */
    fun usedBytes(): Long = rootDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    /** 当前登记的句柄数（测试用）。 */
    fun entryCount(): Int = entries.size

    /** 新 blob 落盘前的闸门：先回收，仍超限就拒绝（抛异常由调用方转成 lastError）。 */
    private fun ensureCapacityForNewBlobLocked() {
        pruneDeadEntriesLocked()
        shrinkToLimitLocked()
        if (usedBytes() >= maxTotalBytes) {
            throw IOException(
                "插件暂存目录已达上限（${maxTotalBytes / 1024 / 1024}MB），请稍后重试"
            )
        }
    }

    /** 清掉文件已消失的登记（插件 release 失败或外部清理都会造成）。 */
    private fun pruneDeadEntriesLocked() {
        val dead = entries.entries.filter { !it.value.file.isFile }.map { it.key }
        for (id in dead) entries.remove(id)
    }

    /**
     * 按「最旧优先」删除**未登记**文件，直到目录总量不超过 [maxTotalBytes]。
     *
     * 已登记的 blob 不在这里删：它们有活着的插件句柄，删掉会让插件拿到半截文件
     * （表现为下载「成功」但内容损坏，比直接失败更难排查）。
     */
    private fun shrinkToLimitLocked() {
        val files = rootDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxTotalBytes) return
        val registered = entries.values
            .mapNotNull { runCatching { it.file.canonicalPath }.getOrNull() }
            .toHashSet()
        var removed = 0
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxTotalBytes) break
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
            if (canonical in registered) continue
            val length = file.length()
            if (file.delete()) {
                total -= length
                removed++
            }
        }
        if (removed > 0) Log.i(TAG, "暂存目录超限，回收未登记文件 $removed 个")
    }

    /** 清理 init 之前的残留暂存文件（进程被杀/崩溃留下的 blob 不该长期占用 cache）。 */
    private fun sweepStaleLocked() {
        val deadline = System.currentTimeMillis() - STALE_MILLIS
        val stale = rootDir.listFiles()?.filter { it.isFile && it.lastModified() < deadline } ?: return
        var removed = 0
        for (file in stale) {
            if (file.delete()) removed++
        }
        if (removed > 0) Log.i(TAG, "清理残留 blob 暂存文件 $removed 个")
    }
}
