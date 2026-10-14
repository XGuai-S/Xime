package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用其他应用打开配置目录」与「新安装/升级是否覆盖配置」契约测试。
 *
 * 用户需求回归锁定：
 * 1. **枚举所有打开方式**：Android 没有「打开文件夹」标准协议，第三方管理器
 *    （MT管理器、MiXplorer 等）**不在 intent-filter 里声明目录 MIME**，拿目录 URI 反查
 *    永远查不到。必须「探测」已知文件管理器包名，再叠加按能力查询与系统选择器兜底。
 * 2. **包可见性**：Android 11 起 `getLaunchIntentForPackage`/`queryIntentActivities`/
 *    `resolveActivity` 默认看不到第三方应用，AndroidManifest 必须声明
 *    `<queries><package>`（探测已知管理器）与 `<queries><intent>`（按能力发现）。
 * 3. **目录 URI 必须是 SAF 文档 URI**：FileProvider 只服务单文件、拿到也无法列目录，
 *    传目录必须用 `com.android.externalstorage.documents` 的 `document/primary%3AXime`。
 *    （补充 2026-10-14 真机实测：MT 管理器根本不认目录型 URI——它只注册文件入口，
    且 `mt://` scheme 未注册。可行做法是用 FileProvider 传**目录内的锚点文件**，
    MT 打开该文件时会定位到它所在目录。FileProvider 在此仍只服务单文件，
    与上面「不能拿它传目录」并不矛盾。）
 * 4. **覆盖安装**：检测到已有 `xime.yaml` 且当前版本还没问过时才弹窗；两个分支都要记录
 *    versionCode，覆盖分支走 [UnifiedConfigFile.generate] 重新生成快照并立即重载。
 */
class ConfigDirReleaseContractTest {

    private fun sourceOf(fileName: String): String =
        RepoFiles.mainKotlinSources.first { it.name == fileName }
            .readText().replace("\r\n", "\n")

    private fun repoText(rel: String): String =
        RepoFiles.file(rel).readText().replace("\r\n", "\n")

    // ── 打开方式枚举 ──

    @Test
    fun `打开目录用探测法枚举已知文件管理器包名`() {
        val source = sourceOf("ConfigDirOpener.kt")
        // 反查目录 URI 查不到第三方管理器，必须维护已知包名清单
        assertTrue(source.contains("KNOWN_FILE_MANAGERS"))
        // MT管理器是用户点名的目标，必须在清单里
        assertTrue(source.contains("bin.mt.plus"))
        // 探测方式：按包名看是否已安装
        assertTrue(source.contains("getLaunchIntentForPackage(pkg)"))
        // 清单里的包逐个探测，装了就列出
        assertTrue(source.contains("for (pkg in KNOWN_FILE_MANAGERS)"))
    }

    @Test
    fun `目录定位：SAF 目录 URI 与锚点文件双路径`() {
        val source = sourceOf("ConfigDirOpener.kt")
        // 两条路径并存，各自负责不同场景：
        // 1. SAF 文档 URI：系统选择器与多数管理器（能直接列目录）走这条；
        // 2. 锚点文件 + FileProvider：MT 管理器这类**只注册文件入口**的走这条
        //    （真机实测：它拒收目录 URI，但打开目录内文件时会定位到该目录）。
        // 注意 FileProvider 在此仍然只服务单文件，没有拿它传目录。
        assertTrue(
            "应保留 SAF 文档 URI 路径",
            source.contains("com.android.externalstorage.documents"),
        )
        assertTrue(
            "应有锚点文件路径（FileProvider 单文件，供只认文件入口的管理器定位）",
            source.contains("FileProvider.getUriForFile") &&
                source.contains("UserConfigDir.README_FILE"),
        )
        assertTrue(
            "目录 URI 必须用系统外部存储 DocumentsProvider",
            source.contains("com.android.externalstorage.documents"),
        )
        assertTrue(source.contains("document/primary%3A"))
    }

    @Test
    fun `候选启动 Intent 以实际能否启动为准`() {
        val source = sourceOf("ConfigDirOpener.kt")
        // 旧设计只靠 resolveActivity 预校验，实测不够：第三方管理器声明了通配 MIME
        // 时预校验必然通过，却不会跳转目录；反之也可能因需要额外 extra 而被误判为不可用。
        // 现在以「实际 startActivity 成功与否」为准，预校验只用于排序。
        assertTrue(
            "构造候选时应仍做可解析性排序",
            source.contains("resolveActivity(pm) != null"),
        )
        assertTrue(
            "启动时应遍历候选逐个尝试",
            source.contains("for (intent in candidates)"),
        )
        // 全部不通时退回「只打开管理器」，保证点了一定有反应
        assertTrue("应保留退回「只打开管理器」的兜底", source.contains("?: launch"))
    }

    @Test
    fun `目录跳转使用 SAF 标准形态而非 file URI`() {
        val source = sourceOf("ConfigDirOpener.kt")
        // 这两条是实测踩坑的结论，详见 ConfigDirOpenerIntentTest
        assertTrue(
            "应提供标准 SAF 文档树入口",
            source.contains("Intent.ACTION_OPEN_DOCUMENT_TREE"),
        )
        assertTrue(
            "应带 EXTRA_INITIAL_URI 指定初始目录",
            source.contains("DocumentsContract.EXTRA_INITIAL_URI"),
        )
    }

    @Test
    fun `打开目录同时叠加按能力查询并去重`() {
        val source = sourceOf("ConfigDirOpener.kt")
        // 目录型 MIME 仍要查，兜住系统「文件」等按能力声明的应用
        assertTrue(source.contains("\"resource/folder\""))
        assertTrue(source.contains("\"vnd.android.document/directory\""))
        // 去重 key 是包名
        assertTrue(source.contains("LinkedHashMap<String, ConfigDirTarget>"))
        assertTrue(source.contains("byPackage.containsKey(pkg)"))
    }

    @Test
    fun `打开目录列表必须兜底系统文档树选择器`() {
        val source = sourceOf("ConfigDirOpener.kt")
        assertTrue(
            "必须提供 ACTION_OPEN_DOCUMENT_TREE 兜底，保证列表不为空",
            source.contains("Intent.ACTION_OPEN_DOCUMENT_TREE"),
        )
        assertTrue(
            "应带 EXTRA_INITIAL_URI 让系统选择器直接定位到配置目录",
            source.contains("DocumentsContract.EXTRA_INITIAL_URI"),
        )
    }

    @Test
    fun `打开目录必须声明包可见性`() {
        val manifest = repoText("src/main/AndroidManifest.xml")
        assertTrue(
            "AndroidManifest 必须用 <queries> 声明可见性，否则 Android 11+ 探测不到第三方管理器",
            manifest.contains("<queries>"),
        )
        assertTrue(
            "MT管理器包名必须在 <queries> 里声明",
            manifest.contains("android:name=\"bin.mt.plus\""),
        )
        // 按能力发现清单外的管理器
        assertTrue(manifest.contains("android.intent.action.VIEW"))
        assertTrue(manifest.contains("android.intent.action.OPEN_DOCUMENT_TREE"))
    }

    @Test
    fun `设置页挂载了用其他应用打开入口`() {
        val schemaSource = sourceOf("SchemaSettingsScreen.kt")
        assertTrue(schemaSource.contains("用其他应用打开目录"))
        assertTrue(schemaSource.contains("ConfigDirOpener.queryTargets(context)"))
        assertTrue(schemaSource.contains("ConfigDirOpenDialog("))
    }

    // ── 覆盖安装弹窗 ──

    @Test
    fun `主界面挂载了覆盖安装弹窗`() {
        assertTrue(sourceOf("MainActivity.kt").contains("ConfigOverwriteDialog()"))
    }

    @Test
    fun `覆盖弹窗用基线指纹而非 versionCode 控制询问`() {
        val source = sourceOf("ConfigOverwriteDialog.kt")
        // versionCode 是硬编码常量，每次构建都一样：用它当判据会导致覆盖安装后永不弹窗
        assertFalse(
            "不得用 versionCode 作新鲜度判据（它是硬编码常量，永远不变）",
            source.contains("BuildConfig.VERSION_CODE"),
        )
        assertTrue(source.contains("UnifiedConfigFile.baselineHash(context)"))
        assertTrue(source.contains("UnifiedConfigFile.isSnapshotStale(context)"))
        assertTrue(source.contains("SettingsPreferences.getConfigBaseline(context)"))
        // 两个分支都记录指纹，否则同一份内置基线会反复打扰
        assertTrue(source.contains("SettingsPreferences.setConfigBaseline(context, it)"))
    }

    @Test
    fun `快照记录基线指纹并可判定过期`() {
        val source = sourceOf("UnifiedConfigFile.kt")
        assertTrue("生成快照时必须写入基线指纹标记", source.contains("BASELINE_MARKER_PREFIX"))
        assertTrue(source.contains("fun baselineHash(context: Context): String?"))
        assertTrue(source.contains("fun isSnapshotStale(context: Context): Boolean"))
        // 没有指纹标记的旧快照必须视为落后，否则老用户永远收不到新默认值。
        // 该行为由 ConfigBaselineStalenessTest 直接验证真实判定结果，这里只锁结构。
        assertTrue(
            "落后判定应抽成可测的纯函数",
            source.contains("internal fun isSnapshotStale(snapshotText: String, currentHash: String)"),
        )
        // 未授权 / 文件不存在时不该打扰用户
        assertTrue(source.contains("if (!UserConfigDir.hasWritePermission(context)) return false"))
    }

    @Test
    fun `基线指纹持久化到偏好设置`() {
        val source = sourceOf("SettingsPreferences.kt")
        assertTrue(source.contains("KEY_CONFIG_BASELINE"))
        assertTrue(source.contains("fun getConfigBaseline(context: Context): String?"))
        assertTrue(source.contains("fun setConfigBaseline(context: Context, hash: String)"))
    }

    @Test
    fun `覆盖分支重新生成快照并立即重载配置`() {
        val source = sourceOf("ConfigOverwriteDialog.kt")
        // 覆盖必须走 UnifiedConfigFile.generate 的无条件重生成入口（ensureGenerated 永不覆盖）
        assertTrue(source.contains("UnifiedConfigFile.generate(context)"))
        // 覆盖后要立刻重载，让用户不用手动再点一次部署
        assertTrue(source.contains("KeysConfigHelper.loadConfig(context)"))
    }

    @Test
    fun `覆盖弹窗仅在快照确实落后且未就本基线做过决定时出现`() {
        val source = sourceOf("ConfigOverwriteDialog.kt")
        // 判据核心：指纹不同 + 快照落后
        assertTrue(source.contains("UnifiedConfigFile.isSnapshotStale(context)"))
        assertTrue(source.contains("SettingsPreferences.getConfigBaseline(context)"))
        // 未授权时 isSnapshotStale 内部返回 false，不打扰用户（授权引导由 StoragePermissionDialog 负责）
        assertTrue(source.contains("UnifiedConfigFile.baselineHash(context)"))
    }

    @Test
    fun `UnifiedConfigFile 提供无条件重新生成入口`() {
        val source = sourceOf("UnifiedConfigFile.kt")
        assertTrue(
            "generate 必须是无条件重写入口（ensureGenerated 永不覆盖，不能复用）",
            source.contains("fun generate(context: Context): Boolean"),
        )
        // ensureGenerated 的保护语义不能被破坏
        assertTrue(
            "ensureGenerated 必须在文件已存在时直接返回、不覆盖用户编辑",
            source.contains("if (UserConfigDir.exists(context, UserConfigDir.UNIFIED_CONFIG_FILE)) return true"),
        )
    }

    @Test
    fun `授权弹窗与覆盖弹窗互斥`() {
        // 未授权时 isSnapshotStale 内部即返回 false，覆盖弹窗不会出现；
        // 授权引导由 StoragePermissionDialog 负责，两个弹窗不会同时出现。
        val overwrite = sourceOf("ConfigOverwriteDialog.kt")
        assertTrue(
            "覆盖弹窗的判据必须经由 isSnapshotStale（其内部含授权检查）",
            overwrite.contains("UnifiedConfigFile.isSnapshotStale(context)"),
        )
        assertFalse(
            "覆盖弹窗不应自己发起授权（那是 StoragePermissionDialog 的职责）",
            overwrite.contains("allFilesAccessIntent"),
        )
    }
}
