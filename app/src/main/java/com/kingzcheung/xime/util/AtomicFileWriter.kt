package com.kingzcheung.xime.util

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 用户数据的原子写盘。
 *
 * ## 为什么必须有
 *
 * `File.writeText()` 是「truncate 后写」：先把文件清空，再逐块写入。输入法是常驻进程，
 * 随时可能被 LMK 杀掉；若恰好死在 truncate 之后、写完之前，文件只剩半截或全空。
 * 下次加载解析失败，用户的词频、自动造词、候选排序**全部清零**（且无法恢复）。
 *
 * 词库文件还有个放大器：它们大多有「私有 + 可见镜像」两份。一份写坏后读侧会
 * 误判为「用户手改」，把坏内容回灌进内存表，再写盘把另一份也覆盖掉。
 *
 * ## 做法
 *
 * 同目录临时文件 → 写入 → fsync → rename 覆盖。rename 在同一文件系统内是原子的，
 * 因此任何时刻被中断，目标文件要么是完整旧内容、要么是完整新内容，不存在半截。
 *
 * `fsync` 不能省：rename 只保证目录项原子，数据块可能还在页缓存里；掉电后
 * 会出现「文件名指向了、内容还是空」的情况。
 *
 * ## 边界
 *
 * 少数 FUSE / 外置存储上 `renameTo` 会失败，此时退回覆盖写（至少不丢内容，
 * 只是失去了原子性保护）。临时文件清理失败不影响正确性，留待下次覆盖。
 */
object AtomicFileWriter {

    private const val TAG = "AtomicFileWriter"

    /**
     * 原子写入文本（UTF-8）。
     *
     * @return true = 已写入；false = 失败（异常已记录，调用方按需处理）
     */
    fun write(file: File, text: String): Boolean = write(file, text.toByteArray(Charsets.UTF_8))

    /**
     * 按目标路径的进程内锁：临时文件名固定为 `<name>.tmp`，同一目标的并发写
     * 会交错写进同一个 tmp 再 rename，原子性失效。不改唯一名：崩溃残留的 tmp
     * 需要被下次写入覆盖，否则会无限堆积。
     */
    private val pathLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun write(file: File, bytes: ByteArray): Boolean {
        val lock = pathLocks.computeIfAbsent(file.absolutePath) { Any() }
        return synchronized(lock) { writeLocked(file, bytes) }
    }

    private fun writeLocked(file: File, bytes: ByteArray): Boolean {
        // 临时文件放同目录：跨文件系统 rename 会退化成 copy，失去原子性
        val parent = file.absoluteFile.parentFile ?: return false
        // 目标若是目录，必须直接失败：下面 rename 会失败，而回退的 copyTo(overwrite=true)
        // 会先 delete() 再写 —— 那会把一个目录删掉再变成文件，比失败更糟。
        if (file.isDirectory) {
            Log.w(TAG, "target is a directory, refuse to overwrite: ${file.absolutePath}")
            return false
        }
        val tmp = File(parent, "${file.name}.tmp")
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) {
                Log.w(TAG, "cannot create dir: ${parent.absolutePath}")
                return false
            }
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                // 必须 fsync：否则掉电后可能出现「文件名在、内容空」
                out.fd.sync()
            }
            if (tmp.renameTo(file)) return true
            // rename 失败（FUSE/外置存储）：退回覆盖写，至少不丢内容。
            // 但必须再查一次目标是不是目录：write 开头的检查与这里之间，
            // 目标可能被并发创建成目录，而 copyTo(overwrite=true) 会先 delete 再写，
            // 那等于把目录删掉换成文件——比失败更糟。
            if (file.isDirectory) {
                Log.w(TAG, "target became a directory, refuse fallback overwrite: ${file.absolutePath}")
                tmp.delete()
                return false
            }
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "atomic write failed: ${file.absolutePath}", t)
            runCatching { tmp.delete() }
            false
        }
    }

    /**
     * 原子写入，失败时抛出（供需要感知失败的调用方使用）。
     */
    fun writeOrThrow(file: File, text: String) {
        if (!write(file, text)) throw java.io.IOException("写入失败: ${file.absolutePath}")
    }
}
