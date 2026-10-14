package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.security.MessageDigest

/**
 * 把 Rime 工作目录（私有 `files/rime/`）里**用户可编辑的文本配置**镜像到
 * `/storage/emulated/0/Xime/rime/`，让第三方编辑器（MT管理器等）能直接改方案与词库。
 *
 * ## 为什么是「镜像子集」而不是把工作目录整体搬过去
 *
 * Rime 工作目录里混着三类东西，只有第一类适合给用户改：
 *
 * 1. **用户可编辑的文本**：`*.custom.yaml`（方案补丁）、`*.dict.yaml`（词库）、
 *    `default.custom.yaml`、`symbols.yaml`、`custom_phrase.txt` —— 本类同步这些；
 * 2. **二进制与运行状态**：`*.userdb`（用户词典数据库）、`installation.yaml` ——
 *    手改会损坏数据，且 `userdb` 依赖正常文件锁语义，模拟存储上的行为并不一致；
 * 3. **编译产物**：`build/`、`logs/`、`sync/` —— 部署时整体重写，同步毫无意义。
 *
 * 另外，Rime 工作目录**必须**留在私有空间：`/storage/emulated/0/Xime` 需要
 * 「所有文件访问」权限，用户可拒绝、系统可回收，而输入法在开机后与锁屏界面就必须可用。
 * 把工作目录整体搬过去，等于「没授权键盘就打不出字」。
 *
 * ## 同步方向：三向基线比对，而不是简单覆盖
 *
 * 每个文件记录「上次同步后的内容指纹」作为基线，据此判断是谁改的：
 *
 * - 私有变了、可见没变（可见 == 基线）→ 是应用更新了它 → 覆盖可见；
 * - 可见变了、私有没变（私有 == 基线）→ 是用户改了它 → 覆盖私有；
 * - 两侧都变（都不等于基线）→ 冲突 → **保留可见**（用户的显式编辑优先），
 *   并把应用侧的版本另存为 `<文件名>.app-backup` 供用户比对合并。
 *
 * 只有「没有基线且两侧内容不同」时才直接以可见为准（视作首次同步前用户已预置文件）。
 *
 * ## 删除语义是不对称的，这是刻意的
 *
 * - **应用删除了私有文件**（卸载方案后的残留清理、删除单个方案文件）：
 *   若可见副本自上次同步未变，则一并删除；若用户改过它，则保留用户的成果并写回私有目录。
 *   没有这一条，同步会把应用刚清理掉的文件从镜像里「复活」，表现为「卸载了方案但文件还在」。
 * - **用户在可见目录里删除了文件**：一律从私有目录恢复，**不**删除私有文件。
 *   输入法是常驻应用，一次误删 `symbols.yaml` 就会让标点输入失效，
 *   而「文件自己回来」远好过「键盘坏了」。要真正删除请用内置文件管理器
 *   （设置 → 输入方案 → 打开配置目录），它操作的是同一份文件。
 */
object RimeUserFileSync {

    private const val TAG = "RimeUserFileSync"

    /** 可见目录下的镜像子目录名：`/storage/emulated/0/Xime/rime/`。 */
    const val MIRROR_DIR_NAME = "rime"

    /**
     * 基线清单文件名，存放在**私有**目录：
     * 既不会被 Rime 扫描（不在 rime/ 内），也不会给可见目录添乱。
     */
    private const val BASELINE_FILE = "rime_user_sync_baseline.txt"

    /** 冲突备份的后缀：`foo.custom.yaml.app-backup`（不满足同步后缀，不会被再次同步）。 */
    private const val CONFLICT_BACKUP_SUFFIX = ".app-backup"

    // ── 同步范围规则（纯函数，可单测）──

    /** 不参与同步的目录：编译产物、运行状态、日志、版本库。 */
    internal val EXCLUDED_DIRS = setOf("build", "sync", "logs", ".git")

    /**
     * 不参与同步的文件名。
     *
     * - `installation.yaml` / `user.yaml`：Rime 的安装与用户状态，手改无意义；
     * - `xime.yaml`：**本应用的键盘配置**，已单独释放到 `Xime/` 根目录，
     *   这里排除以免在镜像里出现同名副本让人分不清该改哪个。
     */
    internal val EXCLUDED_NAMES = setOf("installation.yaml", "user.yaml", "xime.yaml")

    /** 不参与同步的后缀：二进制数据库、索引、日志与临时文件。 */
    internal val EXCLUDED_SUFFIXES = listOf(
        ".userdb", ".userdb.txt", ".bin", ".trie", ".db", ".ldb", ".log", ".tmp", CONFLICT_BACKUP_SUFFIX,
    )

    /** 参与同步的后缀：Rime 的文本配置与词表。 */
    internal val SYNCABLE_SUFFIXES = listOf(".yaml", ".txt")

    /**
     * 判断某个相对路径是否参与同步。
     *
     * 先判排除、再判包含：顺序不能反，否则 `foo.userdb.txt` 会因为以 `.txt` 结尾
     * 而被当成可同步的用户词库（它是 `userdb` 的导出，由 Rime 重新生成）。
     */
    internal fun isSyncable(relativePath: String): Boolean {
        val path = relativePath.replace('\\', '/').trim()
        if (path.isEmpty()) return false
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        if (segments.any { it in EXCLUDED_DIRS }) return false
        val name = segments.last()
        if (name.startsWith(".")) return false
        if (name in EXCLUDED_NAMES) return false
        val lower = name.lowercase()
        if (EXCLUDED_SUFFIXES.any { lower.endsWith(it) }) return false
        return SYNCABLE_SUFFIXES.any { lower.endsWith(it) }
    }

    /** 单个文件的同步决策。 */
    internal enum class SyncAction {
        /** 两侧一致或都不存在，无需动作。 */
        NONE,

        /** 把私有版本覆盖到可见目录（首次释放，或应用更新了该文件）。 */
        PUSH_TO_VISIBLE,

        /** 把可见版本覆盖到私有目录（用户新增或修改了该文件）。 */
        PUSH_TO_PRIVATE,

        /** 两侧都改过：保留可见版本，并把私有版本另存备份。 */
        CONFLICT_KEEP_VISIBLE,

        /**
         * 私有目录已没有该文件、可见副本未被改动 → 删除可见副本。
         *
         * 用于**传播应用侧的删除**（卸载方案后 `cleanRimeDir` 清理残留、删除单个方案文件等）。
         * 没有这个动作，同步会把应用刚清理掉的文件从镜像里「复活」回去，
         * 表现为「卸载了方案但文件还在」。
         */
        DELETE_VISIBLE,
    }

    /**
     * 三向基线比对的核心决策（纯函数）。
     *
     * @param privHash 私有版本内容指纹；null 表示私有不存在
     * @param visHash 可见版本内容指纹；null 表示可见不存在
     * @param baseHash 上次同步后的基线指纹；null 表示没有基线记录
     *
     * ## 删除语义是**不对称**的，这是刻意的
     *
     * - **私有被删**（应用卸载方案 / 清理残留）：可见副本未改动时一并删除（[DELETE_VISIBLE]），
     *   否则用户的编辑比应用意图更重要，保留并写回（[PUSH_TO_PRIVATE]）。
     * - **可见被删**（用户在文件管理器里删掉）：一律从私有目录恢复（[PUSH_TO_VISIBLE]）。
     *   输入法是常驻应用，一次误删 `symbols.yaml` 就会让标点输入失效；
     *   「文件自己回来」远好过「键盘坏了」。
     */
    internal fun decideAction(privHash: String?, visHash: String?, baseHash: String?): SyncAction = when {
        privHash == null && visHash == null -> SyncAction.NONE

        // 私有没有、可见有：可能是用户在可见目录新增，也可能是应用删除了私有文件
        privHash == null -> when {
            baseHash == null -> SyncAction.PUSH_TO_PRIVATE
            // 可见副本自上次同步未变 → 是应用删的 → 同步删除可见副本
            visHash == baseHash -> SyncAction.DELETE_VISIBLE
            // 用户改过可见副本 → 保留用户的成果，写回私有
            else -> SyncAction.PUSH_TO_PRIVATE
        }

        // 可见没有、私有有：首次释放，或用户删了可见文件（按设计恢复而非传播删除）
        visHash == null -> SyncAction.PUSH_TO_VISIBLE
        privHash == visHash -> SyncAction.NONE
        // 无基线且两侧不同：以可见为准（用户显式编辑优先于应用内副本）
        baseHash == null -> SyncAction.PUSH_TO_PRIVATE
        privHash == baseHash -> SyncAction.PUSH_TO_PRIVATE
        visHash == baseHash -> SyncAction.PUSH_TO_VISIBLE
        else -> SyncAction.CONFLICT_KEEP_VISIBLE
    }

    // ── 基线清单（纯文本，便于单测；避免 org.json 在 JVM 单测里不可用）──

    /** 解析基线清单：每行 `<sha256>\t<相对路径>`。忽略空行与格式不符的行。 */
    internal fun parseBaseline(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val hash = line.substring(0, tab).trim()
            val path = line.substring(tab + 1).trim()
            if (hash.isNotEmpty() && path.isNotEmpty()) out[path] = hash
        }
        return out
    }

    /** 渲染基线清单，按路径排序，保证内容稳定可比对。 */
    internal fun renderBaseline(baseline: Map<String, String>): String =
        baseline.entries
            .sortedBy { it.key }
            .joinToString(separator = "\n", postfix = "\n") { "${it.value}\t${it.key}" }

    // ── 路径 ──

    /** 可见目录下的镜像目录 `/storage/emulated/0/Xime/rime`。 */
    fun mirrorDir(context: Context): File = File(UserConfigDir.absoluteDir, MIRROR_DIR_NAME)

    private fun baselineFile(context: Context): File = File(context.filesDir, BASELINE_FILE)

    // ── 同步 ──

    /**
     * 同步结果。字段分开列出，便于 UI 提示「改了哪些文件」以及排查权限问题。
     */
    data class SyncReport(
        /** 是否执行完成（未授权/不支持时为 false，且不产生任何写入）。 */
        val ok: Boolean,
        /** 缺少写入权限，未执行同步。 */
        val permissionRequired: Boolean = false,
        /** 平台不支持该目录方案。 */
        val unsupported: Boolean = false,
        /** 本次被写入可见目录的文件（相对路径）。 */
        val pushedToVisible: List<String> = emptyList(),
        /** 本次被写回私有目录的文件（相对路径）。 */
        val pushedToPrivate: List<String> = emptyList(),
        /** 两侧都改过、保留了可见版本的文件。 */
        val conflicts: List<String> = emptyList(),
        /** 因应用侧已删除而一并移除的可见副本。 */
        val removedFromVisible: List<String> = emptyList(),
    ) {
        /** 本次是否真的改动了文件。 */
        val changed: Boolean
            get() = pushedToVisible.isNotEmpty() ||
                pushedToPrivate.isNotEmpty() ||
                removedFromVisible.isNotEmpty()
    }

    /**
     * 执行一次双向同步。幂等：两侧一致时不做任何写入。
     *
     * 调用时机（两处，缺一不可）：
     * 1. 应用启动与授权后（见 [UserConfigDir.releaseUserConfig]）—— 把方案与词库释放到可见目录；
     * 2. **部署之前**（设置页「部署方案」）—— 把用户在可见目录里的修改写回 Rime 工作目录，
     *    否则用户改完点部署，部署读到的还是旧文件。
     */
    fun sync(context: Context): SyncReport {
        if (!UserConfigDir.isSupported) return SyncReport(ok = false, unsupported = true)
        if (!UserConfigDir.hasWritePermission(context)) {
            return SyncReport(ok = false, permissionRequired = true)
        }

        val rimeDir = SchemaManager.getRimeDir(context)
        val mirror = mirrorDir(context)
        if (!rimeDir.isDirectory && !mirror.isDirectory) {
            // 两侧都还没有内容：不创建空目录，避免给用户一个空壳
            return SyncReport(ok = true)
        }

        val baseline = readBaseline(context)
        val nextBaseline = LinkedHashMap<String, String>()
        val pushedToVisible = mutableListOf<String>()
        val pushedToPrivate = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val removedFromVisible = mutableListOf<String>()

        val relativePaths = LinkedHashSet<String>()
        collectSyncable(rimeDir, relativePaths)
        collectSyncable(mirror, relativePaths)

        for (rel in relativePaths) {
            val priv = File(rimeDir, rel)
            val vis = File(mirror, rel)
            val privHash = hashOrNull(priv)
            val visHash = hashOrNull(vis)
            val resultHash: String? = when (decideAction(privHash, visHash, baseline[rel])) {
                SyncAction.NONE -> privHash ?: visHash

                SyncAction.PUSH_TO_VISIBLE -> {
                    if (copyFile(priv, vis)) {
                        pushedToVisible += rel
                        privHash
                    } else {
                        // 复制失败：保留旧基线，下次重试
                        baseline[rel]
                    }
                }

                SyncAction.PUSH_TO_PRIVATE -> {
                    if (copyFile(vis, priv)) {
                        pushedToPrivate += rel
                        visHash
                    } else {
                        baseline[rel]
                    }
                }

                SyncAction.CONFLICT_KEEP_VISIBLE -> {
                    conflicts += rel
                    // 先把应用侧版本另存到可见目录，用户才能比对合并
                    backupAppVersion(vis, priv)
                    if (copyFile(vis, priv)) {
                        pushedToPrivate += rel
                        visHash
                    } else {
                        baseline[rel]
                    }
                }

                SyncAction.DELETE_VISIBLE -> {
                    if (runCatching { vis.delete() }.getOrDefault(false)) {
                        removedFromVisible += rel
                    }
                    // 文件已不存在，不再记入基线
                    null
                }
            }
            if (resultHash != null) nextBaseline[rel] = resultHash
        }

        // 清掉镜像里的空目录，避免清理后留下层层空壳
        pruneEmptyDirs(mirror)

        writeBaseline(context, nextBaseline)

        if (pushedToVisible.isNotEmpty() || pushedToPrivate.isNotEmpty()) {
            FileLogger.i(
                TAG,
                "同步完成：释放到可见目录 ${pushedToVisible.size} 个，写回私有目录 ${pushedToPrivate.size} 个",
            )
        }
        if (removedFromVisible.isNotEmpty()) {
            FileLogger.i(
                TAG,
                "应用侧已删除 ${removedFromVisible.size} 个文件，同步移除了对应镜像副本",
            )
        }
        conflicts.forEach {
            FileLogger.w(TAG, "同步冲突：$it 两侧都被修改，已保留可见版本并另存 .app-backup")
        }
        return SyncReport(
            ok = true,
            pushedToVisible = pushedToVisible,
            pushedToPrivate = pushedToPrivate,
            conflicts = conflicts,
            removedFromVisible = removedFromVisible,
        )
    }

    /**
     * 自底向上删除镜像目录里的空目录。
     *
     * 卸载方案会删掉整个子目录（如 `themes/xxx/`），若只删文件就会留下空壳目录，
     * 用户会以为「卸载没干净」。镜像根目录本身保留，否则用户会找不到入口。
     */
    private fun pruneEmptyDirs(root: File) {
        if (!root.isDirectory) return
        runCatching {
            root.walkBottomUp()
                .filter { it.isDirectory && it != root }
                .forEach { dir ->
                    if (dir.listFiles().isNullOrEmpty()) dir.delete()
                }
        }
    }

    /** 收集一棵目录树里所有可同步文件的相对路径（用 onEnter 剪掉排除目录，避免遍历 build/）。 */
    private fun collectSyncable(root: File, out: MutableSet<String>) {
        if (!root.isDirectory) return
        runCatching {
            root.walkTopDown()
                .onEnter { dir ->
                    dir == root || (dir.name !in EXCLUDED_DIRS && !dir.name.startsWith("."))
                }
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.relativeTo(root).path.replace('\\', '/')
                    if (isSyncable(rel)) out += rel
                }
        }.onFailure { FileLogger.w(TAG, "遍历 ${root.absolutePath} 失败：${it.message}", it) }
    }

    /** 文件内容指纹；不是文件（不存在/是目录）返回 null。 */
    private fun hashOrNull(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /** 复制文件（覆盖目标），必要时创建父目录。 */
    private fun copyFile(source: File, target: File): Boolean = runCatching {
        if (!source.isFile) return false
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return false }
        source.copyTo(target, overwrite = true)
        true
    }.getOrElse {
        FileLogger.w(TAG, "复制 ${source.name} -> ${target.absolutePath} 失败：${it.message}", it)
        false
    }

    /**
     * 冲突时把**应用侧（私有）版本**另存到可见目录，文件名加 `.app-backup` 后缀。
     *
     * 放可见目录而不是私有目录，是因为用户要能在同一个文件管理器里看到两份并手工合并；
     * 该后缀不满足 [SYNCABLE_SUFFIXES]，不会被再次同步，不会造成循环。
     */
    private fun backupAppVersion(visFile: File, privFile: File) {
        if (!privFile.isFile) return
        runCatching {
            val backup = File(visFile.parentFile, privFile.name + CONFLICT_BACKUP_SUFFIX)
            privFile.copyTo(backup, overwrite = true)
        }.onFailure { FileLogger.w(TAG, "写冲突备份失败：${it.message}", it) }
    }

    private fun readBaseline(context: Context): Map<String, String> {
        val file = baselineFile(context)
        if (!file.isFile) return emptyMap()
        return runCatching { parseBaseline(file.readText()) }.getOrDefault(emptyMap())
    }

    private fun writeBaseline(context: Context, baseline: Map<String, String>) {
        runCatching {
            baselineFile(context).writeText(renderBaseline(baseline))
        }.onFailure { FileLogger.w(TAG, "写基线清单失败：${it.message}", it) }
    }
}
