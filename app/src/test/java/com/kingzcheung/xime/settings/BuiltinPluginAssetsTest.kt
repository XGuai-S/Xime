package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

/**
 * 内置插件（随 APK 打包、安装后释放到 plugins 目录）的契约测试。
 *
 * 两处曾经真实出问题、但编译器与运行时都不报错的地方：
 *
 * 1. 安装入口名带 `ForDebug`，只有 debug 构建会执行 —— release 装出来插件列表是空的，
 *    「内置」在发布版里并不存在。
 * 2. 打包脚本把产物多套了一层目录（`<插件 id>/manifest.json`）。宿主按顶层 `manifest.json`
 *    解析、按顶层入口脚本解压，结果是「安装失败：缺少 manifest.json」。
 */
class BuiltinPluginAssetsTest {

    private val assetsDir by lazy { RepoFiles.file("app/src/main/assets/plugins") }

    private val bundledXipks by lazy {
        assetsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("webdav-backup-") && it.name.endsWith(".xipk") }
            .orEmpty()
    }

    @Test
    fun `WebDAV 云备份随包内置`() {
        assertTrue(
            "assets/plugins 下应存在 webdav-backup 的 xipk，否则 release 装出来没有这个插件",
            bundledXipks.isNotEmpty(),
        )
    }

    @Test
    fun `内置包顶层必须是清单与入口脚本`() {
        assertTrue("缺少内置包，先看上一个用例", bundledXipks.isNotEmpty())
        ZipFile(bundledXipks.first()).use { zip ->
            assertNotNull("顶层必须有 manifest.json", zip.getEntry("manifest.json"))
            assertNotNull("顶层必须有入口脚本 main.js", zip.getEntry("main.js"))
        }
    }

    @Test
    fun `内置插件在 debug 与 release 都会安装`() {
        val application = RepoFiles.file("app/src/main/java/com/kingzcheung/xime/XimeApplication.kt").readText()
        assertTrue(
            "启动时应安装内置插件",
            application.contains("PluginManager.installBundledPluginsFromAssets("),
        )
        assertFalse(
            "不得再出现只在 debug 生效的安装入口",
            application.contains("installPluginsFromAssetsForDebug"),
        )
    }

    @Test
    fun `安装把内置包释放到应用私有 plugins 目录`() {
        val installer = RepoFiles.file(
            "plugin-core/src/main/java/com/kingzcheung/xime/plugin/core/runtime/installer/InstallerManager.kt",
        ).readText()
        assertTrue(
            "插件目录应为 filesDir 下的 plugins，与「插件管理」占用统计一致",
            installer.contains("File(context.filesDir, PLUGINS_DIR)"),
        )
    }
}
