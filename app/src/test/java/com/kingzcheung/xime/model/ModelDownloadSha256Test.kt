package com.kingzcheung.xime.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 模型归档下载 sha256 强校验。
 *
 * 缺陷：`ModelVersion.sha256` 被 ModelIndexLoader 解析，但 ModelDownloader 从未使用——
 * 损坏或被篡改的归档会被直接解压安装。方案/插件/布局下载早已有 sha256 强校验，
 * 唯独模型下载漏掉。
 *
 * 修法：下载归档后、解压前校验 sha256；不匹配触发重试，重试耗尽报错。
 * 索引未提供 sha256 时放行（渐进增强，不破坏旧索引）。
 */
class ModelDownloadSha256Test {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `sha256Hex 计算正确`() {
        val f = temp.newFile("data.bin")
        f.writeBytes("abc".toByteArray())
        // SHA-256("abc") 标准值
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelDownloader.sha256Hex(f),
        )
    }

    @Test
    fun `匹配的哈希通过校验忽略大小写`() {
        val f = temp.newFile("data.bin")
        f.writeBytes("abc".toByteArray())
        assertTrue(ModelDownloader.verifySha256(f, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
        assertTrue(ModelDownloader.verifySha256(f, "  ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  "))
    }

    @Test
    fun `不匹配的哈希被拒绝`() {
        val f = temp.newFile("data.bin")
        f.writeBytes("abc".toByteArray())
        assertFalse(ModelDownloader.verifySha256(f, "0000000000000000000000000000000000000000000000000000000000000000"))
    }

    @Test
    fun `内容改变后哈希校验失败`() {
        val f = temp.newFile("data.bin")
        f.writeBytes("abc".toByteArray())
        val expected = ModelDownloader.sha256Hex(f)
        f.writeBytes("abd".toByteArray())
        assertFalse("内容被篡改后校验必须失败", ModelDownloader.verifySha256(f, expected))
    }

    @Test
    fun `索引未提供 sha256 时放行`() {
        val f = temp.newFile("data.bin")
        f.writeBytes("abc".toByteArray())
        assertTrue("未提供哈希应放行（渐进增强）", ModelDownloader.verifySha256(f, null))
        assertTrue("空哈希应放行", ModelDownloader.verifySha256(f, ""))
        assertTrue("纯空白哈希应放行", ModelDownloader.verifySha256(f, "   "))
    }

    @Test
    fun `大文件流式计算不失真`() {
        val f = temp.newFile("big.bin")
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        f.outputStream().use { out -> repeat(20) { out.write(chunk) } }
        val h1 = ModelDownloader.sha256Hex(f)
        val h2 = ModelDownloader.sha256Hex(f)
        assertEquals("同文件多次计算结果一致", h1, h2)
        assertTrue("大文件哈希必须是有效 hex", h1.matches(Regex("[0-9a-f]{64}")))
    }
}
