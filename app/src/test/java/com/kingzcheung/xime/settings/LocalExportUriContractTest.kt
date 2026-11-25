package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地导出必须返回「本次实际发布的那条记录」。
 *
 * 缺陷：`saveToDownloads` 返回 Boolean 丢掉 MediaStore 插入得到的 uri，
 * 导出完成后又按**文件名**回查。同一天多次备份文件名相同（含日期），
 * 回查拿到的是先插入的旧包——用户点"打开"看到的是上一次的备份；
 * 发布成功但查询失败的场景还会被误报为保存失败。
 *
 * 涉及 MediaStore 与真实 Downloads 目录，JVM 单测无法执行，
 * 因此用生产源码契约钉住不变量。
 */
class LocalExportUriContractTest {
    private val source: String = RepoFiles.mainKotlinSources
        .single { it.name == "RimeExportManager.kt" }
        .readText()
        .replace("\r\n", "\n")

    @Test
    fun `saveToDownloads returns the published uri instead of a boolean`() {
        val signature = source.substringAfter("private fun saveToDownloads(")
            .substringBefore("): ", missingDelimiterValue = "")
        assertTrue("saveToDownloads 必须存在", signature.isNotEmpty())
        val returnType = source.substringAfter("private fun saveToDownloads(")
            .substringAfter("): ")
            .substringBefore(" {")
            .trim()
        assertTrue("saveToDownloads 必须返回实际发布的 Uri，实际为 $returnType", returnType.startsWith("Uri?"))
        assertFalse("不得退回 Boolean 返回值", returnType == "Boolean")
    }

    @Test
    fun `pending insert uri is the one returned after publish`() {
        val body = source.substringAfter("private fun saveToDownloads(").substringBefore("private fun copyArchive(")
        // 插入得到的 uri 必须在发布成功后被返回，而不是丢弃
        assertTrue("插入结果必须先判空", body.contains("if (uri == null) return null"))
        assertTrue("发布成功后必须返回同一条 uri", body.contains("published = true\n                    uri"))
        assertFalse("不得在发布后丢弃 uri 后返回 true", body.contains("published = true\n                    true"))
    }

    @Test
    fun `legacy pre-Q path also returns a uri`() {
        val body = source.substringAfter("private fun saveToDownloads(").substringBefore("private fun copyArchive(")
        assertTrue("旧版路径必须返回目标文件 uri", body.contains("Uri.fromFile(dest)"))
    }

    @Test
    fun `export does not re-query by file name after a successful save`() {
        assertFalse(
            "不得再按文件名回查（同日重名会拿到旧包）",
            source.contains("resolveDownloadsUri"),
        )
        val export = source.substringAfter("suspend fun exportArchive(").substringBefore("/**")
        assertTrue("导出必须直接使用保存返回的 uri", export.contains("val savedUri = try"))
        assertTrue("失败判定改为 null 检查", export.contains("if (savedUri == null)"))
    }
}
