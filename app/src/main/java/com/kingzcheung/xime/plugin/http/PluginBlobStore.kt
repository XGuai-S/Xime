package com.kingzcheung.xime.plugin.http

import android.util.Log
import com.kingzcheung.xime.plugin.core.js.http.BlobStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** 全插件共享的下载目录预算；网络读取不持锁，落盘按最多 64KB 原子计费。 */
class PluginBlobStore(
    private val rootDir: File,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
) : BlobStore {
    companion object {
        private const val TAG = "PluginBlobStore"
        private const val STALE_MILLIS = 24L * 60 * 60 * 1000
        private const val WRITE_CHUNK_BYTES = 64 * 1024
        const val MAX_BLOB_BYTES = 512L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
        const val MAX_ENTRIES = 32

        /** 句柄在最近这段时间内被取用过就免于 LRU 淘汰（见 ensureSlotLocked）。 */
        private const val EVICT_GRACE_MS = 30_000L

        /**
         * 被 LRU 淘汰的文件在这段时间内不被字节回收删除。
         *
         * 覆盖「插件 resolve 后锁外慢读」的常见情况。窗口取值权衡：
         * - 太短：慢读被删 → 插件侧读失败
         * - 太长：淘汰文件在保护期内占着字节预算 → 反复淘汰时可能写入失败
         *
         * 取 90 秒：覆盖绝大多数「下载后立即读取」的形态（读本地文件通常秒级）。
         * 真正的慢网上传是流式写入 pending，不走 entries 淘汰路径，不受此窗口影响。
         * 窗口过后文件仍未受保护，字节回收或 STALE_MILLIS 陈旧清扫会处理。
         */
        private const val EVICT_PROTECT_MS = 90_000L
    }

    private data class Entry(
        val pluginId: String,
        val file: File,
        val owned: Boolean,
        /**
         * 最近一次被 resolve 取用的时刻（单调时钟）。
         *
         * 必须用 elapsedRealtime 而不是 currentTimeMillis：用户改系统时间/NTP 校正会让
         * 墙钟跳变，若跳到未来则该条目 `lastUsedMs > cutoff` 恒成立、永不被淘汰（槽位泄漏）；
         * 跳到过去则会连带淘汰刚用过的条目。单调时钟不受墙钟调整影响。
         */
        var lastUsedMs: Long = android.os.SystemClock.elapsedRealtime(),
    )
    private enum class StreamState { NEW, OPEN, CLOSED, FAILED }
    private class Pending(val owner: String, val file: File) {
        var state = StreamState.NEW
        var reserved = 0L
        var output: OutputStream? = null
    }

    private val capacityLock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val pending = mutableMapOf<String, Pending>()
    // owned=false 即使注销也不转成可回收垃圾。
    private val callerOwned = mutableSetOf<String>()
    private var chargedBytes = 0L

    /**
     * 刚被 LRU 淘汰、但可能仍被插件读取的文件（path → 可删时刻）。
     *
     * 淘汰只摘句柄不删文件（见 ensureSlotLocked），但 [reclaimLocked] 在字节压力下
     * 会删所有「未受保护」的文件——那会把正在读的淘汰文件一起删掉，等于把问题
     * 从 LRU 挪到了字节回收。这里给淘汰文件一个保护窗口，窗口内两个回收路径都不碰它。
     */
    private val evictedProtection = mutableMapOf<String, Long>()

    /**
     * 淘汰前最后被 resolve 的时刻（path → 时刻）。
     *
     * 用来判断「这个淘汰文件是否真的可能正在被读」：
     * 只有淘汰前不久还被取用过的，才值得占着字节预算保护；
     * 淘汰前就已经闲置很久的，肯定没人在读，字节压力下可以直接回收。
     */
    private val evictedLastUse = mutableMapOf<String, Long>()

    init {
        require(maxTotalBytes >= 0)
        require(maxEntries >= 0)
        synchronized(capacityLock) {
            check(rootDir.isDirectory || rootDir.mkdirs()) { "无法创建 blob 暂存目录" }
            val deadline = System.currentTimeMillis() - STALE_MILLIS
            directoryFilesLocked().filter { it.lastModified() < deadline }.forEach { it.delete() }
            reconcileLocked()
            reclaimLocked(0)
        }
    }

    override fun createTempFile(ownerPluginId: String): File = synchronized(capacityLock) {
        reconcileLocked()
        ensureSlotLocked()
        ensureBytesLocked(1)
        check(rootDir.isDirectory || rootDir.mkdirs()) { "无法创建 blob 暂存目录" }
        val file = File.createTempFile("blob-", ".tmp", rootDir)
        pending[file.canonicalPath] = Pending(ownerPluginId, file)
        file
    }

    override fun openTempOutputStream(ownerPluginId: String, file: File): OutputStream =
        synchronized(capacityLock) {
            val path = file.canonicalPath
            val item = requirePendingLocked(ownerPluginId, path)
            check(item.state == StreamState.NEW) { "blob 临时流只能打开一次" }
            // 不截断 legacy 写入；这样已占用的字节不会被静默抹掉。
            check(item.file.length() == 0L) { "blob 临时文件已写入" }
            try {
                item.output = item.file.outputStream()
                item.state = StreamState.OPEN
            } catch (e: Exception) {
                item.state = StreamState.FAILED
                throw e
            }
            object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (offset < 0 || length < 0 || offset > bytes.size - length) {
                        throw IndexOutOfBoundsException()
                    }
                    var position = offset
                    var remaining = length
                    synchronized(capacityLock) { requireOpenLocked(path, item) }
                    while (remaining > 0) {
                        val count = minOf(remaining, WRITE_CHUNK_BYTES)
                        synchronized(capacityLock) {
                            requireOpenLocked(path, item)
                            if (count.toLong() > MAX_BLOB_BYTES - item.reserved) {
                                throw IOException("响应体超过上限（512MB）")
                            }
                            ensureBytesLocked(count.toLong())
                            chargedBytes += count
                            item.reserved += count
                            try {
                                item.output!!.write(bytes, position, count)
                            } catch (e: Exception) {
                                // 部分写入量不确定，预扣直到 discard 确认删除才释放。
                                item.state = StreamState.FAILED
                                throw e
                            }
                        }
                        position += count
                        remaining -= count
                    }
                }

                override fun flush() = synchronized(capacityLock) {
                    requireOpenLocked(path, item)
                    try {
                        item.output!!.flush()
                    } catch (e: Exception) {
                        item.state = StreamState.FAILED
                        throw e
                    }
                }

                override fun close() = synchronized(capacityLock) {
                    if (pending[path] !== item || item.output == null) return@synchronized
                    val output = item.output!!
                    item.output = null
                    try {
                        output.close()
                        if (item.state == StreamState.OPEN) item.state = StreamState.CLOSED
                    } catch (e: Exception) {
                        item.state = StreamState.FAILED
                        throw e
                    }
                }
            }
        }

    override fun discardTempFile(ownerPluginId: String, file: File) {
        synchronized(capacityLock) {
            val path = file.canonicalPath
            val item = pending[path] ?: return
            require(item.owner == ownerPluginId) { "blob 临时文件不属于当前插件" }
            var closeFailure: Exception? = null
            try {
                item.output?.close()
            } catch (e: Exception) {
                closeFailure = e
            } finally {
                item.output = null
                item.state = StreamState.FAILED
            }
            if (!item.file.exists() || item.file.delete() || !item.file.exists()) {
                pending.remove(path)
                reconcileLocked()
            } else {
                val failure = IOException("blob 暂存文件删除失败: ${item.file.name}")
                closeFailure?.let { failure.addSuppressed(it) }
                throw failure
            }
            closeFailure?.let { throw it }
        }
    }

    override fun register(ownerPluginId: String, file: File, owned: Boolean): String =
        synchronized(capacityLock) {
            val path = file.canonicalPath
            val item = pending[path]
            if (item != null) {
                require(item.owner == ownerPluginId) { "blob 临时文件不属于当前插件" }
                check(item.state == StreamState.NEW || item.state == StreamState.CLOSED) {
                    "blob 临时流尚未成功关闭"
                }
            }
            // 先保护本次登记文件，不能被边界回收误删。
            reconcileLocked()
            if (item == null) ensureSlotLocked()
            if (owned || item != null) {
                if (file.length() > MAX_BLOB_BYTES) throw IOException("响应体超过上限（512MB）")
                if (chargedBytes > maxTotalBytes) {
                    reclaimLocked(0, path)
                    if (chargedBytes > maxTotalBytes) throw IOException("插件暂存目录已达上限")
                }
            }
            val id = UUID.randomUUID().toString()
            entries[id] = Entry(ownerPluginId, file, owned)
            if (!owned) callerOwned.add(path)
            pending.remove(path)
            reconcileLocked()
            id
        }

    override fun resolve(pluginId: String, id: String): File? = synchronized(capacityLock) {
        val entry = entries[id] ?: return@synchronized null
        if (entry.pluginId != pluginId) return@synchronized null
        if (!entry.file.isFile) {
            entries.remove(id)
            reconcileLocked()
            return@synchronized null
        }
        // 刷新 LRU 时间戳：槽位耗尽时按「最久未取用」淘汰，正在用的句柄不会被误删
        entry.lastUsedMs = android.os.SystemClock.elapsedRealtime()
        entry.file
    }

    override fun release(id: String) {
        synchronized(capacityLock) {
            val entry = entries.remove(id) ?: return
            if (entry.owned && entry.file.canonicalPath !in callerOwned &&
                entry.file.exists() && !entry.file.delete() && entry.file.exists()
            ) {
                Log.w(TAG, "blob 暂存文件删除失败: ${entry.file.name}")
            }
            reconcileLocked()
        }
    }

    override fun releaseAll(ownerPluginId: String): Int = synchronized(capacityLock) {
        // 卸载插件时必须连**未登记**的临时文件一起清：下载中断留下的 pending 半成品
        // 同样占磁盘预算，且 owner 已经不在了，没有任何机会再被正常释放。
        val pendingPaths = pending.filterValues { it.owner == ownerPluginId }.keys.toList()
        var reclaimed = 0
        for (path in pendingPaths) {
            val item = pending[path] ?: continue
            runCatching { item.output?.close() }
            item.output = null
            item.state = StreamState.FAILED
            if (item.file.exists() && !item.file.delete() && item.file.exists()) {
                Log.w(TAG, "插件卸载时 blob 临时文件删除失败: ${item.file.name}")
            }
            pending.remove(path)
            reclaimed++
        }
        entries.filterValues { it.pluginId == ownerPluginId }.keys.forEach { id ->
            val entry = entries.remove(id) ?: return@forEach
            if (entry.owned && entry.file.exists() && !entry.file.delete() && entry.file.exists()) {
                Log.w(TAG, "插件卸载时 blob 文件删除失败: ${entry.file.name}")
            }
            reclaimed++
        }
        callerOwned.removeAll { path ->
            val file = File(path)
            !file.exists()
        }
        reconcileLocked()
        if (reclaimed > 0) {
            Log.d(TAG, "回收插件 $ownerPluginId 的 $reclaimed 个 blob 句柄")
        }
        reclaimed
    }

    fun usedBytes(): Long = synchronized(capacityLock) { directoryFilesLocked().sumOf { it.length() } }
    fun entryCount(): Int = synchronized(capacityLock) { entries.size }

    private fun requirePendingLocked(owner: String, path: String): Pending {
        val item = pending[path] ?: throw IllegalArgumentException("不是当前仓库的临时文件")
        require(item.owner == owner) { "blob 临时文件不属于当前插件" }
        return item
    }

    private fun requireOpenLocked(path: String, item: Pending) {
        if (pending[path] !== item || item.state != StreamState.OPEN) {
            throw IOException("blob 临时流已关闭或失效")
        }
    }

    /**
     * 保证还有空槽位；满了先按 LRU 淘汰**最久未使用**的句柄，仍不够才报错。
     *
     * 为什么不直接抛错：槽位是**全插件共享**的 32 个（`entries` 单池）。某个插件
     * 反复下载却不 release 就能把池占满，导致其它插件的 download / backup.pull 全部失败——
     * 一个插件的 bug 变成所有插件不可用。LRU 淘汰让它自愈；插件下次取用被淘汰的 id 时
     * 会拿到「句柄不存在」的正常错误，而不是被别的插件挤死。
     *
     * 只淘汰 owned 的（本仓创建的）；callerOwned 属于插件自己的文件，绝不代删。
     *
     * ## 为什么不直接删文件
     *
     * 插件 `resolve` 拿到 File 后是在**锁外**慢慢读的。若淘汰时立刻删文件，
     * 那个正在进行的读会中途失败。因此淘汰只做两件事：
     * **摘掉句柄**（释放槽位）+ 把文件登记进 [evictedProtection] 保护窗口。
     *
     * 保护窗口（[EVICT_PROTECT_MS]）内，LRU 与字节回收都不碰该文件。
     * **窗口过后**，文件重新变为「未受保护」，[reclaimLocked] 在字节压力下仍会删除它。
     *
     * 所以这里的保证是**有限的**：覆盖「下载完立即读取」这类秒级读取，
     * 不覆盖「拿到 File 后持有超过窗口再慢慢读」的极端形态。
     * 要彻底消除需要引用计数（resolve 时 +1、插件显式归还 -1），
     * 那要求插件配合改协议，暂不做。
     */
    private fun ensureSlotLocked() {
        if (entries.size + pending.size < maxEntries) return
        // 按最后使用时间升序淘汰，最多淘汰到刚好够一个槽位。
        //
        // 宽限期：刚注册的句柄不参与淘汰。否则「注册 1 个 → 立刻请求第 2 个」会把自己
        // 刚拿到的句柄淘汰掉，调用方拿到的 id 转眼失效——比直接报错更难排查。
        // 只有确实存在「很久没用」的旧句柄时才自愈。
        val cutoff = android.os.SystemClock.elapsedRealtime() - EVICT_GRACE_MS
        val victims = entries.entries
            .filter { it.value.owned && it.value.lastUsedMs <= cutoff }
            .sortedBy { it.value.lastUsedMs }
            .take(entries.size + pending.size - maxEntries + 1)
        val now = android.os.SystemClock.elapsedRealtime()
        for ((id, entry) in victims) {
            // 只摘句柄释放槽位，**不删文件**：可能有插件正在锁外读它（见上方说明）。
            // 登记保护窗口，期间字节回收也不删它。
            entries.remove(id)
            runCatching {
                val path = entry.file.canonicalPath
                evictedProtection[path] = now + EVICT_PROTECT_MS
                evictedLastUse[path] = entry.lastUsedMs
            }
            Log.d(TAG, "LRU 淘汰 blob 句柄 $id（插件 ${entry.pluginId}，文件保护 ${EVICT_PROTECT_MS}ms）")
        }
        evictedProtection.entries.removeAll { it.value <= now }
        evictedLastUse.keys.retainAll(evictedProtection.keys)
        if (entries.size + pending.size >= maxEntries) {
            throw IllegalStateException("blob 句柄已达上限（$maxEntries），请先 release 不再使用的句柄")
        }
    }

    private fun ensureBytesLocked(additional: Long) {
        // 健康写入只读缓存；只有触及容量边界才扫描/回收目录。
        if (chargedBytes <= maxTotalBytes && additional <= maxTotalBytes - chargedBytes) return
        reconcileLocked()
        reclaimLocked(additional)
        if (chargedBytes > maxTotalBytes || additional > maxTotalBytes - chargedBytes) {
            throw IOException("插件暂存目录已达上限，请先 release 不再使用的句柄")
        }
    }

    private fun directoryFilesLocked(): List<File> =
        rootDir.walkTopDown().filter { it.isFile }.toList()

    private fun reconcileLocked() {
        entries.entries.removeAll { !it.value.file.isFile }
        // callerOwned 的成员是「插件自己拥有、本仓只登记不删除」的外部文件。
        //
        // 语义（保持不变）：这类文件**永远受保护、绝不被本仓删除**——它属于插件，
        // 本仓只是借来给 JS 读。因此清空条件只能是「文件已不存在」。
        //
        // 注意：不能因为「句柄都释放了」就把路径移出集合 —— 那会让 reclaimLocked
        // 认为它可以删，从而删掉插件的文件（PluginBlobQuotaTest 已钉住这条）。
        // 这类文件长期占用磁盘属于插件自己的责任，不在本仓配额管理范围内。
        callerOwned.removeAll { !File(it).exists() }
        val files = directoryFilesLocked()
        chargedBytes = files.sumOf { it.length() }
        for (item in pending.values) {
            // 失败写入保留全部预扣，但不把已落盘字节重复计算。
            chargedBytes += (item.reserved - item.file.length()).coerceAtLeast(0)
        }
    }

    private fun reclaimLocked(additional: Long, registeringPath: String? = null) {
        if (chargedBytes <= maxTotalBytes && additional <= maxTotalBytes - chargedBytes) return
        val protected = entries.values.mapTo(mutableSetOf()) { it.file.canonicalPath }
        protected.addAll(pending.keys)
        protected.addAll(callerOwned)
        registeringPath?.let { protected.add(it) }
        // 刚被 LRU 淘汰的文件：只保护「淘汰前不久还被 resolve 过」的那些。
        //
        // 淘汰前就已闲置很久的（比如 lastUsed 是几分钟前），不可能有人在读，
        // 字节压力下直接回收——否则 90 秒保护期内反复淘汰会把预算占满，
        // 导致所有插件写入抛 IOException（这是终审指出的 P2）。
        val now = android.os.SystemClock.elapsedRealtime()
        evictedProtection.entries.removeAll { it.value <= now }
        evictedLastUse.keys.retainAll(evictedProtection.keys)
        val recentlyUsedCutoff = now - EVICT_PROTECT_MS
        for (path in evictedProtection.keys) {
            // 取不到 lastUse（淘汰时登记失败）按 0L 处理 → 不保护 → 可回收。
            // 方向是 fail-safe：不确定就允许回收，避免「因为记不清而永久占着字节预算」。
            // 代价是那个文件可能正被读取，但登记失败本身极罕见（runCatching 包着 map put）。
            val lastUse = evictedLastUse[path] ?: 0L
            if (lastUse >= recentlyUsedCutoff) protected.add(path)
        }
        for (file in directoryFilesLocked().sortedBy { it.lastModified() }) {
            if (chargedBytes <= maxTotalBytes && additional <= maxTotalBytes - chargedBytes) break
            if (file.canonicalPath in protected) continue
            val length = file.length()
            if (file.delete() || !file.exists()) chargedBytes -= length
        }
    }
}
