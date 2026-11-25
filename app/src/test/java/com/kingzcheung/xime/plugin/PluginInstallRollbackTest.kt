package com.kingzcheung.xime.plugin

import com.kingzcheung.xime.SymlinkSupport
import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.TrustLevel
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import com.kingzcheung.xime.plugin.core.runtime.installer.PluginRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginInstallRollbackTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var files: File
    private lateinit var registry: PluginRegistry
    private lateinit var installer: InstallerManager
    private lateinit var application: Application
    private val id = "rollback.test"
    private val target get() = File(files, "plugins/$id")
    private val registryFile get() = File(files, "plugins.json")

    @Before fun setUp() {
        files = temporary.newFolder("files")
        application = mock(Application::class.java)
        `when`(application.filesDir).thenReturn(files)
        `when`(application.cacheDir).thenReturn(temporary.newFolder("cache"))
        registry = PluginRegistry(application)
        installer = InstallerManager(application, registry)
        target.mkdirs()
        File(target, "main.js").writeText("old script")
        File(target, "resources").mkdir()
        File(target, "resources/data.txt").writeText("old resource")
        registry.addPlugin(PluginInfo(
            id = id, name = "Old", iconResId = 0, versionCode = 0,
            versionName = "1.0.0", path = File(target, "main.js").absolutePath,
            description = "Old description", type = "tool", enabled = false,
            installTime = 123456789L, source = PluginSource.FILE, entryScript = "main.js"
        ))
        registry.flushToDisk()
        assertTrue(registryFile.isFile)
    }

    @Test fun missingEntryPreservesOldFilesAndRegistryBytes() = runBlocking {
        assertRejectedWithoutChanges(archive("missing", includeEntry = false))
    }

    @Test fun illegalZipPathPreservesOldFilesAndRegistryBytes() = runBlocking {
        assertRejectedWithoutChanges(archive("invalid", extraPath = "../escape.txt"))
        assertFalse(File(files, "escape.txt").exists())
    }

    @Test fun entryDirectoryPreservesOldFilesAndRegistryBytes() = runBlocking {
        assertRejectedWithoutChanges(archive("directory", includeEntry = false, extraPath = "main.js/child"))
    }

    @Test fun symbolicLinkTargetCannotRedirectAnUpgrade() = runBlocking {
        val before = registryFile.readBytes()
        val outside = temporary.newFolder("outside-plugin-root")
        val marker = File(outside, "keep.txt").apply { writeText("unrelated data") }
        assertTrue(target.canonicalPath.startsWith(files.canonicalPath + File.separator))
        assertTrue(target.deleteRecursively())
        SymlinkSupport.create(target, outside)
        val result = installer.installPlugin(archive("symlink"), forceOverwrite = true)
        assertTrue(result is InstallerManager.InstallResult.Failure)
        assertEquals("unrelated data", marker.readText())
        assertArrayEquals(before, registryFile.readBytes())
        assertStagingEmpty()
    }

    @Test fun symbolicLinkStagingRootCannotRedirectAnUpgrade() = runBlocking {
        val outside = temporary.newFolder("outside-staging-root")
        SymlinkSupport.create(File(files, "plugin-install-staging"), outside)
        assertRejectedWithoutChanges(archive("redirected-staging"))
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun contentImportStopsAtArchiveLimitAndRemovesItsTemporaryFile() = runBlocking {
        val limit = 10 * 1024 * 1024
        val bytes = registryFile.readBytes()
        val stream = object : InputStream() {
            var consumed = 0
            override fun read(): Int {
                if (consumed > limit) error("Provider read beyond size probe")
                consumed++
                return 0
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (consumed > limit) error("Provider read beyond size probe")
                java.util.Arrays.fill(buffer, offset, offset + length, 0.toByte())
                consumed += length
                return length
            }
        }
        val uri = mock(Uri::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(uri.scheme).thenReturn("content")
        `when`(application.contentResolver).thenReturn(resolver)
        `when`(resolver.openInputStream(uri)).thenReturn(stream)
        val result = installer.installPluginFromUri(uri)
        assertTrue(result is InstallerManager.InstallResult.Failure)
        assertEquals(limit + 1, stream.consumed)
        assertEquals("old script", File(target, "main.js").readText())
        assertEquals("old resource", File(target, "resources/data.txt").readText())
        assertArrayEquals(bytes, registryFile.readBytes())
        assertTrue(application.cacheDir.listFiles()!!.isEmpty())
    }

    @Test fun upgradePublishesNewMetadataAndPreservesUserState() = runBlocking {
        val result = installer.installPlugin(archive("upgrade"), forceOverwrite = true)
        assertTrue(result is InstallerManager.InstallResult.Success)
        val info = (result as InstallerManager.InstallResult.Success).pluginInfo
        assertEquals("2.0.0", info.versionName)
        assertEquals("New upgrade", info.name)
        assertEquals("new description", info.description)
        assertEquals("tool", info.type)
        assertEquals(listOf("example.com"), info.declaredHosts)
        assertTrue(info.allowCustomHosts)
        assertEquals("N", info.manifestIcon)
        assertEquals(listOf("android"), info.platforms)
        assertEquals(PluginSource.FILE, info.source)
        assertEquals(TrustLevel.THIRD_PARTY, info.trustLevel)
        assertFalse(info.enabled)
        assertEquals(123456789L, info.installTime)
        assertEquals(File(target, "main.js").absolutePath, info.path)
        assertEquals("script upgrade", File(info.path).readText())
        assertEquals("resource upgrade", File(target, "resources/data.txt").readText())
        assertEquals(info, registry.getPluginById(id))
        val diskInfo = PluginRegistry(application).getPluginById(id)!!
        assertEquals(info.versionName, diskInfo.versionName)
        assertEquals(info.path, diskInfo.path)
        assertFalse(diskInfo.enabled)
        assertEquals(info.installTime, diskInfo.installTime)
        assertStagingEmpty()
    }

    @Test fun concurrentOverwritesAcrossInstallersDoNotMixFiles() = runBlocking {
        val a = archive("a")
        val b = archive("b")
        val second = InstallerManager(application, registry)
        coroutineScope {
            val firstResult = async { installer.installPlugin(a, forceOverwrite = true) }
            val secondResult = async { second.installPlugin(b, forceOverwrite = true) }
            assertTrue(firstResult.await() is InstallerManager.InstallResult.Success)
            assertTrue(secondResult.await() is InstallerManager.InstallResult.Success)
        }
        val info = registry.getPluginById(id)!!
        val winner = info.name.removePrefix("New ")
        assertTrue(winner == "a" || winner == "b")
        assertEquals("script $winner", File(target, "main.js").readText())
        assertEquals("resource $winner", File(target, "resources/data.txt").readText())
        assertEquals(info.name, PluginRegistry(application).getPluginById(id)!!.name)
        assertFalse(info.enabled)
        assertEquals(123456789L, info.installTime)
        assertStagingEmpty()
    }

    private suspend fun assertRejectedWithoutChanges(archive: File) {
        val bytes = registryFile.readBytes()
        val old = registry.getPluginById(id)
        val script = File(target, "main.js").readBytes()
        val resource = File(target, "resources/data.txt").readBytes()
        val result = installer.installPlugin(archive, forceOverwrite = true)
        assertTrue(result is InstallerManager.InstallResult.Failure)
        assertArrayEquals(script, File(target, "main.js").readBytes())
        assertArrayEquals(resource, File(target, "resources/data.txt").readBytes())
        assertArrayEquals(bytes, registryFile.readBytes())
        assertEquals(old, registry.getPluginById(id))
        assertStagingEmpty()
    }

    private fun assertStagingEmpty() {
        val staging = File(files, "plugin-install-staging")
        assertTrue(!staging.exists() || staging.listFiles()!!.isEmpty())
    }

    private fun archive(label: String, includeEntry: Boolean = true, extraPath: String? = null): File {
        val zip = temporary.newFile("$label.xipk")
        ZipOutputStream(zip.outputStream()).use { output ->
            fun entry(name: String, content: String) {
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
            entry("manifest.json", """{"id":"$id","name":"New $label","version":"2.0.0","description":"new description","type":"tool","entry":"main.js","network":{"hosts":["example.com"],"allowCustomHosts":true},"icon":"N","platforms":["android"]}""")
            if (includeEntry) entry("main.js", "script $label")
            entry("resources/data.txt", "resource $label")
            extraPath?.let { entry(it, "bad") }
        }
        return zip
    }
}
