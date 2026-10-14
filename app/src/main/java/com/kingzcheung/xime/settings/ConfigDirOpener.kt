package com.kingzcheung.xime.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File
import com.kingzcheung.xime.util.FileLogger

/**
 * 「用其他应用打开配置目录」的一个候选项。
 *
 * @param label 展示给用户的应用名
 * @param packageName 应用包名（取图标、去重）；系统选择器为空串
 * @param intent 已验证可解析的启动 Intent
 */
data class ConfigDirTarget(
    val label: String,
    val packageName: String,
    val intent: Intent,
)

/**
 * 枚举并启动「能打开 `/storage/emulated/0/Xime` 的应用」。
 *
 * 三条关键结论（前两条是「打开方式里找不到 MT管理器」的真正原因）：
 *
 * 1. **不能用 FileProvider 的 `content://` URI 打开目录**。FileProvider 只服务单个文件，
 *    拿到目录 URI 的应用无法列目录，等于打不开。目录必须用系统外部存储 DocumentsProvider
 *    的标准文档 URI：`content://com.android.externalstorage.documents/document/primary%3AXime`。
 * 2. **不能只靠「拿 URI 反查」**。MT管理器、MiXplorer、ES、Solid Explorer 这类管理器并不
 *    在 intent-filter 里声明目录 MIME，`queryIntentActivities(VIEW + 目录URI)` 永远查不到它们。
 *    因此维护 [KNOWN_FILE_MANAGERS] 包名清单，逐个用 `getLaunchIntentForPackage` 探测是否已安装。
 * 3. **候选 Intent 必须先验证可解析**。第三方管理器对 MIME 的支持各不相同，硬塞
 *    `resource/folder` 会抛 `ActivityNotFoundException`。这里逐个候选试 `resolveActivity`，
 *    全部不通就退回「只把管理器本身打开」，保证点了一定有反应。
 *
 * 注意：Android 11 起包可见性生效，`getLaunchIntentForPackage` / `queryIntentActivities` /
 * `resolveActivity` 都受限制，必须配合 AndroidManifest 里的
 * `<queries><package>`（已知管理器）与 `<queries><intent>`（按能力发现）声明。
 */
object ConfigDirOpener {

    private const val TAG = "ConfigDirOpener"

    /** 系统外部存储 DocumentsProvider 的 authority（系统内置）。 */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * 目录型 MIME：按能力发现「声明自己能看目录」的应用。
     *
     * 两项都保留：`vnd.android.document/directory` 是 SAF 标准；
     * `resource/folder` 是部分管理器自定的私有类型，虽然少见但仍可能有应用只声明它。
     */
    private val DIRECTORY_MIME_TYPES = listOf(
        "vnd.android.document/directory",
        "resource/folder",
    )

    /**
     * 常见第三方/系统文件管理器包名。
     *
     * 与 AndroidManifest 里 `<queries><package>` 一一对应：这边负责探测，那边负责让这些包名
     * 在 Android 11+ 对本应用可见，两边缺一个都会「搜不到」。
     */
    private val KNOWN_FILE_MANAGERS = listOf(
        // MT管理器（用户点名）
        "bin.mt.plus",
        "bin.mt.plus.canary",
        // Solid Explorer / ES / MiXplorer
        "pl.solidexplorer2",
        "com.estrongs.android.pop",
        "com.mixplorer",
        "com.mixplorer.beta",
        "com.mixplorer.silver",
        // Root Explorer
        "com.speedsoftware.rootexplorer",
        "com.speedsoftware.explorer",
        // Google 文件极客 / 系统 DocumentsUI
        "com.google.android.apps.nbu.files",
        "com.google.android.documentsui",
        "com.android.documentsui",
        // 各 OEM 自带文件管理器
        "com.mi.android.globalFileexplorer",
        "com.huawei.filemanager",
        "com.coloros.filemanager",
        "com.vivo.filemanager",
        "com.oppo.filemanager",
        "net.micode.fileexplorer",
        "com.zte.filemanager",
        "com.sec.android.app.myfiles",
        "com.lge.filemanager",
        "com.htc.filemanager",
        "com.asus.filemanager",
        "com.motorola.filemanager",
        "com.sonymobile.explorer",
        "com.qihoo360.mobilesafe.filemanager",
        "com.qihoo.filemanager",
        "com.lionmobi.filemanager",
    )

    /**
     * 配置目录的标准文档 URI：`content://com.android.externalstorage.documents/document/primary%3AXime`。
     *
     * 用官方 [DocumentsContract.buildDocumentUri] 构造，而不是手拼字符串：
     * 它会正确地对 document ID 做百分号编码（`primary:Xime` → `primary%3AXime`）。
     * 手拼时很容易漏掉编码或编码错位置，导致提供方把 ID 解析成 `primary`（即存储根），
     * 于是第三方管理器只跳到 `/storage/emulated/0/` 而进不去目标子目录。
     *
     * 这是可列举的目录 URI（区别于只能读单文件的 FileProvider URI），
     * 也是 `EXTRA_INITIAL_URI` 需要的形态。
     */
    private fun documentUri(): Uri =
        DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            "primary:${UserConfigDir.DIR_NAME}",
        )

    /**
     * 搜索手机上所有能打开配置目录的方式。
     *
     * 三类来源，按包名去重、按应用名排序：
     * 1. 声明了 `ACTION_VIEW` + 目录 MIME 的应用（系统「文件」/DocumentsUI 等，按能力发现）；
     * 2. 已安装的常见文件管理器（探测包名，见 [KNOWN_FILE_MANAGERS]）；
     * 3. 系统文档树选择器（必然存在，作兜底）。
     */
    fun queryTargets(context: Context): List<ConfigDirTarget> {
        val pm = context.packageManager
        val byPackage = LinkedHashMap<String, ConfigDirTarget>()
        val uri = documentUri()

        // 1. 按能力发现：谁声明自己能看这个目录
        for (mime in DIRECTORY_MIME_TYPES) {
            val probe = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            for (info in pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || byPackage.containsKey(pkg)) continue
                byPackage[pkg] = ConfigDirTarget(
                    label = info.loadLabel(pm).toString(),
                    packageName = pkg,
                    intent = Intent(probe).setPackage(pkg),
                )
            }
        }

        // 2. 探测已知文件管理器：它们多数不声明目录 MIME，只能按包名找
        for (pkg in KNOWN_FILE_MANAGERS) {
            if (pkg == context.packageName || byPackage.containsKey(pkg)) continue
            // 未安装 / 不可见 → 跳过
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            byPackage[pkg] = ConfigDirTarget(
                label = appLabel(pm, pkg),
                packageName = pkg,
                intent = directoryIntentFor(context, pm, pkg, uri) ?: launch,
            )
        }

        return byPackage.values.sortedBy { it.label } + systemPickerTargets(pm)
    }

    /**
     * 为指定管理器挑一个能真正**定位到配置目录**的 Intent。
     *
     * ## 真机日志得出的结论（2026-10）
     *
     * 对 MT管理器实测，下列两个**标准** Intent 都抛 `ActivityNotFoundException`：
     * - `ACTION_OPEN_DOCUMENT_TREE`（SAF 目录树）
     * - `ACTION_VIEW` + `vnd.android.document/directory`
     *
     * 说明它根本没注册这些入口，**任何通用 URI 方案都不可能成功**。
     * 因此顺序改为「私有深链优先，通用形态兜底」：
     *
     * 1. [packageDeepLink]：该包专属 scheme（MT管理器用 `mt://open?url=`），实测有效路径；
     * 2. [safDirectoryIntent]：标准 SAF，对系统选择器与多数管理器有效；
     * 3. [viewDirectoryIntent]：`ACTION_VIEW` + 目录 MIME。
     *
     * 三者都不行时返回 null，调用方会退回「只打开管理器」——但那只是让应用打开，
     * **并不定位**，所以界面文案必须如实说明，不能让用户以为跳转成功。
     */
    private fun directoryIntentFor(context: Context, pm: PackageManager, pkg: String, uri: Uri): Intent? =
        mtDeepLinksIfMt(context, pkg).firstOrNull { it.resolveActivity(pm) != null }
            ?: safDirectoryIntent(pkg, uri).takeIf { it.resolveActivity(pm) != null }
            ?: viewDirectoryIntent(pkg, uri).takeIf { it.resolveActivity(pm) != null }

    /**
     * 标准 SAF 目录树入口，指定初始位置。
     *
     * 这是**跨应用打开某个目录**的官方方式：`EXTRA_INITIAL_URI` 让选择器直接落在该目录，
     * 而不是让用户从头翻。
     *
     * 【实测修正】把目录 URI 同时放进 `data` 与 extra：
     * 真机上 MT管理器收到该 Intent 后**只停在 `/storage/emulated/0/`**（少一级）。
     * 原因是一些第三方管理器读的是 `intent.getData()` 而不是 `EXTRA_INITIAL_URI`，
     * 拿不到 data 时就用 authority 的根目录兜底——正好是存储根。
     * 两处都放，兼容两类实现。
     */
    private fun safDirectoryIntent(pkg: String, uri: Uri): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .setPackage(pkg)
            .setData(uri)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

    /** `ACTION_VIEW` + 目录 document URI：部分管理器（含系统文件极客）走这条。 */
    private fun viewDirectoryIntent(pkg: String, uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "vnd.android.document/directory")
            .setPackage(pkg)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

    /** MT管理器包名（正式版与 canary 版）。 */
    private val MT_PACKAGES = setOf("bin.mt.plus", "bin.mt.plus.canary")

    /** 若目标是 MT管理器，返回其全部候选深链；否则返回空列表。 */
    private fun mtDeepLinksIfMt(context: Context, pkg: String): List<Intent> =
        if (pkg in MT_PACKAGES) mtDeepLinks(context, pkg) else emptyList()

    /**
     * MT管理器定位到配置目录的 Intent。
     *
     * ## 真机实测结论（2026-10-14，Xiaomi Android 17 + MT 管理器）
     *
     * 旧实现按「`mt://` 深链」猜了两种写法，真机日志里两条都是
     * `ActivityNotFoundException`。用 `dumpsys package bin.mt.plus` 核对 MT 的
     * Activity Resolver Table 后确认：
     * - MT **没有注册 `mt://` scheme**（只注册 file / content / mtplugin），
     *   所以任何 `mt://...` 深链注定失败；
     * - 目录型 MIME（`vnd.android.document/directory`）也被拒：MT 只注册**文件**类型。
     *
     * 可行做法（同机实测）：给 MT 传**目录里的一个文件**（`content://` + `text/plain`），
     * MT 会打开该文件并自动定位到它所在目录——也就是 `/storage/emulated/0/Xime/`。
     * 因此这里用目录里的说明文件当锚点，而不是给 MT 传目录本身。
     *
     * URI 用**本应用自己的 FileProvider**，而不是 externalstorage DocumentsProvider：
     * 后者虽然格式正确，但真机实测会被系统拒——
     * `MANAGE_EXTERNAL_STORAGE` 只授予 File API，不包含 DocumentsProvider 的 URI 访问权，
     * 发送时报 “UID ... does not have permission to content://com.android.externalstorage…”）。
     * FileProvider 跑在本进程内，可以合法地把文件授权给接收方。
     *
     * @param context 用于取得 FileProvider authority
     * @return 候选 Intent 列表；锚点文件不存在（如未授权释放）时返回空列表，
     *   调用方会退回系统文件选择器。
     */
    private fun mtDeepLinks(context: Context, pkg: String): List<Intent> {
        val anchor = File(UserConfigDir.absoluteDir, UserConfigDir.README_FILE)
        if (!anchor.isFile) return emptyList()
        val fileUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", anchor)
        }.getOrNull() ?: return emptyList()
        return listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setPackage(pkg)
                setDataAndType(fileUri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private fun appLabel(pm: PackageManager, pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** 系统文档树选择器：必然可用，且直接定位到配置目录，保证列表不会为空。 */
    private fun systemPickerTargets(pm: PackageManager): List<ConfigDirTarget> {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentUri())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val handler = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).firstOrNull()
            ?: return emptyList()
        return listOf(
            ConfigDirTarget(
                label = "系统文件选择器",
                packageName = handler.activityInfo?.packageName.orEmpty(),
                intent = intent,
            ),
        )
    }

    /**
     * 启动某个打开方式。
     *
     * 返回 [LaunchOutcome]：区分「真的定位到目录」与「只把管理器打开」。
     * 两者都算 `startActivity` 成功，但对用户意义完全不同——旧版把后者也当成功，
     * 界面于是不提示任何异常，用户以为跳转好了，实际只是打开了首页
     * （真机日志里正是这条掩盖了前面两个候选的失败）。
     */
    fun launch(context: Context, target: ConfigDirTarget): LaunchOutcome {
        // 依次尝试候选 Intent，直到有一个真正启动成功。
        //
        // 为什么在启动时还要再试一遍：`resolveActivity` 的判断并不可靠——
        // 它对「声明了该 MIME 但需要额外 extra」「仅在特定 ROM 上注册」等情形会误判为不可用，
        // 而真正 startActivity 时才成功。反过来，预校验通过的也可能启动时抛异常。
        // 因此以「实际能不能启动」为准，预校验只用于排序与兜底。
        val uri = documentUri()
        val candidates = buildList {
            val pkg = target.packageName
            if (pkg.isNotEmpty()) {
                // 私有深链优先：MT管理器等只认自家 scheme，标准 SAF/VIEW 会被拒绝。
                // 可能有多种已知写法，全部加入候选依次尝试。
                addAll(mtDeepLinksIfMt(context, pkg))
                safDirectoryIntent(pkg, uri).let { add(it) }
                viewDirectoryIntent(pkg, uri).let { add(it) }
            }
            add(target.intent)
        }.distinct()

        for (intent in candidates) {
            try {
                context.startActivity(intent)
                // ACTION_MAIN 意味着「只是把应用打开了」，并未定位到目录，必须区分记录。
                //
                // 用 w 级而非 i 级：默认日志级别是「仅错误」，i 级不会落盘，
                // 用户按提示导出日志时会发现这条关键信息根本不在——等于白让用户操作一次。
                val located = intent.action != Intent.ACTION_MAIN
                FileLogger.w(
                    TAG,
                    "launch ${target.packageName} ${if (located) "LOCATED" else "OPENED-ONLY"}: " +
                        "action=${intent.action} data=${intent.data} mime=${intent.type} " +
                        "initialUri=${intent.getStringExtra(DocumentsContract.EXTRA_INITIAL_URI)} " +
                        "targetUri=$uri",
                )
                return if (located) LaunchOutcome.Located else LaunchOutcome.OpenedOnly
            } catch (t: Throwable) {
                // 该形态不被目标接受：记录后试下一个。
                // 必须走 FileLogger（而非 android.util.Log）：后者只进 logcat，
                // 用户从「设置 → 导出日志」拿不到，等于这些诊断信息不存在。
                FileLogger.w(
                    TAG,
                    "candidate failed for ${target.packageName}: action=${intent.action} " +
                        "data=${intent.data} mime=${intent.type} -> ${t.message}",
                    t,
                )
            }
        }
        // 全部失败是真正的错误，用 e 级：确保在默认「仅错误」档下也一定落盘
        FileLogger.e(TAG, "all candidates failed for ${target.packageName}; targetUri=$uri")
        return LaunchOutcome.Failed
    }
}

/** [ConfigDirOpener.launch] 的结果：区分「定位成功」「只打开应用」「完全失败」。 */
enum class LaunchOutcome {
    /** 目标应用收到了目录 URI，预期已定位到目标目录。 */
    Located,

    /** 目标应用只被打开（`ACTION_MAIN`），**没有**定位到目录。 */
    OpenedOnly,

    /** 所有候选 Intent 都启动失败。 */
    Failed,
}
