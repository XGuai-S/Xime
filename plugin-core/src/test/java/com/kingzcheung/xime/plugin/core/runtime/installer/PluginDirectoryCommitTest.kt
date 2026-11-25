package com.kingzcheung.xime.plugin.core.runtime.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicInteger

class PluginDirectoryCommitTest {
    @get:Rule
    val temp = TemporaryFolder()

    private data class Paths(val work: File, val staged: File, val target: File, val backup: File)

    private fun paths(withOldTarget: Boolean = true): Paths {
        val root = temp.root
        val work = File(root, "install-work").apply { mkdirs() }
        val staged = File(work, "staged").apply { tree(this, "new") }
        val target = File(root, "plugins/demo")
        checkNotNull(target.parentFile).mkdirs()
        if (withOldTarget) tree(target, "old")
        return Paths(work, staged, target, File(work, "backup"))
    }

    private fun tree(dir: File, value: String) {
        dir.mkdirs()
        File(dir, "version.txt").writeText(value)
    }

    private fun value(dir: File) = File(dir, "version.txt").readText()

    @Test
    fun `成功替换并返回 publish 结果`() {
        val p = paths()
        val result = PluginDirectoryCommit().replace(p.staged, p.target, p.backup) {
            assertEquals("new", value(p.target))
            "published"
        }
        assertEquals("published", result)
        assertEquals("new", value(p.target))
        assertFalse(p.backup.exists())
        assertFalse(p.staged.exists())
    }

    @Test
    fun `首次安装不要求旧目录`() {
        val p = paths(withOldTarget = false)
        assertEquals("ok", PluginDirectoryCommit().replace(p.staged, p.target, p.backup) { "ok" })
        assertEquals("new", value(p.target))
        assertFalse(p.backup.exists())
    }

    @Test
    fun `旧目录备份失败时保留新旧目录`() {
        val p = paths()
        var published = false
        val commit = PluginDirectoryCommit { from, to ->
            if (from == p.target && to == p.backup) false else from.renameTo(to)
        }
        assertThrows(IOException::class.java) {
            commit.replace(p.staged, p.target, p.backup) { published = true }
        }
        assertFalse(published)
        assertEquals("old", value(p.target))
        assertEquals("new", value(p.staged))
        assertFalse(p.backup.exists())
    }

    @Test
    fun `切换新目录失败时恢复旧目录`() {
        val p = paths()
        val calls = AtomicInteger()
        val commit = PluginDirectoryCommit { from, to ->
            when (calls.incrementAndGet()) {
                2 -> false
                else -> from.renameTo(to)
            }
        }
        assertThrows(IOException::class.java) {
            commit.replace(p.staged, p.target, p.backup) { error("publish 不应运行") }
        }
        assertEquals("old", value(p.target))
        assertEquals("new", value(p.staged))
        assertFalse(p.backup.exists())
        assertEquals(3, calls.get())
    }

    @Test
    fun `publish 失败时将新旧目录恢复`() {
        val p = paths()
        val failure = IllegalStateException("registry commit failed")
        val actual = assertThrows(IllegalStateException::class.java) {
            PluginDirectoryCommit().replace(p.staged, p.target, p.backup) { throw failure }
        }
        assertSame(failure, actual)
        assertEquals("old", value(p.target))
        assertEquals("new", value(p.staged))
        assertFalse(p.backup.exists())
    }

    @Test
    fun `回滚失败时保留旧备份并报告恢复路径`() {
        val p = paths()
        val calls = AtomicInteger()
        val commit = PluginDirectoryCommit { from, to ->
            when (calls.incrementAndGet()) {
                3 -> false // 不允许把新目录退回 staged
                else -> from.renameTo(to)
            }
        }
        val error = assertThrows(IOException::class.java) {
            commit.replace(p.staged, p.target, p.backup) { error("publish failed") }
        }
        assertTrue(error.message.orEmpty().contains(p.backup.absolutePath))
        assertEquals("old", value(p.backup))
        assertEquals("new", value(p.target))
        assertFalse(p.staged.exists())
    }

    @Test
    fun `拒绝工作目录与目标目录重叠`() {
        val p = paths()
        val overlappingTarget = File(p.work, "plugins/demo")
        val commit = PluginDirectoryCommit { _, _ -> error("不得移动") }
        assertThrows(IllegalArgumentException::class.java) {
            commit.replace(p.staged, overlappingTarget, p.backup) { error("不得发布") }
        }
        assertTrue(p.staged.exists())
    }

    @Test
    fun `拒绝指向工作目录内其他目录的 staged 符号链接`() {
        val work = File(temp.root, "install-work").apply { mkdirs() }
        val realDirectory = File(work, "real-staged").apply { tree(this, "new") }
        val stagedLink = File(work, "staged-link")
        try {
            Files.createSymbolicLink(stagedLink.toPath(), realDirectory.toPath())
        } catch (failure: Exception) {
            if (System.getProperty("os.name").orEmpty().contains("Linux", ignoreCase = true)) throw failure
            Assume.assumeNoException(failure)
        }
        val target = File(temp.root, "plugins/demo")
        val backup = File(work, "backup")

        assertThrows(IllegalArgumentException::class.java) {
            PluginDirectoryCommit().replace(stagedLink, target, backup) { error("不得发布") }
        }
        assertTrue(Files.isSymbolicLink(stagedLink.toPath()))
        assertEquals("new", value(realDirectory))
        assertFalse(target.exists())
    }

    @Test
    fun `注册表 UTF8 内容经同目录 rename 成功替换`() {
        val target = File(temp.root, "plugins.json").apply { writeText("旧注册表") }
        writeRegistryAtomically(target, "新注册表-中文")
        assertEquals("新注册表-中文", target.readText())
        assertEquals(1, temp.root.listFiles().orEmpty().size)
    }

    @Test
    fun `注册表 rename 返回失败时保留旧文件并清理本次临时文件`() {
        val target = File(temp.root, "plugins.json").apply { writeText("旧注册表") }
        var tempFile: File? = null
        val error = assertThrows(IOException::class.java) {
            writeRegistryAtomically(target, "新注册表") { from, to ->
                assertEquals(checkNotNull(target.parentFile).canonicalFile, checkNotNull(from.parentFile).canonicalFile)
                assertEquals(target.canonicalFile, to.canonicalFile)
                tempFile = from
                false
            }
        }
        assertTrue(error.message.orEmpty().contains(target.absolutePath))
        assertEquals("旧注册表", target.readText())
        assertFalse(tempFile!!.exists())
        assertEquals(listOf(target), temp.root.listFiles()!!.toList())
    }

    @Test
    fun `清理目录树删除链接但不跟随符号链接`() {
        val tree = File(temp.root, "to-delete").apply { mkdirs() }
        val outside = File(temp.root, "outside.txt").apply { writeText("keep") }
        val link = File(tree, "outside-link")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (failure: Exception) {
            if (System.getProperty("os.name").orEmpty().contains("Linux", ignoreCase = true)) throw failure
            Assume.assumeNoException(failure)
        }

        assertTrue(deleteInstallTree(tree))
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", outside.readText())
    }
}
