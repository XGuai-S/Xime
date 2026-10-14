package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存储授权弹窗 / 授权后释放 / 配置入口位置 契约测试。
 *
 * 用户可见缺陷与需求的回归锁定：
 *
 * 1. **授权入口不能藏在设置里**：改为应用启动后的 `AlertDialog`（`StoragePermissionDialog`），
 *    设置页不再出现「授予存储权限」项。
 * 2. **授权后必须立刻释放文件**：旧实现只在 `Application.onCreate` 里释放一次，而授权发生在
 *    系统设置页、进程并未重启，`onCreate` 不会重跑 → 用户授权回来看到的仍是空目录。
 *    现在 `MainActivity.onResume` 会补跑 [UserConfigDir.releaseUserConfig]。
 * 3. **「配置文件」入口归位**：「打开配置目录」从「主题与定制」页移到「输入方案」页（与
 *    「部署方案」相邻），「打开本地目录」按钮整体删除。
 */
class StoragePermissionFlowTest {

    private fun sourceOf(fileName: String): String =
        RepoFiles.mainKotlinSources.first { it.name == fileName }
            .readText().replace("\r\n", "\n")

    private val mainActivitySource = sourceOf("MainActivity.kt")
    private val themeSettingsSource = sourceOf("ThemeSettingsScreen.kt")
    private val dialogSource = sourceOf("StoragePermissionDialog.kt")
    private val applicationSource = sourceOf("XimeApplication.kt")

    // ── 1. 授权以弹窗方式运行 ──

    @Test
    fun `授权弹窗仅在待授权状态下出现`() {
        assertTrue(
            "弹窗必须以 PERMISSION_REQUIRED 为唯一显示条件",
            dialogSource.contains("state != UserConfigDir.ReleaseState.PERMISSION_REQUIRED || dismissed"),
        )
        assertTrue(dialogSource.contains("AlertDialog("))
    }

    @Test
    fun `授权弹窗提供立即授权与稍后两个动作`() {
        assertTrue(dialogSource.contains("\"立即授权\""))
        assertTrue(dialogSource.contains("\"稍后\""))
        // 「稍后」只关闭本次弹窗，不落盘标记：下次启动仍会提醒，用户不会彻底找不到入口
        assertTrue(dialogSource.contains("dismissed = true"))
        assertFalse(
            "不得为「稍后」写入任何持久化标记",
            dialogSource.contains("SettingsPreferences") || dialogSource.contains("getSharedPreferences"),
        )
    }

    @Test
    fun `API30 以上走所有文件访问设置页 以下走运行时权限`() {
        assertTrue(dialogSource.contains("UserConfigDir.allFilesAccessIntent(context)"))
        assertTrue(
            "allFilesAccessIntent 返回 null（API 28/29）时必须退回运行时权限请求",
            dialogSource.contains("legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)"),
        )
        assertTrue(dialogSource.contains("rememberLauncherForActivityResult"))
    }

    @Test
    fun `授权入口不再放在设置页里`() {
        assertFalse(
            "设置页不应再出现「授予存储权限」项",
            themeSettingsSource.contains("授予存储权限"),
        )
        assertFalse(
            "设置页不应再自行发起权限请求（已由启动弹窗负责）",
            themeSettingsSource.contains("rememberLauncherForActivityResult"),
        )
    }

    @Test
    fun `主界面挂载了授权弹窗`() {
        assertTrue(mainActivitySource.contains("StoragePermissionDialog()"))
    }

    // ── 2. 授权后立即释放 ──

    @Test
    fun `回到前台会重跑配置释放`() {
        val onResume = mainActivitySource.substringAfter("override fun onResume()")
            .substringBefore("override fun onDestroy()")
        assertTrue(
            "onResume 必须补跑释放，否则授权后不重启应用就看不到文件",
            onResume.contains("UserConfigDir.releaseUserConfig"),
        )
        assertTrue(
            "已就绪时应跳过，避免每次回前台都扫目录；判据是清单内文件齐全（而非过期的进程内状态快照）",
            onResume.contains("UserConfigDir.allFilesReleased"),
        )
    }

    @Test
    fun `释放判据基于文件清单而非状态快照`() {
        val source = sourceOf("UserConfigDir.kt")
        assertTrue(
            "应提供 releasedFiles 清单，列出授权后必须出现在配置目录的全部文件",
            source.contains("val releasedFiles: List<String>"),
        )
        assertTrue(
            "应提供 allFilesReleased 判据，逐个检查清单内文件是否齐全",
            source.contains("fun allFilesReleased(context: Context): Boolean"),
        )
        // 就绪判据必须是「清单齐全」而不是「上次 releaseState」：否则新版本新增配置文件、
        // 或用户删了说明文件后，老用户永远不会被自动补上
        val ensureReleased = source.substringAfter("fun ensureReleased(context: Context): Boolean {")
        assertTrue(
            "ensureReleased 的就绪判据应复用清单完整性检查",
            ensureReleased.contains("allFilesReleased(context)"),
        )
    }

    @Test
    fun `释放流程合并了迁移与生成两个前置步骤`() {
        val source = sourceOf("UserConfigDir.kt")
        val body = source.substringAfter("fun releaseUserConfig(context: Context): Boolean {")
            .substringBefore("\n    }")
        assertTrue(body.contains("migrateLegacyDir(context)"))
        assertTrue(body.contains("UnifiedConfigFile.ensureGenerated(context)"))
        assertTrue(body.contains("ensureReleased(context)"))
        // 顺序：迁移必须早于生成，否则已存在 xime.yaml 会让迁移误判为「无需迁移」
        assertTrue(
            body.indexOf("migrateLegacyDir") < body.indexOf("UnifiedConfigFile.ensureGenerated"),
        )
    }

    @Test
    fun `启动与回到前台都调用同一个释放入口`() {
        assertTrue(
            "Application 启动时用统一入口释放",
            applicationSource.contains("UserConfigDir.releaseUserConfig(this@XimeApplication)"),
        )
        assertTrue(mainActivitySource.contains("UserConfigDir.releaseUserConfig(this@MainActivity)"))
    }

    // ── 3. 「打开本地目录」已删除，「配置文件」入口移到输入方案页 ──

    @Test
    fun `打开本地目录按钮已删除`() {
        assertFalse(
            "「打开本地目录」入口应已移除",
            themeSettingsSource.contains("打开本地目录"),
        )
        assertFalse(
            "对应的跳转实现应一并删除，不留死代码",
            themeSettingsSource.contains("openLocalConfigDir"),
        )
        assertFalse(themeSettingsSource.contains("DIRECTORY_MIMES"))
        // openAppDetails 是授权弹窗的兜底，必须保留
        assertTrue(
            "openAppDetails 仍被 StoragePermissionDialog 使用，不得误删",
            themeSettingsSource.contains("internal fun openAppDetails(context: Context)"),
        )
    }

    @Test
    fun `配置文件入口位于输入方案页`() {
        val schemaSource = sourceOf("SchemaSettingsScreen.kt")
        assertTrue(
            "「打开配置目录」应出现在输入方案页",
            schemaSource.contains("打开配置目录"),
        )
        assertTrue(
            "入口应接到内置文件管理器",
            schemaSource.contains("onClick = onNavigateToRimeFileBrowser"),
        )
        assertTrue(
            "输入方案页应显示配置目录的真实状态",
            schemaSource.contains("UserConfigDir.releaseState"),
        )
        assertFalse(
            "「主题与定制」页不应再保留配置文件区块",
            themeSettingsSource.contains("SettingsSection(title = \"配置文件\")"),
        )
    }

    @Test
    fun `主题与定制页不再需要配置目录回调`() {
        // 入口移走后，ThemeSettingsContent 的 onNavigateToRimeFileBrowser 参数已无消费者，
        // 留着就是「传了没人用」的误导性 API
        assertFalse(
            "ThemeSettingsContent 不应再接收 onNavigateToRimeFileBrowser",
            themeSettingsSource.contains("onNavigateToRimeFileBrowser"),
        )
        assertFalse(
            "调用处也不应再传该参数",
            sourceOf("SettingsScreen.kt")
                .substringAfter("composable(SettingsRoutes.Theme) {")
                .substringBefore("composable(SettingsRoutes.Plugins)")
                .contains("onNavigateToRimeFileBrowser"),
        )
    }
}
