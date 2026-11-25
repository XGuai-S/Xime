package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 导入文件名的路径穿越防护。
 *
 * 文件名并非总是可信：无线导入的 multipart `filename` 由客户端任意填写，
 * 分享 intent 的 displayName 也可能带路径。旧实现原样返回，
 * `File(rimeDir, "../../foo")` 会写到 rime/ 之外（实测可写出 filesDir）。
 *
 * 这里钉住 `sanitizeDisplayName` 的行为：只取最后一段，拒绝 `..`/控制字符，
 * 且保留原有的 `.yaml.txt` 后缀修正语义。
 */
class ImportFileNameSanitizeTest {

    @Test
    fun `父目录穿越被剥掉只留文件名`() {
        assertEquals("evil.yaml", SchemaManager.sanitizeDisplayName("../../evil.yaml"))
        assertEquals("evil.yaml", SchemaManager.sanitizeDisplayName("a/b/c/evil.yaml"))
    }

    @Test
    fun `反斜杠路径同样被剥掉`() {
        // Windows 风格分隔符不能绕过校验
        assertEquals("evil.yaml", SchemaManager.sanitizeDisplayName("..\\..\\evil.yaml"))
        assertEquals("evil.yaml", SchemaManager.sanitizeDisplayName("a\\b\\evil.yaml"))
    }

    @Test
    fun `绝对路径退化为文件名`() {
        assertEquals("passwd.yaml", SchemaManager.sanitizeDisplayName("/etc/passwd.yaml"))
        assertEquals("xime.yaml", SchemaManager.sanitizeDisplayName("/storage/emulated/0/rime/xime.yaml"))
    }

    @Test
    fun `纯穿越与空名返回空串`() {
        assertEquals("", SchemaManager.sanitizeDisplayName(".."))
        assertEquals("", SchemaManager.sanitizeDisplayName("."))
        assertEquals("", SchemaManager.sanitizeDisplayName(""))
        assertEquals("", SchemaManager.sanitizeDisplayName("a/b/.."))
    }

    @Test
    fun `控制字符被拒绝`() {
        assertEquals("", SchemaManager.sanitizeDisplayName("evil\u0000.yaml"))
        assertEquals("", SchemaManager.sanitizeDisplayName("evil\n.yaml"))
    }

    @Test
    fun `原有的 yaml txt 后缀修正不受影响`() {
        assertEquals("xime.custom.yaml", SchemaManager.sanitizeDisplayName("xime.custom.yaml.txt"))
        assertEquals("wubi86.dict.yaml", SchemaManager.sanitizeDisplayName("wubi86.dict.yaml.txt"))
    }

    @Test
    fun `正常文件名原样保留`() {
        assertEquals("wubi86.schema.yaml", SchemaManager.sanitizeDisplayName("wubi86.schema.yaml"))
        assertEquals("自定义词库.dict.yaml", SchemaManager.sanitizeDisplayName("自定义词库.dict.yaml"))
    }

    @Test
    fun `净化后拼接不会逃出目标目录`() {
        // 端到端语义：落盘用的 File(dir, name) 必须仍在 dir 内
        val root = File(System.getProperty("java.io.tmpdir"), "rime-sanitize-test")
        val dir = File(root, "rime").apply { mkdirs() }
        for (raw in listOf("../../evil.yaml", "..\\..\\evil.yaml", "/etc/passwd", "a/b/../c.yaml")) {
            val name = SchemaManager.sanitizeDisplayName(raw)
            if (name.isEmpty()) continue
            val target = File(dir, name).canonicalFile
            assertTrue("净化后仍逃出目录：$raw -> $target", target.path.startsWith(dir.canonicalFile.path))
        }
    }

    @Test
    fun `净化结果不含路径分隔符`() {
        for (raw in listOf("../../a.yaml", "a\\b.yaml", "/x/y.yaml")) {
            val name = SchemaManager.sanitizeDisplayName(raw)
            assertFalse("仍含分隔符：$raw -> $name", name.contains('/') || name.contains('\\'))
        }
    }
}
