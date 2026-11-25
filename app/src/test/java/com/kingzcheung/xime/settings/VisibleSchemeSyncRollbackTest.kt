package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 可见目录 schemes 同步的回滚契约。
 *
 * 历史事故：3.4.42 用「先删旧 → 搬 staging」的写法，在 `/sdcard`（FUSE）上
 * renameTo 失败后留下 29MB 的 schemes.tmp；更早的「先删后建」在复制失败时
 * 会让用户看到 schemes/ 直接变空、方案全没了。
 *
 * 现在的写法：旧目录改名成 .old → 建新目录 → 搬运 → 成功才删 .old。
 * 任何阶段失败都必须回滚到旧内容。
 */
class VisibleSchemeSyncRollbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 造一个「私有目录有 2 个方案、可见目录有 1 个旧方案」的场景。 */
    private fun setup(): Triple<File, File, File> {
        val priv = tmp.newFolder("private")
        val vis = tmp.newFolder("visible")
        val privSchemes = File(priv, "schemes")
        File(File(privSchemes, "五笔86"), "wubi86.schema.yaml").apply {
            parentFile.mkdirs(); writeText("schema-a")
        }
        File(File(privSchemes, "简体拼音"), "pinyin.schema.yaml").apply {
            parentFile.mkdirs(); writeText("schema-b")
        }
        // 可见目录里先放一份旧的
        File(File(File(vis, "schemes"), "旧方案"), "old.schema.yaml").apply {
            parentFile.mkdirs(); writeText("old")
        }
        return Triple(priv, vis, privSchemes)
    }

    @Test
    fun `staging dir is cleaned up after success`() {
        val (priv, vis, _) = setup()
        SchemaManager.syncVisibleSchemesForTest(priv, vis)

        assertTrue(
            "成功后不能留下 schemes.tmp——真机上它堆了 29MB",
            !File(vis, "schemes.tmp").exists(),
        )
        assertTrue(
            "成功后不能留下 schemes.old 备份",
            !File(vis, "schemes.old").exists(),
        )
    }

    @Test
    fun `leftover tmp from old version is cleaned`() {
        val (priv, vis, _) = setup()
        // 模拟旧版本留下的残留
        File(File(vis, "schemes.tmp"), "junk.yaml").apply {
            parentFile.mkdirs(); writeText("junk")
        }

        SchemaManager.syncVisibleSchemesForTest(priv, vis)

        assertTrue(
            "历史残留的 schemes.tmp 必须被清掉（29MB 垃圾不该一直占着用户空间）",
            !File(vis, "schemes.tmp").exists(),
        )
    }

    @Test
    fun `visible schemes content matches private after sync`() {
        val (priv, vis, _) = setup()
        SchemaManager.syncVisibleSchemesForTest(priv, vis)

        val visSchemes = File(vis, "schemes")
        assertTrue("应有五笔86 文件夹", File(visSchemes, "五笔86/wubi86.schema.yaml").isFile)
        assertTrue("应有简体拼音文件夹", File(visSchemes, "简体拼音/pinyin.schema.yaml").isFile)
        assertTrue(
            "旧的「旧方案」应被替换掉（可见目录是私有目录的镜像）",
            !File(visSchemes, "旧方案").exists(),
        )
    }
}
