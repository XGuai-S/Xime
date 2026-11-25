package com.kingzcheung.xime.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * M04：本地 SAF 导入的大小上限 + 恢复 ZIP 目录条目的预算与正文校验。
 */
class BackupImportLimitsTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 生产上限（internal 常量，模块内测试可直接引用，避免测试与实现取值漂移）。 */
    private val limit = BackupManager.MAX_ARCHIVE_FILE_BYTES

    /** 与 BackupManager.MAX_ENTRY_COUNT（私有常量）同值。 */
    private val entryCountLimit = 20_000

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var context: Context
    private lateinit var uri: Uri

    private fun setup(stream: InputStream) {
        filesDir = temp.newFolder("files")
        cacheDir = temp.newFolder("cache")
        val resolver = mock<ContentResolver>()
        uri = mock<Uri>().also { whenever(it.scheme).thenReturn("content") }
        whenever(resolver.openInputStream(uri)).thenReturn(stream)
        context = mock<Context>().also {
            whenever(it.filesDir).thenReturn(filesDir)
            whenever(it.cacheDir).thenReturn(cacheDir)
            whenever(it.contentResolver).thenReturn(resolver)
        }
    }

    /** 超限的本地包必须被拒绝，且不留下临时文件、也不读完整个流。 */
    @Test
    fun `本地导入超过大小上限时拒绝且清理临时文件`() = runBlocking {
        // 只比上限多 8MB：旧实现会把整段拷完，新实现读到第 limit+1 字节即失败
        val offered = limit + 8L * 1024 * 1024
        var consumed = 0L
        val body = object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (consumed >= offered) return -1
                val n = minOf(length.toLong(), offered - consumed).toInt()
                java.util.Arrays.fill(buffer, offset, offset + n, 0.toByte())
                consumed += n
                return n
            }
        }
        setup(body)

        val result = BackupManager.importLocal(context, uri)

        assertTrue("超限导入必须失败: $result", result.isFailure)
        assertTrue(
            "失败原因应是大小上限: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("上限"),
        )
        assertTrue(
            "读满上限后必须立即停止读取，实际已读 $consumed 字节",
            consumed <= limit + 1024 * 1024,
        )
        assertEquals(
            "不得残留临时文件: ${cacheDir.list()?.toList()}",
            emptyList<String>(),
            cacheDir.listFiles().orEmpty().map { it.name },
        )
        assertTrue("不得把超限内容写成恢复暂存", File(filesDir, ".restore-staging").listFiles().orEmpty().isEmpty())
    }

    /** 正常大小的本地包仍然要能导入（上限不误伤正常包）。 */
    @Test
    fun `本地导入正常大小的包仍然成功`() = runBlocking {
        // 带 _xime_backup/ 元数据才被识别为完整方案包
        setup(zipOfText(mapOf(
            "default.custom.yaml" to "patch:",
            "${BackupManager.META_PREFIX}settings.json" to "{}",
        )).inputStream())

        val result = BackupManager.importLocal(context, uri)

        // 纯 JVM 里 Context 是 mock，收尾阶段（写 prefs / 镜像可见目录）必然失败并报
        // 「部分内容未恢复」；那不是本用例要守的语义。这里只断言：没被大小上限误伤、
        // 文件已按恢复通道落盘、临时文件已清理。
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertFalse("正常包不得因大小上限被拒: $message", message.contains("上限"))
        assertTrue("正常包应完成文件恢复: $message", File(filesDir, "rime/default.custom.yaml").isFile)
        assertEquals("临时文件应清理", emptyList<String>(), cacheDir.listFiles().orEmpty().map { it.name })
    }

    /** 带正文的目录条目必须拒绝（正文会绕过解压预算）。 */
    @Test
    fun `带正文的目录条目被拒绝`() {
        val filesDir = temp.newFolder("files-dir-body")
        val bytes = zipWithDirectoryBody("evil/", "PAYLOAD".toByteArray())

        val result = BackupManager.restoreArchiveOutcome(filesDir, { _, _ -> }, bytes)

        assertTrue("带正文的目录条目必须拒绝: $result", result.isFailure)
    }

    /** 目录条目也要计入条目预算，不能让海量目录条目绕过条目上限。 */
    @Test
    fun `目录条目计入条目数预算`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("default.custom.yaml"))
            zos.write("patch:".toByteArray())
            zos.closeEntry()
            repeat(entryCountLimit) { index ->
                zos.putNextEntry(ZipEntry("dir-$index/"))
                zos.closeEntry()
            }
        }
        val filesDir = temp.newFolder("files-entry-budget")

        val result = BackupManager.restoreArchiveOutcome(filesDir, { _, _ -> }, bos.toByteArray())

        assertTrue("海量目录条目必须触发条目上限: $result", result.isFailure)
        assertTrue(
            "失败原因应是条目过多: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("过多"),
        )
    }

    /** 正常的嵌套目录（空目录条目 + 文件）仍要能通过恢复。 */
    @Test
    fun `正常嵌套目录仍然可以通过`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for (name in listOf("opencc/", "opencc/dicts/", "opencc/dicts/ts.txt", "default.custom.yaml")) {
                zos.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zos.write("data-$name".toByteArray())
                zos.closeEntry()
            }
        }
        val filesDir = temp.newFolder("files-nested")

        val outcome = BackupManager.restoreArchiveOutcome(filesDir, { _, _ -> }, bos.toByteArray())

        assertTrue("正常嵌套目录不应被拒: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals("data-opencc/dicts/ts.txt", File(filesDir, "rime/opencc/dicts/ts.txt").readText())
        assertEquals("data-default.custom.yaml", File(filesDir, "rime/default.custom.yaml").readText())
    }

    private fun zipOfText(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun zipWithDirectoryBody(name: String, body: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("default.custom.yaml"))
            zos.write("patch:".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(name))
            zos.write(body)
            zos.closeEntry()
        }
        return bos.toByteArray()
    }
}
