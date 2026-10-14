package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁住 formatByteSize 与 parseMegabyteTextToBytes 的既有行为。
 *
 * 二者分别合并自 MarketHubScreen.modelFormatSize / RimeFileBrowserScreen.formatSize
 * 与三处逐字相同的 " MB" 文本估算，用例覆盖原实现的全部输出形状与回退路径，
 * 以保证这次去重不改变任何一处调用方的可见结果。
 */
class ByteSizeFormatTest {

    @Test
    fun `bytes below 1 KiB show as integer B`() {
        assertEquals("0 B", formatByteSize(0L))
        assertEquals("1 B", formatByteSize(1L))
        assertEquals("1023 B", formatByteSize(1023L))
    }

    @Test
    fun `exactly 1 KiB switches to KB`() {
        assertEquals("1 KB", formatByteSize(1024L))
    }

    @Test
    fun `KB range truncates toward zero`() {
        assertEquals("1 KB", formatByteSize(1025L))
        assertEquals("1023 KB", formatByteSize(1024L * 1024L - 1))
    }

    @Test
    fun `exactly 1 MiB switches to MB with one decimal`() {
        assertEquals("1.0 MB", formatByteSize(1024L * 1024L))
    }

    @Test
    fun `MB range keeps one decimal place`() {
        assertEquals("1.5 MB", formatByteSize(1024L * 1024L * 3 / 2))
        assertEquals("2.0 MB", formatByteSize(1024L * 1024L * 2))
        assertEquals("4.0 MB", formatByteSize(4L * 1024L * 1024L))
    }

    @Test
    fun `large values stay in MB`() {
        assertEquals("1024.0 MB", formatByteSize(1024L * 1024L * 1024L))
    }

    @Test
    fun `thresholds match the pre-merge implementations`() {
        // 原实现阈值：bytes < 1024 与 bytes < 1024 * 1024
        assertEquals("1023 B", formatByteSize(BYTES_PER_KB - 1))
        assertEquals("1 KB", formatByteSize(BYTES_PER_KB))
        assertEquals("1023 KB", formatByteSize(BYTES_PER_MB - 1))
        assertEquals("1.0 MB", formatByteSize(BYTES_PER_MB))
    }

    @Test
    fun `megabyte text parses to bytes`() {
        assertEquals(1024L * 1024L, parseMegabyteTextToBytes("1 MB"))
        assertEquals(1024L * 1024L * 15 / 10, parseMegabyteTextToBytes("1.5 MB"))
        assertEquals(100L * 1024L * 1024L, parseMegabyteTextToBytes("100 MB"))
    }

    @Test
    fun `megabyte text only strips an exact trailing MB suffix`() {
        // 原实现先 removeSuffix(" MB") 再 trim，故尾部空格会使后缀不匹配而回退 0L；
        // 这是合并前的既有行为，此处如实锁定，不做修正。
        assertEquals(1024L * 1024L, parseMegabyteTextToBytes("1 MB"))
        assertEquals(1024L * 1024L, parseMegabyteTextToBytes("1  MB"))
        assertEquals(0L, parseMegabyteTextToBytes("  1 MB "))
        assertEquals(0L, parseMegabyteTextToBytes("1 MB\n"))
    }

    @Test
    fun `unparsable megabyte text falls back to zero`() {
        // 原实现：解析失败一律 0L（与 sizeBytes 缺省时的回退一致）
        assertEquals(0L, parseMegabyteTextToBytes(null))
        assertEquals(0L, parseMegabyteTextToBytes(""))
        assertEquals(0L, parseMegabyteTextToBytes("unknown"))
        assertEquals(0L, parseMegabyteTextToBytes("MB"))
    }

    @Test
    fun `parse truncates fractional bytes`() {
        // 原实现用 toLong() 截断，保留该行为
        assertEquals(1572864L, parseMegabyteTextToBytes("1.5 MB"))
        assertEquals(524288L, parseMegabyteTextToBytes("0.5 MB"))
    }
}
