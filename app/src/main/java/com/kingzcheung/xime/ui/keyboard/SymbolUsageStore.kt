package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import com.kingzcheung.xime.settings.UserConfigDir
import com.kingzcheung.xime.util.AtomicFileWriter
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * 运算符符号使用次数的读写（[SymbolUsagePolicy] 的持久化层）。
 *
 * 内存表是唯一真源，写盘是异步补写：点击符号时**立刻**更新内存（下一次渲染即新顺序），
 * 落盘合并到下一次调用，避免每次点击都碰磁盘。
 *
 * 存放位置与 xime.yaml 同级；可见目录不可用时回退私有目录。
 */
internal object SymbolUsageStore {
    private const val TAG = "SymbolUsageStore"

    @Volatile
    private var counts: Map<String, Int>? = null

    @Volatile
    private var dirty = false

    /**
     * 当前内存表（**不读磁盘**）。
     *
     * 设计：首次访问一律返回空表（即默认顺序），并在后台线程载入真实数据。
     * 这样即使在 Compose 组合期首次调用也不会碰磁盘——组合在主线程，
     * 一次 `readText()` 就足以让键盘首帧掉帧。载入完成后的下一次组合即可用上真实顺序，
     * 视觉上表现为「键盘弹出后顺序很快稳定」，而不会有首帧卡顿。
     */
    fun counts(context: Context): Map<String, Int> {
        counts?.let { return it }
        ensureLoaded(context)
        return emptyMap()
    }

    /** 载入完成信号：保证 [awaitLoaded] 能等到结果。 */
    private val loadLatch = java.util.concurrent.CountDownLatch(1)

    /**
     * 等待后台载入完成并返回真实计数表（**挂起，不阻塞线程**）。
     *
     * 供 Compose 的 `LaunchedEffect` 调用：组合期先用默认顺序显示，载入完成后刷新。
     * 已载入或载入失败都会立即返回，最坏情况回退为空表（即默认顺序）。
     */
    suspend fun awaitLoaded(context: Context): Map<String, Int> {
        counts?.let { return it }
        ensureLoaded(context)
        return withContext(Dispatchers.IO) {
            runCatching { loadLatch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
            counts ?: emptyMap()
        }
    }

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "symbol-usage-io").apply { isDaemon = true }
    }

    @Volatile
    private var loadScheduled = false

    private fun ensureLoaded(context: Context) {
        if (loadScheduled) return
        synchronized(this) {
            if (loadScheduled || counts != null) return
            loadScheduled = true
        }
        val appContext = context.applicationContext
        runCatching {
            ioExecutor.execute {
                try {
                    val loaded = runCatching {
                        val file = readFile(appContext)
                        if (file != null && file.isFile) SymbolUsagePolicy.parse(file.readText()) else emptyMap()
                    }.getOrElse {
                        FileLogger.w(TAG, "符号使用记录读取失败：${it.message}")
                        emptyMap()
                    }
                    synchronized(this) {
                        // 后台载入期间若用户已点击过（counts 被赋值），以内存为准，不覆盖。
                        if (counts == null) counts = loaded
                    }
                } finally {
                    // 成功/失败都必须放行，否则 awaitLoaded 会白等满超时。
                    loadLatch.countDown()
                }
            }
        }.onFailure {
            FileLogger.w(TAG, "符号使用记录载入投递失败：${it.message}")
            loadLatch.countDown()
        }
    }

    /**
     * 记录一次点击并返回**新的**顺序。
     *
     * 顺序在下一次渲染立即生效；写盘走独立线程，**不阻塞点击所在的主线程**。
     *
     * 这里刻意不在调用线程 flush：符号键是热路径，ATOMIC 写 + fsync 在慢存储上可达
     * 数十毫秒，会让「点一下符号」明显卡顿。用户每次点击都改不了几个字节，
     * 用后台单线程顺序补写即可；进程被杀最多丢最近几次计数，不影响使用。
     */
    fun recordAndOrder(context: Context, symbol: String): List<String> {
        val next = synchronized(this) {
            val updated = SymbolUsagePolicy.withIncrement(counts(context), symbol)
            counts = updated
            dirty = true
            updated
        }
        scheduleFlush(context)
        return SymbolUsagePolicy.orderedSymbols(next)
    }

    private val flushExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "symbol-usage-flush").apply { isDaemon = true }
    }

    /** 投递一次后台补写（合并：已排队的会被本次覆盖，因为 flush 总是写当前全量）。 */
    private fun scheduleFlush(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            flushExecutor.execute {
                runCatching { flush(appContext) }
                    .onFailure { FileLogger.w(TAG, "符号使用记录落盘失败：${it.message}") }
            }
        }.onFailure { FileLogger.w(TAG, "符号使用记录补写投递失败：${it.message}") }
    }

    /** 把内存表落盘（幂等）。 */
    fun flush(context: Context) {
        val snapshot = synchronized(this) {
            if (!dirty) return
            dirty = false
            counts.orEmpty()
        }
        val text = SymbolUsagePolicy.serialize(snapshot)
        val visible = File(UserConfigDir.absoluteDir, SymbolUsagePolicy.FILE_NAME)
        val privateCopy = File(context.filesDir, SymbolUsagePolicy.FILE_NAME)
        // 优先可见目录（用户可查看/备份）；不可写时回退私有目录，行为与其它用户数据表一致。
        val target = if (UserConfigDir.isSupported && UserConfigDir.hasWritePermission(context)) visible else privateCopy
        if (!AtomicFileWriter.write(target, text)) {
            synchronized(this) { dirty = true }
            FileLogger.w(TAG, "符号使用记录写入失败：${target.absolutePath}")
        }
    }

    /** 仅测试用：丢弃内存状态。 */
    internal fun resetForTest() {
        synchronized(this) {
            counts = null
            dirty = false
        }
    }

    private fun readFile(context: Context): File? {
        if (UserConfigDir.isSupported && UserConfigDir.hasWritePermission(context)) {
            val visible = File(UserConfigDir.absoluteDir, SymbolUsagePolicy.FILE_NAME)
            if (visible.isFile) return visible
        }
        return File(context.filesDir, SymbolUsagePolicy.FILE_NAME)
    }
}