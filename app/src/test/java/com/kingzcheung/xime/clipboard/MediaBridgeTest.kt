package com.kingzcheung.xime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图片中转目录口径与清理条件（纯 JVM）。中转必须落在 Download（相册不索引），清理只动自己插入的过期行。 */
class MediaBridgeTest {

    @Test
    fun `中转目录在 Download（相册不索引）`() {
        assertEquals("Download/Xime/", MediaBridge.relativeDir())
        assertEquals("Download", MediaBridge.BRIDGE_PARENT_DIR)
    }

    @Test
    fun `清理条件同时限定包名、中转目录与过期时间`() {
        val (selection, args) = MediaBridge.cleanupSelection(
            packageName = "com.kingzcheung.xime",
            cutoffSeconds = 1_700_000_000L,
        )
        assertTrue("必须限定只删本应用插入的行", selection.contains("owner_package_name = ?"))
        assertTrue("必须限定中转目录", selection.contains("relative_path = ?"))
        assertTrue("必须限定过期时间", selection.contains("date_added < ?"))
        assertEquals(
            listOf("com.kingzcheung.xime", "Download/Xime/", "1700000000"),
            args.toList(),
        )
    }

    @Test
    fun `中转文件是短寿命的：分钟级保留后即删`() {
        assertEquals(5 * 60 * 1000L, MediaBridge.TTL_MS)
        assertTrue("TTL 不能短于接收方读完所需（秒级）", MediaBridge.TTL_MS >= 60_000L)
    }
}
