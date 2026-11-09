package com.kingzcheung.xime.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 锁定「备份包条目名是否指向 rime 目录之外」的判定语义。
 *
 * 背景：BackupManager.restoreArchive 用 `File(name).isAbsolute` 拒绝绝对路径条目。
 * 该判定依赖平台：Windows 上 `File("/etc/evil.txt").isAbsolute` 为 false（Windows
 * 绝对路径需要有盘符），于是 POSIX 风格的绝对路径会被当作相对路径拼接进 rime 目录。
 *
 * 本测试不依赖运行平台，因此对两种语义都给出明确断言，便于定位平台差异：
 * 无论 isAbsolute 返回什么，`startsWith("/")` 都能识别 POSIX 绝对路径。
 */
class BackupPathValidationTest {

    @Test
    fun `posix style absolute path is detected regardless of platform`() {
        // 这是平台无关的判据：以 / 或 \ 开头的条目名一定是绝对/根路径
        assertTrue(isRootedEntryName("/etc/evil.txt"))
        assertTrue(isRootedEntryName("\\evil.txt"))
        assertTrue(isRootedEntryName("/"))
    }

    @Test
    fun `windows drive absolute path is detected`() {
        assertTrue(isRootedEntryName("C:\\evil.txt"))
        assertTrue(isRootedEntryName("C:/evil.txt"))
    }

    @Test
    fun `relative entry names are accepted`() {
        assertFalse(isRootedEntryName("default.yaml"))
        assertFalse(isRootedEntryName("opencc/ts.txt"))
        assertFalse(isRootedEntryName("."))
    }

    @Test
    fun `file isAbsolute alone is insufficient for posix paths on windows`() {
        // 记录平台差异本身：这条断言在 Windows 与 Linux 上结果不同，
        // 因此绝不能把 File(name).isAbsolute 当作唯一防线。
        val posixAbsolute = File("/etc/evil.txt")
        if (File.separatorChar == '\\') {
            // Windows：isAbsolute 为 false，正是缺陷成因
            assertFalse(posixAbsolute.isAbsolute)
            // 而字符判据仍能识别
            assertTrue(isRootedEntryName("/etc/evil.txt"))
        } else {
            assertTrue(posixAbsolute.isAbsolute)
        }
    }

    /** 与 BackupManager 中将要采用的判据保持一致。 */
    private fun isRootedEntryName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return true
        // 盘符形式 C:\ 或 C:/
        if (name.length >= 2 && name[1] == ':' &&
            (name[0].isLetter()) &&
            (name.length == 2 || name[2] == '\\' || name[2] == '/')
        ) {
            return true
        }
        return File(name).isAbsolute
    }
}
