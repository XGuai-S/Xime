package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileProvider 路径契约测试。
 *
 * `FileProvider.getUriForFile` 对**未在 `res/xml/filepaths.xml` 中声明的根路径**会抛
 * `IllegalArgumentException: Failed to find configured root`。这是个纯配置层面的约束：
 * 编译器不管、单元测试看不见、符号检查也发现不了，只有真机点到那个按钮才会炸。
 *
 * 本项目已经因此踩过一次坑：「导出全部日志」把汇总文件写到 `cacheDir/log_export/`，
 * 而 filepaths.xml 当时只声明了 `emoji_cache/` 与 `files/`，导致分享面板永远不弹，
 * 只会静默退化去写下载目录——功能看似可用、实则半残。
 *
 * 因此这里对**所有** FileProvider 分享点做统一核对，避免同类问题在别处复发。
 */
class FileProviderPathsContractTest {

    private val filepaths by lazy {
        RepoFiles.file("app/src/main/res/xml/filepaths.xml").readText()
    }

    /** 断言某个 cacheDir 子目录已被 filepaths.xml 声明。 */
    private fun assertCachePathDeclared(dirName: String, why: String) {
        assertTrue(
            "filepaths.xml 必须声明 <cache-path path=\"$dirName/\"/>，否则 $why 时 " +
                "FileProvider.getUriForFile 会抛 IllegalArgumentException",
            filepaths.contains("path=\"$dirName/\""),
        )
    }

    @Test
    fun `导出日志目录已声明`() {
        assertCachePathDeclared("log_export", "分享汇总日志")
    }

    @Test
    fun `表情缓存目录已声明`() {
        // 表情/剪贴板图片分享先复制到 cacheDir/emoji_cache 再交给 FileProvider
        assertCachePathDeclared("emoji_cache", "分享表情或剪贴板图片")
    }

    @Test
    fun `filesDir 全路径已声明`() {
        // files-path path="." 覆盖 filesDir 下全部内容：
        // 日志目录 files/logs/、rime 目录 files/rime/ 等都依赖这一条
        assertTrue(
            "必须以 files-path path=\".\" 覆盖整个 filesDir，否则 files/ 下的分享会失败",
            filepaths.contains("<files-path") && filepaths.contains("path=\".\""),
        )
    }

    @Test
    fun `分享日志的实际写入目录与声明一致`() {
        // 写入方（FileLogger.exportAllLogs）与声明方（filepaths.xml）必须对齐：
        // 任何一方改名而另一方没跟上，导出功能就会静默失效
        val fileLogger = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/util/FileLogger.kt",
        ).readText()
        val writeDir = Regex("""File\(context\.cacheDir,\s*"([^"]+)"""")
            .find(fileLogger)?.groupValues?.get(1)
        assertTrue("应能从 FileLogger 解析出导出目录名", writeDir != null)
        assertTrue(
            "FileLogger 写入的 cacheDir/$writeDir/ 必须在 filepaths.xml 中声明",
            filepaths.contains("path=\"$writeDir/\""),
        )
    }

    @Test
    fun `每个 getUriForFile 调用点都在已声明目录内`() {
        // 扫描全部 FileProvider 调用点，确认其文件来源落在已声明根内。
        // 新增分享功能时若忘了声明目录，这里会失败。
        //
        // 判据是「整个文件」而非调用点附近的片段：文件来源常定义在几百行之外
        // （如 ImeTextCommit 的 cacheDir/emoji_cache 与 getContentUriForImage 相距 100+ 行），
        // 只扫邻近窗口会误报。
        val callSites = RepoFiles.mainKotlinSources.filter {
            it.readText().contains("FileProvider.getUriForFile")
        }
        assertTrue("应能找到 FileProvider 调用点", callSites.isNotEmpty())

        // 已知被声明覆盖的文件来源。新增分享目录时需同步更新此处与 filepaths.xml。
        // 允许「文件由调用方传入」的情形：来源在生产者文件里，由
        // `分享日志的实际写入目录与声明一致` 等测试单独核对。
        // UserConfigDir 指向外部存储的 Xime 目录，由 filepaths.xml 的 <external-path> 覆盖。
        val coveredSources = listOf("cacheDir", "filesDir", "ClipboardImageStore", "UserConfigDir")
        val fileParamIndirection = "fun shareExportedLog(context: Context, file: File)"
        callSites.forEach { f ->
            val text = f.readText()
            val ok = coveredSources.any { text.contains(it) } || text.contains(fileParamIndirection)
            assertTrue(
                "${f.name} 的 FileProvider 调用点未能确认文件位于已声明目录内" +
                    "（未出现 cacheDir / filesDir / ClipboardImageStore，也不属于" +
                    "「文件由调用方传入」的已知签名）；" +
                    "新增分享目录时请同时更新 filepaths.xml 与本测试",
                ok,
            )
        }
    }

    @Test
    fun `clipboard 与 emoji 的 cacheDir 子树都在声明范围内`() {
        // ImeTextCommit 把图片复制到 cacheDir/emoji_cache 再交给 FileProvider；
        // 该目录必须被 cache-path 覆盖
        val imeTextCommit = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/service/ImeTextCommit.kt",
        ).readText()
        assertTrue(
            "ImeTextCommit 应把图片放入 cacheDir/emoji_cache",
            imeTextCommit.contains("cacheDir, \"emoji_cache\""),
        )
        assertCachePathDeclared("emoji_cache", "图片上屏/表情发送")
    }
}
