package com.kingzcheung.xime.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * 用户可见配置目录 `/storage/emulated/0/Xime/`（内部存储根下的 `Xime` 目录）。
 *
 * 背景：应用私有目录（`/data/data/<pkg>/files/rime/`）在 Android 11+ 下系统「文件」App 与
 * 第三方编辑器都无法打开，用户改配置只能依赖内置文件管理器或无线导入。本类把配置释放到
 * **内部存储根下的 `Xime/` 目录**，用户在任意文件管理器、USB/MTP 与第三方编辑器里都能直接
 * 打开编辑；设置 →「输入方案」页也提供「打开配置目录」入口（内置文件管理器）。
 *
 * 权限：内部存储根下的自定义目录不属于 MediaStore 公共集合，写入需要授权——
 * - Android 11（API 30）及以上：「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`），
 *   需用户到系统设置手动开启，跳转入口见 [allFilesAccessIntent]；
 * - Android 10 及以下：`WRITE_EXTERNAL_STORAGE` 运行时权限。
 *
 * 未授权时所有读写操作返回 false/null，调用方回退私有目录 + 内置文件管理器；
 * 设置页据 [releaseState] 的 [ReleaseState.PERMISSION_REQUIRED] 提示用户去授权。
 *
 * 目录内文件：
 * - [UNIFIED_CONFIG_FILE] `xime.yaml`：**唯一的用户配置入口**（完整快照，见 [UnifiedConfigFile]）
 * - [LEGACY_UNIFIED_CONFIG_FILE] `xime.txt`：旧文件名，仅保留兼容读取
 * - [CUSTOM_CONFIG_FILE] `xime.custom.yaml`：更早的入口，仅保留兼容读取
 * - [README_FILE]：目录说明
 */
object UserConfigDir {

    private const val TAG = "UserConfigDir"

    /** 目录名（位于内部存储根下）。 */
    const val DIR_NAME = "Xime"

    /** 配置目录：`/storage/emulated/0/Xime`。 */
    val absoluteDir: File
        get() = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    /** 展示给用户的路径（与真实绝对路径一致，便于在文件管理器里定位）。 */
    val DISPLAY_PATH: String get() = absoluteDir.absolutePath

    /**
     * 旧配置目录 `Documents/Xime`：Android 10 起曾用它免权限写入。
     * 新方案不再向此路径释放任何文件，仅在迁移后清理（见 [migrateLegacyDir]）。
     */
    val legacyDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            DIR_NAME,
        )

    /** 统一配置文件名：用户唯一的配置入口。 */
    const val UNIFIED_CONFIG_FILE = "xime.yaml"

    /** 统一配置的旧文件名：仅兼容读取，首次释放时迁移为 [UNIFIED_CONFIG_FILE]。 */
    const val LEGACY_UNIFIED_CONFIG_FILE = "xime.txt"

    /** 更早的自定义配置文件名：仅兼容读取，内容会被折叠进 [UNIFIED_CONFIG_FILE]。 */
    const val CUSTOM_CONFIG_FILE = "xime.custom.yaml"

    /** 目录说明文件名。 */
    const val README_FILE = "说明.txt"

    /**
     * 授权后必须释放到配置目录的用户可编辑文件清单。
     *
     * 用户打开 [DISPLAY_PATH] 就该看到**完整**的一套文件，不必去猜「是不是还有文件没生成」。
     * [releaseUserConfig] 每次都会补齐清单里缺失的文件，[ensureReleased] 也以清单是否齐全
     * 作为「目录就绪」的判据——这样将来新增配置文件时，老用户升级后也会被自动补上
     * （只判 `xime.yaml` 是否存在会让新增文件永远不释放）。
     */
    val releasedFiles: List<String> get() = listOf(UNIFIED_CONFIG_FILE, README_FILE)

    /** 清单内文件是否已全部就绪；未授权一律 false。 */
    fun allFilesReleased(context: Context): Boolean =
        usable(context) && releasedFiles.all { exists(context, it) }

    /** 当前系统是否支持该目录方案（API 28 起均可通过授权写入）。 */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * 释放结果。个别 ROM 会拒绝创建目录，用户也可能尚未授权——
     * 必须让 UI 能区分「没试过 / 成功 / 失败 / 待授权 / 平台不支持」，
     * 否则用户只看到一个空目录而无从判断。
     */
    enum class ReleaseState { NOT_ATTEMPTED, SUCCESS, FAILED, PERMISSION_REQUIRED, UNSUPPORTED }

    private val _releaseState = mutableStateOf(ReleaseState.NOT_ATTEMPTED)

    /** 最近一次 [ensureReleased] 的结果；Compose 可观察，用于设置页提示。 */
    val releaseState: ReleaseState get() = _releaseState.value

    /** 目录内一个条目。 */
    data class Entry(val name: String, val size: Long, val lastModified: Long)

    // ── 权限 ──

    /**
     * 是否已获得写入配置目录所需的权限：
     * API 30+ 判「所有文件访问」，API 28/29 判 `WRITE_EXTERNAL_STORAGE`。
     */
    fun hasWritePermission(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 「所有文件访问」授权页 Intent（API 30+）。
     * API 28/29 返回 null，调用方改用运行时权限请求 `WRITE_EXTERNAL_STORAGE`。
     */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }

    /** 目录当前是否可直接用 File API 读写（平台支持 + 已授权）。 */
    private fun usable(context: Context): Boolean = isSupported && hasWritePermission(context)

    // ── 文件读写（File API：授权后可直接操作任意文件名）──

    /** 目录内某个已存在的文件；未授权或不存在返回 null。 */
    private fun fileOf(context: Context, name: String): File? =
        if (!usable(context)) null else File(absoluteDir, name).takeIf { it.isFile }

    /** 目录内文件是否存在。 */
    fun exists(context: Context, name: String): Boolean = fileOf(context, name) != null

    /**
     * 读取文本配置。文件不存在或未授权返回 null（调用方继续回退私有目录/assets）。
     * 去掉 UTF-8 BOM，避免编辑器保存后 YAML 解析首键失败。
     */
    fun readText(context: Context, name: String): String? {
        val file = fileOf(context, name) ?: return null
        return try {
            file.readText().trimStart('\uFEFF')
        } catch (t: Throwable) {
            Log.w(TAG, "readText failed: $name", t)
            null
        }
    }

    /** 写入（覆盖）文本配置；目录不存在时先创建。未授权返回 false。 */
    fun writeText(context: Context, name: String, text: String): Boolean {
        if (!usable(context)) return false
        return try {
            val dir = absoluteDir
            if (!dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "cannot create config dir: ${dir.absolutePath}")
                return false
            }
            File(dir, name).writeText(text)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "writeText failed: $name", t)
            false
        }
    }

    /**
     * 读取文件的变更戳（修改时间秒, 字节数），供配置热重载判断是否需要重新解析。
     * 返回 null 表示文件不存在或未授权。
     */
    fun stamp(context: Context, name: String): Pair<Long, Long>? {
        val file = fileOf(context, name) ?: return null
        return (file.lastModified() / 1000L) to file.length()
    }

    /** 删除目录内某个文件；不存在或未授权返回 false。 */
    fun delete(context: Context, name: String): Boolean {
        val file = fileOf(context, name) ?: return false
        return try {
            file.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "delete failed: $name", t)
            false
        }
    }

    /** 列出目录内所有文件（按名称排序）；未授权返回空列表。 */
    fun list(context: Context): List<Entry> {
        if (!usable(context)) return emptyList()
        val files = absoluteDir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile }
            .map { Entry(it.name, it.length(), it.lastModified() / 1000L) }
            .sortedBy { it.name }
            .toList()
    }

    /**
     * 写入用户配置，保证「只有一个真源」：
     * - 可见目录可用且写入成功 → 以可见目录为准，并删除旧入口文件（可见目录里的
     *   `xime.custom.yaml`、旧名 `xime.txt`，以及私有目录里的两份副本）。留着旧副本会让
     *   用户改错地方：读取优先级下它们会被静默忽略。
     * - 可见目录不可用或写入失败 → 退回私有目录，避免配置丢失。
     *
     * @return true 表示至少有一处写入成功。
     */
    fun writeCustomConfig(context: Context, text: String): Boolean {
        val visible = writeText(context, UNIFIED_CONFIG_FILE, text)
        delete(context, CUSTOM_CONFIG_FILE)
        delete(context, LEGACY_UNIFIED_CONFIG_FILE)
        val privateLegacy = File(context.filesDir, "rime/$CUSTOM_CONFIG_FILE")
        val privateUnified = File(context.filesDir, "rime/$UNIFIED_CONFIG_FILE")
        if (visible) {
            runCatching { privateLegacy.delete() }
            runCatching { privateUnified.delete() }
            return true
        }
        return try {
            privateUnified.parentFile?.mkdirs()
            privateUnified.writeText(text)
            Log.i(TAG, "visible dir unavailable, wrote $UNIFIED_CONFIG_FILE to private dir")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "writeCustomConfig failed", t)
            false
        }
    }

    /**
     * 从旧目录 `Documents/Xime` 迁移并清理：
     *
     * 新目录还没有 [UNIFIED_CONFIG_FILE] 时，把旧目录里的统一配置（`xime.yaml`，或更早的
     * `xime.txt`）复制过来，避免升级用户丢失已改好的配置；迁移成功后再删除旧目录，
     * 防止用户改错地方（旧目录的文件已不再被读取）。
     *
     * 迁移失败时**保留**旧目录，宁可不清理也不丢配置。幂等，每次启动调用都安全。
     *
     * @return 旧目录是否已清理干净（不存在也算清理完成）。
     */
    fun migrateLegacyDir(context: Context): Boolean {
        if (!usable(context)) return false
        return try {
            val legacy = legacyDir
            if (!legacy.isDirectory) return true
            val migrated = if (exists(context, UNIFIED_CONFIG_FILE)) {
                true
            } else {
                val old = listOf(UNIFIED_CONFIG_FILE, LEGACY_UNIFIED_CONFIG_FILE)
                    .map { File(legacy, it) }
                    .firstOrNull { it.isFile }
                when {
                    old == null -> true
                    else -> {
                        val text = runCatching { old.readText().trimStart('\uFEFF') }.getOrNull()
                        val ok = !text.isNullOrBlank() &&
                            writeText(context, UNIFIED_CONFIG_FILE, text)
                        if (ok) Log.i(TAG, "migrated ${old.name} -> $UNIFIED_CONFIG_FILE")
                        ok
                    }
                }
            }
            if (!migrated) {
                Log.w(TAG, "skip legacy dir cleanup: migration failed")
                return false
            }
            val leftovers = legacy.listFiles().orEmpty()
            var removed = 0
            for (f in leftovers) {
                if (runCatching { f.delete() }.getOrDefault(false)) removed++
            }
            val dirDeleted = runCatching { legacy.delete() }.getOrDefault(false)
            Log.i(TAG, "legacy dir cleanup: files=$removed, dirDeleted=$dirDeleted")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "migrateLegacyDir failed", t)
            false
        }
    }

    /**
     * 释放目录说明文件并刷新 [releaseState]。
     *
     * 只负责说明文件与状态判定；配置内容由 [UnifiedConfigFile.ensureGenerated] 生成，
     * 调用方需先调它再调本方法（本方法内部也会补一次旧目录迁移，保证迁移先于生成判断）。
     *
     * @return 目录最终是否就绪（配置与说明都在）；未授权或写入被系统拒绝返回 false。
     */
    fun ensureReleased(context: Context): Boolean {
        if (!isSupported) {
            _releaseState.value = ReleaseState.UNSUPPORTED
            return false
        }
        if (!hasWritePermission(context)) {
            _releaseState.value = ReleaseState.PERMISSION_REQUIRED
            return false
        }
        migrateLegacyDir(context)
        if (!exists(context, README_FILE)) {
            writeText(context, README_FILE, README_TEXT)
        }
        // 判定「目录最终是否就绪」，而不是「本次是否写了文件」：否则第二次启动时两个文件
        // 都已存在、什么都没写，会被误判为失败。
        val ready = allFilesReleased(context)
        _releaseState.value = if (ready) ReleaseState.SUCCESS else ReleaseState.FAILED
        if (!ready) {
            Log.w(TAG, "user config dir NOT ready at $DISPLAY_PATH (write rejected?)")
        }
        return ready
    }

    /**
     * 释放用户配置的完整流程：迁移旧目录 → 生成 `xime.yaml` → 写说明并刷新 [releaseState]。
     *
     * 顺序要紧：先生成 `xime.yaml` 会让 [migrateLegacyDir] 误判为「新目录已有配置」而跳过迁移，
     * 升级用户已改好的配置就丢了。
     *
     * 启动时调用；用户从系统授权页返回后也调用一次，这样「刚授权完」就能立刻看到文件，
     * 不必重启应用——否则用户会以为授权没生效（这正是「授权后目录里还是空的」的原因）。
     *
     * @return 目录最终是否就绪。
     */
    fun releaseUserConfig(context: Context): Boolean {
        migrateLegacyDir(context)
        UnifiedConfigFile.ensureGenerated(context)
        val ready = ensureReleased(context)
        // 把用户可编辑的 Rime 配置与词库镜像到 `/storage/emulated/0/Xime/rime/`，
        // 让第三方编辑器（MT管理器等）也能改方案与词库。未授权时内部直接返回，不做任何写入。
        //
        // 放在这里而不是引擎初始化里：引擎初始化有多个触发时机（含输入法服务重启），
        // 而「目录就绪」这件事只应该在启动与授权后各做一次。
        RimeUserFileSync.sync(context)
        return ready
    }

    private val README_TEXT: String = """
        Xime 输入法 · 用户配置目录
        ================================

        这个目录里的文件可以直接用系统「文件」App、电脑 USB 或任意文本编辑器修改。
        改完后在键盘工具栏菜单里点「部署方案」即可生效（会重新读取本目录并重载皮肤），无需重装。

        文件说明
        --------
        $UNIFIED_CONFIG_FILE
            全部键盘配置。这是一份**完整快照**：应用内置的默认值已经全量写在这里，
            你看到的每一项都是真实生效的值，不必去猜默认值是多少。

            可以改按键布局、手势（滑动/长按）、配色方案、字体、九键与笔画键位等。
            只改你需要改的地方即可；把某个键删掉就会退回应用内置默认值。

        $README_FILE
            本文件，可以随意删除，删掉后下次启动会重新生成。

        ${RimeUserFileSync.MIRROR_DIR_NAME}/
            输入方案本身：方案配置（default.custom.yaml、*.custom.yaml、*.schema.yaml）、
            词库（*.dict.yaml，如 pinyin_simp、wubi86 等）、标点表（symbols.yaml）
            与自定义短语（custom_phrase.txt）。

            这些文件与输入法内部的工作目录**双向同步**：在这里改完，回输入法点一次
            「部署方案」就生效，不需要重装。第一次生成时会自动补齐，文件很多是正常的。

            注意两点：
            1. 在这里**删除**文件不会真的删掉它——下次启动会从内部目录恢复回来。
               这是为了避免误删导致输入法不可用（例如删掉 symbols.yaml 会让标点输入失效）。
               确实要删除，请用输入法内置的文件管理器（设置 → 输入方案 → 打开配置目录）。
            2. 编译产物、用户词典数据库（*.userdb）等不会出现在这里，它们由输入法自动维护，
               手改会损坏数据。

        修改建议
        --------
        - 这是 YAML 格式：缩进必须用空格（不要用 Tab），冒号后面要有一个空格。
        - 改坏了不会导致输入法崩溃：解析失败时会自动回退到内置默认配置。
          实在改不回来，直接删掉 $UNIFIED_CONFIG_FILE 再重启即可恢复默认。
    """.trimIndent()
}
