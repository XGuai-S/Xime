package com.kingzcheung.xime.util

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 原子写盘回归（P1-1）。
 *
 * 缺陷背景：用户词典（词频/自动造词/候选排序/个人短语）长期用 `File.writeText` 直写——
 * 先 truncate 再写。输入法是常驻进程，随时可能被 LMK 杀掉；死在 truncate 之后、写完之前
 * 就留下半截或空文件，下次加载解析失败 → 用户全部词库数据清零。
 *
 * 这里钉住的核心不变量：
 * 1. 写入成功时内容完整可读
 * 2. 覆盖已有文件时不残留旧内容（长度变化场景）
 * 3. 同目录临时文件不残留（避免被后续扫描当成正式文件）
 * 4. 目标文件不可写时返回 false 而非抛异常
 * 5. UTF-8 多字节内容（中文/emoji）不被截断
 */
class AtomicFileWriterTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("atomic-writer-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `writes full content and leaves no temp file`() {
        val target = File(dir, "word_freq.dict.yaml")
        val text = "ni\t你\t10\nhao\t好\t5\n"

        assertTrue(AtomicFileWriter.write(target, text))

        assertEquals("内容必须完整可读", text, target.readText())
        assertFalse(
            "同目录不得残留临时文件（会被当成正式文件扫描）",
            File(dir, "${target.name}.tmp").exists(),
        )
    }

    @Test
    fun `overwrite replaces old content completely without trailing residue`() {
        val target = File(dir, "auto_word.dict.yaml")
        val longText = "a".repeat(4096) + "\n"
        val shortText = "b\n"

        assertTrue(AtomicFileWriter.write(target, longText))
        assertTrue(AtomicFileWriter.write(target, shortText))

        assertEquals(
            "覆盖后不得残留旧内容尾巴（truncate 语义必须保留）",
            shortText,
            target.readText(),
        )
    }

    @Test
    fun `multibyte content is not truncated`() {
        val target = File(dir, "custom_phrase.txt")
        val text = "你好\tni hao\n世界\tshi jie\n😀\temoji\n"

        assertTrue(AtomicFileWriter.write(target, text))
        assertEquals("UTF-8 多字节（中文/emoji）必须完整", text, target.readText())
    }

    @Test
    fun `creates missing parent directories`() {
        val target = File(dir, "nested/deep/word_freq.dict.yaml")

        assertTrue(AtomicFileWriter.write(target, "ni\t你\t1\n"))
        assertEquals("ni\t你\t1\n", target.readText())
    }

    @Test
    fun `returns false when target path is a directory`() {
        // 用「目标是个目录」稳定模拟写入失败：root/FUSE 下 setWritable(false) 不生效，
        // 但 FileOutputStream 打开目录必失败，跨环境一致。
        val target = File(dir, "not-a-file").apply { mkdirs() }

        assertFalse(
            "写入失败必须返回 false，不得抛异常打断调用方",
            AtomicFileWriter.write(target, "x"),
        )
    }

    @Test
    fun `writeOrThrow throws on failure`() {
        val target = File(dir, "also-not-a-file").apply { mkdirs() }

        var threw = false
        try {
            AtomicFileWriter.writeOrThrow(target, "x")
        } catch (_: java.io.IOException) {
            threw = true
        }
        assertTrue("writeOrThrow 在失败时必须抛 IOException", threw)
    }

    @Test
    fun `existing temp file from a crashed run is overwritten`() {
        val target = File(dir, "candidate_order.dict.yaml")
        // 模拟上次进程被杀留下的半截临时文件
        File(dir, "${target.name}.tmp").writeText("half-written-garbage")

        assertTrue(AtomicFileWriter.write(target, "good\tcontent\n"))
        assertEquals("good\tcontent\n", target.readText())
        assertFalse(File(dir, "${target.name}.tmp").exists())
    }
}
