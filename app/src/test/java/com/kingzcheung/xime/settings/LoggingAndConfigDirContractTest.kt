package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「日志功能」与「打开配置目录直达配置文件目录」的契约测试。
 *
 * 这两个功能都有过**发布包里静默失效**的历史，靠肉眼 review 抓不住：
 * 1. 日志：`FileLogger` 的 v/d/i 写文件被 `BuildConfig.DEBUG` 卡死，开关又被
 *    `if (BuildConfig.DEBUG)` 包住 —— 用户装的 release 包里既没有开关、也没有日志，
 *    而 debug 下一切正常，开发者永远复现不出来。
 * 2. 打开配置目录：入口叫「打开配置目录」，却落在私有 `files/rime/` 根目录，
 *    用户还要再点一层虚拟入口才看到 `xime.yaml`。
 *
 * 因此这里断言的是**结构约束**（release 下也必须成立），而不是运行时行为。
 */
class LoggingAndConfigDirContractTest {

    private val fileLogger by lazy {
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/util/FileLogger.kt").readText()
    }
    private val prefs by lazy {
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/settings/SettingsPreferences.kt").readText()
    }
    private val logSettingsScreen by lazy {
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/ui/settings/LogSettingsScreen.kt").readText()
    }
    private val settingsMain by lazy {
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/ui/settings/SettingsMainScreen.kt").readText()
    }
    private val settingsScreen by lazy {
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/ui/settings/SettingsScreen.kt").readText()
    }
    private val routes by lazy {
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/ui/settings/SettingsRoutes.kt").readText()
    }
    private val browser by lazy {
        RepoFiles.file("app/src/main/java/com/kingzcheung/xime/ui/settings/RimeFileBrowserScreen.kt").readText()
    }

    // ---------- 日志：写文件只受级别控制，不被 BuildConfig.DEBUG 卡住 ----------

    @Test
    fun `写文件不再依赖 BuildConfig_DEBUG`() {
        // 旧实现是 `if (BuildConfig.DEBUG && verboseLoggingEnabled) writeToFile(...)`：
        // release 包一行日志都不落盘。现在写文件只受级别门槛控制。
        assertFalse(
            "写文件不得再被 BuildConfig.DEBUG 限制（release 包会完全无日志）",
            fileLogger.contains("BuildConfig.DEBUG && loggingEnabled"),
        )
        // 各写入函数统一走 write()，由 LogLevel.allows 决定是否落盘
        listOf("v", "d", "i").forEach { fn ->
            assertTrue(
                "$fn 级应经 write(LogLevel...) 受级别控制",
                fileLogger.contains("fun $fn(tag: String, message: String)"),
            )
        }
        assertTrue(
            "级别门槛判定应集中在 write() 里",
            fileLogger.contains("private fun write(level: LogLevel, tag: String, message: String)") &&
                fileLogger.contains("if (logLevel.allows(level)) writeToFile("),
        )
    }

    @Test
    fun `logcat 仍只在 debug 构建输出`() {
        // 写文件放开了，但 logcat 不该在正式包里刷屏：保留 `if (BuildConfig.DEBUG) Log.`
        assertTrue(
            "v/d/i 的 logcat 输出应仍限定 Debug",
            fileLogger.contains("if (BuildConfig.DEBUG) Log.v(tag, message)") &&
                fileLogger.contains("if (BuildConfig.DEBUG) Log.d(tag, message)") &&
                fileLogger.contains("if (BuildConfig.DEBUG) Log.i(tag, message)"),
        )
    }

    @Test
    fun `警告与错误同样受级别门槛约束`() {
        // 用户要求「只记录崩溃、ERROR/WARN，可在 xime.yaml 设置」：
        // 默认 ERROR 档**不**记 WARN，选 warn 档才记。这与旧设计（w/e 无条件落盘）相反。
        val wBody = fileLogger.substringAfter("fun w(tag: String")
            .substringBefore("fun e(tag: String")
        assertTrue(
            "w 级应经 write(LogLevel.WARN) 受级别控制，而不是无条件落盘",
            wBody.contains("write(LogLevel.WARN"),
        )
        val eBody = fileLogger.substringAfter("fun e(tag: String")
        assertTrue(
            "e 级应经 write(LogLevel.ERROR) 受级别控制",
            eBody.contains("write(LogLevel.ERROR"),
        )
        // 异常堆栈必须保留，否则错误日志没有定位价值
        assertTrue("异常堆栈应附加到消息", fileLogger.contains("throwable.stackTraceToString()"))
    }

    @Test
    fun `日志级别默认取值为仅错误`() {
        assertTrue(
            "默认应为 ERROR 档（只记崩溃与错误）",
            fileLogger.contains("private var logLevel: LogLevel = LogLevel.DEFAULT") &&
                prefs.contains("KEY_LOG_LEVEL"),
        )
        assertTrue(
            "旧键 verbose_logging 需保留迁移读取，避免老用户设置被重置",
            prefs.contains("\"verbose_logging\""),
        )
    }

    @Test
    fun `FileLogger 暴露运行时级别并与配置解析一致`() {
        assertTrue(fileLogger.contains("fun setLogLevel(level: LogLevel)"))
        assertTrue(fileLogger.contains("fun getLogLevel(): LogLevel"))
        assertTrue(
            "init 时应按「界面 → xime.yaml → 默认」解析级别",
            fileLogger.contains("logLevel = LogLevelResolver.resolve(context)"),
        )
        assertFalse(
            "旧的布尔字段名应已收敛，避免两套语义并存",
            fileLogger.contains("private var verboseLoggingEnabled"),
        )
    }

    @Test
    fun `所有功能判定都走含迁移的读取函数`() {
        // 曾经的缺陷：init/IME/设置页都直接调 isLoggingEnabled（只读新键），
        // 而旧版本写的是 verbose_logging。老用户若曾关掉日志，升级后新键缺失，
        // 会被静默重置成「默认开」——又开始写盘，且开关显示与实际不一致。
        // 判据必须统一到含旧键迁移的 isVerboseLoggingEnabled。
        listOf(
            "app/src/main/java/com/kingzcheung/xime/util/FileLogger.kt",
            "app/src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt",
            "app/src/main/java/com/kingzcheung/xime/ui/settings/LogSettingsScreen.kt",
        ).forEach { path ->
            val text = RepoFiles.file(path).readText()
            assertFalse(
                "$path 不应直接用 isLoggingEnabled 做功能判定（会丢掉旧键迁移）",
                text.contains("SettingsPreferences.isLoggingEnabled("),
            )
        }
    }

    // ---------- 日志导出 ----------

    @Test
    fun `提供汇总导出且覆盖三类日志来源`() {
        assertTrue("应提供导出全部日志的入口", fileLogger.contains("fun exportAllLogs(context: Context)"))
        // 用户反馈问题时需要一份完整上下文，分文件发送拼不回现场
        assertTrue("应汇总 APP 日志", fileLogger.contains("\"APP 日志\""))
        assertTrue("应汇总 RIME 引擎日志", fileLogger.contains("\"RIME 日志\""))
        assertTrue("应汇总插件错误日志", fileLogger.contains("\"插件错误\""))
        assertTrue(
            "导出前必须 flushNow：否则最近几秒的关键日志不在文件里",
            fileLogger.contains("flushNow()"),
        )
    }

    @Test
    fun `导出文件带设备与版本头部`() {
        listOf(
            "Xime 输入法 · 日志导出",
            "应用版本：",
            "构建类型：",
            "日志级别：",
            "Android：",
            "ABI：",
        ).forEach { header ->
            assertTrue("导出头部应含「$header」", fileLogger.contains(header))
        }
    }

    @Test
    fun `日志入口在 release 包里也必须渲染`() {
        // 这是本功能最初的真实缺陷：入口被 `if (BuildConfig.DEBUG)` 包住，正式包里
        // 整行都不存在，用户根本找不到开关。断言「日志入口所在的 SettingsItem
        // 不在任何 DEBUG 条件块内」——用位置关系表达，而不是简单查子串。
        val entryIndex = settingsMain.indexOf("title = \"日志\"")
        assertTrue("设置主页应有「日志」入口", entryIndex > 0)

        // 取其之前的最近一个 DEBUG 判断；若该判断块尚未闭合，说明入口被包含其中
        val before = settingsMain.substring(0, entryIndex)
        val lastDebug = before.lastIndexOf("BuildConfig.DEBUG")
        if (lastDebug > 0) {
            val between = before.substring(lastDebug)
            val opens = between.count { it == '{' }
            val closes = between.count { it == '}' }
            assertTrue(
                "「日志」入口不得被 BuildConfig.DEBUG 条件块包住（正式包里会整行消失）",
                closes >= opens,
            )
        }
    }

    @Test
    fun `日志设置页不依赖 DEBUG 构建`() {
        // 页面本身若在 release 下被裁掉，上面的入口就是死链
        assertFalse(
            "日志设置页不得以 BuildConfig.DEBUG 控制可见性",
            logSettingsScreen.contains("if (BuildConfig.DEBUG)"),
        )
    }

    @Test
    fun `日志设置页提供级别选择与导出`() {
        assertTrue("应有日志设置页", logSettingsScreen.contains("fun LogSettingsContent("))
        assertTrue("应能选择记录级别", logSettingsScreen.contains("LogLevelDialog("))
        assertTrue("应写入级别偏好", logSettingsScreen.contains("SettingsPreferences.setLogLevelOverride"))
        assertTrue("应同步运行时级别", logSettingsScreen.contains("FileLogger.setLogLevel"))
        assertTrue("应能导出全部日志", logSettingsScreen.contains("FileLogger.exportAllLogs"))
        assertTrue("应能进入日志查看器", logSettingsScreen.contains("onOpenLogViewer"))
        assertTrue("应能清空日志", logSettingsScreen.contains("FileLogger.clearAllLogs"))
        assertTrue(
            "应提示可在 xime.yaml 中配置",
            logSettingsScreen.contains("在 xime.yaml 中配置") &&
                logSettingsScreen.contains("clearLogLevelOverride"),
        )
    }

    @Test
    fun `日志设置页统计与导出走 IO 线程`() {
        // 日志文件可能很大，readText/listFiles 压主线程会掉帧
        assertTrue(logSettingsScreen.contains("Dispatchers.IO"))
        assertTrue(logSettingsScreen.contains("withContext(Dispatchers.IO)"))
    }

    @Test
    fun `导出目录必须在 FileProvider 的 filepaths 中声明`() {
        // 真实缺陷：exportAllLogs 把汇总文件写到 cacheDir/log_export/，而
        // res/xml/filepaths.xml 只声明了 emoji_cache/ 与 files/。
        // FileProvider.getUriForFile 对未声明根路径会抛 IllegalArgumentException
        // （Failed to find configured root），于是「导出全部日志」永远走不到系统分享面板，
        // 而是静默退化去写下载目录——功能看似可用、实则半残。
        //
        // 这个契约编译器管不了、单测也看不见，只能在文本层面对齐「写入路径」与「声明路径」。
        val filepaths = RepoFiles.file("app/src/main/res/xml/filepaths.xml").readText()

        // 导出目录名（与 FileLogger.exportAllLogs 中的 File(context.cacheDir, "log_export") 一致）
        val exportDirName = "log_export"
        assertTrue(
            "FileLogger 应把汇总文件写在 cacheDir/$exportDirName 下",
            fileLogger.contains("File(context.cacheDir, \"$exportDirName\")"),
        )
        assertTrue(
            "filepaths.xml 必须声明 cache-path $exportDirName/，否则 FileProvider 取 URI 会抛异常",
            filepaths.contains("path=\"$exportDirName/\""),
        )
    }

    @Test
    fun `分享失败必须记日志而不是静默退化`() {
        // 退化路径会「假装成功」（用户拿到文件，只是没弹分享面板），
        // 不记日志就无从发现 provider 配置错误
        assertTrue(
            "分享失败应写入日志",
            logSettingsScreen.contains("FileLogger.w(TAG"),
        )
        assertTrue(
            "分享失败文案应说明退化去向",
            logSettingsScreen.contains("退化为保存到 Download"),
        )
    }

    @Test
    fun `日志入口出现在设置主页并可导航`() {
        assertTrue("设置主页应有日志入口", settingsMain.contains("onNavigateToLogSettings"))
        assertTrue("入口文案应为「日志」", settingsMain.contains("title = \"日志\""))
        assertTrue("路由常量应存在", routes.contains("const val LogSettings"))
        assertTrue(
            "应注册路由并挂载页面",
            settingsScreen.contains("composable(SettingsRoutes.LogSettings)"),
        )
        assertTrue(
            "主页回调应导航到日志页",
            settingsScreen.contains("onNavigateToLogSettings = { navController.navigate(SettingsRoutes.LogSettings) }"),
        )
    }

    // ---------- 打开配置目录直达配置文件目录 ----------

    @Test
    fun `文件浏览器支持直接落在用户配置目录`() {
        assertTrue(
            "应提供 startInUserConfigDir 参数",
            browser.contains("startInUserConfigDir: Boolean = false"),
        )
        assertTrue(
            "有权限时初始即进入配置目录",
            browser.contains("mutableStateOf(startInUserConfigDir && canUseUserConfig)"),
        )
        assertTrue(
            "判据是配置目录释放成功",
            browser.contains("UserConfigDir.releaseState == UserConfigDir.ReleaseState.SUCCESS"),
        )
    }

    @Test
    fun `授权晚于页面组合时仍能进入配置目录`() {
        // 真实触发时机：StoragePermissionDialog 由 MainActivity 挂在设置页**之上**，
        // 用户完全可能在本页已组合之后才授权成功。若只靠 remember 初值，
        // releaseState 从 PERMISSION_REQUIRED 翻到 SUCCESS 时本页不会重算，
        // 用户被留在私有 files/rime 目录——「打开配置目录却看不到配置」换个时机重演。
        assertTrue(
            "应有一个随 canUseUserConfig 变化的副作用来补跳",
            browser.contains("LaunchedEffect(startInUserConfigDir, canUseUserConfig)"),
        )
        assertTrue(
            "补跳只应发生一次",
            browser.contains("autoEnteredUserConfig"),
        )
        // 目录刷新必须跟着浏览态走，否则补跳后列表仍停留在旧内容
        assertTrue(
            "refresh 应以 browsingUserConfig 为 key",
            browser.contains("LaunchedEffect(currentDir, browsingUserConfig) { refresh() }"),
        )
    }

    @Test
    fun `直达模式下返回键回设置页而不是私有目录`() {
        // 直达进入时不存在「上一层」；若仍退到私有 rime 列表，用户会以为配置目录打错了
        assertTrue(
            "直达模式返回应回到上级页面",
            browser.contains("if (startInUserConfigDir) onBack() else browsingUserConfig = false"),
        )
    }

    @Test
    fun `配置目录入口走直达路由而文件管理器仍走通用路由`() {
        assertTrue("应有独立直达路由", routes.contains("const val UserConfigBrowser"))
        assertTrue(
            "「打开配置目录」应导航到直达路由",
            settingsScreen.contains(
                "onNavigateToRimeFileBrowser = { navController.navigate(SettingsRoutes.UserConfigBrowser) }",
            ),
        )
        assertTrue(
            "「文件管理器」应仍走通用路由",
            settingsScreen.contains(
                "onNavigateToFileManager = { navController.navigate(SettingsRoutes.RimeFileBrowser) }",
            ),
        )
        assertTrue(
            "直达路由必须传 startInUserConfigDir = true",
            settingsScreen.contains("startInUserConfigDir = true"),
        )
    }

    @Test
    fun `文件浏览器两个入口的文案承诺与实际落点一致`() {
        val entry = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/ui/settings/SchemaSettingsScreen.kt",
        ).readText()
        assertTrue(
            "「打开配置目录」副标题应说明直达 xime.yaml 所在目录",
            entry.contains("直接打开 xime.yaml 所在目录"),
        )
        assertTrue(
            "工具栏「文件管理器」应走通用回调",
            entry.contains("onNavigateToFileManager()"),
        )
    }

    // ---------- 文案工具函数 ----------

    @Test
    fun `日志体积文案按量级切换单位`() {
        assertEquals("暂无日志文件", logStatLine(0, 0L))
        assertEquals("0 B", formatLogSize(0L))
        assertEquals("512 B", formatLogSize(512L))
        assertEquals("1.0 KB", formatLogSize(1024L))
        assertEquals("2.50 MB", formatLogSize(2L * 1024 * 1024 + 512 * 1024))
        assertEquals("3 个文件 · 1.0 KB", logStatLine(3, 1024L))
    }
}
