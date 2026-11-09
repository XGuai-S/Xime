package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用其他应用打开配置目录」的意图契约测试。
 *
 * 这个功能连续失败过两次，原因是两个**编译器与运行时都不会报错**的错误：
 *
 * 1. 用 `Uri.fromFile(...)`（file://）交给第三方应用 —— targetSdk >= 24 时系统会抛
 *    `FileUriExposedException`，Intent 根本发不出去。代码看起来完全正常。
 * 2. 用通配 MIME（星号斜杠星号）兜底 —— 第三方管理器普遍声明了它，所以
 *    `resolveActivity` 必然通过，但它们把 URI 当**文件**处理，只打开自己的首页，
 *    不会跳转到目录。表现就是「打开了 MT管理器但没进 Xime 目录」。
 *
 * 两者都属于「看着成功、实际没用」的假成功，只能靠文本层契约测试锁住。
 */
class ConfigDirOpenerIntentTest {

    private val opener by lazy {
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/settings/ConfigDirOpener.kt")
            .readText()
    }

    /** 只保留可执行代码行，剔除注释——注释里为说明历史踩坑会提到这些字符串。 */
    private val executableCode: String by lazy {
        opener.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    @Test
    fun `打开前先把私有 rime 工作目录同步到公开 rime 根`() {
        // 第三方应用无法访问应用私有目录 /data/user/0/...，因此「用其他应用打开」前
        // 必须先把私有 rime 工作目录（方案词典真正所在）同步到公开 /storage/emulated/0/rime
        val schema = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/ui/settings/SchemaSettingsScreen.kt",
        ).readText()
        assertTrue(
            "打开前应同步私有 rime 到公开目录",
            schema.contains("RimeUserFileSync.sync(context)"),
        )
        // 只同步可编辑文本时，第三方看到的目录比「打开配置目录」少 build/、*.userdb、
        // 安装状态文件，用户会以为两个入口内容不一致
        assertTrue(
            "打开前应把运行状态与编译产物也补进镜像，保证两个入口内容一致",
            schema.contains("RimeUserFileSync.mirrorRuntimeFiles(context)"),
        )
    }

    @Test
    fun `不得用 file URI 交付给其他应用`() {
        // file:// 在 targetSdk >= 24 上会被 FileUriExposedException 拦截
        assertFalse(
            "不得用 Uri.fromFile 构造交给外部应用的 URI（会抛 FileUriExposedException）",
            executableCode.contains("Uri.fromFile("),
        )
        assertFalse(
            "不得依赖 disableDeathOnFileUriExposure 绕过检查（全局降低安全性）",
            executableCode.contains("disableDeathOnFileUriExposure"),
        )
    }

    @Test
    fun `不得用通配 MIME 兜底目录跳转`() {
        // 通配 MIME 对第三方管理器必然可解析，却必然不跳转目录 —— 假成功
        assertFalse(
            "目录跳转不得使用通配 MIME 兜底（能解析但不跳转）",
            executableCode.contains("\"*/*\""),
        )
    }

    @Test
    fun `使用系统外部存储的可列举目录 URI`() {
        // content://com.android.externalstorage.documents/document/primary%3AXime
        // 是 SAF 标准形态，可实现定位且不受 FileUriExposedException 约束
        assertTrue(
            "应使用 externalstorage DocumentsProvider 的 authority",
            opener.contains("com.android.externalstorage.documents"),
        )
        assertTrue(
            "目录 URI 应为 document/primary%3A<目录名> 形态",
            opener.contains("document/primary%3A"),
        )
    }

    @Test
    fun `提供 SAF 文档树入口并指定初始位置`() {
        // ACTION_OPEN_DOCUMENT_TREE + EXTRA_INITIAL_URI 是跨应用打开目录的官方方式
        assertTrue(
            "应提供 ACTION_OPEN_DOCUMENT_TREE 形态",
            opener.contains("Intent.ACTION_OPEN_DOCUMENT_TREE"),
        )
        assertTrue(
            "必须带 EXTRA_INITIAL_URI，否则选择器不会落在目标目录",
            opener.contains("DocumentsContract.EXTRA_INITIAL_URI"),
        )
    }

    @Test
    fun `启动时按候选链逐个尝试而不是只发一个`() {
        // resolveActivity 会误判（声明了 MIME 但需要额外 extra 的情形），
        // 因此以「实际能不能 startActivity」为准
        val launchBody = opener.substringAfter("fun launch(context: Context")
        assertTrue(
            "launch 应遍历多个候选 Intent",
            launchBody.contains("for (intent in candidates)"),
        )
        assertTrue(
            "候选应含私有深链 + SAF + VIEW 三种形态",
            launchBody.contains("mtDeepLinksIfMt") &&
                launchBody.contains("safDirectoryIntent") &&
                launchBody.contains("viewDirectoryIntent"),
        )
        assertTrue(
            "失败应记录日志，便于真机定位（走 FileLogger，导出日志才拿得到）",
            launchBody.contains("FileLogger.w(") || launchBody.contains("FileLogger.e("),
        )
    }

    @Test
    fun `ACTION_MAIN 兜底不得被当作定位成功`() {
        // 真机日志的核心教训：MT管理器拒绝 OPEN_DOCUMENT_TREE 与 VIEW 后，
        // 代码退回 getLaunchIntentForPackage（ACTION_MAIN），那条被记成 "OK"，
        // 掩盖了前两条失败的事实，用户看到「打开了但没进目录」却拿不到任何提示。
        assertTrue(
            "应定义启动结果枚举以区分定位与仅打开",
            opener.contains("enum class LaunchOutcome") &&
                opener.contains("LaunchOutcome.Located") &&
                opener.contains("LaunchOutcome.OpenedOnly") &&
                opener.contains("LaunchOutcome.Failed"),
        )
        assertTrue(
            "应以 action 是否为 ACTION_MAIN 判定是否真正定位",
            opener.contains("intent.action != Intent.ACTION_MAIN"),
        )
    }

    @Test
    fun `界面必须如实区分仅打开与定位失败`() {
        // 只打开应用时若不提示，用户会以为功能坏了却不知原因
        val screen = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/ui/settings/SchemaSettingsScreen.kt",
        ).readText()
        assertTrue("应处理 Located 分支", screen.contains("LaunchOutcome.Located"))
        assertTrue("应处理 OpenedOnly 分支", screen.contains("LaunchOutcome.OpenedOnly"))
        assertTrue("应处理 Failed 分支", screen.contains("LaunchOutcome.Failed"))
        assertTrue(
            "仅打开时应提示不支持定位，并给出目录路径",
            screen.contains("不支持直接定位") && screen.contains("RimeUserFileSync.mirrorDir(context)"),
        )
    }

    @Test
    fun `MT管理器通过目录内锚点文件定位`() {
        // 真机确认：MT管理器对 OPEN_DOCUMENT_TREE 与 vnd.android.document/directory
        // 都抛 ActivityNotFoundException，通用目录 URI 方案对它无效。
        // 真机（2026-10-14）用 `dumpsys package bin.mt.plus` 核对 MT 的 Activity
        // Resolver Table：MT **根本没注册 `mt://` scheme**（只有 file / content /
        // mtplugin），目录型 MIME 也被拒——旧实现猜的深链注定全部失败。
        // 实测可行：给它传**目录里的一个文件**（content:// + text/plain），
        // MT 打开该文件并自动定位到它所在目录，也就是 Xime 目录。
        val mtBody = executableCode.substringAfter("private fun mtDeepLinks(pkg: String)")
            .substringBefore("private fun appLabel")
        assertFalse(
            "不得再猜 mt:// 深链（MT 未注册该 scheme，真机全部 ActivityNotFoundException）",
            mtBody.contains("mt://"),
        )
        assertTrue(
            "应以目录内的锚点文件（rime 根的 default.yaml）作为跳转目标",
            mtBody.contains("MT_ANCHOR_FILE"),
        )
        // URI 必须由**本应用自己的 FileProvider** 生成：
        // 真机实测（2026-10-14）发 externalstorage 的 DocumentsProvider URI 会被系统拒——
        // `MANAGE_EXTERNAL_STORAGE` 只授权 File API，不含 DocumentsProvider 的 URI 访问权
        // （报 “UID ... does not have permission to content://com.android.externalstorage…”）。
        assertTrue(
            "文件 URI 应由本应用 FileProvider 生成（可合法授权给接收方）",
            mtBody.contains("FileProvider.getUriForFile("),
        )
        assertTrue(
            "应声明 FLAG_GRANT_READ_URI_PERMISSION（否则接收方读不到）",
            mtBody.contains("FLAG_GRANT_READ_URI_PERMISSION"),
        )
        // MT 锚点文件取自公开 rime 根 /storage/emulated/0/rime（打开前由 RimeUserFileSync
        // 从私有工作目录同步过来），必须由 filepaths.xml 的 external-path 覆盖；
        // 否则 getUriForFile 抛 IllegalArgumentException，MT 深链被静默跳过
        val paths = RepoFiles.file("app/src/main/res/xml/filepaths.xml").readText()
        assertTrue(
            "filepaths.xml 应声明 external-path 覆盖公开 rime 根目录",
            paths.contains("<external-path") && paths.contains("path=\"rime/\""),
        )
        assertTrue(
            "应带 text/plain（MT 只对已声明的文件类型有入口）",
            mtBody.contains("text/plain"),
        )
        assertTrue(
            "锚点不存在时应返回空列表（回退系统选择器）",
            mtBody.contains("return emptyList()"),
        )
        assertTrue(
            "mt 深链应覆盖正式包与 canary 包",
            executableCode.contains("bin.mt.plus.canary"),
        )
    }

    @Test
    fun `启动诊断必须能在默认日志级别下被用户拿到`() {
        // 真实教训：第一版诊断用 android.util.Log.d —— 只进 logcat，
        // 用户从「设置 → 导出日志」拿不到；改用 FileLogger.i 后，
        // 默认级别「仅错误」下 i 级同样不落盘。两次都让用户白操作一次。
        // 因此诊断必须用 w/e 级，且不得再用裸 android.util.Log。
        assertTrue(
            "应采用 FileLogger 记录启动结果",
            executableCode.contains("FileLogger.w(") && executableCode.contains("FileLogger.e("),
        )
        assertFalse(
            "不得用裸 android.util.Log（只进 logcat，导出日志里看不到）",
            executableCode.contains("android.util.Log"),
        )
    }

    @Test
    fun `诊断内容需含 action data 与目标 URI`() {
        // 「跳到了但位置不对」这类问题，必须能从日志看出走的哪条候选、URI 是什么
        listOf("action=", "data=", "mime=", "initialUri=", "targetUri=").forEach { field ->
            assertTrue("诊断日志应包含 $field", executableCode.contains(field))
        }
    }

    @Test
    fun `SAF 意图需同时把目录 URI 放进 data 与 extra`() {
        // 实测：MT管理器只停在 /storage/emulated/0/（少一级），
        // 因为部分管理器读 getData() 而非 EXTRA_INITIAL_URI，取不到就用 authority 根兜底。
        val safBody = executableCode.substringAfter("private fun safDirectoryIntent")
            .substringBefore("private fun viewDirectoryIntent")
        assertTrue("应 setData(uri)", safBody.contains(".setData(uri)"))
        assertTrue(
            "应 putExtra(EXTRA_INITIAL_URI)",
            safBody.contains("DocumentsContract.EXTRA_INITIAL_URI"),
        )
    }

    @Test
    fun `文档 URI 应经官方 API 构造而非手拼字符串`() {
        // 手拼 "…/document/primary%3AXime" 时编码容易出错，提供方可能把 ID 解析成
        // "primary"（存储根），表现就是跳到 /storage/emulated/0/ 进不去子目录
        assertTrue(
            "应用 DocumentsContract.buildDocumentUri 构造",
            executableCode.contains("DocumentsContract.buildDocumentUri("),
        )
        assertTrue(
            "document ID 应为 primary:<目录名> 形态",
            executableCode.contains("primary:"),
        )
    }

    @Test
    fun `第三方管理器包名清单与 manifest queries 一一对应`() {
        // Android 11+ 包可见性：这边探测、manifest 声明，缺一个都会「搜不到」
        val manifest = RepoFiles.file("app/src/main/AndroidManifest.xml").readText()
        listOf("bin.mt.plus", "com.mixplorer", "pl.solidexplorer2").forEach { pkg ->
            assertTrue("$pkg 应在 ConfigDirOpener 的探测清单中", opener.contains("\"$pkg\""))
            assertTrue("$pkg 应在 AndroidManifest 的 queries 中声明", manifest.contains("android:name=\"$pkg\""))
        }
    }
}
