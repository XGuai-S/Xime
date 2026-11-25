package com.kingzcheung.xime.rime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 旧目录迁移：任一子项没搬走时必须报告失败，调用方据此保留旧目录（里面有 userdb）。
 */
class RimeDirMigrationTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("rime-migration").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `moves all children and reports success`() {
        val from = File(root, "old").apply { mkdirs() }
        val to = File(root, "new").apply { mkdirs() }
        File(from, "user.yaml").writeText("a")
        File(from, "pinyin.userdb").apply { mkdirs() }.resolve("data").writeText("db")

        assertTrue(RimeConfigHelper.moveChildrenInto(from, to))
        assertEquals("a", File(to, "user.yaml").readText())
        assertEquals("db", File(to, "pinyin.userdb/data").readText())
    }

    @Test
    fun `existing target counts as migrated and is not overwritten`() {
        val from = File(root, "old").apply { mkdirs() }
        val to = File(root, "new").apply { mkdirs() }
        File(from, "user.yaml").writeText("old")
        File(to, "user.yaml").writeText("new")

        assertTrue(RimeConfigHelper.moveChildrenInto(from, to))
        assertEquals("new", File(to, "user.yaml").readText())
    }

    @Test
    fun `unmovable child reports failure and source is kept`() {
        val from = File(root, "old").apply { mkdirs() }
        File(from, "pinyin.userdb").writeText("precious")
        // 目标「目录」实际是普通文件：rename 与复制兜底都必然失败
        val to = File(root, "not-a-dir").apply { writeText("x") }

        assertFalse(RimeConfigHelper.moveChildrenInto(from, to))
        assertEquals("precious", File(from, "pinyin.userdb").readText())
    }
}
