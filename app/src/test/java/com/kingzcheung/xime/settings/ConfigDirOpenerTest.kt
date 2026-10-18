package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `open_dir` 命令的解析（纯字符串逻辑，不涉及 Android）。 */
class ConfigDirOpenerTest {

    @Test
    fun `识别 open_dir 命令`() {
        assertTrue(ConfigDirOpener.isOpenDirCommand("open_dir"))
        assertTrue(ConfigDirOpener.isOpenDirCommand("open_dir:/storage/emulated/0/rime"))
    }

    @Test
    fun `其它命令不误判`() {
        assertFalse(ConfigDirOpener.isOpenDirCommand("reload"))
        // 前缀相同但不是本命令（无 `:` 分隔）不能算
        assertFalse(ConfigDirOpener.isOpenDirCommand("open_dirs"))
    }

    @Test
    fun `不带路径时返回空`() {
        assertNull(ConfigDirOpener.parseOpenDirPath("open_dir"))
    }

    @Test
    fun `带路径时取冒号后的目录`() {
        assertEquals(
            "/storage/emulated/0/rime",
            ConfigDirOpener.parseOpenDirPath("open_dir:/storage/emulated/0/rime"),
        )
        assertEquals(
            "/storage/emulated/0/rime/xime",
            ConfigDirOpener.parseOpenDirPath("open_dir:/storage/emulated/0/rime/xime"),
        )
    }
}
