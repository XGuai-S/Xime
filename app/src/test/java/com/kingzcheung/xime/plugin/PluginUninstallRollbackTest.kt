package com.kingzcheung.xime.plugin

import android.app.Application
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import com.kingzcheung.xime.plugin.core.runtime.installer.PluginRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/**
 * 卸载的「先删目录、后落盘」回归：注册表落盘失败时不得吞掉错误。
 *
 * 回归背景：旧实现先 deleteInstallTree 删掉插件目录，再 removePlugin + flushToDisk，
 * 且 flushToDisk 的异常在内部被 Log.e 吞掉、返回值也没检查 —— 于是磁盘满 / rename 失败时
 * 卸载仍返回 true：脚本已删、内存注册项已被抹除，重启后从 plugins.json 读回旧注册项，
 * 用户看到「插件在列表里但打不开」（入口脚本已丢失）。
 *
 * 这里用「plugins.json 的位置被目录占住」模拟落盘必然失败（rename 无法覆盖目录），
 * 该手法不依赖文件权限，root 下同样有效。
 */
class PluginUninstallRollbackTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var files: File
    private lateinit var application: Application
    private lateinit var registry: PluginRegistry
    private lateinit var installer: InstallerManager

    private val id = "uninstall.test"
    private val target get() = File(files, "plugins/$id")
    private val registryFile get() = File(files, "plugins.json")
    private val stagingRoot get() = File(files, "plugin-uninstall-staging")

    @Before
    fun setUp() {
        files = temporary.newFolder("files")
        application = mock(Application::class.java)
        `when`(application.filesDir).thenReturn(files)
        `when`(application.cacheDir).thenReturn(temporary.newFolder("cache"))
        registry = PluginRegistry(application)
        installer = InstallerManager(application, registry)
        target.mkdirs()
        File(target, "main.js").writeText("old script")
        File(target, "resources").mkdirs()
        File(target, "resources/data.txt").writeText("old resource")
        registry.addPlugin(
            PluginInfo(
                id = id, name = "Old", iconResId = 0, versionCode = 0,
                versionName = "1.0.0", path = File(target, "main.js").absolutePath,
                description = "Old description", type = "tool", enabled = true,
                installTime = 123456789L, source = PluginSource.FILE, entryScript = "main.js"
            )
        )
        registry.flushToDisk()
        assertTrue(registryFile.isFile)
    }

    /** 让注册表落盘必然失败：把 plugins.json 换成目录，rename 无法覆盖目录。 */
    private fun breakRegistryWrites() {
        assertTrue(registryFile.delete())
        assertTrue("占位目录创建失败", registryFile.mkdirs())
        File(registryFile, "occupied.txt").writeText("not a registry file")
    }

    @Test
    fun `注册表落盘失败时保留插件目录与注册项并返回 false`() = runBlocking {
        breakRegistryWrites()
        val before = registry.getPluginById(id)

        val result = installer.uninstallPlugin(id)

        assertFalse(
            "落盘失败必须返回 false（旧实现先删目录再吞掉落盘异常，返回 true）",
            result
        )
        assertEquals(
            "插件脚本必须还原（旧实现已删掉，重启后注册项指向不存在的入口）",
            "old script", File(target, "main.js").readText()
        )
        assertEquals(
            "资源目录也必须还原",
            "old resource", File(target, "resources/data.txt").readText()
        )
        assertEquals("内存注册项必须恢复", before, registry.getPluginById(id))

        // 恢复可写后落盘：磁盘注册表仍含该插件，证明目录 / 内存 / 磁盘三者一致
        assertTrue("占位目录清理失败", registryFile.deleteRecursively())
        registry.flushToDisk()
        assertEquals(
            "磁盘注册表仍含该插件",
            before?.versionName, PluginRegistry(application).getPluginById(id)?.versionName
        )
        assertStagingEmpty()
    }

    @Test
    fun `卸载成功时目录与注册项都消失`() = runBlocking {
        assertTrue(installer.uninstallPlugin(id))

        assertFalse("插件目录应被真正删除", target.exists())
        assertNull("内存注册项应移除", registry.getPluginById(id))
        assertNull("磁盘注册项应移除", PluginRegistry(application).getPluginById(id))
        assertStagingEmpty()
    }

    @Test
    fun `目录已不存在时仍能注销注册项`() = runBlocking {
        assertTrue(target.deleteRecursively())

        assertTrue("目录缺失不应阻断注销", installer.uninstallPlugin(id))
        assertNull(registry.getPluginById(id))
        assertNull(PluginRegistry(application).getPluginById(id))
        assertStagingEmpty()
    }

    private fun assertStagingEmpty() {
        assertTrue(
            "暂存目录必须清理干净（残留 = 卸载后磁盘被白占）",
            !stagingRoot.exists() || stagingRoot.listFiles().orEmpty().isEmpty()
        )
    }

    @Test
    fun `遗留的卸载暂存目录会在下次操作前被清理`() = runBlocking {
        // 进程在「改名暂存」之后、「真删暂存」之前被杀：暂存目录会留下且没人清理，
        // 插件文件被改名搬走既不算已安装也不会被删，磁盘永久占用。
        val orphan = File(stagingRoot, "$id-123456789").apply { mkdirs() }
        File(orphan, "main.js").writeText("orphaned script")
        File(orphan, "resources").mkdirs()
        File(orphan, "resources/data.txt").writeText("orphaned resource")
        assertTrue("前置：孤儿暂存目录应存在", orphan.isDirectory)

        // 任意一次安装/卸载操作都应顺手清掉它
        installer.uninstallPlugin(id)

        assertFalse("孤儿暂存目录必须被清理", orphan.exists())
    }

    @Test
    fun `正常卸载不会留下暂存目录`() = runBlocking {
        val result = installer.uninstallPlugin(id)
        assertTrue("正常卸载应成功", result)
        assertFalse("插件目录应已移除", target.exists())
        val leftovers = stagingRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        assertTrue("卸载成功后不得遗留暂存目录：${leftovers.map { it.name }}", leftovers.isEmpty())
    }
}
