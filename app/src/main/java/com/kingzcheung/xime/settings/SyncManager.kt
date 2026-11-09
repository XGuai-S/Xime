package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import com.kingzcheung.xime.rime.RimeEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * rime 原生用户词典同步：
 *
 * - sync 目录固定在引擎用户目录下（filesDir/rime/sync/<installation_id> 下的 .userdb.txt），
 *   与桌面端 rime 的 sync 目录同构；快照为 TSV 文本，librime 以时间戳合并进 userdb，
 *   规避对 leveldb 文件做整体覆盖的一致性风险。
 * - 数据不出私有目录，无外部存储权限：导入/导出走文件选择器与 Downloads，
 *   远端走备份插件通道（快照包固定名 rime-sync-<installation_id>.zip，
 *   与云备份的配置包在同一远端目录下按前缀隔离，互不感知）。
 *
 * 与云备份（[BackupManager]）的语义分工：云备份=时间点覆盖式灾难恢复（全量），
 * 词库同步=增量合并（仅用户词典）。
 */
object SyncManager {

    private const val TAG = "SyncManager"

    /** 远端快照包文件名前缀：与配置备份条目在远端列表中区分 */
    const val REMOTE_SYNC_PREFIX = "rime-sync-"

    private const val IMPORT_DIR_PREFIX = "imported"

    // ---------- installation id ----------

    /**
     * 稳定 installation id 核心（纯 JVM 便于单测）：yaml 缺失或 id 不一致时
     * 以 [stableId] 重写最小集（仅 installation_id，其余字段由 librime 的
     * installation_update 维护并保留该 id）。
     * 场景：部署会删除 installation.yaml；云备份恢复可能带回旧 id 的文件。
     */
    internal fun ensureInstallationFile(yamlFile: File, stableId: String): String {
        val currentId = if (yamlFile.exists()) {
            yamlFile.readLines().firstOrNull { it.trimStart().startsWith("installation_id:") }
                ?.substringAfter(':')?.trim()?.trim('"', '\'')
        } else null
        if (currentId == stableId) return stableId
        yamlFile.parentFile?.mkdirs()
        yamlFile.writeText("installation_id: \"$stableId\"\n")
        return stableId
    }

    fun ensureInstallationYaml(context: Context): String =
        ensureInstallationFile(
            File(File(context.filesDir, "rime"), "installation.yaml"),
            SettingsPreferences.getRimeInstallationId(context)
        )

    // ---------- 同步 ----------

    /**
     * 立即同步：合并 sync 目录下已有快照 + 导出本机快照。须在引擎所在进程调用。
     *
     * 注意：librime 的 `Synchronize` 只处理 `GetUserDictList`（本机已有 `<词典名>.userdb`
     * 目录）列出的词典——换机/清数据后本机一个用户词库都没有，快照会被**静默跳过**
     * 且 `sync_user_data()` 仍返回成功。这类场景必须用 [mergeSnapshots] 逐个直接合并。
     */
    fun syncNow(context: Context): Result<Unit> {
        ensureInstallationYaml(context)
        if (!RimeEngine.isInitialized()) {
            return Result.failure(IllegalStateException("输入法引擎尚未初始化，请先在任意输入框唤起键盘一次"))
        }
        return if (RimeEngine.getInstance().syncUserData()) {
            SettingsPreferences.setLastRimeSyncAt(context, System.currentTimeMillis())
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("同步未完成，详见应用日志"))
        }
    }

    /** 递归收集 [dir] 下的词库快照文件（`*.userdb.txt`），按路径排序。纯函数。 */
    internal fun snapshotFilesUnder(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(SNAPSHOT_EXTENSION) }
            .sortedBy { it.absolutePath }
            .toList()
    }

    /** rime 目录下 sync 目录里的全部快照文件（恢复/补偿合并的输入）。 */
    fun snapshotFiles(context: Context): List<File> =
        snapshotFilesUnder(File(File(context.filesDir, "rime"), "sync"))

    /**
     * 逐个合并快照文件（不依赖 sync 目录扫描，纯引擎操作，便于单测）。
     *
     * librime 的 `Synchronize` 只合并本机已有 `<词典名>.userdb` 的词典，
     * 换机/清数据恢复时快照会被静默跳过；这里按文件调 [RimeEngine.restoreUserDictSnapshot]
     * （目标库名取自快照 `#@/db_name` 元数据，不存在则创建）。
     *
     * @return 成功合并的文件数；引擎未就绪返回 failure
     */
    internal fun mergeSnapshotFiles(files: List<File>): Result<Int> {
        if (!RimeEngine.isInitialized()) {
            return Result.failure(IllegalStateException("输入法引擎尚未初始化，请先在任意输入框唤起键盘一次"))
        }
        var merged = 0
        for (file in files) {
            if (RimeEngine.getInstance().restoreUserDictSnapshot(file.absolutePath)) {
                merged++
            } else {
                // 合并失败的真机常见原因：目标词库被同进程的其他持有者占用
                // （如 t9_digit.userdb 被 T9 数字词库模块长期 Open），leveldb 拿不到锁
                Log.w(TAG, "mergeSnapshots: 快照合并失败：${file.absolutePath}")
            }
        }
        return Result.success(merged)
    }

    /**
     * 把快照文件合并进用户词库，随后 [syncNow] 把本机快照重新导出（含刚并入的词条）。
     * @return 成功合并的文件数；引擎未就绪返回 failure
     */
    fun mergeSnapshots(context: Context, files: List<File>): Result<Int> {
        val merged = mergeSnapshotFiles(files)
        if (merged.isSuccess) syncNow(context)
        return merged
    }

    /** 合并 sync 目录下的全部快照（恢复备份时的 pending 补偿入口）。 */
    fun mergePendingSnapshots(context: Context): Result<Int> =
        mergeSnapshots(context, snapshotFiles(context))

    // ---------- 打包 / 解包（纯 JVM，单测锚定） ----------

    /**
     * 打包 sync 目录：包内条目为 rime 目录相对路径（sync/...）。
     *
     * **只打包自造词快照 `*.userdb.txt`**：librime 的 `sync_user_data` 会把用户目录下
     * 所有顶层 `.yaml`/`.txt`（含 20MB+ 方案词典）镜像进 `sync/<user_id>/`
     * （`backup_config_files` 任务），而那批镜像 librime **只写不读**
     * （`UserDictManager::Synchronize` 只找 `<dict>.userdb.txt`）。把它们当词库快照
     * 推到云上既不是词库、也不会被读回，纯死重量；配置与方案由备份功能两档显式承担。
     *
     * @return zip 字节流；sync 目录不存在或没有快照时返回 null
     */
    internal fun packSyncDir(rimeDir: File): ByteArray? {
        val bos = ByteArrayOutputStream()
        return if (packSyncDirToStream(rimeDir, bos) == 0) null else bos.toByteArray()
    }

    /**
     * 流式打包 sync 目录到文件（远端推送用：包体不驻留内存）。
     * @return 写入条目数；0 表示没有快照（文件已删除）
     */
    internal fun packSyncDirToFile(rimeDir: File, dest: File): Int {
        dest.parentFile?.mkdirs()
        val count = dest.outputStream().use { out ->
            packSyncDirToStream(rimeDir, BufferedOutputStream(out))
        }
        if (count == 0) dest.delete()
        return count
    }

    private fun packSyncDirToStream(rimeDir: File, out: java.io.OutputStream): Int {
        val syncDir = File(rimeDir, "sync")
        if (!syncDir.isDirectory) return 0
        var count = 0
        ZipOutputStream(out).use { zos ->
            syncDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(SNAPSHOT_EXTENSION) }
                // 旧版本把导入暂存放在 sync/imported/ 下：那是本机临时副本、不是设备快照，
                // 继续打包只会让包越传越大、目录越套越深（历史遗留，新版暂存在 cacheDir）。
                .filter {
                    !it.relativeTo(syncDir).path.replace('\\', '/')
                        .startsWith("$IMPORT_DIR_PREFIX/")
                }
                .forEach { f ->
                    zos.putNextEntry(ZipEntry(f.relativeTo(rimeDir).path.replace('\\', '/')))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
        }
        return count
    }

    /**
     * 解包快照 zip 到 [baseDir]：条目路径可为 "sync/<id>/..."、"<id>/..."，也会遇到
     * 历史包里的层层包装（"sync/sync/..."、"imported/<uuid>/..."）——统一规范化后落盘。
     * 只收词库快照 `*.userdb.txt`，其余条目（配置镜像、包装目录）不落盘。
     * 含路径穿越/逃逸 baseDir 条目的包整体抛出 [SecurityException]。
     * @return 落盘文件数
     */
    internal fun unpackArchive(baseDir: File, bytes: ByteArray): Int =
        unpackArchiveStream(baseDir, ByteArrayInputStream(bytes))

    /** 文件入口（远端拉取的快照包直接流式解包，不读进内存）。 */
    internal fun unpackArchive(baseDir: File, archive: File): Int =
        unpackArchiveStream(baseDir, archive.inputStream())

    /**
     * 规范化包内条目路径：循环剥掉 `sync/` 前缀与 `imported/<uuid>/` 暂存包装，直到稳定。
     *
     * 只剥一层会让每次导入再多套一层（`imported/u2/imported/u1/...`），历史上正是这么套娃的。
     * 纯函数，单测锚定。
     */
    internal fun normalizeEntryPath(name: String): String {
        var rel = name.replace('\\', '/').trimStart('/')
        while (true) {
            rel = when {
                rel.startsWith("sync/") -> rel.removePrefix("sync/")
                // 暂存目录包装：imported/<uuid>/... → 两段一起丢
                rel.startsWith("$IMPORT_DIR_PREFIX/") ->
                    rel.removePrefix("$IMPORT_DIR_PREFIX/").substringAfter('/', "")
                else -> break
            }
        }
        return rel
    }

    private fun unpackArchiveStream(baseDir: File, rawInput: java.io.InputStream): Int {
        val canonicalBase = baseDir.canonicalPath + File.separator
        var count = 0
        ZipInputStream(rawInput).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val rel = normalizeEntryPath(entry.name)
                if (rel.isEmpty()) continue
                val target = File(baseDir, rel)
                if (!target.canonicalPath.startsWith(canonicalBase)) {
                    throw SecurityException("路径穿越: ${entry.name}")
                }
                // 只落盘词库快照：其它条目既不参与合并，也不该进 sync 目录被再次打包
                if (!rel.endsWith(SNAPSHOT_EXTENSION)) continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zis.copyTo(it) }
                count++
            }
        }
        return count
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    // ---------- 导入格式识别（纯函数，单测锚定） ----------

    /**
     * 文件选择器里的三种合法输入。
     *
     * rime 生态里"导出"和"备份"是**两种不同格式**，列序不同、互不通用：
     * - [SNAPSHOT]（用户词典「备份」/其他设备 sync 产物）：`# Rime user dictionary` 头，
     *   行 = `码 + 空格 \t 词 \t c=<次数> d=<权重> t=<tick>`，只被快照解析器（UniformRestore）读
     * - [CODE_TABLE]（用户词典「导出文本码表」/本机用户词典页导出）：`# Rime user dictionary export` 头，
     *   行 = `词 \t 码 \t 频率`，只被码表导入器（UserDictImporter）读
     * 二者混用不会报错、只会把码与词写反 —— 必须按格式分流。
     */
    internal enum class SnapshotFileKind { ZIP, SNAPSHOT, CODE_TABLE, UNKNOWN }

    /** librime 快照扩展名（`UserDb::snapshot_extension()` = `.userdb.txt`）。 */
    internal const val SNAPSHOT_EXTENSION = ".userdb.txt"

    private const val SNAPSHOT_HEADER = "# Rime user dictionary"
    private const val CODE_TABLE_HEADER = "# Rime user dictionary export"

    /** 解析 TSV 头里的 `#@/db_name\t<词典名>`（快照与码表都写这一行）。 */
    internal fun metaDbName(head: String): String? = head.lineSequence()
        .firstOrNull { it.startsWith("#@/db_name\t") }
        ?.substringAfter('\t')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** 读取文件头部文本（识别格式用，最多 8KB；含空字节视为二进制）。 */
    private fun headText(bytes: ByteArray): String? {
        val head = bytes.take(8192).toByteArray().toString(Charsets.UTF_8)
        return if (head.contains('\u0000')) null else head
    }

    internal fun classifySnapshotFile(fileName: String, bytes: ByteArray): SnapshotFileKind {
        if (isZip(bytes)) return SnapshotFileKind.ZIP
        val head = headText(bytes) ?: return SnapshotFileKind.UNKNOWN
        val firstLine = head.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: return SnapshotFileKind.UNKNOWN
        if (!firstLine.startsWith("#")) {
            // 无头码表：仍按 <词典名>.txt 认（用户手改过文件）
            return if (fileName.endsWith(".txt")) SnapshotFileKind.CODE_TABLE else SnapshotFileKind.UNKNOWN
        }
        if (firstLine.startsWith(CODE_TABLE_HEADER)) return SnapshotFileKind.CODE_TABLE
        // `# Rime user dictionary`，更稳的判据是元数据 `#@/db_type userdb`
        if (firstLine == SNAPSHOT_HEADER || head.contains("#@/db_type\tuserdb")) {
            return SnapshotFileKind.SNAPSHOT
        }
        return SnapshotFileKind.UNKNOWN
    }

    /**
     * 快照落盘文件名：优先取 `#@/db_name` 元数据（用户重命名过也能归位），
     * 退回原文件名；非 `<词典名>.userdb.txt` 形态无法被 librime 合并，返回 null。
     */
    internal fun snapshotTargetName(fileName: String, bytes: ByteArray): String? {
        val head = headText(bytes) ?: return null
        val dict = metaDbName(head) ?: fileName.removeSuffix(SNAPSHOT_EXTENSION).takeIf {
            fileName.endsWith(SNAPSHOT_EXTENSION)
        } ?: return null
        if (dict.isBlank() || dict.contains('/') || dict.contains('\\') || dict == "." || dict == "..") return null
        return dict + SNAPSHOT_EXTENSION
    }

    /** 码表目标词库名：优先 `#@/db_name`，退回 `<词典名>.txt` 的文件名。 */
    internal fun codeTableDictName(fileName: String, bytes: ByteArray): String? {
        val head = headText(bytes)
        val fromMeta = head?.let { metaDbName(it) }
        val dict = fromMeta ?: fileName.removeSuffix(".txt").takeIf { fileName.endsWith(".txt") }
        ?: return null
        if (dict.isBlank() || dict.contains('/') || dict.contains('\\') || dict == "." || dict == "..") return null
        return dict
    }

    /** 导入结果（快照文件数与直接入库的词条数分开报，UI 文案据此区分）。 */
    data class SnapshotImportResult(
        val snapshotFiles: Int,
        val importedEntries: Int,
        val merged: Boolean
    )

    // ---------- 导入 ----------

    /**
     * 导入快照/码表（文件选择器）：
     * - **快照包 zip**（`sync/<id>/...` 或 `<id>/...`，历史包可能层层包装）→ 规范化条目路径
     *   解到 cacheDir 的暂存目录，由 [mergeSnapshots] 逐个直接合并（用完即删，不进 sync 树）
     * - **快照文本** `<词典名>.userdb.txt`（用户词典备份、其他设备 sync 产物）→ 同上，
     *   文件名按 `#@/db_name` 元数据归位
     * - **词条码表** `<词典名>.txt`（用户词典导出、本机用户词典页导出）→ 按词库名直接
     *   走 `UserDictManager::Import` 合并入库（不经过 sync）
     *
     * 三种格式列序不同，识别错了会静默把码与词写反，故无法识别时明确失败。
     */
    fun importSnapshots(context: Context, uris: List<Uri>): Result<SnapshotImportResult> {
        if (uris.isEmpty()) return Result.failure(IllegalArgumentException("未选择文件"))
        // 暂存目录放 cacheDir：绝不能落在 rime/sync 里，否则导入内容会被下次同步一起打包，
        // 且每次导入都会在自己的上一层再套一层 imported/<uuid>/（目录无限套娃）。
        val targetDir = File(File(context.cacheDir, "sync-import"), UUID.randomUUID().toString())
        return try {
            targetDir.mkdirs()
            var snapshotFiles = 0
            var importedEntries = 0
            for (uri in uris) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return Result.failure(IllegalStateException("无法读取所选文件"))
                val raw = queryDisplayName(context, uri) ?: "snapshot.userdb.txt"
                val safeName = raw.substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_")
                when (classifySnapshotFile(safeName, bytes)) {
                    SnapshotFileKind.ZIP -> snapshotFiles += unpackArchive(targetDir, bytes)

                    SnapshotFileKind.SNAPSHOT -> {
                        val target = snapshotTargetName(safeName, bytes)
                            ?: return Result.failure(
                                IllegalArgumentException(
                                    "「$safeName」不是可识别的词库快照（应为 <词典名>.userdb.txt）"
                                )
                            )
                        File(targetDir, target).writeBytes(bytes)
                        snapshotFiles++
                    }

                    SnapshotFileKind.CODE_TABLE -> {
                        val dict = codeTableDictName(safeName, bytes)
                            ?: return Result.failure(
                                IllegalArgumentException("「$safeName」没能识别出目标词库，请重命名为 <词典名>.txt")
                            )
                        if (!com.kingzcheung.xime.rime.RimeEngine.isInitialized()) {
                            return Result.failure(
                                IllegalStateException("导入词条码表需要引擎就绪，请先唤起键盘一次")
                            )
                        }
                        val count = UserDictIoManager.importFrom(context, dict, uri).getOrElse { e ->
                            return Result.failure(
                                IllegalArgumentException(
                                    "「$safeName」是词条码表，但导入词库「$dict」失败：${e.message}"
                                )
                            )
                        }
                        importedEntries += count
                    }

                    SnapshotFileKind.UNKNOWN -> return Result.failure(
                        IllegalArgumentException(
                            "无法识别「$safeName」：支持快照包 zip、<词典名>.userdb.txt 词库快照、" +
                                "<词典名>.txt 词条码表"
                        )
                    )
                }
            }
            if (snapshotFiles == 0 && importedEntries == 0) {
                return Result.failure(IllegalArgumentException("所选文件中没有可导入的内容"))
            }
            // 快照逐个直接合并（本机还没有该用户词库时也能建库并合并，见 mergeSnapshots）；
            // 码表已在 Import 里直接入库
            val merged = if (snapshotFiles > 0) {
                mergeSnapshots(context, snapshotFilesUnder(targetDir)).getOrElse { return Result.failure(it) }
                true
            } else false
            Result.success(SnapshotImportResult(snapshotFiles, importedEntries, merged))
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // 快照已逐个合并进用户词库（mergeSnapshots 内是同步 JNI），暂存目录用完即删
            targetDir.deleteRecursively()
        }
    }

    // ---------- 导出 ----------

    /** 将 sync 目录打包 zip 保存到 Downloads，返回文件名。 */
    fun exportToDownloads(context: Context): Result<String> {
        val rimeDir = File(context.filesDir, "rime")
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val fileName = "Xime词库快照-$dateStr.zip"
        // 先流式打包到 cacheDir（大目录不驻留内存），再搬运到 Downloads
        val tempZip = File(context.cacheDir, fileName)
        if (packSyncDirToFile(rimeDir, tempZip) == 0) {
            return Result.failure(IllegalStateException("sync 目录为空，请先执行一次同步"))
        }
        return try {
            if (RimeExportManager.saveSyncArchive(context, fileName, tempZip)) Result.success(fileName)
            else Result.failure(IllegalStateException("保存到下载目录失败"))
        } finally {
            tempZip.delete()
        }
    }

    // ---------- 远端（备份插件通道） ----------

    internal fun isRemoteSyncEntry(name: String): Boolean = name.startsWith(REMOTE_SYNC_PREFIX)

    /** 远端快照条目（已按前缀过滤；获取列表失败返回 null）。 */
    suspend fun listRemoteSnapshots(plugin: BackupPlugin): List<RemoteBackupEntry>? =
        plugin.listBackups()?.filter { isRemoteSyncEntry(it.name) }

    /**
     * 远端同步：本地同步 → 拉取其他设备的快照包并解包合并 → 再次本地同步 →
     * 推送本机包（固定名覆盖，每台设备一份）。须在引擎所在进程调用。
     * @return 拉取合并的远端设备包数
     */
    suspend fun remoteSync(context: Context, plugin: BackupPlugin): Result<Int> {
        val rimeDir = File(context.filesDir, "rime")
        syncNow(context).getOrElse { return Result.failure(it) }

        val entries = plugin.listBackups()
            ?: return Result.failure(IllegalStateException("获取远端列表失败"))
        val myName = "$REMOTE_SYNC_PREFIX${SettingsPreferences.getRimeInstallationId(context)}.zip"
        val others = entries.filter { isRemoteSyncEntry(it.name) && it.name != myName }

        var pulled = 0
        for (entry in others) {
            val download = plugin.pullBackup(entry.id) ?: continue
            try {
                unpackArchive(File(rimeDir, "sync"), download.file)
                pulled++
            } finally {
                plugin.releaseBackup(download.blobId)
            }
        }
        if (pulled > 0) {
            // 逐个直接合并（不要求本机已有对应 .userdb：换机后本机一个用户词库都没有时，
            // librime 的 Synchronize 会把新设备的快照整个跳过），随后重导本机快照
            mergePendingSnapshots(context).getOrElse { return Result.failure(it) }
        }

        // 推送本机包：流式打包到 cacheDir（包体不进内存），推完即删
        val snapshot = File(context.cacheDir, myName)
        if (packSyncDirToFile(rimeDir, snapshot) == 0) {
            return Result.failure(IllegalStateException("本地快照为空"))
        }
        val push = try {
            plugin.pushBackup(myName, snapshot)
        } finally {
            snapshot.delete()
        }
        if (!push.ok) {
            return Result.failure(IllegalStateException(push.message ?: "上传快照失败"))
        }
        return Result.success(pulled)
    }

    /** 删除远端本机快照包（解绑设备时使用）。 */
    suspend fun deleteRemoteSnapshot(context: Context, plugin: BackupPlugin): Boolean {
        val myName = "$REMOTE_SYNC_PREFIX${SettingsPreferences.getRimeInstallationId(context)}.zip"
        val entries = plugin.listBackups() ?: return false
        val mine = entries.firstOrNull { it.name == myName } ?: return true
        return plugin.deleteBackup(mine.id)
    }
}
