package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「安装新版本后配置为什么没覆盖」的根因回归测试。
 *
 * 背景（用户可见缺陷）：覆盖安装新版本后，`/storage/emulated/0/Xime/xime.yaml` 仍是旧内容，
 * 新版内置默认值（按键宽度 1.1 / 2.5、英文空格上滑切中英等）全部没生效。
 *
 * 根因：新鲜的判据用了 `BuildConfig.VERSION_CODE`，而本项目 versionCode 是硬编码常量
 * （`app/build.gradle.kts` 的 `20261005`，只有 nightly 用 `-PversionCode` 覆盖），
 * 每次构建都一样 → 判据恒为「同版本已问过」→ 覆盖弹窗永不出现。
 *
 * 修正：改用**内置基线的内容指纹**。本测试直接验证判定的真实行为（纯函数），
 * 而不是只检查源码里出现了哪些字符串。
 */
class ConfigBaselineStalenessTest {

    private val realYaml: ByteArray
        get() = RepoFiles.file("src/main/assets/xime.yaml").readBytes()

    // ── 指纹本身 ──

    @Test
    fun `同样的内容得到同样的指纹、内容变了指纹就变`() {
        val a = UnifiedConfigFile.baselineHashOf(realYaml)
        val b = UnifiedConfigFile.baselineHashOf(realYaml)
        assertTrue("同一内容必须得到稳定指纹", a != null && a == b)

        // 模拟内置配置被改动（例如这轮把空格宽度 2.7 改成 2.5）
        val modified = realYaml.toString(Charsets.UTF_8).replace("width: 2.5", "width: 9.9")
            .toByteArray(Charsets.UTF_8)
        assertNotEquals("内置配置一改，指纹必须变化", a, UnifiedConfigFile.baselineHashOf(modified))
    }

    @Test
    fun `指纹是定长十六进制`() {
        val hash = UnifiedConfigFile.baselineHashOf(realYaml)!!
        assertEquals("取 SHA-256 前 8 字节 = 16 个十六进制字符", 16, hash.length)
        assertTrue("必须是十六进制", Regex("^[0-9a-f]{16}$").matches(hash))
    }

    @Test
    fun `versionCode 不是有效的判据来源`() {
        // 锁定根因：build.gradle.kts 里 versionCode 是字面量常量，不随内容变化。
        // 一旦有人改回用 versionCode 做新鲜度判据，这个测试会提醒他为什么不行。
        val gradle = RepoFiles.file("app/build.gradle.kts").readText()
        assertTrue(
            "versionCode 应是硬编码常量（这正是它不能当判据的原因）",
            Regex("versionCode\\s*=\\s*\\d+").containsMatchIn(gradle),
        )
        val dialog = RepoFiles.mainKotlinSources
            .first { it.name == "ConfigOverwriteDialog.kt" }.readText()
        assertFalse(
            "覆盖判据不得依赖 versionCode",
            dialog.contains("BuildConfig.VERSION_CODE"),
        )
    }

    // ── 落后判定 ──

    @Test
    fun `指纹一致视为不落后`() {
        val hash = UnifiedConfigFile.baselineHashOf(realYaml)!!
        val snapshot = UnifiedConfigFile.snapshotText("keyboard:\n  qwerty: {}\n", hash)
        assertFalse(
            "快照就是当前基线生成的，不该再问用户",
            UnifiedConfigFile.isSnapshotStale(snapshot, hash),
        )
    }

    @Test
    fun `指纹不一致视为落后`() {
        val snapshot = UnifiedConfigFile.snapshotText("keyboard: {}\n", "0123456789abcdef")
        assertTrue(
            "快照基于旧基线生成，应提示可覆盖",
            UnifiedConfigFile.isSnapshotStale(snapshot, "fedcba9876543210"),
        )
    }

    @Test
    fun `没有指纹标记的旧快照视为落后`() {
        // 本次改动之前生成的快照没有标记行：它们的宽度/手势确实还是旧默认值，
        // 必须视为落后，否则老用户永远收不到新默认值。
        val legacySnapshot = """
            # Xime 输入法 · 统一配置（完整快照）
            keyboard:
              qwerty:
                keys:
                  space: { width: 2.7 }
        """.trimIndent()
        assertNull(
            "旧快照没有指纹",
            UnifiedConfigFile.snapshotBaseline(legacySnapshot),
        )
        assertTrue(
            "旧快照必须视为落后",
            UnifiedConfigFile.isSnapshotStale(legacySnapshot, "0123456789abcdef"),
        )
    }

    @Test
    fun `快照的指纹标记可被完整回读`() {
        val hash = UnifiedConfigFile.baselineHashOf(realYaml)!!
        val snapshot = UnifiedConfigFile.snapshotText("keyboard: {}\n", hash)
        assertEquals(
            "写入的指纹必须能原样读回，否则每次启动都会误判为落后",
            hash,
            UnifiedConfigFile.snapshotBaseline(snapshot),
        )
    }

    @Test
    fun `快照内容仍原样保留在内`() {
        // 加标记不能破坏原有快照内容（注释、缩进都要在）
        val body = "# 控制行宽度比：四个侧键等宽 1.1、空格 2.5\nkeyboard:\n  qwerty:\n    keys: {}\n"
        val snapshot = UnifiedConfigFile.snapshotText(body, "0123456789abcdef")
        assertTrue("原有正文必须完整保留", snapshot.contains("# 控制行宽度比：四个侧键等宽 1.1、空格 2.5"))
        assertTrue(snapshot.contains("keyboard:"))
        assertTrue(snapshot.contains("  qwerty:"))
        // 标记是注释行，不影响 YAML 解析
        assertTrue(snapshot.contains("# xime-baseline: 0123456789abcdef"))
    }
}
