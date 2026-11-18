package com.kingzcheung.xime.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 热安装来源路径白名单（[DevPluginInstaller.isAllowedSourcePath]）的边界测试。
 *
 * 背景：热安装入口组件 `exported=true`，安装路径来自外部 Intent。白名单一旦写松，
 * 任何应用都能让宿主把任意可读文件当插件包解析；写太紧则会挡掉 CLI 的正常通道
 * （debug: `files/xipm-dev/`，release: `/data/local/tmp/xipm-dev/`）。
 */
class DevPluginInstallerPathTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `私有暂存目录内的文件允许`() {
        val filesDir = temp.newFolder("files")
        val devDir = File(filesDir, "xipm-dev").apply { mkdirs() }
        val target = File(devDir, "demo.xipk").apply { writeText("x") }
        assertTrue(DevPluginInstaller.isAllowedSourcePath(filesDir, target.absolutePath))
    }

    @Test
    fun `公共暂存目录内的文件允许`() {
        assertTrue(
            DevPluginInstaller.isAllowedSourcePath(
                File(temp.newFolder("files2"), "unused"),
                "/data/local/tmp/xipm-dev/demo.xipk",
            )
        )
    }

    @Test
    fun `私有目录根下的文件不允许`() {
        val filesDir = temp.newFolder("files3")
        val target = File(filesDir, "demo.xipk").apply { writeText("x") }
        assertFalse(DevPluginInstaller.isAllowedSourcePath(filesDir, target.absolutePath))
    }

    @Test
    fun `任意可读路径不允许`() {
        assertFalse(
            DevPluginInstaller.isAllowedSourcePath(
                File(temp.newFolder("files4"), "unused"),
                "/sdcard/Download/demo.xipk",
            )
        )
    }

    @Test
    fun `前缀混淆的兄弟目录不允许`() {
        // /data/local/tmp/xipm-dev-evil 与 /data/local/tmp/xipm-dev 只差一个连字符，
        // 用字符串 startsWith 比较就会放行——必须是路径分隔符边界比较。
        assertFalse(
            DevPluginInstaller.isAllowedSourcePath(
                File(temp.newFolder("files5"), "unused"),
                "/data/local/tmp/xipm-dev-evil/demo.xipk",
            )
        )
    }

    @Test
    fun `私有目录里的前缀混淆兄弟目录不允许`() {
        val filesDir = temp.newFolder("files6")
        File(filesDir, "xipm-dev-evil").mkdirs()
        val target = File(File(filesDir, "xipm-dev-evil"), "demo.xipk").apply { writeText("x") }
        assertFalse(DevPluginInstaller.isAllowedSourcePath(filesDir, target.absolutePath))
    }

    @Test
    fun `穿越到上级目录不允许`() {
        val filesDir = temp.newFolder("files7")
        File(filesDir, "xipm-dev").mkdirs()
        val escape = File(filesDir, "xipm-dev/../../secret.xipk").path
        assertFalse(DevPluginInstaller.isAllowedSourcePath(filesDir, escape))
    }

    @Test
    fun `空路径与 null 不允许`() {
        val filesDir = temp.newFolder("files8")
        assertFalse(DevPluginInstaller.isAllowedSourcePath(filesDir, null))
        assertFalse(DevPluginInstaller.isAllowedSourcePath(filesDir, ""))
        assertFalse(DevPluginInstaller.isAllowedSourcePath(filesDir, "   "))
    }

    @Test
    fun `相对路径不允许`() {
        assertFalse(
            DevPluginInstaller.isAllowedSourcePath(
                File(temp.newFolder("files9"), "unused"),
                "xipm-dev/demo.xipk",
            )
        )
    }
}
