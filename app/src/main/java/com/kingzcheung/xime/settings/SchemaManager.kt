package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

data class SchemaMeta(
    val schemaId: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = ""
)

/**
 * 方案中 `switches` 定义的一个开关项。
 * - [name] 非空时表示布尔开关（如 ascii_mode / full_shape / ascii_punct）。
 * - [options] 非空时表示多选一开关（如简繁切换，同一时刻只有一个 option 为 true）。
 * - [abbrev] 自定义缩写（可能每个状态一个），供菜单栏展示用；为空时取 states 首字符。
 */
data class SchemaSwitch(
    val name: String = "",
    val options: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    val abbrev: List<String> = emptyList(),
)

@Serializable
internal data class SchemaYaml(val schema: SchemaEntry)

@Serializable
internal data class SchemaEntry(
    @SerialName("schema_id") val schemaId: String = "",
    val name: String = "",
    val version: String = "",
    val description: String? = null,
)

object SchemaManager {
    private const val TAG = "SchemaManager"

    /**
     * `dictionary: <name>` 行解析正则（文件级常量）。
     *
     * 旧实现在 `for (raw in schemaFile.readLines())` 循环体内写 `Regex(...)`，
     * 等于**每行编译一次 Pattern**。方案文件常有数百行，而 [getMainDictName] /
     * [getReverseLookupDictName] 会在部署前校验、getSchemaIssues 与设置页列表中
     * 反复调用，单次可达几十上百毫秒。
     *
     * 这个形态锚定行首空白，用于 translator: / reverse_lookup: 段内逐行扫描。
     */
    private val DICT_NAME_RE = Regex("""^\s+dictionary\s*:\s*['"]?(\w[\w-]*)['"]?""")

    /**
     * 全文范围的 dictionary 名匹配（不锚行首），供 [getReferencedDictName] 粗筛。
     *
     * 注意 `reverse_lookup.dictionary` 同样包含 "dictionary:"，
     * 该函数因此可能把反查词典当成主词典；精确判定以 [getMainDictName] 为准
     * （它先定位 translator: 段再逐行扫描）。
     */
    private val DICT_NAME_ANYWHERE_RE = Regex("""dictionary\s*:\s*['"]?(\w[\w-]*)['"]?""")

    private const val CUSTOM_YAML = "default.custom.yaml"
    internal val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, anchorsAndAliases = com.charleskorn.kaml.AnchorsAndAliases.Permitted(maxAliasCount = UInt.MAX_VALUE)))

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getRimeDir(context: Context): File =
        File(context.filesDir, "rime")

    /**
     * 保证方案目录布局就绪：把平铺方案收进 `rime/schemes/<方案>/`，再把 schemes 镜像回顶层。
     *
     * 在 rime 初始化链路里调用（引擎启动**之前**）。做三件事：
     * 1. **首次迁移**：把顶层方案文件搬进 schemes/（幂等，见 [migrateLayoutToSchemes]）
     * 2. **补建**：备份恢复后顶层有文件但 schemes/ 是空的 → 从顶层重建
     * 3. **镜像**：把 schemes/ 里的文件链接回顶层，让 librime 能扫到
     *
     * 任何一步失败都只记日志、不阻断启动——布局是"整理"，方案本身还在，
     * 退回平铺状态也能正常输入。
     */
    fun ensureSchemeLayout(context: Context, rimeDir: File = getRimeDir(context)) {
        if (!rimeDir.isDirectory) return
        try {
            // 0) 内置方案缺失时从 assets 补齐。
            //
            // 这是"保险"，不是常规路径：历史上 3.4.30 的「删除未启用方案文件夹」逻辑
            // 把 english/numbers/wubi98/wubi86_pinyin/wubi86_trad/wubi86_trad_pinyin
            // 六个内置方案的文件从设备上删掉了，导致它们永久消失、设置页里再也看不到。
            // 用户只能从 sync/ 备份手动捞回来。
            //
            // 判据：assets 里有、但设备上既不在顶层也不在任何方案文件夹里的内置方案。
            // 只补内置的（用户从市场装的方案不在 assets 里，天然不受影响）。
            runCatching { restoreMissingBuiltinSchemes(context, rimeDir) }
                .onFailure { FileLogger.w(TAG, "补齐缺失内置方案失败", it) }

            val enabled = getEnabledSchemas(context)

            // 1) 先清幽灵目录：schemes/ 里已经没有对应顶层方案的残留。
            //    必须早于迁移——残留目录里的 schema 会被 resolveOwnership 当成
            //    现存方案，从而被"复活"并继续镜像到根目录。
            runCatching { SchemaLayout.removeGhostSchemeFolders(rimeDir, enabled) }
                .onFailure { FileLogger.w(TAG, "清理幽灵方案目录失败", it) }

            // 2) 增量补齐。每次都跑，而不是"schemes/ 存在就跳过"。
            //    曾经写成 `if (!schemes.exists()) migrate()`，结果升级后 schemes/ 已由
            //    旧版本建好，新增的方案配置/词库**永远补不进去**
            //    （线上实测：目录里只有旧的 schema+dict，新加的 .custom.yaml 一直缺）。
            //    migrateLayoutToSchemes 是增量的（目标存在且大小相同就跳过），
            //    每次跑不会重复写文件，代价只是一次目录遍历。
            migrateLayoutToSchemes(rimeDir)

            // 3) 同步已启用方案的文件夹：只有启用的方案有文件夹（用户要求），
            //    且每个文件夹包含它引用的**全部**配置与词库（硬链接，不占多份空间）。
            if (enabled.isEmpty()) {
                // 一个都没启用（首次启动/配置为空）：全量镜像，至少让默认方案可用
                SchemaLayout.mirrorToRoot(rimeDir)
            } else {
                // 3a) 归档未启用方案的文件：移进各自的 schemes/ 文件夹，顶层保持干净。
                //     必须在 syncEnabledSchemeFolders **之前**：
                //     先归档让顶层只剩启用方案的文件，再让 sync 统一建文件夹、
                //     补全依赖闭包。顺序反了会导致"刚建好又被归档"的来回搬运。
                runCatching { SchemaLayout.archiveDisabledSchemeFiles(rimeDir, enabled) }
                    .onFailure { FileLogger.w(TAG, "归档未启用方案文件失败", it) }
                // 3b) 为**所有已安装方案**建文件夹并补全依赖闭包（硬链接，不占多份空间）。
                runCatching { SchemaLayout.syncEnabledSchemeFolders(rimeDir, enabled) }
                    .onFailure { FileLogger.w(TAG, "同步方案文件夹失败", it) }
                SchemaLayout.mirrorEnabledSchemes(rimeDir, enabled)
            }
            // 5) 清理垃圾文件（*.bak / *.log / *.tmp），它们不参与任何功能，
            //    但会让目录显得乱，还会被备份打包进去。
            runCatching { SchemaLayout.cleanJunkFiles(rimeDir) }
                .onFailure { FileLogger.w(TAG, "清理垃圾文件失败", it) }

            // 6) 可见目录 /sdcard/rime/ 也要整理：用户看到的是它，不是私有目录。
            //
            // 之前只整理私有目录，可见目录里一直停在 3.4.25 时代的旧结构
            // （11 个按 schema_id 命名的目录、内容不全），用户看到的就是"没归纳"。
            runCatching { ensureVisibleSchemeLayout(context, enabled) }
                .onFailure { FileLogger.w(TAG, "整理可见目录方案布局失败", it) }
        } catch (e: Exception) {
            FileLogger.w(TAG, "方案目录布局整理失败（退回平铺，不影响输入）", e)
        }
    }

    /**
     * 从 APK 内置资产补齐设备上缺失的内置方案。
     *
     * 只补「assets 里有、设备上完全找不到」的方案（顶层和 schemes/ 都没有）。
     * 已存在的方案一律不动——避免覆盖用户对内置方案的修改。
     *
     * @return 补齐的文件数
     */
    internal fun restoreMissingBuiltinSchemes(context: Context, rimeDir: File): Int {
        val assets = context.assets.list("rime").orEmpty()
        if (assets.isEmpty()) return 0

        // 设备上已有的方案 id（顶层 + 方案文件夹）
        val present = mutableSetOf<String>()
        rimeDir.listFiles { f -> f.name.endsWith(".schema.yaml") }
            .orEmpty().forEach { present += it.name.removeSuffix(".schema.yaml") }
        SchemaLayout.discoverSchemeFiles(rimeDir).values.flatten()
            .filter { it.name.endsWith(".schema.yaml") }
            .forEach { present += it.name.removeSuffix(".schema.yaml") }

        // assets 里有但设备上没有的方案
        val missing = assets
            .filter { it.endsWith(".schema.yaml") }
            .map { it.removeSuffix(".schema.yaml") }
            .filter { it !in present }
        if (missing.isEmpty()) return 0

        var restored = 0
        missing.forEach { sid ->
            // 该方案相关的全部资产文件：<id>.schema.yaml / <id>.dict.yaml / <id>.custom.yaml
            val related = assets.filter { name ->
                name == "$sid.schema.yaml" || name == "$sid.dict.yaml" || name == "$sid.custom.yaml"
            }
            related.forEach { name ->
                val target = File(rimeDir, name)
                if (target.exists()) return@forEach
                runCatching {
                    context.assets.open("rime/$name").use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    }
                    restored++
                }.onFailure { FileLogger.w(TAG, "补齐内置方案文件失败：$name", it) }
            }
        }
        if (restored > 0) {
            FileLogger.i(TAG, "补齐缺失内置方案：${missing.size} 个方案、$restored 个文件")
        }
        return restored
    }

    /**
     * 整理**可见目录** `/sdcard/rime/` 的方案布局。
     *
     * 用户通过文件管理器看到的是这个目录，所以它的 schemes/ 必须与私有目录一致。
     * 私有目录是权威源：这里把私有目录的 schemes 整体同步过去，并清掉可见侧的旧残留。
     *
     * 只做**单向镜像**（私有 → 可见）：
     * - 可见侧多余的文件/目录（旧版本留下的 id 命名目录）删除
     * - 私有侧有的文件补到可见侧
     *
     * 不反向读取：可见侧的 schemes 是派生产物，用户改它没有意义
     * （真要改方案应该改顶层文件，那部分由 RimeUserFileSync 正常双向同步）。
     */
    private fun ensureVisibleSchemeLayout(context: Context, enabledSchemaIds: List<String>) {
        if (!UserConfigDir.isSupported) return
        if (!UserConfigDir.hasWritePermission(context)) return
        syncVisibleSchemesForTest(getRimeDir(context), RimeUserFileSync.mirrorDir(context))
    }

    /**
     * 可见目录 schemes 同步的纯目录实现（不依赖 Android Context，便于单测回滚路径）。
     */
    internal fun syncVisibleSchemesForTest(privateRime: File, visibleRime: File) {
        if (!privateRime.isDirectory || !visibleRime.isDirectory) return

        val privSchemes = File(privateRime, SchemaLayout.SCHEMES_DIR)
        val visSchemes = File(visibleRime, SchemaLayout.SCHEMES_DIR)
        if (!privSchemes.isDirectory) return

        // 先写到临时目录，全部成功后再替换旧的。
        //
        // 曾经的写法是「先删后建」：删除成功但复制中途失败（空间不足 / 权限变化 / 用户
        // 正在文件管理器里操作）会让 schemes/ 直接变空，用户看到方案全没了，
        // 要等下次启动才能恢复。改成 staging + 原子替换后，失败时旧内容仍在。
        val staging = File(visibleRime, "${SchemaLayout.SCHEMES_DIR}.tmp")
        if (staging.isDirectory) runCatching { staging.deleteRecursively() }
        if (!staging.mkdirs()) {
            FileLogger.w(TAG, "可见目录 schemes 同步跳过：无法创建临时目录")
            return
        }

        var copied = 0
        var failed = 0
        privSchemes.walkTopDown().filter { it.isFile }.forEach { src ->
            val rel = src.relativeTo(privSchemes).path
            val target = File(staging, rel)
            target.parentFile?.mkdirs()
            if (runCatching { src.copyTo(target, overwrite = true) }.isSuccess) copied++ else failed++
        }
        if (failed > 0) {
            // 有失败就放弃替换，保留旧的可见结构（宁可不更新，也不能给用户一个残缺的目录）
            FileLogger.w(TAG, "可见目录 schemes 同步中止：$failed 个文件复制失败，保留原结构")
            runCatching { staging.deleteRecursively() }
            return
        }
        // 替换旧的 schemes/：旧目录改名成 .old → 建新目录 → 搬运 → 成功后删 .old。
        //
        // 不能依赖 `renameTo` 直接把 staging 改名成 schemes：`/sdcard` 是 FUSE，
        // 目录重命名经常失败（实测失败后 staging 留在原地，schemes.tmp 堆了 29MB）。
        // 也不能「先删旧再搬」——搬运中途失败会让用户看到 schemes/ 变空、方案全没。
        // 这里用 .old 备份兜底：搬运阶段有任何失败就把 .old 改回来，
        // 用户看到的始终是完整的一份。
        val oldBackup = File(visibleRime, "${SchemaLayout.SCHEMES_DIR}.old")
        if (oldBackup.isDirectory) runCatching { oldBackup.deleteRecursively() }
        val hadOld = visSchemes.isDirectory && runCatching { visSchemes.renameTo(oldBackup) }.getOrDefault(false)
        if (visSchemes.isDirectory && !hadOld) {
            // rename 失败：保持旧目录原样，放弃本次同步（宁可不更新，也不能给残缺目录）
            FileLogger.w(TAG, "可见目录 schemes 同步跳过：旧目录无法改名保护")
            runCatching { staging.deleteRecursively() }
            return
        }
        if (!visSchemes.mkdirs() && !visSchemes.isDirectory) {
            FileLogger.w(TAG, "可见目录 schemes 同步跳过：无法创建目标目录")
            if (hadOld) runCatching { oldBackup.renameTo(visSchemes) }
            runCatching { staging.deleteRecursively() }
            return
        }

        var moved = 0
        var moveFailed = 0
        staging.walkTopDown().filter { it.isFile }.forEach { src ->
            val rel = src.relativeTo(staging).path
            val target = File(visSchemes, rel)
            target.parentFile?.mkdirs()
            // 优先 rename（同一文件系统内很快），失败则复制
            val ok = runCatching { src.renameTo(target) }.getOrDefault(false) ||
                runCatching { src.copyTo(target, overwrite = true) }.isSuccess
            if (ok) moved++ else moveFailed++
        }
        if (moveFailed > 0) {
            // 搬运有失败：回滚到旧目录，避免用户看到残缺的 schemes
            FileLogger.w(TAG, "可见目录 schemes 搬运失败 $moveFailed 个，回滚到旧版本")
            runCatching { visSchemes.deleteRecursively() }
            if (hadOld) runCatching { oldBackup.renameTo(visSchemes) }
            runCatching { staging.deleteRecursively() }
            return
        }
        // 全部成功：删掉旧备份与 staging
        if (hadOld) runCatching { oldBackup.deleteRecursively() }
        runCatching { staging.deleteRecursively() }
            .onFailure { FileLogger.w(TAG, "清理 staging 失败：${it.message}") }

        // 顺手清掉历史残留的 schemes.tmp（旧版本 rename 失败留下的 29MB 垃圾）。
        // staging 已经在上面删过，这里再删一次是幂等的（目录不存在直接返回）。
        runCatching {
            val leftover = File(visibleRime, "${SchemaLayout.SCHEMES_DIR}.tmp")
            if (leftover.isDirectory) leftover.deleteRecursively()
        }

        if (moved > 0) {
            FileLogger.i(TAG, "可见目录 schemes/ 已同步：$moved 个文件")
        }

        // 顶层的重复文件由 RimeUserFileSync.dedupeVisibleTopLevel 在同步之后统一清理，
        // 这里不重复处理（两处都做会互相干扰：这里删完，同步又写回来）。
    }

    /**
     * 一次性迁移：把 rime 顶层的方案文件收进 `schemes/<方案>/`。
     *
     * **只复制/链接，不删顶层原件**——librime 要读顶层，删了就没方案了。
     * 所以这个迁移本质上是从顶层"备份一份"进 schemes/，然后依靠镜像保持两边一致。
     *
     * 幂等：schemes/ 已存在时直接返回（由 [ensureSchemeLayout] 保证不重复调用）。
     *
     * @return 迁移的方案数
     */
    internal fun migrateLayoutToSchemes(rimeDir: File): Int {
        // 先清理旧版本用 schema_id 命名的目录（3.4.25 曾建出 schemes/english/ 这种）。
        // 不清的话升级后会出现 "english" 和 "English" 两个目录，同一个方案两份内容。
        runCatching { SchemaLayout.removeLegacyIdFolders(rimeDir) }
            .onFailure { FileLogger.w(TAG, "清理旧方案目录失败", it) }

        val ownership = SchemaLayout.resolveOwnership(rimeDir)
        if (ownership.isEmpty()) return 0
        var schemes = 0
        ownership.entries.groupBy({ it.value }, { it.key }).forEach { (schemeName, files) ->
            val dir = File(File(rimeDir, SchemaLayout.SCHEMES_DIR), schemeName)
            var wrote = 0
            files.forEach { top ->
                val rel = top.relativeTo(rimeDir).path
                val target = File(dir, rel)
                if (target.isFile && target.length() == top.length()) return@forEach
                target.parentFile?.mkdirs()
                runCatching {
                    java.nio.file.Files.createLink(target.toPath(), top.toPath())
                    wrote++
                }.recoverCatching {
                    // 跨文件系统/不支持硬链接：复制一份
                    top.copyTo(target, overwrite = true)
                    wrote++
                }
            }
            if (wrote > 0) schemes++
        }
        if (schemes > 0) FileLogger.i(TAG, "方案目录迁移完成：$schemes 个方案收进 schemes/")
        return schemes
    }

    /** 清空 rime/ 目录除 default.yaml、xime.yaml 及受保护文件之外的所有文件。 */
    fun cleanRimeDir(context: Context) {
        val rimeDir = getRimeDir(context)
        if (!rimeDir.exists()) return
        rimeDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name == "default.yaml" || name == "xime.yaml") return@forEach
            if (isProtectedImportName(name)) return@forEach
            // themes/ 存放用户导入或自定义的背景图片，全量清理时保留
            if (name == "themes") return@forEach
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
        Log.i(TAG, "rime/ directory cleaned")
    }

    /** market 根目录（与 rime/ 同级，不属于 Rime 数据）。 */
    fun getMarketDir(context: Context): File =
        File(context.filesDir, "market")

    /** 每个方案在 market 下的独立子目录：market/{schemeId}/ */
    fun getMarketDir(context: Context, schemeId: String): File =
        File(getMarketDir(context), schemeId)

    /** 检查方案的压缩包是否已下载（market/{schemeId}/ 存在且包含文件）。 */
    fun isSchemeDownloaded(context: Context, schemeId: String): Boolean {
        val dir = getMarketDir(context, schemeId)
        return dir.exists() && (dir.listFiles()?.any { it.isFile } == true)
    }

    /** 删除 market 中指定方案的整个子目录（含压缩包），并清理本地版本记录。 */
    fun deleteSchemeArchive(context: Context, schemeId: String): Boolean {
        val dir = getMarketDir(context, schemeId)
        val removed = if (!dir.exists()) false else dir.deleteRecursively()
        if (removed) {
            MarketVersionStore.removeSchemeVersion(context, schemeId)
        }
        return removed
    }

    /**
     * 列出 market/{schemeId}/ 下归档文件解压后将会放置到 rime/ 的所有文件名。
     * 用于安装前的文件冲突提示。
     */
    suspend fun listInstallTargetFiles(context: Context, schemeId: String): List<String> {
        val dir = getMarketDir(context, schemeId)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        return files.flatMap { listArchiveTargetFiles(it) }
    }

    /**
     * 计算市场包中每个目标文件的 sha256（用于冲突检测）。
     * 与 [listInstallTargetFiles] 对应，返回值是相同结构的目标文件名 → sha256。
     */
    suspend fun computeTargetSha256Map(context: Context, schemeId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val dir = getMarketDir(context, schemeId)
        if (!dir.exists()) return@withContext emptyMap()
        val files = dir.listFiles()?.filter { it.isFile } ?: return@withContext emptyMap()
        val result = mutableMapOf<String, String>()

        for (file in files) {
            when {
                file.name.endsWith(".zip", ignoreCase = true) -> {
                    val entryNames = mutableListOf<String>()
                    ZipFile(file).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            if (!entry.isDirectory && !isAppleDouble(entry.name)) {
                                entryNames.add(entry.name)
                            }
                        }
                    }
                    val baseDir = findSchemaBaseDir(entryNames)
                    ZipFile(file).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            val originalName = entry.name
                            if (entry.isDirectory || isAppleDouble(originalName)) return@forEach
                            val name = originalName.removePrefix(baseDir)
                            if (isProtectedImportName(name)) return@forEach
                            val hash = zip.getInputStream(entry).use { streamSha256(it) }
                            result[name] = hash
                        }
                    }
                }

                file.name.endsWith(".tar.gz", ignoreCase = true) || file.name.endsWith(".tgz", ignoreCase = true) -> {
                    val entryNames = mutableListOf<String>()
                    file.inputStream().buffered().use { input ->
                        TarArchiveInputStream(GzipCompressorInputStream(input)).use { tarIn ->
                            var entry = tarIn.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && !isAppleDouble(entry.name)) {
                                    entryNames.add(entry.name)
                                }
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                    val baseDir = findSchemaBaseDir(entryNames)
                    file.inputStream().buffered().use { input ->
                        TarArchiveInputStream(GzipCompressorInputStream(input)).use { tarIn ->
                            var entry = tarIn.nextEntry
                            while (entry != null) {
                                val originalName = entry.name
                                if (entry.isDirectory || isAppleDouble(originalName)) {
                                    entry = tarIn.nextEntry
                                    continue
                                }
                                val name = originalName.removePrefix(baseDir)
                                if (isProtectedImportName(name)) {
                                    entry = tarIn.nextEntry
                                    continue
                                }
                                val hash = streamSha256(tarIn)
                                result[name] = hash
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                }

                else -> {
                    if (!isProtectedImportName(file.name)) {
                        val hash = fileSha256(file)
                        if (hash != null) result[file.name] = hash
                    }
                }
            }
        }
        result
    }

    data class InstallFromDirResult(
        val success: Boolean,
        val conflicts: List<FileConflictInfo> = emptyList(),
        val newSchemaIds: List<String> = emptyList(),
        val unresolvedDeps: List<String> = emptyList(),
        val failureReason: String? = null,
        val parseFailures: List<String> = emptyList(),
    )

    /**
     * 从 market/{packageId}/ 执行统一安装：冲突检测 → 解压 → 清单创建。
     * 所有导入路径（市场/文件/URL）最终都汇聚于此。
     */
    suspend fun installPackageFromMarketDir(
        context: Context,
        packageId: String,
        displayName: String,
        version: String = "",
        fromMarket: Boolean = false,
        dependencies: List<String> = emptyList(),
        resolveDepUrl: (String) -> String? = { null },
        switchEnabled: Boolean = true,
    ): InstallFromDirResult = withContext(Dispatchers.IO) {
        try {
            val dir = getMarketDir(context, packageId)
            if (!dir.exists() || dir.listFiles()?.none { it.isFile } != false) {
                FileLogger.w(TAG, "installPackageFromMarketDir: dir not found or empty: $dir")
                return@withContext InstallFromDirResult(success = false, failureReason = "压缩包不存在")
            }

            val targetFiles = listInstallTargetFiles(context, packageId)
            if (targetFiles.isEmpty()) {
                FileLogger.w(TAG, "installPackageFromMarketDir: no target files in $packageId")
                return@withContext InstallFromDirResult(success = false, failureReason = "归档中没有文件")
            }
            FileLogger.i(TAG, "installPackageFromMarketDir: found ${targetFiles.size} target files for $packageId")

            val sha256Map = computeTargetSha256Map(context, packageId)
            FileLogger.i(TAG, "installPackageFromMarketDir: computed sha256 for ${sha256Map.size} files")

            val conflicts = SchemaManifestManager.detectConflicts(context, packageId, targetFiles, sha256Map)
            if (conflicts.isNotEmpty()) {
                FileLogger.w(TAG, "installPackageFromMarketDir: conflicts detected: ${conflicts.map { "${it.fileName} (${it.claimedBy})" }}")
                return@withContext InstallFromDirResult(success = false, conflicts = conflicts)
            }

            val before = discoverSchemas(context).map { it.schemaId }.toSet()
            FileLogger.i(TAG, "installPackageFromMarketDir: schemas before install: $before")

            val ok = installFromMarketToRime(context, packageId)
            if (!ok) {
                FileLogger.e(TAG, "installPackageFromMarketDir: installFromMarketToRime returned false")
                return@withContext InstallFromDirResult(success = false, failureReason = "安装失败")
            }

            val after = discoverSchemas(context).map { it.schemaId }.toSet()
            val newIds = (after - before).toList()
            FileLogger.i(TAG, "installPackageFromMarketDir: schemas after install: $after, new: $newIds")

            // 检查 target 中的 .schema.yaml 是否都解析成功
            val targetSchemaIds = targetFiles
                .filter { it.endsWith(".schema.yaml") }
                .map { it.removeSuffix(".schema.yaml") }
                .toSet()
            val failedIds = targetSchemaIds - after
            val parseFailures = if (failedIds.isEmpty()) emptyList()
                else failedIds.map { "$it.schema.yaml 解析失败" }
            if (parseFailures.isNotEmpty()) {
                FileLogger.w(TAG, "installPackageFromMarketDir: some schemas failed to parse: $parseFailures")
            }

            var unresolved = emptyList<String>()
            var dependencyIds = emptyList<String>()
            if (dependencies.isNotEmpty()) {
                val completion = RimeDependencyResolver.complete(
                    context = context,
                    schemaId = newIds.firstOrNull() ?: packageId,
                    dependencies = dependencies,
                    resolveUrl = resolveDepUrl,
                )
                unresolved = (completion.unresolved + completion.stillMissingFiles).distinct()
                dependencyIds = completion.downloaded
            }

            SchemaManifestManager.createManifest(
                context = context,
                schemeId = packageId,
                displayName = displayName,
                version = version,
                fromMarket = fromMarket,
                extractedFiles = targetFiles,
                dependencyIds = dependencyIds,
            )

            if (fromMarket) {
                SettingsPreferences.addInstalledMarketId(context, packageId)
            }

            // 至少启用一个新方案，避免 RimeEngine 因 schema_list 为空而挂起。
            // 更新已安装方案时（switchEnabled=false）不强制切换启用列表，保留用户现有方案。
            val firstSchema = newIds.firstOrNull()
                ?: targetFiles.firstOrNull { it.endsWith(".schema.yaml") }
                    ?.removeSuffix(".schema.yaml")
            if (firstSchema != null && switchEnabled) {
                setEnabledSchemas(context, listOf(firstSchema))
            }

            FileLogger.i(TAG, "installPackageFromMarketDir: success for $packageId, firstSchema=$firstSchema")
            InstallFromDirResult(success = true, newSchemaIds = newIds, unresolvedDeps = unresolved, parseFailures = parseFailures)
        } catch (e: Exception) {
            FileLogger.e(TAG, "installPackageFromMarketDir: UNCAUGHT exception for $packageId", e)
            return@withContext InstallFromDirResult(success = false, failureReason = "安装异常: ${e.message}")
        }
    }

    /** 解析单个归档（或普通文件）将被释放到 rime/ 的目标文件名列表。 */
    private fun listArchiveTargetFiles(archiveFile: File): List<String> {
        val entryNames = when {
            archiveFile.name.endsWith(".zip", ignoreCase = true) -> {
                val names = mutableListOf<String>()
                ZipFile(archiveFile).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        if (!entry.isDirectory && !isAppleDouble(entry.name)) names.add(entry.name)
                    }
                }
                names
            }
            archiveFile.name.endsWith(".tar.gz", ignoreCase = true) || archiveFile.name.endsWith(".tgz", ignoreCase = true) -> {
                val names = mutableListOf<String>()
                TarArchiveInputStream(GzipCompressorInputStream(archiveFile.inputStream().buffered())).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !isAppleDouble(entry.name)) names.add(entry.name)
                        entry = tar.nextEntry
                    }
                }
                names
            }
            else -> return listOf(archiveFile.name)
        }
        if (entryNames.isEmpty()) return emptyList()
        val baseDir = findSchemaBaseDir(entryNames)
        return entryNames.map { it.removePrefix(baseDir) }
            .filterNot { isProtectedImportName(it) }
    }

    /** 在 market/{schemeId}/ 中查找已下载的文件。 */
    fun findMarketFile(context: Context, schemeId: String): File? {
        val dir = getMarketDir(context, schemeId)
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull { it.isFile }
    }

    /**
     * 从 market 目录安装方案到 rime 目录：
     * - .zip / .tar.gz / .tgz → 解压
     * - 其他文件 → 直接复制
     */
    /**
     * 从 market/{schemeId}/ 安装所有已下载文件到 rime 目录：
     * - .zip / .tar.gz / .tgz → 解压
     * - 其他文件 → 直接复制
     */
    fun installFromMarketToRime(context: Context, schemeId: String): Boolean {
        val dir = getMarketDir(context, schemeId)
        if (!dir.exists()) return false
        val files = dir.listFiles()?.filter { it.isFile } ?: return false
        if (files.isEmpty()) return false
        val rimeDir = getRimeDir(context)
        if (!rimeDir.exists()) rimeDir.mkdirs()
        var allOk = true
        for (file in files) {
            try {
                val name = file.name
                // 解压前先校验压缩包完整性
                val isArchive = name.endsWith(".zip", ignoreCase = true) ||
                    name.endsWith(".tar.gz", ignoreCase = true) || name.endsWith(".tgz", ignoreCase = true)
                if (isArchive && !validateArchive(file)) {
                    FileLogger.e(TAG, "installFromMarketToRime: ${file.name} is corrupted for $schemeId, deleting")
                    file.delete()
                    allOk = false
                    continue
                }
                val ok = when {
                    name.endsWith(".zip", ignoreCase = true) -> importZipFromFile(file, rimeDir)
                    name.endsWith(".tar.gz", ignoreCase = true) || name.endsWith(".tgz", ignoreCase = true) ->
                        importTarGzFromFile(file, rimeDir)
                    else -> {
                        val target = File(rimeDir, file.name)
                        file.copyTo(target, overwrite = true)
                        FileLogger.i(TAG, "Copied ${file.name} to rime dir")
                        true
                    }
                }
                if (!ok) {
                    FileLogger.e(TAG, "installFromMarketToRime: failed to process ${file.name} for $schemeId, deleting")
                    file.delete()
                    allOk = false
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "installFromMarketToRime: error processing ${file.name} for $schemeId, deleting", e)
                file.delete()
                allOk = false
            }
        }
        // 安装完成后整理布局：新方案收进 schemes/<方案名>/ 并补全依赖。
        //
        // 不整理的话，从市场装的方案会平铺在 rime 顶层、没有自己的文件夹，
        // 用户看到的目录结构和「导入方案」的两种方式不一致。
        if (allOk) {
            runCatching { ensureSchemeLayout(context, rimeDir) }
                .onFailure { FileLogger.w(TAG, "市场安装后整理方案布局失败", it) }
        }
        return allOk
    }

    internal fun getBuildDir(context: Context): File =
        File(getRimeDir(context), "build")

    private fun getCustomYamlFile(context: Context): File =
        File(getRimeDir(context), CUSTOM_YAML)

    fun isSchemaCompiled(context: Context, schemaId: String): Boolean {
        val buildDir = getBuildDir(context)
        return File(buildDir, "$schemaId.prism.bin").exists() ||
               File(buildDir, "$schemaId.schema.yaml").exists()
    }

    // F1: 把启用方案直接写进 default.yaml 的 schema_list（纯函数，可单测）
    fun replaceSchemaListBlock(defaultYamlText: String, enabled: List<String>): String {
        if (enabled.isEmpty()) return defaultYamlText
        // 保留原文件换行风格（CRLF/LF），避免把整文件换行符规范化
        val sep = if (defaultYamlText.contains("\r\n")) "\r\n" else "\n"
        val lines = defaultYamlText.lines()
        val headerIdx = lines.indexOfFirst { it.trim() == "schema_list:" }

        if (headerIdx < 0) {
            // 没有 schema_list 块：在文末追加一个
            val sb = StringBuilder(defaultYamlText)
            if (!defaultYamlText.endsWith("\n")) sb.append(sep)
            sb.append(sep).append("schema_list:").append(sep)
            enabled.forEach { sb.append("  - schema: ").append(it).append(sep) }
            return sb.toString()
        }

        // 吃掉紧跟其后的列表项行；保留缩进风格（默认两空格）
        var j = headerIdx + 1
        var indent = "  "
        var first = true
        while (j < lines.size && lines[j].trimStart().startsWith("-")) {
            if (first) {
                indent = lines[j].takeWhile { it == ' ' || it == '\t' }.ifEmpty { "  " }
                first = false
            }
            j++
        }

        val rebuilt = buildList {
            add(lines[headerIdx])                    // 保留原 header 行（含其缩进）
            enabled.forEach { add("$indent- schema: $it") }
        }
        return (lines.subList(0, headerIdx) + rebuilt + lines.subList(j, lines.size))
            .joinToString(sep)
    }

    /**
     * F1: 把启用方案写回 `default.yaml` 的 schema_list。
     * librime 编译以 default.yaml 的真实 schema_list 为准（default.custom.yaml 的
     * patch 在本项目构建里不作用到词典编译阶段），所以必须直接改 default.yaml。
     *
     * @param schemaIds 缺省取当前启用列表；[setEnabledSchemas] 会显式传入避免重复读取。
     */
    fun applyEnabledSchemasToDefaultYaml(
        context: Context,
        schemaIds: List<String> = getEnabledSchemas(context)
    ): Boolean {
        if (schemaIds.isEmpty()) return false
        val defaultYaml = File(getRimeDir(context), "default.yaml")
        if (!defaultYaml.isFile) return false
        return try {
            val text = defaultYaml.readText()
            val updated = replaceSchemaListBlock(text, schemaIds)
            // write 失败返回 false 不抛异常：原来忽略返回值会在写失败时仍返回 true，
            // 调用方以为方案列表已落盘，重启后配置丢失却查不到任何错误。
            if (updated != text) {
                if (!com.kingzcheung.xime.util.AtomicFileWriter.write(defaultYaml, updated)) {
                    Log.e(TAG, "写 default.yaml schema_list 失败")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write default.yaml schema_list", e)
            false
        }
    }

    // sha256 校验 + 导入保护（纯函数，可单测）
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun streamSha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val dis = DigestInputStream(input, digest)
        val buffer = ByteArray(8192)
        while (dis.read(buffer) != -1) { }
        // 不关闭 dis——调用方负责管理流的生命周期
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun fileSha256(file: File): String? {
        return try {
            FileInputStream(file).use { streamSha256(it) }
        } catch (e: Exception) {
            Log.w(TAG, "sha256 failed for ${file.name}", e)
            null
        }
    }

    /** 生成基于当前时间的导入 ID，如 import_20260712_015947。 */
    fun generateImportId(): String =
        "import_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 导入归档时禁止覆盖系统文件（保护应用核心配置与系统元数据）。 */
    fun isProtectedImportName(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return base == "default.yaml" ||
               base == "xime.yaml" ||
               base == "custom_phrase.txt" ||
               name.startsWith(".registry")
    }

    /**
     * 单文件直接导入是否受清单系统追踪（冲突检测 + 可按清单卸载）。
     * 用户数据（custom_phrase.txt、*.custom.yaml 等）、系统文件（default.yaml、xime.yaml、build/）
     * 与清单元数据本身不追踪，保持直接落盘的历史行为；含 `..` 的路径同样不追踪。
     */
    internal fun shouldTrackImportedFile(name: String): Boolean =
        !isProtectedImportName(name) &&
            !name.startsWith(".manifests") &&
            !SchemaManifestManager.isProtectedSystemFile(name) &&
            !SchemaManifestManager.isUserDataFile(name) &&
            !name.contains("..")

    /** 单文件导入的清单包 id：rime 相对路径展平（manifests 文件名不能含路径分隔符）。 */
    internal fun manifestPackageIdFor(name: String): String = name.replace('/', '_')

    /** macOS Apple Double 资源分支文件（__MACOSX/ 或 ._ 前缀），应当在解压时跳过。 */
    private fun isAppleDouble(name: String): Boolean =
        name.startsWith("__MACOSX/") || name.contains("/._") || name.startsWith("._")

    /** 把归档条目解析到 targetDir 下，越界（zip-slip，如 ../../x）返回 null。 */
    private fun safeChild(targetDir: File, name: String): File? {
        val child = File(targetDir, name)
        return if (child.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) child else null
    }

    fun getReferencedDictName(context: Context, schemaId: String): String? {
        val schemaFile = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!schemaFile.exists()) return null
        return try {
            val content = schemaFile.readText()
            // matches "  dictionary: wubi86" or "translator/dictionary: wubi86" or inline {dictionary:wubi86}
            DICT_NAME_ANYWHERE_RE.find(content)?.groupValues?.getOrNull(1)
        } catch (e: Exception) { null }
    }

    /**
     * 解析 schema 引用的全部 dictionary 名（去重，按出现顺序）。
     * 一个方案可能有多个 translator（如 wubi86_pinyin 同时引用 wubi86 与 pinyin_simp），
     * librime 的 table.bin 按 dictionary 名产出，而非 schemaId——部署完整性校验必须
     * 按这些真实词典名校验，否则复用他人词典的方案（wubi86_pinyin / t9_pinyin）会
     * 永远找不到 $schemaId.table.bin 而被误判为未部署。
     */
    /**
     * 解析 schema 的「主词典」名（决定需要哪个 table.bin）。
     *
     * 只认顶层 `translator:` 段下的 `dictionary:`——`reverse_lookup:` 段的 dictionary
     * （反查词典，如 pinyin_simp 的 stroke、wubi86_pinyin 的 pinyin_simp）产出的是
     * reverse.bin 而非 table.bin，混在一起会把反查词典误判为缺 table.bin 的缺省项。
     *
     * 多个 translator（如翻译器列表含 table_translator + script_translator）时取首个
     * 非空 dictionary；无 translator 段的方案回退 schemaId 自身。
     */
    fun getMainDictName(context: Context, schemaId: String): String? {
        val schemaFile = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!schemaFile.exists()) return null
        return try {
            var inTranslator = false
            var found: String? = null
            for (raw in schemaFile.readLines()) {
                // 顶层 key（无前导空格，且非注释）切换段
                if (raw.isNotEmpty() && !raw[0].isWhitespace()) {
                    if (raw.startsWith("translator:")) {
                        inTranslator = true
                        continue
                    }
                    if (inTranslator) inTranslator = false
                }
                if (inTranslator) {
                    val m = DICT_NAME_RE.find(raw)
                    if (m != null) {
                        found = m.groupValues.getOrNull(1)
                        break
                    }
                }
            }
            found
        } catch (e: Exception) { null }
    }

    /** 解析 schema 的「反查词典」名（决定需要哪个 reverse.bin）。 */
    fun getReverseLookupDictName(context: Context, schemaId: String): String? {
        val schemaFile = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!schemaFile.exists()) return null
        return try {
            var inReverse = false
            var found: String? = null
            for (raw in schemaFile.readLines()) {
                if (raw.isNotEmpty() && !raw[0].isWhitespace()) {
                    if (raw.startsWith("reverse_lookup:")) {
                        inReverse = true
                        continue
                    }
                    if (inReverse) inReverse = false
                }
                if (inReverse) {
                    val m = DICT_NAME_RE.find(raw)
                    if (m != null) {
                        found = m.groupValues.getOrNull(1)
                        break
                    }
                }
            }
            found
        } catch (e: Exception) { null }
    }

    fun hasDictFile(context: Context, schemaId: String): Boolean {
        val dictName = getReferencedDictName(context, schemaId) ?: schemaId
        val f = File(getRimeDir(context), "$dictName.dict.yaml")
        return f.exists()
    }

    fun schemaNeedsDict(context: Context, schemaId: String): Boolean {
        val dictName = getReferencedDictName(context, schemaId) ?: schemaId
        return !File(getRimeDir(context), "$dictName.dict.yaml").exists()
    }

    fun getSchemaIssues(context: Context, schemaId: String): List<String> {
        val issues = mutableListOf<String>()
        val schemaFile = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!schemaFile.exists()) {
            issues.add("缺少 .schema.yaml 文件")
            return issues
        }
        val dictName = getReferencedDictName(context, schemaId) ?: schemaId
        if (!File(getRimeDir(context), "$dictName.dict.yaml").exists()) {
            issues.add("缺少 $dictName.dict.yaml 词典文件，无法编译")
        }
        return issues
    }

    /**
     * 扫描已安装方案。
     *
     * 必须同时扫 rime 顶层**和** schemes/<方案名>/：未启用方案的文件会被
     * [SchemaLayout.archiveDisabledSchemeFiles] 归档进各自文件夹、从顶层移走。
     * 只扫顶层会让这些方案在设置页里完全消失，用户看到「没有其他可用方案」，
     * 也就永远无法重新启用它们（等于把功能删了）。
     *
     * 同一 schema_id 只保留一条：优先顶层那份，其次文件夹里的。
     */
    fun discoverSchemas(context: Context): List<SchemaMeta> {
        val rimeDir = getRimeDir(context)
        if (!rimeDir.exists()) return emptyList()
        return discoverSchemasIn(rimeDir)
    }

    /**
     * [discoverSchemas] 的目录版核心（纯逻辑，可直接单测）。
     *
     * 顶层与 `schemes/<方案名>/` 都要扫：未启用方案的文件被
     * [SchemaLayout.archiveDisabledSchemeFiles] 归档进各自文件夹、从顶层移走，
     * 只扫顶层会让这些方案在设置页里完全消失（用户看到「没有其他可用方案」，
     * 也就无法重新启用它们）。
     *
     * 同一 schema_id 只保留一条：顶层优先（引擎实际加载的是它），其次文件夹里的。
     */
    internal fun discoverSchemasIn(rimeDir: File): List<SchemaMeta> {
        val byId = LinkedHashMap<String, SchemaMeta>()

        fun add(candidates: List<File>) {
            for (file in candidates) {
                val meta = parseSchemaYaml(file) ?: continue
                if (meta.schemaId !in byId) byId[meta.schemaId] = meta
            }
        }

        add(rimeDir.listFiles { f -> f.isFile && f.name.endsWith(".schema.yaml") }.orEmpty().toList())

        val schemesDir = File(rimeDir, SchemaLayout.SCHEMES_DIR)
        if (schemesDir.isDirectory) {
            add(
                schemesDir.listFiles().orEmpty()
                    .filter { it.isDirectory }
                    .flatMap { dir ->
                        dir.listFiles { f -> f.isFile && f.name.endsWith(".schema.yaml") }.orEmpty().toList()
                    }
            )
        }

        return byId.values.sortedBy { it.name }
    }

    internal fun parseSchemaYaml(file: File): SchemaMeta? {
        return try {
            val text = file.readText().trimStart('\uFEFF')
            val schemaBlock = extractSchemaBlock(text) ?: return null
            val entry = yaml.decodeFromString(SchemaYaml.serializer(), schemaBlock).schema
            if (entry.schemaId.isEmpty()) return null

            val author = parseAuthorFromText(text)

            SchemaMeta(
                schemaId = entry.schemaId,
                name = entry.name.ifEmpty { entry.schemaId },
                version = entry.version,
                author = author,
                description = entry.description ?: ""
            )
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse schema file: ${file.name}, skip", e)
            null
        }
    }

    /**
     * 从 YAML 文本中提取顶层 `schema:` 块及其子内容，返回一个可独立解析的 YAML 片段。
     * 避免 kaml 因为文件的其他部分（重复 `__include`、锚点等）而报错。
     */
    internal fun extractSchemaBlock(yamlText: String): String? {
        val lines = yamlText.lines()
        val schemaLineIndex = lines.indexOfFirst {
            it.trimStart().let { t -> t.startsWith("schema:") && (t.length == 7 || t[7] == ' ') }
        }
        if (schemaLineIndex < 0) return null

        val schemaIndent = lines[schemaLineIndex].length - lines[schemaLineIndex].trimStart().length
        val result = mutableListOf("schema:")
        for (i in schemaLineIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            if (indent <= schemaIndent) break
            result.add(line)
        }
        return result.joinToString("\n")
    }

    private fun parseAuthorFromText(yamlText: String): String {
        val lines = yamlText.lines()
        var inAuthor = false
        for (line in lines) {
            val trimmed = line.trimStart()
            if (!inAuthor && trimmed.startsWith("author:")) {
                val rest = trimmed.removePrefix("author:").trim()
                if (rest.isNotEmpty()) return rest.removeSurrounding("\"").removePrefix("- ").trim()
                inAuthor = true
                continue
            }
            if (inAuthor) {
                if (trimmed.startsWith("- ")) {
                    return trimmed.removePrefix("- ").trim().removeSurrounding("\"")
                }
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    inAuthor = false
                }
            }
        }
        return ""
    }

    /** 仅从 .schema.yaml 读取显示名，不依赖编译/启用状态。 */
    fun getSchemaDisplayName(context: Context, schemaId: String): String? {
        val file = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!file.exists()) return null
        return try {
            val entry = yaml.decodeFromString(SchemaYaml.serializer(), file.readText().trimStart('\uFEFF')).schema
            entry.name.ifEmpty { null }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to parse schema name for $schemaId", e)
            null
        }
    }

    /**
     * 读取方案顶层 `switches:` 中可展示的开关项（name 或 options 存在且有 states）。
     * `switches` 位于 schema 块之外，需单独解析。解析失败或不存在时返回空列表。
     */
    fun getSchemaSwitches(context: Context, schemaId: String): List<SchemaSwitch> {
        val file = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!file.exists()) return emptyList()
        return parseSchemaSwitches(file)
    }

    /** 纯解析：从 .schema.yaml 读取 switches。 */
    internal fun parseSchemaSwitches(file: File): List<SchemaSwitch> {
        return try {
            val text = file.readText().trimStart('\uFEFF')
            val block = extractSwitchesBlock(text) ?: return emptyList()
            val listNode = yaml.parseToYamlNode(block) as? YamlList ?: return emptyList()
            listNode.items.mapNotNull { item ->
                parseSwitchEntry(item as? YamlMap ?: return@mapNotNull null)
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse switches for ${file.name}: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseSwitchEntry(entry: YamlMap): SchemaSwitch? {
        val states = (entry["states"] as? YamlList)
            ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            .orEmpty()
        if (states.isEmpty()) return null
        val name = (entry["name"] as? YamlScalar)?.content.orEmpty()
        val options = (entry["options"] as? YamlList)
            ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            .orEmpty()
        val abbrev = parseAbbrev(entry["abbrev"])
        return when {
            name.isNotBlank() -> SchemaSwitch(name = name, options = emptyList(), states = states, abbrev = abbrev)
            options.isNotEmpty() -> SchemaSwitch(name = "", options = options, states = states, abbrev = abbrev)
            else -> null
        }
    }

    /** abbrev 可以是单个字符串或每个状态一个的字符串列表。 */
    private fun parseAbbrev(node: YamlNode?): List<String> = when (node) {
        is YamlScalar -> listOf(node.content)
        is YamlList -> node.items.mapNotNull { (it as? YamlScalar)?.content }
        else -> emptyList()
    }

    /**
     * 从 YAML 文本中提取顶层 `switches:` 的列表项内容（不含 `switches:` 头行），
     * 返回一个可独立作为列表解析的 YAML 片段；不存在时返回 null。
     */
    internal fun extractSwitchesBlock(yamlText: String): String? {
        val lines = yamlText.lines()
        val idx = lines.indexOfFirst {
            it.trimStart().let { t -> t == "switches:" || (t.startsWith("switches:") && t[9] == ' ') }
        }
        if (idx < 0) return null
        val result = mutableListOf<String>()
        for (i in idx + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            if (indent == 0) break
            result.add(line)
        }
        if (result.isEmpty()) return null
        return result.joinToString("\n")
    }

    /** 内置方案（保持默认启用顺序）。 */
    internal val BUILTIN_SCHEMAS = listOf("wubi86", "wubi86_pinyin", "pinyin_simp", "t9_pinyin")

    /**
     * 内置方案补齐（纯函数）：用户启用列表尾部按 [BUILTIN_SCHEMAS] 顺序追加缺失项，
     * 用户已有顺序与选择保持不变；无缺失时原样返回。
     *
     * 背景：老版本升级用户的 custom.yaml 早于新内置方案（如 t9_pinyin）创建，
     * 列表里没有它 → 方案永不部署 → 切九键引擎侧静默失败（按键无候选）。
     */
    internal fun mergeBuiltinSchemas(enabled: List<String>): List<String> {
        val missing = BUILTIN_SCHEMAS.filter { it !in enabled }
        return if (missing.isEmpty()) enabled else enabled + missing
    }

    fun getEnabledSchemas(context: Context): List<String> {
        val customFile = getCustomYamlFile(context)
        if (!customFile.exists()) {
            // 注意：这里是「读取顺带首次初始化」。schemaListLock 可重入，
            // 由 updateEnabledSchemas 在锁内调用时不会死锁。
            setEnabledSchemas(context, BUILTIN_SCHEMAS)
            SettingsPreferences.setBuiltinSchemasMerged(context, true)
            return BUILTIN_SCHEMAS
        }

        try {
            val content = customFile.readText()
            val schemas = mutableListOf<String>()
            var inSchemaList = false
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed == "schema_list:") {
                    inSchemaList = true
                    continue
                }
                if (inSchemaList) {
                    if (trimmed.startsWith("- schema:")) {
                        val id = trimmed.removePrefix("- schema:").trim()
                        if (id.isNotEmpty()) schemas.add(id)
                    } else if (!trimmed.startsWith("- ")) {
                        inSchemaList = false
                    }
                }
            }
            if (schemas.isNotEmpty()) {
                // 内置方案补齐只执行一次（新版本首次运行，治老版本升级残留：
                // 列表无 t9_pinyin → 方案永不部署 → 切九键静默失败）。之后用户
                // 在方案管理中移除内置方案是有效选择，不得每次读取强行补回。
                // 补齐写回后 schema_list 计入部署 hash、build 产物按方案校验，
                // 缺失的方案会自动触发重部署。
                val merged = if (SettingsPreferences.isBuiltinSchemasMerged(context)) {
                    schemas
                } else {
                    val m = mergeBuiltinSchemas(schemas)
                    if (m != schemas) {
                        setEnabledSchemas(context, m)
                    }
                    SettingsPreferences.setBuiltinSchemasMerged(context, true)
                    m
                }
                return merged
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read custom.yaml", e)
        }

        return BUILTIN_SCHEMAS
    }

    /**
     * 方案启用列表的读-改-写互斥。
     *
     * `setEnabledSchemas` / `toggleSchema` 是典型的 load→mutate→save，且会被
     * 用户点击、部署、恢复备份三条路径并发触发。无锁时后写者覆盖前写者，
     * 用户刚切换的方案会「自己变回去」。
     */
    private val schemaListLock = Any()

    /**
     * 写入启用方案列表（对外入口）。
     *
     * 加锁的原因：本函数是「读 default.custom.yaml → 替换 schema_list 块 → 写回」的
     * 读-改-写，且会被用户点击、部署流程、恢复备份三条路径并发调用。无锁时后写者
     * 覆盖前写者，用户刚切换的方案会「自己变回去」。
     */
    fun setEnabledSchemas(context: Context, schemaIds: List<String>) {
        synchronized(schemaListLock) { setEnabledSchemasLocked(context, schemaIds) }
    }

    private fun setEnabledSchemasLocked(context: Context, schemaIds: List<String>) {
        val customFile = getCustomYamlFile(context)
        if (!customFile.exists()) {
            // 首次写入以 app 固定模板为基底（含 menu/page_size 等默认 patch），
            // 不再把整文件重写成只剩 schema_list 的空壳——那会丢掉 default.custom.yaml
            // 的全部补丁（menu/page_size 丢失后每页候选数漂移回引擎兜底值 5）
            if (!copyBuiltinDefaultCustom(context, customFile)) {
                // 模板复制失败兜底：保留旧行为，至少保证文件存在（getEnabledSchemas
                // 以文件存在为前提）且 schema_list 有效
                writeSchemaListOnlyCustom(customFile, schemaIds)
                applyEnabledSchemasToDefaultYaml(context, schemaIds)
                return
            }
        }
        // 只替换 schema_list 块，保留 menu/switcher/key_binder 等其余 patch
        try {
            val text = customFile.readText()
            val updated = replaceSchemaListBlock(text, schemaIds)
            if (updated != text) {
                // 原子写：避免截断窗口把用户的 menu/switcher/key_binder patch 弄丢
                writeTextAtomically(customFile, updated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write custom.yaml schema_list", e)
        }
        // F1: 同步写进 default.yaml，确保 librime 真正编译启用的方案
        applyEnabledSchemasToDefaultYaml(context, schemaIds)
    }

    /**
     * 原子写文本文件：写 `.tmp` 后 rename 覆盖。
     *
     * `File.writeText` 是「截断 + 写」，写到一半被杀/断电就留下半截文件。
     * 对 `default.custom.yaml` 这类承载用户自定义 patch 的文件，截断意味着
     * menu / switcher / key_binder 全部配置丢失（下次读取解析失败会回退内置默认）。
     */
    private fun writeTextAtomically(target: File, text: String): Boolean {
        // 统一实现：同目录临时文件 → 写入 → fsync → rename。
        // 早期版本漏了 fsync，掉电后可能出现「文件名在、内容空」。
        target.parentFile?.mkdirs()
        return com.kingzcheung.xime.util.AtomicFileWriter.write(target, text)
    }

    private fun writeSchemaListOnlyCustom(customFile: File, schemaIds: List<String>) {
        val sb = StringBuilder()
        sb.appendLine("patch:")
        sb.appendLine("  schema_list:")
        for (id in schemaIds) {
            sb.appendLine("    - schema: $id")
        }
        writeTextAtomically(customFile, sb.toString())
    }

    /**
     * 把 [input] 全量写入 [target]（不存在则创建）。
     *
     * 合并自本文件内 9 处逐字相同的 `input.use { target.outputStream().use { it.copyTo(..) } }`
     * 样板：调用方各自负责 [input] 的关闭，本函数只负责打开并关闭目标输出流。
     */
    private fun copyStreamTo(input: java.io.InputStream, target: File) {
        target.outputStream().use { output -> input.copyTo(output) }
    }

    /** 复制 app 固定的 assets/default.custom.yaml 模板（assets 根与 rime 目录同名，与 RimeConfigHelper 同步的是同一份）。 */
    private fun copyBuiltinDefaultCustom(context: Context, target: File): Boolean {
        return try {
            context.assets.open(CUSTOM_YAML).use { input ->
                copyStreamTo(input, target)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy builtin $CUSTOM_YAML", e)
            false
        }
    }

    /**
     * 启用/禁用某个方案。
     *
     * 必须走 [updateEnabledSchemas] 这把「读-改-写」的总锁：旧实现在锁外先
     * getEnabledSchemas() 再 setEnabledSchemas()，两次调用之间是可打断的。
     * 用户在设置页连点两个方案开关、或部署流程与用户点击并发时，两者各自读到
     * 同一份列表，后写者把前者的改动整套覆盖——表现为「刚启用的方案自己变回去了」
     * 「同时开两个方案只有一个生效」。这正是 schemaListLock 注释里承诺要消灭、
     * 但实际没消灭的竞态。
     */
    fun toggleSchema(context: Context, schemaId: String) {
        updateEnabledSchemas(context) { current ->
            if (schemaId in current) current.filterNot { it == schemaId } else current + schemaId
        }
    }

    /**
     * 启用列表的唯一读-改-写入口：读、变换、写、镜像 default.yaml 全在同一把锁内。
     *
     * @param transform 收到当前启用列表，返回新列表
     */
    fun updateEnabledSchemas(context: Context, transform: (List<String>) -> List<String>) {
        synchronized(schemaListLock) {
            val current = getEnabledSchemas(context)
            val next = transform(current)
            if (next != current) setEnabledSchemasLocked(context, next)
        }
    }

    fun isSchemaEnabled(context: Context, schemaId: String): Boolean {
        return schemaId in getEnabledSchemas(context)
    }

    suspend fun deleteSchemaFiles(context: Context, schemaId: String): Boolean {
        // 优先使用清单系统精准卸载
        val result = SchemaManifestManager.uninstallWithManifest(context, schemaId)
        if (result.manifestExisted) {
            if (result.success) {
                Log.i(TAG, "Manifest-based uninstall for $schemaId: ${result.message}")
            } else {
                Log.w(TAG, "Manifest-based uninstall for $schemaId failed: ${result.message}")
            }
        } else {
            // 降级到传统逻辑（无清单的旧方案）
            val rimeDir = getRimeDir(context)
            // 先读 dictName 再删 schema.yaml，否则 getReferencedDictName 永远返回 null
            val dictName = getReferencedDictName(context, schemaId) ?: schemaId
            val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
            if (schemaFile.exists()) schemaFile.delete()
            val dictFile = File(rimeDir, "$dictName.dict.yaml")
            if (dictFile.exists()) dictFile.delete()
            Log.i(TAG, "Legacy uninstall for $schemaId (dict=$dictName)")
        }

        // 删除 schemes/ 里对应的方案文件夹。
        //
        // 只删顶层文件不够：布局整理（ensureSchemeLayout）会从 schemes/ 把文件
        // 重新链接回顶层，用户看到的就是「卸载了但方案还在」。更糟的是
        // syncEnabledSchemeFolders 会为所有「已安装」方案建文件夹，
        // 残留的文件夹会让它以为方案仍然存在。
        runCatching {
            val schemesDir = File(getRimeDir(context), SchemaLayout.SCHEMES_DIR)
            if (schemesDir.isDirectory) {
                schemesDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
                    val hasThisSchema = dir.listFiles { f -> f.name == "$schemaId.schema.yaml" }
                        ?.isNotEmpty() == true
                    if (hasThisSchema) {
                        dir.deleteRecursively()
                        Log.i(TAG, "Removed scheme folder schemes/${dir.name} for $schemaId")
                    }
                }
            }
        }.onFailure { Log.w(TAG, "删除方案文件夹失败: $schemaId", it) }

        // 从已安装市场列表和启用列表中移除
        SettingsPreferences.removeInstalledMarketId(context, schemaId)
        // 读-改-写走总锁：与并发 toggle 互斥，避免把别人刚启用的方案覆盖掉
        updateEnabledSchemas(context) { current -> current.filterNot { it == schemaId } }
        return true
    }

    data class ImportResult(
        val success: Boolean,
        val installedDirect: Boolean = false,
        /** 与已安装方案同名不同内容的文件冲突（清单系统判定）；非空时导入被拒绝。 */
        val conflicts: List<FileConflictInfo> = emptyList(),
    )

    /** 判断文件名是否为压缩包。 */
    fun isArchive(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true) ||
        name.endsWith(".tar.gz", ignoreCase = true) ||
        name.endsWith(".tgz", ignoreCase = true)

    /** 判断文件名是否为图片（背景图等，导入到 themes/）。 */
    fun isImage(name: String): Boolean =
        name.endsWith(".jpg", ignoreCase = true) ||
        name.endsWith(".jpeg", ignoreCase = true) ||
        name.endsWith(".png", ignoreCase = true)

    /** 判断文件名是否为字体文件（导入到 fonts/）。 */
    fun isFont(name: String): Boolean =
        name.endsWith(".ttf", ignoreCase = true) ||
        name.endsWith(".otf", ignoreCase = true) ||
        name.endsWith(".woff", ignoreCase = true) ||
        name.endsWith(".woff2", ignoreCase = true)

    /** fonts 目录：rime/fonts/，存放用户导入的自定义字体。 */
    fun getFontsDir(context: Context): File =
        File(getRimeDir(context), "fonts")

    /** themes 目录：rime/themes/，存放用户导入或自定义的背景图片。 */
    fun getThemesDir(context: Context): File =
        File(getRimeDir(context), "themes")

    /**
     * 某些 Android 内容提供器会将未知 MIME 类型的文件（如 .yaml）自动追加 .txt 后缀，
     * 导致 xime.custom.yaml 变成 xime.custom.yaml.txt。此处修复已知情况。
     *
     * 同时剥掉路径分量：文件名可能来自不可信来源（无线导入的 multipart filename、
     * 分享 intent 的 displayName），`File(rimeDir, "../../foo")` 会写到 rime/ 之外。
     * 这里统一只取最后一段，并拒绝 `..`、空名与控制字符；调用方无需再各自校验。
     */
    fun sanitizeDisplayName(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
        val fixed = when {
            base.endsWith(".yaml.txt", ignoreCase = true) ->
                base.removeSuffix(".txt")
            base.endsWith(".dict.yaml.txt", ignoreCase = true) ->
                base.removeSuffix(".txt")
            else -> base
        }
        // 拒绝空名、`.`/`..` 以及含控制字符的名字（NUL 会让底层截断路径）。
        if (fixed.isEmpty() || fixed == "." || fixed == "..") return ""
        if (fixed.any { it < ' ' || it == '\u007f' }) return ""
        return fixed
    }

    /**
     * 统一保存导入的文件：
     * - 压缩包（zip/tar.gz/tgz）→ market/{importId}/，等待用户手动安装
     * - 非压缩包（yaml/txt/bin 等）→ 直接放入 rime/
     * 所有导入路径（文件选择器、无线导入、URL 下载等）最终都汇聚于此。
     */
    suspend fun saveImportedFile(
        context: Context,
        displayName: String,
        inputStream: java.io.InputStream,
        autoEnable: Boolean = true,
    ): ImportResult = withContext(Dispatchers.IO) {
        val name = sanitizeDisplayName(displayName)
        // 净化后为空说明名字非法（纯 `..`、含控制字符等）。不拦截会退化成
        // File(dir, "") == dir，后续 copyStreamTo 会往目录本身写而抛异常。
        if (name.isEmpty()) {
            FileLogger.w(TAG, "拒绝导入：非法文件名 ${displayName.take(80)}")
            return@withContext ImportResult(false)
        }
        if (isFont(name)) {
            // 字体文件：保存到 rime/fonts/，供自定义字体功能使用
            val fontsDir = getFontsDir(context)
            try {
                fontsDir.mkdirs()
                val target = File(fontsDir, name)
                inputStream.use { input ->
                    copyStreamTo(input, target)
                }
                FileLogger.i(TAG, "Imported $name -> rime/fonts/")
                ImportResult(true)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to import font $name", e)
                ImportResult(false)
            }
        } else if (isImage(name)) {
            // 图片：背景图等，保存到 rime/themes/，供主题使用
            val themesDir = getThemesDir(context)
            try {
                themesDir.mkdirs()
                val target = File(themesDir, name)
                inputStream.use { input ->
                    copyStreamTo(input, target)
                }
                FileLogger.i(TAG, "Imported $name -> rime/themes/")
                ImportResult(true)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to import image $name", e)
                ImportResult(false)
            }
        } else if (isArchive(name)) {
            // 压缩包：保存到 market/ 等待用户手动安装
            val importId = generateImportId()
            val pkgDir = getMarketDir(context, importId)
            try {
                pkgDir.mkdirs()
                val archiveFile = File(pkgDir, name)
                inputStream.use { input ->
                    copyStreamTo(input, archiveFile)
                }
                FileLogger.i(TAG, "Imported $name -> $importId (not installed yet)")
                ImportResult(true)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to import archive $name", e)
                runCatching { if (pkgDir.exists()) pkgDir.deleteRecursively() }
                    .onFailure { FileLogger.w(TAG, "cleanup pkgDir failed: ${it.message}") }
                ImportResult(false)
            }
        } else {
            importIntoRimeDir(context, name, inputStream, autoEnable)
        }
    }


    /**
     * saveImportedFile 的直装分支：非压缩包直接落入 rime/（不经 market）。
     * 受清单追踪的文件先暂存算 sha 并做冲突检测，避免静默覆盖市场方案。
     */
    private suspend fun importIntoRimeDir(
        context: Context,
        name: String,
        inputStream: java.io.InputStream,
        autoEnable: Boolean,
    ): ImportResult {
    // 非压缩包：直接放入 rime/ 目录，不经过 market
    val rimeDir = getRimeDir(context)
    try {
        rimeDir.mkdirs()
        // 受清单追踪的文件先暂存算 sha：与已安装方案同名不同内容时拒绝导入（不落盘，
        // 避免静默覆盖市场方案文件）；同内容视为共享依赖放行；重新导入同一文件视为重装放行。
        val tracked = shouldTrackImportedFile(name)
        var stagedFile: File? = null
        if (tracked) {
            val staged = File.createTempFile("import_stage_", ".tmp", context.cacheDir)
            inputStream.use { input ->
                copyStreamTo(input, staged)
            }
            val sha = fileSha256(staged)
            if (sha != null) {
                val conflicts = SchemaManifestManager.detectConflicts(
                    context, manifestPackageIdFor(name), listOf(name), mapOf(name to sha),
                )
                if (conflicts.isNotEmpty()) {
                    FileLogger.w(
                        TAG,
                        "Import rejected, $name conflicts with: ${conflicts.flatMap { it.claimedBy }}",
                    )
                    staged.delete()
                    return ImportResult(false, conflicts = conflicts)
                }
            }
            stagedFile = staged
        }
        try {
            val target = File(rimeDir, name)
            val source: java.io.InputStream = stagedFile?.inputStream() ?: inputStream
            if (name == UserConfigDir.UNIFIED_CONFIG_FILE ||
                name == UserConfigDir.LEGACY_UNIFIED_CONFIG_FILE ||
                name == UserConfigDir.CUSTOM_CONFIG_FILE
            ) {
                // 用户配置的唯一真源在可见目录 /storage/emulated/0/rime/xime/xime.yaml（读取优先级最高）。
                // 只写 rime/ 会被可见目录里的旧配置静默覆盖，故此处改走统一写入通道。
                val text = source.use { it.readBytes().toString(Charsets.UTF_8) }
                UserConfigDir.writeCustomConfig(context, text)
            } else {
                source.use { input ->
                    copyStreamTo(input, target)
                }
            }
            // 导入完成后整理布局：新方案收进 schemes/<id>/ 并镜像回顶层。
            // 放在自动启用之前——方案启用后 UI 会立即读取方案列表，
            // 那时目录结构应该已经就位。
            runCatching { ensureSchemeLayout(context, rimeDir) }
                .onFailure { FileLogger.w(TAG, "导入后整理方案布局失败", it) }
            // 如果是 .schema.yaml 文件，自动启用
            if (autoEnable && name.endsWith(".schema.yaml")) {
                val schemaId = name.removeSuffix(".schema.yaml")
                // 同上：走总锁，避免覆盖用户的并发改动
                updateEnabledSchemas(context) { current ->
                    if (schemaId in current) current else current + schemaId
                }
            }
            if (tracked) {
                SchemaManifestManager.createManifest(
                    context = context,
                    schemeId = manifestPackageIdFor(name),
                    displayName = name,
                    version = "",
                    fromMarket = false,
                    extractedFiles = listOf(name),
                )
            }
            FileLogger.i(TAG, "Imported $name -> rime/ (direct${if (tracked) ", tracked" else ""})")
            return ImportResult(true, installedDirect = true)
        } finally {
            stagedFile?.delete()
        }
    } catch (e: Exception) {
        FileLogger.e(TAG, "Failed to import $name directly", e)
        return ImportResult(false)
    }
    }
    suspend fun importSchemaFile(context: Context, uri: Uri): ImportResult {
        return withContext(Dispatchers.IO) {
            val displayName = getFileName(context, uri) ?: return@withContext ImportResult(false)

            val inputStream = when (uri.scheme) {
                "file" -> uri.path?.let { java.io.FileInputStream(it) }
                else -> context.contentResolver.openInputStream(uri)
            } ?: return@withContext ImportResult(false)

            saveImportedFile(context, displayName, inputStream)
        }
    }

    /**
     * 找到所有 .schema.yaml 文件所在的共同父目录作为基目录。
     * 例如 zip 结构为 rime-ice-main/rime_ice.schema.yaml，
     * 则返回 "rime-ice-main/"，解压时剥离此前缀。
     * 基目录下的子目录（如 cn_dicts/）会原样保留。
     */
    internal fun findSchemaBaseDir(entryNames: List<String>): String {
        val schemaEntries = entryNames.filter { it.endsWith(".schema.yaml") }
        if (schemaEntries.isEmpty()) {
            // 无 .schema.yaml 的包(如 rime-essay 只含 essay.txt、rime-prelude 含 symbols.yaml）：
            // 若所有条目同处唯一壳目录（GitHub 归档形如 <repo>-<branch>/），剥掉它，
            // 否则文件会落进子目录（rime/rime-essay-master/essay.txt）导致 rime 读不到。
            val files = entryNames.filter { it.isNotBlank() }
            if (files.isEmpty() || files.any { !it.contains('/') }) return ""
            val tops = files.map { it.substringBefore('/') }.distinct()
            return if (tops.size == 1) "${tops[0]}/" else ""
        }
        // 获取所有 .schema.yaml 的父目录
        val parentDirs = schemaEntries.map { name ->
            val idx = name.lastIndexOf('/')
            if (idx >= 0) name.substring(0, idx + 1) else ""
        }.distinct()
        // 如果所有 .schema.yaml 在同一个父目录下，返回该目录作为基目录
        if (parentDirs.size == 1) {
            return parentDirs[0]
        }
        // 在不同目录下，找最长公共前缀
        val commonPrefix = parentDirs.reduce { a, b -> a.commonPrefixWith(b) }
        val idx = commonPrefix.lastIndexOf('/')
        return if (idx >= 0) commonPrefix.substring(0, idx + 1) else ""
    }

    /**
     * 从 URL 下载文件到 market/{schemeId}/ 目录（仅下载，不解压）。
     * 支持任意文件类型（zip、tar.gz、yaml 等）。
     */
    /** 下载结果：success + sha256 校验状态（null=未提供, true=通过, false=不通过）。 */
    data class DownloadResult(val success: Boolean, val sha256Verified: Boolean? = null)

    suspend fun downloadToMarket(
        context: Context,
        url: String,
        schemeId: String,
        fileName: String,
        expectedSha256: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Xime:SchemaDownload")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(5 * 60 * 1000L)
        try {
            val schemeDir = getMarketDir(context, schemeId)
            if (!schemeDir.exists()) schemeDir.mkdirs()
            val targetFile = File(schemeDir, fileName)

            downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: ${response.code} $url")
                    return@withContext DownloadResult(false)
                }
                val body = response.body
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                val md = if (!expectedSha256.isNullOrBlank()) MessageDigest.getInstance("SHA-256") else null
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buf = ByteArray(8192)
                        var n = input.read(buf)
                        while (n >= 0) {
                            output.write(buf, 0, n)
                            md?.update(buf, 0, n)
                            downloadedBytes += n
                            if (totalBytes > 0) onProgress(downloadedBytes, totalBytes)
                            n = input.read(buf)
                        }
                    }
                }
                if (md != null && !expectedSha256.isNullOrBlank()) {
                    val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                        Log.e(TAG, "sha256 mismatch for $url: expected=${expectedSha256.trim()} actual=$actual")
                        targetFile.delete()
                        return@withContext DownloadResult(false, sha256Verified = false)
                    }
                    Log.i(TAG, "Downloaded (sha256 verified): ${targetFile.absolutePath}")
                    DownloadResult(true, sha256Verified = true)
                } else {
                    val valid = validateArchive(targetFile)
                    if (!valid) {
                        Log.e(TAG, "Archive validation failed for $url, file is corrupted")
                        targetFile.delete()
                        return@withContext DownloadResult(false)
                    }
                    Log.i(TAG, "Downloaded (unverified, archive validated): ${targetFile.absolutePath}")
                    DownloadResult(true, sha256Verified = null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadToMarket failed: $url", e)
            val schemeDir = getMarketDir(context, schemeId)
            val targetFile = File(schemeDir, fileName)
            if (targetFile.exists()) targetFile.delete()
            if (schemeDir.exists() && schemeDir.listFiles().isNullOrEmpty()) {
                schemeDir.delete()
            }
            DownloadResult(false)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    /**
     * 验证压缩包完整性：zip 尝试列出条目，tar.gz 尝试读取首条。
     * 非归档文件直接返回 true（无法校验）。
     */
    private fun validateArchive(file: File): Boolean {
        val name = file.name
        return try {
            when {
                name.endsWith(".zip", ignoreCase = true) -> {
                    java.util.zip.ZipFile(file).use { zip -> zip.entries().hasMoreElements() }
                }
                name.endsWith(".tar.gz", ignoreCase = true) || name.endsWith(".tgz", ignoreCase = true) -> {
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                        java.util.zip.GZIPInputStream(file.inputStream())
                    ).use { tar -> tar.nextTarEntry != null }
                }
                else -> true // 非归档文件无法校验
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Archive validation failed for ${file.name}", e)
            false
        }
    }

    /**
     * 从 URL 下载压缩包并解压进 rime 目录。
     * @param expectedSha256 非空时，下载落临时文件并校验 SHA-256；不符则不落盘、返回 false。
     *                       为空/空白时保持原有行为（不校验）。
     */
    /**
     * 从 URL 下载文件（不限格式），通过 [saveImportedFile] 统一存入 rime/ 或 market/。
     * @param archiveName 文件名，为空时从 URL 末段自动推断。
     */
    suspend fun importFromUrl(
        context: Context,
        url: String,
        expectedSha256: String? = null,
        archiveName: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val fileName = archiveName ?: url.substringAfterLast("/").takeIf { it.isNotBlank() }
                ?: "download"
            val tmpFile = File.createTempFile("import_", ".tmp", context.cacheDir)

            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val sha256Ok = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download failed: ${response.code} $url")
                        return@withContext ImportResult(false)
                    }
                    val body = response.body
                    if (body.contentLength() > 256L * 1024 * 1024) { Log.e(TAG, "Download too large: $url"); return@withContext ImportResult(false) }
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    val maxDownloadBytes = 256L * 1024 * 1024 // 防止超大响应写满 cacheDir
                    val md = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buf = ByteArray(8192)
                            var n = input.read(buf)
                            while (n >= 0) {
                                output.write(buf, 0, n)
                                md.update(buf, 0, n)
                                downloadedBytes += n
                                if (downloadedBytes > 256L * 1024 * 1024) throw java.io.IOException("download exceeds size limit")
                                if (totalBytes > 0) onProgress(downloadedBytes, totalBytes)
                                n = input.read(buf)
                            }
                        }
                    }
                    if (!expectedSha256.isNullOrBlank()) {
                        val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                            Log.e(TAG, "sha256 mismatch for $url")
                            return@withContext ImportResult(false)
                        }
                    }
                    true
                }
                if (!sha256Ok) return@withContext ImportResult(false)

                // 通过统一函数保存
                val result = saveImportedFile(context, fileName, tmpFile.inputStream())
                Log.i(TAG, "Imported from url $url -> ${if (result.installedDirect) "rime/" else "market/"} ($fileName)")
                result
            } finally {
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import from URL: $url", e)
            ImportResult(false)
        }
    }

    internal fun importZipFromStream(inputStream: InputStream, targetDir: File): Boolean {
        return try {
            // 保存到临时文件，以便两趟处理（检测共同根目录 + 解压）
            val tempFile = File.createTempFile("rime_import_", ".zip")
            try {
                copyStreamTo(inputStream, tempFile)
                importZipFromFile(tempFile, targetDir)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract zip stream", e)
            false
        }
    }

    private fun importZipFromFile(zipFile: File, targetDir: File): Boolean {
        return try {
            // 第一趟：收集文件名以检测共同根目录（排除 Apple Double）
            val entryNames = mutableListOf<String>()
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (!entry.isDirectory && !isAppleDouble(entry.name)) {
                        entryNames.add(entry.name)
                    }
                }
            }

            val baseDir = findSchemaBaseDir(entryNames)
            if (baseDir.isNotEmpty()) {
                Log.i(TAG, "Found schema base directory: $baseDir, will strip it on extraction")
            }

            // 第二趟：先解到同级暂存目录（按实际写出字节限额），全部成功后再整体替换进目标目录，
            // 中途失败不会留下写了一半的方案文件
            var count = 0
            val budget = ArchiveBudget()
            val staging = ArchiveStaging.create(targetDir)
            try {
                ZipFile(zipFile).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        val originalName = entry.name
                        if (entry.isDirectory || isAppleDouble(originalName)) return@forEach
                        val name = originalName.removePrefix(baseDir)
                        val file = if (isProtectedImportName(name)) null else safeChild(staging.stagingDir, name)
                        if (file == null) {
                            FileLogger.d(TAG, "Skip protected/unsafe entry: $name")
                        } else {
                            file.parentFile?.mkdirs()
                            budget.onEntry()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(file).use { output -> budget.copy(input, output) }
                            }
                            count++
                            FileLogger.d(TAG, "Staged zip entry: $name")
                        }
                    }
                }
                if (count > 0) staging.commit()
            } finally {
                staging.cleanup()
            }
            FileLogger.i(TAG, "Extracted $count files from zip stream")
            count > 0
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to extract zip file", e)
            false
        }
    }

    internal fun importTarGzFromStream(inputStream: InputStream, targetDir: File): Boolean {
        return try {
            // 落临时文件后走文件版（统一 gunzip + zip-slip/受保护文件 防护）
            val tempFile = File.createTempFile("rime_import_", ".tar.gz")
            try {
                copyStreamTo(inputStream, tempFile)
                importTarGzFromFile(tempFile, targetDir)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract tar.gz stream", e)
            false
        }
    }

    private fun importTarGzFromFile(tarGzFile: File, targetDir: File): Boolean {
        return try {
            // 第一趟：收集文件名以检测共同根目录（排除 Apple Double）
            val entryNames = mutableListOf<String>()
            TarArchiveInputStream(GzipCompressorInputStream(tarGzFile.inputStream().buffered())).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && !isAppleDouble(entry.name)) {
                        entryNames.add(entry.name)
                    }
                    entry = tarIn.nextEntry
                }
            }

            val baseDir = findSchemaBaseDir(entryNames)
            if (baseDir.isNotEmpty()) {
                Log.i(TAG, "Found schema base directory in tar.gz: $baseDir, will strip it")
            }

            // 第二趟：先解到同级暂存目录（按实际写出字节限额），全部成功后再整体替换进目标目录
            var count = 0
            val budget = ArchiveBudget()
            val staging = ArchiveStaging.create(targetDir)
            try {
                TarArchiveInputStream(GzipCompressorInputStream(tarGzFile.inputStream().buffered())).use { tarIn ->
                    var entry = tarIn.nextEntry
                    while (entry != null) {
                        val originalName = entry.name
                        if (entry.isDirectory || isAppleDouble(originalName)) {
                            entry = tarIn.nextEntry
                            continue
                        }
                        val name = originalName.removePrefix(baseDir)
                        val file = if (isProtectedImportName(name)) null else safeChild(staging.stagingDir, name)
                        if (file == null) {
                        } else {
                            file.parentFile?.mkdirs()
                            budget.onEntry()
                            FileOutputStream(file).use { output -> budget.copy(tarIn, output) }
                            count++
                        }
                        entry = tarIn.nextEntry
                    }
                }
                if (count > 0) staging.commit()
            } finally {
                staging.cleanup()
            }
            Log.i(TAG, "Extracted $count files from tar.gz")
            count > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract tar.gz", e)
            false
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment
    }
}