package com.kingzcheung.xime.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 旧根目录迁移（`/storage/emulated/0/Xime/` → `rime/xime/`）核心逻辑测试。
 *
 * 迁移 = 递归复制整棵树（含 `rime/` 镜像子目录）+ 复制成功后删除旧目录。
 * [UserConfigDir.copyTree] 是纯文件操作，可在 JVM 单测里直接验证：
 * 1. 嵌套目录与文件都被完整复制；
 * 2. 已有同名文件被覆盖（旧目录里的新版本优先）；
 * 3. 复制失败时返回 false（调用方据此保留旧目录，不丢数据）。
 */
class UserConfigDirMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `递归复制保留目录结构与文件内容`() {
        val from = tempFolder.newFolder("from")
        write(File(from, "xime.yaml"), "k: v\n")
        write(File(from, "word_freq.txt"), "词\t码\t1\n")
        val nested = File(from, "rime")
        write(File(nested, "XGuai_flypy.schema.yaml"), "schema:\n")
        write(File(nested, "custom_phrase.txt"), "天气\ttq\t20\n")

        val to = tempFolder.newFolder("to")
        assertTrue(UserConfigDir.copyTree(from, to))

        assertTrue(File(to, "xime.yaml").isFile)
        assertTrue(File(to, "word_freq.txt").isFile)
        assertTrue(File(to, "rime/XGuai_flypy.schema.yaml").isFile)
        assertTrue(File(to, "rime/custom_phrase.txt").isFile)
        // 目标目录里的文件内容应与源一致
        assertTrue(File(to, "rime/custom_phrase.txt").readText().contains("天气"))
    }

    @Test
    fun `复制到已有内容的目录时覆盖同名文件`() {
        val from = tempFolder.newFolder("from")
        write(File(from, "xime.yaml"), "new\n")
        val to = tempFolder.newFolder("to")
        write(File(to, "xime.yaml"), "old\n")

        assertTrue(UserConfigDir.copyTree(from, to))
        // 旧目录里的版本优先（覆盖目标已有文件）
        assertTrue(File(to, "xime.yaml").readText().contains("new"))
    }

    @Test
    fun `空目录复制返回成功且不产生目标文件`() {
        val from = tempFolder.newFolder("from")
        val to = tempFolder.newFolder("to")
        assertTrue(UserConfigDir.copyTree(from, to))
        assertTrue(to.listFiles().isNullOrEmpty())
    }

    @Test
    fun `源目录不存在时返回 false`() {
        val missing = File(tempFolder.root, "no-such-dir")
        val to = tempFolder.newFolder("to")
        assertFalse(UserConfigDir.copyTree(missing, to))
    }

    @Test
    fun `迁移方向：新目录使用 rime_xime 子路径`() {
        // 与 UserConfigDirContractTest 互补：这里锁迁移路径的常量关系
        assertTrue(UserConfigDir.DIR_SUB_PATH.endsWith("xime"))
        assertTrue(UserConfigDir.DIR_SUB_PATH.startsWith("rime"))
        // 旧根目录名保留给迁移源
        assertTrue(UserConfigDir.oldRootDir.name == UserConfigDir.DIR_NAME)
        // 新目录不是旧根目录（同根不同子路径）
        assertTrue(!UserConfigDir.absoluteDir.absolutePath.contains("/Xime/"))
    }
}