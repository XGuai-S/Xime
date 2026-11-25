package com.kingzcheung.xime.settings

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 解压暂存区：成功才替换，失败不动目标目录，替换中途失败可回滚。 */
class ArchiveStagingTest {

    private lateinit var root: File
    private lateinit var target: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("archive-staging").toFile()
        target = File(root, "rime").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun stage(s: ArchiveStaging, rel: String, text: String) {
        File(s.stagingDir, rel).apply {
            parentFile?.mkdirs()
            writeText(text)
        }
    }

    @Test
    fun `staged files are invisible in target until commit`() {
        val s = ArchiveStaging.create(target)
        stage(s, "a.schema.yaml", "new")
        assertFalse(File(target, "a.schema.yaml").exists())
        s.cleanup()
        assertTrue(target.listFiles().isNullOrEmpty())
    }

    @Test
    fun `commit adds new files and replaces existing ones`() {
        File(target, "old.yaml").writeText("old")
        val s = ArchiveStaging.create(target)
        stage(s, "old.yaml", "new")
        stage(s, "sub/dir/x.txt", "x")
        assertEquals(2, s.commit())
        s.cleanup()
        assertEquals("new", File(target, "old.yaml").readText())
        assertEquals("x", File(target, "sub/dir/x.txt").readText())
    }

    @Test
    fun `commit failure rolls back every applied file`() {
        File(target, "b.txt").writeText("old-b")
        // 目标里 c 是普通文件，暂存的 c/x 无法落地，迫使 commit 在中途失败
        File(target, "c").writeText("plain")
        val s = ArchiveStaging.create(target)
        stage(s, "a.txt", "new-a")
        stage(s, "b.txt", "new-b")
        stage(s, "c/x", "new-x")
        assertThrows(IOException::class.java) { s.commit() }
        s.cleanup()
        assertFalse(File(target, "a.txt").exists())
        assertEquals("old-b", File(target, "b.txt").readText())
        assertEquals("plain", File(target, "c").readText())
    }

    @Test
    fun `cleanup removes staging and backup siblings`() {
        File(target, "b.txt").writeText("old")
        val s = ArchiveStaging.create(target)
        stage(s, "b.txt", "new")
        s.commit()
        s.cleanup()
        val leftovers = root.listFiles()!!.filter { it.name.startsWith(".xime_import_") }
        assertTrue("残留：$leftovers", leftovers.isEmpty())
    }
}
