package com.kingzcheung.xime.settings

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 解压额度：按**实际写出**的字节累计（不信任条目头里的声明大小），超限即抛 [IOException] 中止。
 * 防止高压缩比的包写满 /data。
 */
internal class ArchiveBudget(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private var totalBytes = 0L
    private var entries = 0

    /** 每写一个条目前调用。 */
    fun onEntry() {
        if (++entries > maxEntries) throw IOException("压缩包条目数超过 $maxEntries，已中止")
    }

    fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            totalBytes += n
            if (totalBytes > maxTotalBytes) {
                throw IOException("压缩包解压后超过 ${maxTotalBytes / 1024 / 1024}MB，已中止")
            }
            output.write(buffer, 0, n)
        }
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 5000
        private const val BUFFER_SIZE = 64 * 1024
    }
}
