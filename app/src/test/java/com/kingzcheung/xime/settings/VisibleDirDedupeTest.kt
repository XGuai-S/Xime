package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 可见目录 `/sdcard/rime/` 顶层的去重。
 *
 * 用户反馈：「/storage/emulated/0/rime/ 下和方案文件夹下都有一样的文件」。
 * 可见目录是给用户看的镜像，引擎读的是私有目录，所以**独占**的方案文件只需要
 * 在 `schemes/<方案>/` 里出现一次；但**共享资源**（symbols.yaml 等）和全局配置必须留在顶层。
 *
 * 真实场景：共享资源也会被 resolveSchemeDependencies 硬链接进方案文件夹，
 * 所以"名字出现在方案文件夹里"不能作为删除依据——这正是本测试守护的回归。
 */
class VisibleDirDedupeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun setup(): Pair<File, File> {
        val priv = tmp.newFolder("private")
        val vis = tmp.newFolder("visible")

        // 私有目录：一个五笔86方案（schema + 独占词典） + 共享 symbols。
        // symbols 被两个方案引用 → resolveOwnership 判为共享（留根，不在 ownership 里）。
        File(priv, "wubi86.schema.yaml").writeText(
            "schema:\n  name: wubi86\n  dictionary: wubi86\n  __include: symbols:/default\n"
        )
        File(priv, "wubi86.dict.yaml").writeText("dict")
        File(priv, "symbols.yaml").writeText("shared")
        File(priv, "pinyin_simp.schema.yaml").writeText(
            "schema:\n  name: pinyin_simp\n  dictionary: pinyin_simp\n  __include: symbols:/default\n"
        )
        File(priv, "pinyin_simp.dict.yaml").writeText("dict")

        // 可见目录的方案文件夹：真实场景下共享 symbols 也被链接进来
        val scheme = File(File(vis, "schemes"), "五笔86")
        scheme.mkdirs()
        File(scheme, "wubi86.dict.yaml").writeText("dict")
        File(scheme, "wubi86.schema.yaml").writeText("schema")
        File(scheme, "symbols.yaml").writeText("shared") // 共享资源也进文件夹

        // 可见目录顶层：独占文件重复（应删） + 共享/全局（应留）
        File(vis, "wubi86.dict.yaml").writeText("dict")        // 重复 → 删
        File(vis, "wubi86.schema.yaml").writeText("schema")    // 重复 → 删
        File(vis, "symbols.yaml").writeText("shared")          // 共享 → 留
        File(vis, "default.yaml").writeText("global")          // 全局 → 留
        File(vis, "build").mkdirs()                            // 引擎产物目录 → 留
        return priv to vis
    }

    @Test
    fun `top level duplicates are removed`() {
        val (priv, vis) = setup()

        val removed = RimeUserFileSync.dedupeVisibleTopLevel(priv, vis)

        assertEquals("应删除 2 个独占重复文件", 2, removed)
        assertTrue("重复的 dict 应被删", !File(vis, "wubi86.dict.yaml").exists())
        assertTrue("重复的 schema 应被删", !File(vis, "wubi86.schema.yaml").exists())
    }

    @Test
    fun `shared symbols resource in scheme folder is still kept at top level`() {
        val (priv, vis) = setup()

        RimeUserFileSync.dedupeVisibleTopLevel(priv, vis)

        assertTrue(
            "symbols.yaml 虽被硬链接进方案文件夹，但属共享资源，可见顶层必须保留——删了所有方案都用不了符号",
            File(vis, "symbols.yaml").isFile,
        )
        assertTrue("全局配置 default.yaml 必须保留", File(vis, "default.yaml").isFile)
        assertTrue("引擎产物目录 build/ 必须保留", File(vis, "build").isDirectory)
    }

    @Test
    fun `scheme folder content is untouched`() {
        val (priv, vis) = setup()

        RimeUserFileSync.dedupeVisibleTopLevel(priv, vis)

        val scheme = File(File(vis, "schemes"), "五笔86")
        assertTrue("方案文件夹里的文件不能被删", File(scheme, "wubi86.dict.yaml").isFile)
        assertTrue("方案文件夹里的文件不能被删", File(scheme, "wubi86.schema.yaml").isFile)
        assertTrue("方案文件夹里的共享链接不能被删", File(scheme, "symbols.yaml").isFile)
    }

    @Test
    fun `userdb dirs are removed from visible dir`() {
        val priv = tmp.newFolder("p3")
        val vis = tmp.newFolder("v3")
        File(priv, "wubi86.schema.yaml").writeText("schema:\n  name: wubi86\n  dictionary: wubi86\n")
        File(priv, "wubi86.dict.yaml").writeText("d")
        File(File(File(vis, "schemes"), "五笔86"), "wubi86.dict.yaml").apply {
            parentFile.mkdirs(); writeText("d")
        }
        File(vis, "wubi86.userdb").mkdirs()
        File(File(vis, "wubi86.userdb"), "LOG").writeText("")
        File(vis, "pinyin_simp.userdb").mkdirs()
        File(vis, "symbols.yaml").writeText("s")

        RimeUserFileSync.dedupeVisibleTopLevel(priv, vis)

        assertTrue("*.userdb 应被清掉", !File(vis, "wubi86.userdb").exists())
        assertTrue("另一个 userdb 也应清掉", !File(vis, "pinyin_simp.userdb").exists())
        assertTrue("共享文件必须保留", File(vis, "symbols.yaml").isFile)
    }

    @Test
    fun `userdb is not mirrored to visible dir`() {
        assertTrue(
            "*.userdb 下的文件不该被镜像到可见目录",
            !RimeUserFileSync.shouldMirrorRuntime("wubi86.userdb/LOG"),
        )
        assertTrue(
            "*.userdb 下的数据文件同样不镜像",
            !RimeUserFileSync.shouldMirrorRuntime("pinyin_simp.userdb/000005.ldb"),
        )
        assertTrue(
            "build/ 下的编译产物仍应镜像",
            RimeUserFileSync.shouldMirrorRuntime("build/wubi86.table.bin"),
        )
    }

    @Test
    fun `no schemes dir means nothing removed`() {
        val priv = tmp.newFolder("p2")
        val vis = tmp.newFolder("v2")
        File(vis, "a.dict.yaml").writeText("x")

        assertEquals(
            "没有 schemes 目录时不该删任何东西",
            0,
            RimeUserFileSync.dedupeVisibleTopLevel(priv, vis),
        )
        assertTrue(File(vis, "a.dict.yaml").isFile)
    }

    @Test
    fun `symbols shared by only one enabled scheme is still preserved`() {
        // 边界：即使只启用一个方案，symbols 仍是共享语义（被多个方案定义引用），
        // 不能因为"只在一个方案文件夹出现"就当独占删除。
        val priv = tmp.newFolder("p4")
        val vis = tmp.newFolder("v4")
        File(priv, "wubi86.schema.yaml").writeText(
            "schema:\n  name: wubi86\n  dictionary: wubi86\n  __include: symbols:/default\n"
        )
        File(priv, "wubi86.dict.yaml").writeText("d")
        // 另一个方案也引用 symbols（即使未启用）
        File(priv, "pinyin_simp.schema.yaml").writeText(
            "schema:\n  name: pinyin_simp\n  __include: symbols:/default\n"
        )
        File(priv, "symbols.yaml").writeText("s")
        val scheme = File(File(vis, "schemes"), "五笔86")
        scheme.mkdirs()
        File(scheme, "symbols.yaml").writeText("s")
        File(scheme, "wubi86.schema.yaml").writeText("schema")
        File(vis, "symbols.yaml").writeText("s")

        RimeUserFileSync.dedupeVisibleTopLevel(priv, vis)

        assertTrue("symbols 被 2 个方案引用，属共享，顶层必须保留", File(vis, "symbols.yaml").isFile)
    }
}
