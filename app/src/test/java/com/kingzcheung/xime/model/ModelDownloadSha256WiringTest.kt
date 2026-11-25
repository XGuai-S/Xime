package com.kingzcheung.xime.model

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型下载 sha256 校验的接线契约。
 *
 * 行为层由 ModelDownloadSha256Test 覆盖；这里钉住下载流程确实做了三件事：
 * 1. downloadModel 把 target.sha256 传进归档下载；
 * 2. 下载后、解压前调用 verifySha256；
 * 3. 校验失败走重试而非静默放行。
 */
class ModelDownloadSha256WiringTest {

    private val source: String by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/model/ModelDownloader.kt").readText()
    }

    @Test
    fun `downloadModel 传入索引中的 sha256`() {
        assertTrue(
            "downloadModel 必须把 target.sha256 传入归档下载链路",
            "downloadAndExtractArchive(context, target.archiveUrl, target.sha256, staged, onProgress)" in source,
        )
    }

    @Test
    fun `下载后解压前做 sha256 校验`() {
        val dl = source.indexOf("downloadArchive(archiveUrl, tmpFile, onProgress)")
        val extract = source.indexOf("extractTarBz2(tmpFile, attemptDir)")
        assertTrue("找不到下载或解压调用", dl >= 0 && extract > dl)
        val between = source.substring(dl, extract)
        assertTrue("下载后、解压前必须调用 verifySha256", "verifySha256(tmpFile" in between)
    }

    @Test
    fun `校验失败触发重试而非放行`() {
        assertTrue("必须有 sha256 不匹配异常", "Sha256MismatchException" in source)
        // 该异常不能被列入「立即抛出、不重试」的白名单（UnsafeModelArchiveException 才是）
        val catchFast = Regex("catch \\(e: Sha256MismatchException\\)").find(source)
        assertTrue("sha256 失败必须走重试，不得直接抛出跳过重试", catchFast == null)
    }

    @Test
    fun `ModelVersion 保留 sha256 字段供下载使用`() {
        val info = RepoFiles.file("src/main/java/com/kingzcheung/xime/model/ModelInfo.kt").readText()
        assertTrue("ModelVersion 必须有 sha256 字段", "val sha256: String" in info)
    }
}
