package com.kingzcheung.xime.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 方案布局里的「同尺寸即相同」缺陷。
 *
 * 曾经多处用 `target.length() == src.length()` 判断两份文件是否一致：
 * 真机上出现过两份 1.3MB 词典大小完全相同但内容不同，被误判成"同一份"——
 * 该建的硬链接没建（顶层和 schemes/ 各留独立副本），
 * 反镜像时又把内容已被用户改过的顶层文件当成镜像删掉。
 *
 * 已统一改为 `sameContent`（流式逐字节比较）；这里钉住它不受同尺寸干扰。
 */
class SchemaLayoutSameContentTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `内容相同返回 true`() {
        val a = temp.newFile("a.yaml").apply { writeText("schema:\n  name: wubi86\n") }
        val b = temp.newFile("b.yaml").apply { writeText("schema:\n  name: wubi86\n") }
        assertTrue(SchemaLayout.sameContent(a, b))
    }

    @Test
    fun `同尺寸不同内容返回 false`() {
        // 关键回归：长度一致但内容不同，绝不能被判为相同
        val a = temp.newFile("a.yaml").apply { writeText("AAAA-BBBB") }
        val b = temp.newFile("b.yaml").apply { writeText("AAAA-CCCC") }
        assertFalse("同尺寸不同内容必须判 false", SchemaLayout.sameContent(a, b))
    }

    @Test
    fun `不同尺寸返回 false`() {
        val a = temp.newFile("a").apply { writeText("short") }
        val b = temp.newFile("b").apply { writeText("much longer content") }
        assertFalse(SchemaLayout.sameContent(a, b))
    }

    @Test
    fun `大文件流式比较正确且跨 buffer 边界`() {
        val a = temp.newFile("a.bin")
        val b = temp.newFile("b.bin")
        val c = temp.newFile("c.bin")
        val data = ByteArray(64 * 1024 + 100) { (it % 257).toByte() }
        a.writeBytes(data)
        b.writeBytes(data)
        val tampered = data.copyOf()
        // 只改最后一个字节；取一个与原值确定不同的值（原值是 it % 257）
        val last = tampered.lastIndex
        tampered[last] = if (data[last] == 0x7f.toByte()) 0x11.toByte() else 0x7f.toByte()
        c.writeBytes(tampered)
        assertTrue(SchemaLayout.sameContent(a, b))
        assertFalse("跨 buffer 边界的尾部差异必须被发现", SchemaLayout.sameContent(a, c))
    }

    @Test
    fun `非文件或缺失返回 false`() {
        val a = temp.newFile("a").apply { writeText("x") }
        val dir = temp.newFolder("dir")
        assertFalse(SchemaLayout.sameContent(a, dir))
        assertFalse(SchemaLayout.sameContent(a, File(temp.root, "missing")))
    }
}
