package com.kingzcheung.xime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打包体积优化的契约测试。
 *
 * 背景：APK 里长期带着约 3.3MB **运行时从不读取**的资源，全部来自依赖的上游打包疏漏：
 * - `tables/Transcoder_*.bin`（约 2.9MB）：字符集转码映射表，随 Sora 编辑器的
 *   language-textmate 传递进来；本应用只用它做 YAML 语法高亮，YAML 是 UTF-8，
 *   永不触发 Big5/GBK/日文/韩文转码。
 * - `src/org/eclipse/jdt` 下的注解源码（约 41KB）：运行时只需 class 文件。
 * - `org/apache/commons/codec/language` 下的语音编码规则表（约 234KB）：Commons Codec 的
 *   规则数据，okhttp 只用 Codec 做 Base64/Hex，不碰语言编码器。
 *
 * 这类排除容易在后续升级依赖时被无声移除（谁也不会注意到多出 3MB），
 * 所以在这里锁死：既锁住配置存在，也锁住「代码确实没用到」这个前提。
 */
class ApkPackagingOptimizationTest {

    private val buildGradle by lazy {
        RepoFiles.file("app/build.gradle.kts").readText()
    }

    @Test
    fun `打包排除依赖带入的无用资源`() {
        assertTrue("build.gradle.kts 应有 packaging.resources 排除块", buildGradle.contains("resources {"))
        assertTrue("应排除字符集转码表目录（约 2.9MB）", buildGradle.contains("tables/"))
        assertTrue("应排除 JDT 注解源码目录", buildGradle.contains("src/"))
        assertTrue(
            "应排除 commons-codec 语言编码规则表",
            buildGradle.contains("org/apache/commons/codec/language/"),
        )
        assertTrue("应排除 kotlin-tooling-metadata.json", buildGradle.contains("kotlin-tooling-metadata.json"))
        assertTrue("应排除 DebugProbesKt.bin", buildGradle.contains("DebugProbesKt.bin"))
    }

    @Test
    fun `排除清单不得误伤许可证文件`() {
        // 剔除 META-INF 下的许可证/声明会让第三方许可证在 APK 中消失，存在合规风险，
        // 而体积收益只有几十 KB，不值得冒险。
        //
        // 断言必须落在 excludes 的**条目**上，不能对整份 build 文件做子串匹配：
        // 解释性注释里会出现这些名字，那时匹配会把说明文字误判成违规条目。
        val excludesBlock = buildGradle.substringAfter("excludes += setOf(", "")
            .substringBefore(")")
        assertTrue("应能定位到 excludes 条目块", excludesBlock.isNotBlank())
        assertFalse(
            "excludes 中不得出现许可证条目：会丢失第三方许可证正文",
            excludesBlock.contains("LICENSE"),
        )
        assertFalse(
            "excludes 中不得出现声明条目：同上",
            excludesBlock.contains("NOTICE"),
        )
    }

    @Test
    fun `被排除的资源确实没有任何代码引用`() {
        val sources = RepoFiles.mainKotlinSources.joinToString("\n") { it.readText() }
        // 这三项排除的前提都是「代码不引用」。一旦将来真的用上了，
        // 排除就必须撤销，否则运行时会拿不到资源而崩溃。
        assertFalse(
            "代码中出现 Transcoder 引用时，字符集转码表不能再排除",
            sources.contains("Transcoder"),
        )
        assertFalse(
            "代码中出现 org.eclipse.jdt 引用时，注解源码不能再排除",
            sources.contains("org.eclipse.jdt"),
        )
        assertFalse(
            "代码中出现 commons.codec.language 引用时，该规则表不能再排除",
            sources.contains("commons.codec.language"),
        )
    }

    @Test
    fun `保留按 ABI 分包与 universal 包`() {
        // 单架构包才是真正省流量的手段：libonnxruntime.so 单 arm64 就 28MB，
        // universal 包会把四份带在一起
        assertTrue("应启用 ABI 分包", buildGradle.contains("isEnable = true"))
        assertTrue("应保留 universal 包", buildGradle.contains("isUniversalApk = true"))
        assertTrue("应包含 arm64-v8a", buildGradle.contains("\"arm64-v8a\""))
    }

    @Test
    fun `release 构建开启混淆与资源压缩`() {
        assertTrue("release 应开启代码混淆/缩减", buildGradle.contains("isMinifyEnabled = true"))
        assertTrue("release 应开启资源压缩", buildGradle.contains("isShrinkResources = true"))
    }
}
