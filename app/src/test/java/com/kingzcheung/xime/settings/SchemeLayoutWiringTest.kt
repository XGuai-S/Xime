package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约：**所有会新增方案文件的入口**都必须整理布局。
 *
 * 用户问「以后新装都能这样吗，导入方案也这样逻辑吗」。
 * 三个入口必须一致，漏一个就会出现「这个方案没文件夹」的不一致：
 *
 * 1. 新装/升级启动 → RimeConfigHelper 初始化链路
 * 2. 单文件/压缩包导入 → SchemaManager.importIntoRimeDir
 * 3. 市场安装 → SchemaManager.installFromMarketToRime
 */
class SchemeLayoutWiringTest {

    private fun source(name: String) =
        RepoFiles.mainKotlinSources.single { it.name == name }.readText()

    @Test
    fun `startup path ensures scheme layout after asset copy`() {
        val text = source("RimeConfigHelper.kt")
        // 同步与异步两条初始化链路都要有
        val count = Regex("ensureSchemeLayout\\(context, rimeDir\\)").findAll(text).count()
        assertTrue(
            "启动链路必须调用 ensureSchemeLayout（同步/异步各一次），实际 $count 处",
            count >= 2,
        )
        // 必须排在 copyAssetsToRimeDir 之后：首次安装 rime/ 为空时无文件可归类
        val async = text.substringAfter("suspend fun initializeRimeDataAsync(")
            .substringBefore("fun initializeRimeData(")
        assertTrue(
            "ensureSchemeLayout 必须晚于 copyAssetsToRimeDir（否则首次安装建不出 schemes/）",
            async.indexOf("copyAssetsToRimeDir") < async.indexOf("ensureSchemeLayout"),
        )
    }

    @Test
    fun `import path ensures scheme layout`() {
        val text = source("SchemaManager.kt")
        val importBlock = text.substringAfter("private suspend fun importIntoRimeDir(")
            .substringBefore("private fun getCustomYamlFile(")
        assertTrue(
            "导入方案后必须整理布局，否则新方案没有自己的文件夹",
            importBlock.contains("ensureSchemeLayout("),
        )
    }

    @Test
    fun `restore path ensures scheme layout`() {
        val text = source("BackupManager.kt")
        val restoreBlock = text.substringAfter("private fun restoreWithMirror(")
            .substringBefore("private fun ")
        assertTrue(
            "备份恢复后必须整理布局，确保顶层文件按启用状态归档并与可见目录一致",
            restoreBlock.contains("ensureSchemeLayout("),
        )
    }

    @Test
    fun `restore mirrors flat files before archiving disabled schemes`() {
        val text = source("BackupManager.kt")
        val restoreBlock = text.substringAfter("private fun restoreWithMirror(")
            .substringBefore("private fun ")
        val mirror = restoreBlock.indexOf("RimeUserFileSync.forceSyncPrivateToVisible")
        val layout = restoreBlock.indexOf("SchemaManager.ensureSchemeLayout")

        assertTrue("恢复流程必须镜像恢复清单", mirror >= 0)
        assertTrue("布局整理会移动禁用方案文件，必须在镜像之后", layout > mirror)
    }

    @Test
    fun `ghost folder removal is disabled`() {
        val text = source("SchemaLayout.kt")
        val fn = text.substringAfter("fun removeGhostSchemeFolders(")
            .substringBefore("fun ")
        assertTrue(
            "幽灵清理必须恒返回 0——原判据会把未启用方案误判成幽灵删掉，" +
                "3.4.30 就是这样丢了 6 个内置方案",
            fn.contains("= 0"),
        )
    }

    @Test
    fun `market install path ensures scheme layout`() {
        val text = source("SchemaManager.kt")
        val marketBlock = text.substringAfter("fun installFromMarketToRime(")
            .substringBefore("internal fun getBuildDir(")
        assertTrue(
            "市场安装方案后必须整理布局，否则与「导入方案」的目录结构不一致",
            marketBlock.contains("ensureSchemeLayout("),
        )
    }
}
