package com.kingzcheung.xime.rime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** 内置资源整体替换：中途失败时旧文件保持完整，且不残留临时文件。 */
class RimeAssetReplaceTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rime-asset-replace").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `replaces existing file`() {
        val target = File(dir, "default.yaml").apply { writeText("old-content-longer") }
        RimeConfigHelper.replaceFromStream(ByteArrayInputStream("new".toByteArray()), target)
        assertEquals("new", target.readText())
        assertFalse(File(dir, "default.yaml.tmp").exists())
    }

    @Test
    fun `failure mid-stream keeps old file intact`() {
        val target = File(dir, "default.yaml").apply { writeText("old") }
        val broken = object : InputStream() {
            private var n = 0
            override fun read(): Int = if (n++ < 10) 'x'.code else throw IOException("boom")
        }
        assertThrows(IOException::class.java) { RimeConfigHelper.replaceFromStream(broken, target) }
        assertEquals("old", target.readText())
        assertFalse(File(dir, "default.yaml.tmp").exists())
    }
}
