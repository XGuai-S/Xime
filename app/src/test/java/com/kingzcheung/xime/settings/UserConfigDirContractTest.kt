package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * 用户配置目录契约测试。
 *
 * 需求：配置目录从「免权限的公共 `Documents/Xime`」改为「内部存储根下的 `Xime/`」，
 * 统一配置文件名为 `xime.yaml`，安装时不再向 `Documents/Xime` 释放文件。
 *
 * 新目录不属于 MediaStore 公共集合，写入需要「所有文件访问」（API 30+）或
 * `WRITE_EXTERNAL_STORAGE`（API 28/29），因此本测试同时锁定：
 * 1. 目录与文件名的取值；
 * 2. 读取优先级 `xime.yaml` → `xime.txt` → `xime.custom.yaml`；
 * 3. 旧目录只参与迁移清理，不参与任何读写；
 * 4. 清单里声明了所需权限，输入方案页提供了打开配置目录的入口。
 */
class UserConfigDirContractTest {

    // 仓库源码是 CRLF，先归一化行尾，否则按 "\n\n" 找空行会失配
    private val userConfigDirSource: String =
        RepoFiles.mainKotlinSources.first { it.name == "UserConfigDir.kt" }
            .readText().replace("\r\n", "\n")

    private fun sourceOf(fileName: String): String =
        RepoFiles.mainKotlinSources.first { it.name == fileName }
            .readText().replace("\r\n", "\n")

    /** 截取 `fun <name>(` 起、到下一个顶层成员声明之前的函数体文本。 */
    private fun bodyOf(source: String, funSignature: String): String {
        val start = source.indexOf(funSignature)
        require(start >= 0) { "未找到 $funSignature" }
        val next = source.indexOf("\n    private ", start)
        val nextFun = source.indexOf("\n    fun ", start)
        val ends = listOfNotNull(
            next.takeIf { it > start },
            nextFun.takeIf { it > start },
        )
        return if (ends.isEmpty()) source.substring(start) else source.substring(start, ends.min())
    }

    /** 截取一个属性的声明块（签名起，到下一个空行为止），避免把后面无关代码一起圈进来。 */
    private fun propertyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "未找到 $signature" }
        val rest = source.substring(start + signature.length)
        val end = rest.indexOf("\n\n")
        return if (end < 0) rest else rest.substring(0, end)
    }

    @Test
    fun `配置目录位于内部存储根的 Xime 目录`() {
        assertEquals("Xime", UserConfigDir.DIR_NAME)
        // 单测 JVM 里 Environment 返回 null，路径层级只能在源码层锁定
        assertEquals("Xime", UserConfigDir.absoluteDir.name)
        assertEquals("Xime", UserConfigDir.legacyDir.name)

        val absolute = propertyOf(userConfigDirSource, "val absoluteDir: File")
        assertTrue(
            "新目录应挂在内部存储根下（而不是 Documents 等公共集合）",
            absolute.contains("Environment.getExternalStorageDirectory()"),
        )
        assertTrue("新目录应使用 DIR_NAME", absolute.contains("DIR_NAME"))
        assertTrue(
            "新目录不得再使用公共集合目录",
            !absolute.contains("getExternalStoragePublicDirectory"),
        )

        val legacy = propertyOf(userConfigDirSource, "val legacyDir: File")
        assertTrue(
            "旧目录应位于 Documents 下——两者根不同才保证新目录不在 Documents 里",
            legacy.contains("Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)"),
        )
        assertTrue("旧目录应使用 DIR_NAME", legacy.contains("DIR_NAME"))
    }

    @Test
    fun `统一配置文件名改为 xime_yaml 且保留旧名兼容`() {
        assertEquals("xime.yaml", UserConfigDir.UNIFIED_CONFIG_FILE)
        assertEquals("xime.txt", UserConfigDir.LEGACY_UNIFIED_CONFIG_FILE)
        assertEquals("xime.custom.yaml", UserConfigDir.CUSTOM_CONFIG_FILE)
        assertTrue(
            "新名不得与旧名重合，否则兼容读取没有意义",
            UserConfigDir.UNIFIED_CONFIG_FILE != UserConfigDir.LEGACY_UNIFIED_CONFIG_FILE,
        )
    }

    @Test
    fun `读取优先级 xime_yaml 高于 xime_txt 高于 xime_custom_yaml`() {
        val body = bodyOf(sourceOf("KeysConfigHelper.kt"), "private fun readUserDataText(")
        val unified = body.indexOf("UserConfigDir.UNIFIED_CONFIG_FILE")
        val legacy = body.indexOf("UserConfigDir.LEGACY_UNIFIED_CONFIG_FILE")
        val custom = body.indexOf("UserConfigDir.readText(context, fileName)")
        assertTrue("应优先读 xime.yaml", unified >= 0)
        assertTrue("其次应读旧名 xime.txt", legacy > unified)
        assertTrue("最后才读 xime.custom.yaml", custom > legacy)
    }

    @Test
    fun `旧目录只参与迁移清理不参与任何读写`() {
        val decl = userConfigDirSource.indexOf("val legacyDir")
        val migrate = userConfigDirSource.indexOf("fun migrateLegacyDir(")
        assertTrue("legacyDir 应有声明", decl >= 0)
        assertTrue("legacyDir 应在 migrateLegacyDir 之前声明", migrate > decl)
        // 声明与迁移函数之间若再出现 legacyDir，说明有读写路径碰了旧目录
        val between = userConfigDirSource.substring(decl + "val legacyDir".length, migrate)
        assertEquals(
            "旧目录不得出现在迁移以外的读写路径中",
            0,
            Regex("legacyDir").findAll(between).count(),
        )
        // 释放流程必须带上清理，否则旧目录会一直留在设备上
        val ensure = bodyOf(userConfigDirSource, "fun ensureReleased(")
        assertTrue("ensureReleased 应调用 migrateLegacyDir", ensure.contains("migrateLegacyDir(context)"))
    }

    @Test
    fun `旧目录迁移成功后才清理且迁移失败时保留`() {
        val migrate = bodyOf(userConfigDirSource, "fun migrateLegacyDir(")
        // 新配置已存在时不覆盖（否则会把用户在新目录里改好的内容冲掉）
        assertTrue("迁移前应判新配置是否已存在", migrate.contains("exists(context, UNIFIED_CONFIG_FILE)"))
        assertTrue("应先读旧目录里的统一配置", migrate.contains("File(legacy, it)"))
        assertTrue("迁移失败必须保留旧目录", migrate.contains("skip legacy dir cleanup"))
    }

    @Test
    fun `ReleaseState 提供待授权状态供设置页提示`() {
        val values = enumValues<UserConfigDir.ReleaseState>().map { it.name }
        assertTrue("缺少待授权状态: $values", values.contains("PERMISSION_REQUIRED"))
        assertTrue("缺少成功状态: $values", values.contains("SUCCESS"))
        assertTrue("缺少失败状态: $values", values.contains("FAILED"))
    }

    @Test
    fun `API30 以下不提供所有文件访问授权页`() {
        // 单测 JVM 里 Build.VERSION.SDK_INT 为 0，等价于「低于 API 30」
        assertNull(UserConfigDir.allFilesAccessIntent(mock<Context>()))
    }

    @Test
    fun `清单声明写入配置目录所需权限`() {
        val manifest = RepoFiles.file("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "缺少 MANAGE_EXTERNAL_STORAGE，API 30+ 无法写入内部存储根目录",
            manifest.contains("android.permission.MANAGE_EXTERNAL_STORAGE"),
        )
        val legacyPermission = Regex(
            """<uses-permission\s+android:name="android\.permission\.WRITE_EXTERNAL_STORAGE"\s+android:maxSdkVersion="29"""",
        )
        assertTrue(
            "WRITE_EXTERNAL_STORAGE 应仅声明到 API 29（API 30+ 改走所有文件访问）",
            legacyPermission.containsMatchIn(manifest),
        )
    }

    @Test
    fun `输入方案页提供打开配置目录的入口`() {
        // 「打开配置目录」从「主题与定制」页移到「输入方案」页，与「部署方案」相邻
        val schema = sourceOf("SchemaSettingsScreen.kt")
        assertTrue("输入方案页缺少「打开配置目录」入口", schema.contains("打开配置目录"))
        assertTrue("入口应调用内置文件管理器", schema.contains("onNavigateToRimeFileBrowser"))
        assertTrue("入口应展示配置目录路径", schema.contains("UserConfigDir.DISPLAY_PATH"))
        assertTrue(
            "输入方案页缺少待授权提示",
            schema.contains("UserConfigDir.ReleaseState.PERMISSION_REQUIRED"),
        )
        val theme = sourceOf("ThemeSettingsScreen.kt")
        assertFalse("主题与定制页不应再保留配置文件区块", theme.contains("打开配置目录"))
        assertFalse("「打开本地目录」按钮应已删除", theme.contains("打开本地目录"))
    }

    @Test
    fun `权限判定与授权入口成对提供`() {
        val source = userConfigDirSource
        assertTrue("应提供写权限判定", source.contains("fun hasWritePermission(context: Context)"))
        assertTrue("应提供授权页入口", source.contains("fun allFilesAccessIntent(context: Context)"))
        assertTrue(
            "API 30+ 应判「所有文件访问」",
            source.contains("Environment.isExternalStorageManager()"),
        )
        assertTrue(
            "API 28/29 应判运行时写权限",
            source.contains("Manifest.permission.WRITE_EXTERNAL_STORAGE"),
        )
    }

    @Test
    fun `未授权时读写操作全部安全降级`() {
        val source = userConfigDirSource
        // 未授权 → usable() 为 false → 读写/列表/删除一律返回空值，绝不抛异常
        assertTrue("应集中判定可用性", source.contains("private fun usable(context: Context)"))
        val usable = bodyOf(source, "private fun usable(context: Context)")
        assertTrue("可用性应同时要求平台支持与已授权", usable.contains("isSupported && hasWritePermission(context)"))
    }
}
