package com.kingzcheung.xime.model

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 模型文件迁移：失败时不得留下截断的目标文件，且必须保留源文件以便重试。
 */
class ModelMoveFileTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("model-move").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `moves file with identical content`() {
        val bytes = ByteArray(4096) { it.toByte() }
        val source = File(root, "src/model.onnx").apply { parentFile.mkdirs(); writeBytes(bytes) }
        val target = File(root, "dst/model.onnx").apply { parentFile.mkdirs() }

        assertTrue(ModelStorage.moveModelFile(source, target))
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(target.parentFile, "model.onnx.partial").exists())
    }

    @Test
    fun `failure keeps source and leaves no target`() {
        val source = File(root, "src/model.onnx").apply { parentFile.mkdirs(); writeBytes(ByteArray(16)) }
        // 目标父目录是普通文件：rename 与复制都必然失败
        val blocker = File(root, "dst").apply { writeText("x") }
        val target = File(blocker, "model.onnx")

        assertFalse(ModelStorage.moveModelFile(source, target))
        assertTrue("失败时必须保留源文件", source.isFile)
        assertFalse(target.exists())
    }
}
