package com.kingzcheung.xime.settings

import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 方案目录布局：让 rime 目录下的方案「一个方案一个文件夹」，而不是散落平铺。
 *
 * ## 为什么需要"镜像"
 *
 * librime 的 schema_discovery **只扫描 RIME_USER_DATA_DIR 顶层**的 `*.schema.yaml`
 * （C++ 侧硬编码，见 `/librime/src/rime/config/config_component.cc`）。
 * 把方案文件真的搬进子目录，引擎就一个方案都加载不到——输入法直接没法用。
 *
 * 所以这里采用「源文件分目录 + 部署时镜像回根」：
 * - `rime/schemes/<方案>/` 存该方案**独占**的文件，这是权威副本；
 * - 引擎读的是镜像到 rime 顶层的同一份（硬链接，内容同步，不占双份空间）。
 *
 * ## 归属规则
 *
 * 按引用关系判定：一个数据文件被几个方案引用。
 * - 只被 1 个方案引用 → 独占，归到该方案的目录
 * - 被 2+ 个引用 → **共享，留在 rime 根**（否则第二份会覆盖第一份的链接）
 *
 * 实测的内置方案里，`symbols.yaml` 被 4 个方案引用、`wubi86.dict.yaml` 被 3 个、
 * `pinyin_simp.dict.yaml` 被 4 个——都属共享，留在根。
 */
object SchemaLayout {

    private const val TAG = "SchemaLayout"

    /** 方案源文件目录名（rime/schemes/）。 */
    const val SCHEMES_DIR = "schemes"

    /**
     * 引擎/逻辑产物：不参与镜像、不参与迁移、也不该被删除。
     *
     * 这些是 librime 编译产生的，或由同步通道单独维护的，搬走会让引擎重新全量编译
     * 甚至丢词。判断标准是「删了能重建但不能保证等价」。
     */
    private val ENGINE_ARTIFACT_SUFFIXES = listOf(
        ".bin", ".gram", ".table.bin", ".reverse.bin", ".prism.bin",
        ".db", ".db-wal", ".db-shm", ".userdb.txt",
    )

    /** 完全跳过、不碰的顶层目录名。 */
    private val SKIP_DIRS = setOf("build", "schemes", "sync", "opencc", "lua", "fonts", "themes")

    data class MirrorStats(
        val linked: Int = 0,
        val copied: Int = 0,
        val skipped: Int = 0,
    ) {
        val total: Int get() = linked + copied + skipped
        override fun toString(): String = "linked=$linked copied=$copied skipped=$skipped"
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 方案目录：`rime/schemes/<清洗后的 schemaId>`。 */
    fun schemeDir(rimeDir: File, schemaId: String): File =
        File(File(rimeDir, SCHEMES_DIR), sanitizeSchemeName(schemaId))

    /**
     * 清洗方案显示名作目录名。
     *
     * 用户要求文件夹以**方案名**（显示名）命名，如 `五笔86`、`简体拼音`。
     * 显示名来自 schema 的 `name:` 字段，可能含引号、斜杠、`..` 等，
     * 必须清洗后才能当目录名（否则 `../../x` 能写到 schemes 外）。
     */
    internal fun sanitizeSchemeName(name: String): String {
        val cleaned = name.trim().trim('"', '\'')
            .replace('/', '_').replace('\\', '_')
            .replace(Regex("""[<>:"|?*]"""), "_")
            .trim('.', ' ')
        if (cleaned.isEmpty() || cleaned == "_") return "_unnamed"
        return cleaned
    }

    /**
     * 方案的目录名：优先用显示名（用户要求），拿不到显示名时退回 schema_id。
     *
     * 同一个 scheme_id 在 schemes/ 下**只能有一个目录**——所以这里要先读 schema 的
     * `name:` 字段。读不到时用 schema_id，至少不会丢方案。
     */
    fun schemeFolderName(rimeDir: File, schemaId: String): String {
        val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
        val display = if (schemaFile.isFile) parseDisplayName(safeReadText(schemaFile)) else null
        return sanitizeSchemeName(display ?: schemaId)
    }

    /** 解析 schema 顶层的 `name:` 字段（取 `schema:` 块下的第一个 name）。 */
    internal fun parseDisplayName(schemaYaml: String): String? {
        val schemaBlock = extractSchemaBlockText(schemaYaml) ?: schemaYaml
        Regex("""(?m)^\s{2}name:\s*(.+)$""").find(schemaBlock)?.let { m ->
            val v = m.groupValues[1].trim().trim('"', '\'')
            if (v.isNotEmpty()) return v
        }
        return null
    }

    /** 粗略取 `schema:` 顶层块（到下一个同级键之前）。 */
    private fun extractSchemaBlockText(text: String): String? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimEnd() == "schema:" }
        if (start < 0) return null
        val out = StringBuilder()
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("-")) break
            out.append(line).append('\n')
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /** 扫描 `schemes/`，得到 方案目录名 → 文件列表（只含普通文件，递归）。 */
    fun discoverSchemeFiles(rimeDir: File): Map<String, List<File>> {
        val schemesDir = File(rimeDir, SCHEMES_DIR)
        if (!schemesDir.isDirectory) return emptyMap()
        return schemesDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .associate { dir ->
                dir.name to dir.walkTopDown().filter { it.isFile }.toList()
            }
    }

    /** rime 顶层里属于「方案相关」的文件（排除引擎产物与专用目录）。 */
    fun rootSchemaCandidates(rimeDir: File): List<File> {
        if (!rimeDir.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        rimeDir.listFiles().orEmpty().forEach { f ->
            when {
                f.isDirectory -> {
                    if (f.name !in SKIP_DIRS) out += f.walkTopDown().filter { it.isFile }.toList()
                }
                else -> if (isSchemaRelatedFile(f.name)) out += f
            }
        }
        return out
    }

    /** 是否方案相关文件（yaml / txt 等配置与词典，排除引擎产物）。 */
    internal fun isSchemaRelatedFile(name: String): Boolean {
        if (ENGINE_ARTIFACT_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }) return false
        return name.endsWith(".yaml", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true) ||
            name.endsWith(".yml", ignoreCase = true)
    }

    // ------------------------------------------------------------------
    // 归属判定
    // ------------------------------------------------------------------

    /**
     * 解析方案文件引用的资源名。
     *
     * 三种引用形式都要认（都真实存在于内置方案里）：
     * - `dictionary: wubi86`            —— schema 的主词典（wubi86.schema.yaml:51）
     * - `__include: symbols:/xxx`       —— 引用共享层（pinyin_simp.schema.yaml / wubi86.schema.yaml）
     * - `import_tables: [wubi86_extra]` —— **词典文件**里再引别的词典
     *   （wubi86.dict.yaml:7、pinyin_simp.dict.yaml）
     *
     * 最后一种最容易漏：`wubi86_extra.dict.yaml` 不是被 schema 直接引用，
     * 而是被 `wubi86.dict.yaml` 的 import_tables 引用。不认它的话，
     * 这类词典会全部留在根目录，用户看到的就是「没整理」。
     *
     * 只做保守的文本解析，不解析完整 YAML——方案文件常有重复键/锚点，
     * 严格解析会抛异常；这里只需要文件名主干用于判重，不需要语义正确。
     */
    internal fun referencedResources(text: String): Set<String> {
        val out = mutableSetOf<String>()
        val singleRe = listOf(
            Regex("""(?m)^\s*dictionary:\s*([A-Za-z0-9_/.-]+)"""),
            Regex("""(?m)^\s*__include:\s*([A-Za-z0-9_/.-]+)"""),
        )
        singleRe.forEach { re ->
            re.findAll(text).forEach {
                // 同样要剥注释：`dictionary: wubi86  # 主词库`
                stripYamlComment(it.groupValues[1]).substringAfterLast('/')
                    .takeIf { v -> v.isNotEmpty() }?.let { v -> out += v }
            }
        }
        // import_tables 两种写法都支持：
        //   import_tables:
        //     - wubi86_extra
        //   import_tables: [a, b]
        //
        // 逐行扫而不是整块正则：块后面紧跟其他键（encoder:/sort:）很常见，
        // 整块匹配容易在边界上失配（曾导致 wubi86_extra 被漏掉、留在根目录）。
        val lines = text.lines()
        var inBlock = false
        for (raw in lines) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed.startsWith("import_tables:") -> {
                    val inline = trimmed.removePrefix("import_tables:").trim()
                    if (inline.startsWith("[")) {
                        stripYamlComment(inline.trim('[', ']', ' ')).split(',').forEach { v ->
                            v.trim().trim('"', '\'').substringAfterLast('/')
                                .takeIf { it.isNotEmpty() }?.let { out += it }
                        }
                        inBlock = false
                    } else {
                        inBlock = true
                    }
                }
                inBlock && trimmed.startsWith("- ") -> {
                    // 必须剥掉行尾注释：真实词库的 import_tables 几乎每行都带注释
                    //    - Flypy                     # 主词库(完整码表 23.3 万行)
                    // 不剥的话资源名会变成 "Flypy   # 主词库..."，与任何文件都对不上，
                    // 该词典就永远归不进方案文件夹（线上实测：XGuai_flypy 的 5 个词库全漏）。
                    stripYamlComment(trimmed.removePrefix("- "))
                        .trim().trim('"', '\'').substringAfterLast('/')
                        .takeIf { it.isNotEmpty() }?.let { out += it }
                }
                // 块内出现非列表行（下一个键）→ 块结束
                inBlock && trimmed.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("\t") -> {
                    inBlock = false
                }
            }
        }
        return out
    }

    /**
     * 判定 rime 顶层每个文件的归属：文件 → 方案**文件夹名**（显示名）；共享文件不出现。
     *
     * 一个方案文件夹里要装下它**专属**的全部内容：
     * - 它的 schema 文件 `X.schema.yaml`
     * - 它的方案配置补丁 `X.custom.yaml`（librime 的方案级 patch，属该方案私有）
     * - 它独占的词典 `X.dict.yaml` 及 import_tables 链上的子词典
     *
     * 被 2+ 方案引用的（如 `wubi86.dict.yaml` 被 5 个方案用）留根——
     * 用户选了「只放专属文件（省空间）」，复制多份几十 MB 的词典不划算。
     *
     * @return 专属文件 → 所属方案文件夹名
     */
    fun resolveOwnership(rimeDir: File): Map<File, String> {
        val candidates = rootSchemaCandidates(rimeDir)
        if (candidates.isEmpty()) return emptyMap()

        val schemeNameById = buildMap {
            candidates.filter { it.name.endsWith(".schema.yaml") }.forEach {
                put(schemaIdOf(it), sanitizeSchemeName(parseDisplayName(safeReadText(it)) ?: schemaIdOf(it)))
            }
        }

        // 资源名 → 文件（同名可能有多个扩展名，如 xxx.dict.yaml / xxx.yaml）
        val byStem = candidates.groupBy { resourceStemOf(it) }

        val schemaFiles = candidates.filter { it.name.endsWith(".schema.yaml") }

        // 每个资源名被哪些方案「直接」引用（schema 的 dictionary / __include）
        val directClaimers = mutableMapOf<String, MutableSet<String>>()
        schemaFiles.forEach { f ->
            val sid = schemaIdOf(f)
            referencedResources(safeReadText(f)).forEach { res ->
                directClaimers.getOrPut(res) { mutableSetOf() }.add(sid)
            }
        }

        // 传递解析：词典文件之间的 import_tables 引用要顺着归属传递。
        // 例：wubi86.schema.yaml --dictionary--> wubi86.dict.yaml --import_tables--> wubi86_extra.dict.yaml
        // 不传递的话 wubi86_extra 无人引用，会被留在根目录（用户看到"没整理"）。
        val resolved = directClaimers.mapValues { it.value.toMutableSet() }.toMutableMap()
        var changed = true
        var guard = 0
        while (changed && guard++ < 16) {
            changed = false
            resolved.toMap().forEach { (res, claimers) ->
                byStem[res].orEmpty().forEach { file ->
                    if (!file.name.endsWith(".dict.yaml") && !file.name.endsWith(".dict.yml")) return@forEach
                    referencedResources(safeReadText(file)).forEach { child ->
                        val set = resolved.getOrPut(child) { mutableSetOf() }
                        if (set.addAll(claimers)) changed = true
                    }
                }
            }
        }

        val result = LinkedHashMap<File, String>()
        candidates.forEach { file ->
            val name = file.name
            // 方案自身的 schema：天然归自己
            if (name.endsWith(".schema.yaml")) {
                schemeNameById[schemaIdOf(file)]?.let { result[file] = it }
                return@forEach
            }
            // 方案的配置补丁 X.custom.yaml：与 X.schema.yaml 配套，属该方案私有。
            // 这是「方案配置」被漏掉的那一类——用户明确指出方案配置没归纳进去。
            if (name.endsWith(".custom.yaml")) {
                val sid = name.removeSuffix(".custom.yaml")
                // 只有确实存在同名方案时才算方案配置；default.custom.yaml / trime.custom.yaml
                // 是全局配置，不能被当成某个方案的私有文件
                if (schemeNameById.containsKey(sid)) result[file] = schemeNameById.getValue(sid)
                return@forEach
            }
            val claimers = resolved[resourceStemOf(file)].orEmpty()
            // 恰好一个方案引用 → 独占。0 个（如 custom_phrase.txt）或 2+ → 留根
            if (claimers.size == 1) {
                schemeNameById[claimers.first()]?.let { result[file] = it }
            }
        }
        return result
    }

    /**
     * 资源名：去掉扩展名与 `.dict` 后缀。
     * `pinyin_simp.dict.yaml` → `pinyin_simp`（对应 `dictionary: pinyin_simp`）
     */
    internal fun resourceStemOf(file: File): String = file.name
        .removeSuffix(".yaml").removeSuffix(".yml").removeSuffix(".txt")
        .removeSuffix(".dict")

    /** 找到包含指定文件名的方案文件夹（没有则 null）。 */
    internal fun findSchemeFolderContaining(rimeDir: File, fileName: String): File? {
        val schemesDir = File(rimeDir, SCHEMES_DIR)
        if (!schemesDir.isDirectory) return null
        return schemesDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .firstOrNull { File(it, fileName).isFile }
    }

    internal fun schemaIdOf(schemaFile: File): String = schemaFile.name.removeSuffix(".schema.yaml")

    /**
     * 剥掉 YAML 行尾注释（`值  # 说明`）。
     *
     * 只在「注释前是空白」时才算注释——`#` 可能出现在值内部（如颜色 `#FF0000`），
     * 那不该被误剥。够用即可：这里解析的是资源名/路径，不含 `#`。
     */
    internal fun stripYamlComment(value: String): String {
        val idx = value.indexOf('#')
        if (idx < 0) return value.trim()
        if (idx == 0) return ""
        // 注释符前必须是空白才算注释
        return if (value[idx - 1].isWhitespace()) value.substring(0, idx).trim() else value.trim()
    }

    // ------------------------------------------------------------------
    // 镜像
    // ------------------------------------------------------------------

    /**
     * 找出某个方案**完整依赖**的文件集合（含共享资源），用于「启用哪个方案就用哪个方案的东西」。
     *
     * 依赖链：schema 自身 → dictionary 引用的词典 → 词典 import_tables 的子词典
     *        → __include 引用的共享层（symbols 等）→ opencc 配置 → lua 模块 → 方案的 custom 补丁
     *
     * 返回的路径都是**相对 rime 根**的形式（如 `wubi86.schema.yaml`、`symbols.yaml`），
     * 因为共享资源留在根、专属文件在 schemes 里，两者位置不同但引擎看到的都是根。
     */
    fun resolveSchemeDependencies(rimeDir: File, schemaId: String): Set<String> {
        val out = linkedSetOf<String>()
        // schema 可能在顶层（已启用）或已归档进某个方案文件夹（未启用）。
        // 只在顶层找的话，已归档方案会返回空集合 → 文件夹内容被当成"多余"清空。
        val schemeFolder = findSchemeFolderContaining(rimeDir, "$schemaId.schema.yaml")
        val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
            .takeIf { it.isFile }
            ?: schemeFolder?.let { File(it, "$schemaId.schema.yaml") }
            ?: return out
        out += "$schemaId.schema.yaml"
        // 方案配置补丁（若存在）——同样可能在顶层或文件夹里
        if (File(rimeDir, "$schemaId.custom.yaml").isFile ||
            schemeFolder?.let { File(it, "$schemaId.custom.yaml").isFile } == true
        ) {
            out += "$schemaId.custom.yaml"
        }

        fun fileFor(res: String): File? {
            val names = listOf("$res.dict.yaml", "$res.dict.yml", "$res.yaml", "$res.yml", "$res.txt")
            // 优先顶层，其次该方案的文件夹
            names.map { File(rimeDir, it) }.firstOrNull { it.isFile }?.let { return it }
            schemeFolder?.let { dir ->
                names.map { File(dir, it) }.firstOrNull { it.isFile }?.let { return it }
            }
            return null
        }

        val queue = ArrayDeque<String>()
        referencedResources(safeReadText(schemaFile)).forEach { queue += it }
        val seen = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val res = queue.removeFirst()
            if (!seen.add(res)) continue
            val file = fileFor(res) ?: continue
            out += file.name
            // 词典可能再 import 别的词典
            if (file.name.endsWith(".dict.yaml") || file.name.endsWith(".dict.yml")) {
                referencedResources(safeReadText(file)).forEach { queue += it }
            }
        }

        // **不收集 lua 脚本与 opencc 词典**。
        //
        // 它们是跨方案的共享资源：`lua/uuid.lua` 被五笔86/简体拼音等多个方案引用，
        // `opencc/*.ocd2` 被所有需要简繁转换的方案共用。
        // 把它们复制进每个方案文件夹既浪费空间，也会让「改一处要同步 N 份」变成维护负担。
        // 保持它们在 rime 根下的 lua/ 与 opencc/ 目录，各方案按 librime 的约定引用即可。
        return out
    }

    /**
     * 同步「已启用方案」的文件夹：**只有启用的方案有文件夹**，且每个文件夹包含它引用的全部文件。
     *
     * 用户的两条要求都在这里落地：
     * 1. **只显示已启用的方案** → 未启用方案的文件夹被删除
     * 2. **引用的配置和词库归纳完全** → 按 [resolveSchemeDependencies] 的完整闭包补齐，
     *    包括共享词典（wubi86.dict.yaml 等）、`symbols.yaml`、lua 脚本、opencc 配置
     *
     * 用**硬链接**而不是复制：同一个共享词典被 5 个方案引用时，5 个文件夹里各有一个
     * 名字，但磁盘上只有一份 inode。既满足「文件夹完整」，又不会把几十 MB 的词典
     * 复制 5 份。
     */
    fun syncEnabledSchemeFolders(rimeDir: File, enabledSchemaIds: List<String>): FolderStats {
        val schemesDir = File(rimeDir, SCHEMES_DIR)
        schemesDir.mkdirs()

        val enabledFolders = LinkedHashSet<String>()
        var files = 0

        // 处理**所有已安装方案**，不只是已启用的。
        //
        // 用户要求「都放在方案文件夹」：未启用方案的配置和词库也要归档到
        // schemes/<方案名>/ 里待命，否则它们会散在 rime 顶层，
        // 或者在被归档后又因为"文件夹不存在"而丢失。
        //
        // 已启用方案的 schema 在顶层（引擎要读），未启用的可能在文件夹里——
        // 两种来源都要能取到它的依赖闭包。
        // 权威依据：**APK 内置资产 + 顶层现存 schema**。
        //
        // 不能只看「文件夹里有 schema」——那会形成自锁：
        // 卸载方案时如果删文件夹失败，下次启动又从文件夹反推出「方案还在」并重建，
        // 用户看到的就是「卸载了但方案又回来了」。
        // 内置资产由 restoreMissingBuiltinSchemes 负责补齐，市场方案看顶层。
        val installedIds = LinkedHashSet<String>()
        installedIds += enabledSchemaIds
        // 顶层 schema（引擎能扫到的，也是卸载时会删的）
        rimeDir.listFiles { f -> f.name.endsWith(".schema.yaml") }.orEmpty()
            .forEach { installedIds += schemaIdOf(it) }

        installedIds.forEach { sid ->
            val schema = File(rimeDir, "$sid.schema.yaml")
            // 顶层没有该方案时，先从 schemes/ 里恢复回来。
            // 归档过的方案（未启用时被移进 schemes/）重新启用必须走这条路，
            // 否则引擎永远看不到它——用户点了启用但方案不生效。
            if (!schema.isFile) restoreSchemeFromFolders(rimeDir, sid)
            if (!schema.isFile) return@forEach
            val folderName = schemeFolderName(rimeDir, sid)
            val dir = File(schemesDir, folderName)
            enabledFolders += folderName
            dir.mkdirs()

            val deps = resolveSchemeDependencies(rimeDir, sid)

            // 先清掉文件夹里不在依赖闭包内的旧文件，保证"内容 == 该方案真正引用的"。
            //
            // **安全前提：依赖闭包非空才清理。**
            // `resolveSchemeDependencies` 在「schema 找不到」时返回空集合，
            // 若此时仍执行清理，会把整个方案文件夹清空——方案文件全丢。
            // 空集合只可能来自解析失败（正常方案至少有自己那条 schema），所以跳过最安全。
            if (deps.isEmpty()) {
                FileLogger.w(TAG, "跳过清理 schemes/$folderName：依赖闭包为空（解析失败，保留现有文件）")
            } else {
                runCatching {
                    dir.walkTopDown().filter { it.isFile }.toList().forEach { f ->
                        if (f.relativeTo(dir).path !in deps) f.delete()
                    }
                    // 删空的子目录（lua/、opencc/ 清空后不该留空壳）
                    dir.walkBottomUp().filter { it.isDirectory && it != dir }.toList().forEach { d ->
                        if (d.listFiles().isNullOrEmpty()) d.delete()
                    }
                }
            }

            deps.forEach { rel ->
                val src = File(rimeDir, rel)
                if (!src.isFile) return@forEach   // 引用但不存在（如尚未生成的用户词库）跳过
                val target = File(dir, rel)
                if (ensureLinked(src, target)) files++
            }
        }

        // **不删除未启用方案的文件夹**。
        //
        // 曾经的写法是「未启用方案的文件夹一律删除」，但和 archiveDisabledSchemeFiles
        // 直接冲突：那边刚把未启用方案的文件归档进文件夹，这边下一轮就删掉，
        // 用户的结果是「未启用方案的文件全没了」——重新启用时无文件可恢复。
        //
        // 正确语义（用户确认过）：
        // - **所有已安装方案**都有自己的文件夹（一个方案一个文件夹）
        // - 启用的方案：文件同时在 rime 顶层（引擎要读）
        // - 未启用的方案：文件只在文件夹里待命，不占顶层
        val removed = 0

        FileLogger.i(TAG, "已启用方案文件夹：${enabledFolders.size} 个，共 $files 个文件")
        return FolderStats(enabledFolders.size, files, removed)
    }

    data class FolderStats(val schemes: Int, val files: Int, val removed: Int)

    /**
     * 确保 [target] 与 [src] 是同一份内容：优先硬链接，失败则复制。
     *
     * @return 是否写入了内容（已一致返回 false）
     */
    private fun ensureLinked(src: File, target: File): Boolean {
        // 已是同一实体（硬链接）→ 无事可做
        if (target.isFile && isSameFile(src, target)) return false
        // 目标存在但是**独立副本**（内容相同、inode 不同）→ 删掉重建硬链接。
        //
        // 曾经的写法是「大小相同就 return false」，结果永远不会尝试建链接：
        // 真机上 wubi86.dict.yaml 顶层和文件夹里各有一份 1.3MB 的独立副本，
        // 十几 MB 的词典被复制了 N 份。用户看到的就是"没归纳好"。
        // 先把旧目标改名成 .old，而不是直接删。
        // 这样链接/复制都失败时还能把它改回来——否则「先删后建」失败就是净丢一份文件。
        val backup = File(target.parentFile, "${target.name}.old")
        var hadOld = false
        if (target.isFile) {
            runCatching {
                if (backup.exists()) backup.delete()
                hadOld = target.renameTo(backup)
                if (!hadOld) { target.delete(); hadOld = false }
            }
        }
        target.parentFile?.mkdirs()
        return runCatching {
            Files.createLink(target.toPath(), src.toPath())
            if (hadOld) runCatching { backup.delete() }
            true
        }.recoverCatching { e ->
            // 跨文件系统 / 不支持硬链接时才复制。记一条日志，否则出问题时无从判断
            // 到底是"走了复制"还是"链接成功"（真机上曾出现两份独立副本却查不到原因）。
            FileLogger.w(
                TAG,
                "硬链接失败，回退复制：${src.name} -> ${target.path}（${e.javaClass.simpleName}: ${e.message}）",
            )
            try {
                Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (hadOld) runCatching { backup.delete() }
                true
            } catch (copyError: Exception) {
                // 链接和复制都失败：把旧目标改回来，保证不丢文件
                if (hadOld) runCatching { backup.renameTo(target) }
                FileLogger.w(TAG, "链接与复制均失败，已恢复原文件：${target.path}")
                false
            }
        }.getOrDefault(false)
    }

    /**
     * 按「已启用方案」镜像：只有启用方案的依赖才会出现在 rime 根。
     *
     * 这是「启用哪个方案就用方案里的东西」的落地点：
     * - 启用方案的专属文件从 `schemes/<显示名>/` 链接到根
     * - 共享资源（多方案共用的词典/symbols）本就留在根，不动
     * - **未启用方案**的专属文件从根删掉（它们只在 schemes 里待命）
     *
     * 注意：只能删「确认属于未启用方案」的文件。共享文件、全局配置、引擎产物一律不碰——
     * 删错会直接导致输入法没有方案或读不到符号表。
     *
     * @param enabledSchemaIds 已启用的 schema_id 列表
     */
    fun mirrorEnabledSchemes(rimeDir: File, enabledSchemaIds: List<String>): MirrorStats {
        // 权威依据是 schemes/ 里的内容，**不是**当前根目录——
        // 上一轮可能刚把未启用方案的文件从根删掉，再根据根目录反推就会漏掉它们
        // （实测：从 pinyin_simp 切到 wubi98 时，wubi98 的文件永远链接不回来）。
        val schemes = discoverSchemeFiles(rimeDir)
        if (schemes.isEmpty()) return MirrorStats()

        // 文件夹名 → schema_id
        val folderToId = schemes.keys.associateWith { folder ->
            val schemaInFolder = schemes.getValue(folder)
                .firstOrNull { it.name.endsWith(".schema.yaml") }
            schemaInFolder?.let { schemaIdOf(it) } ?: folder
        }
        val enabledFolders = enabledSchemaIds.mapNotNull { sid ->
            folderToId.entries.firstOrNull { it.value == sid }?.key
        }.toSet()

        // 1) 把已启用方案的文件链接回根
        var linked = 0
        var skipped = 0
        enabledFolders.forEach { folder ->
            val base = File(File(rimeDir, SCHEMES_DIR), folder)
            schemes.getValue(folder).forEach { src ->
                val rel = src.relativeTo(base).path
                val target = File(rimeDir, rel)
                if (target.isFile && (isSameFile(src, target) || sameContent(src, target))) {
                    skipped++
                    return@forEach
                }
                target.parentFile?.mkdirs()
                val ok = runCatching {
                    if (target.exists()) target.delete()
                    Files.createLink(target.toPath(), src.toPath())
                }.recoverCatching {
                    Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.isSuccess
                if (ok) linked++
            }
        }

        // 不做「删除未启用方案文件」这一步。
        //
        // 曾实现过，但和 RimeConfigHelper.updateBuiltinAssets 直接冲突：
        // 后者每次启动都会把 assets 里的 10 个内置方案文件**重新写回**根目录
        // （维护者决策：内置方案随 app 更新）。两边一个删一个加，结果是
        // 每启动一次就重写/删除一批文件，引擎反复判定 hash 失配；
        // 而且删掉后 discoverSchemas（扫顶层）看不到这些方案，设置页里
        // 用户就没法把它们重新启用——等于把功能删了。
        //
        // 现在的语义：**顶层保留全部已安装方案**（引擎按 schema_list 决定激活哪个），
        // schemes/ 只做「一个方案一个文件夹」的归档与镜像，不干预启用状态。

        if (linked > 0) {
            FileLogger.i(TAG, "按启用方案镜像：链接 $linked，跳过 $skipped")
        }
        return MirrorStats(linked = linked, skipped = skipped)
    }

    /**
     * 清理 `schemes/` 里**已经没有对应顶层方案**的目录（幽灵目录）。
     *
     * 线上实测：设备上实际只有 5 个方案，`schemes/` 里却堆了 11 个目录——
     * 多出来的是早就被用户删掉/从未存在的方案（english、numbers、wubi98…），
     * 由早期版本的迁移逻辑凭内置资产建出来，之后一直没清。
     *
     * 这些幽灵目录的害处：镜像时会把它们的文件当"有效方案文件"处理，
     * 让用户看到一堆没启用的方案散在根目录。
     *
     * 判据：目录名对应的 schema 既不在顶层、也不在启用列表里 → 幽灵。
     * **不删有顶层 schema 的目录**（那是真实方案）。
     *
     * @param enabledSchemaIds 当前启用的 schema_id（用于保护"已启用但顶层暂时缺失"的方案）
     * @return 删除的目录数
     */
    /**
     * 清理 `schemes/` 里的幽灵目录。
     *
     * **这个函数已废弃，永远返回 0。**
     *
     * 原判据是「目录里的 schema 既不在顶层、也不在启用列表 → 幽灵」。
     * 但**未启用方案正是这个状态**：文件已归档进文件夹、不在顶层、也不在启用列表。
     * 结果是每轮启动都会把未启用方案的文件夹删掉——3.4.30 就是这样丢了
     * english / numbers / wubi98 / wubi86_pinyin / wubi86_trad / wubi86_trad_pinyin
     * 六个内置方案，用户只能从 sync/ 备份手动捞回。
     *
     * 「方案是否还存在」的权威依据应该是 **APK 内置资产 + 市场安装记录**，
     * 而不是「顶层有没有文件」——因为未启用方案的文件本来就不在顶层。
     * 真正的缺失由 [SchemaManager.restoreMissingBuiltinSchemes] 从 assets 补齐，
     * 不需要（也不能）靠删文件夹来"清理"。
     *
     * @return 恒为 0
     */
    @Deprecated("会误删未启用方案的文件夹，改用 SchemaManager.restoreMissingBuiltinSchemes")
    fun removeGhostSchemeFolders(rimeDir: File, enabledSchemaIds: List<String>): Int = 0

    /**
     * 从 schemes/ 里把某个方案的文件恢复到 rime 顶层（重新启用时用）。
     *
     * 归档是「移走」而不是「删除」，恢复就是把文件链接回顶层。
     * 找不到该方案时静默返回 false——调用方会跳过。
     *
     * @return 是否恢复了至少一个文件
     */
    fun restoreSchemeFromFolders(rimeDir: File, schemaId: String): Boolean {
        val schemesDir = File(rimeDir, SCHEMES_DIR)
        if (!schemesDir.isDirectory) return false
        // 找到含该 schema 的文件夹
        val dir = schemesDir.listFiles().orEmpty().filter { it.isDirectory }.firstOrNull { d ->
            d.listFiles { f -> f.name == "$schemaId.schema.yaml" }?.isNotEmpty() == true
        } ?: return false

        var restored = 0
        dir.walkTopDown().filter { it.isFile }.forEach { src ->
            val rel = src.relativeTo(dir).path
            val target = File(rimeDir, rel)
            if (ensureLinked(src, target) || target.isFile) restored++
        }
        if (restored > 0) FileLogger.i(TAG, "恢复方案 $schemaId：$restored 个文件回到顶层")
        return restored > 0
    }

    /**
     * 归档未启用方案的文件：从 rime 顶层移进 `schemes/<方案名>/`，让顶层保持干净。
     *
     * 用户要求「该归纳的归纳」——真机上顶层有 46 个文件，其中 9 个属于未启用的
     * 内置方案（english/numbers/wubi98/wubi86_pinyin/wubi86_trad/wubi86_trad_pinyin），
     * 它们只在设置页被启用时才需要出现在顶层。
     *
     * **只移动、不删除**：方案本体完整保留在 schemes/ 里，用户随时可以重新启用。
     *
     * 安全前提（任一不满足就跳过该文件，宁可不归档也不能弄丢方案）：
     * - 该文件确实属于某个**未启用**方案（按 [resolveOwnership] 判定）
     * - 目标方案文件夹已建好
     * - 复制/链接成功后才删源文件
     *
     * @return 归档的文件数
     */
    fun archiveDisabledSchemeFiles(rimeDir: File, enabledSchemaIds: List<String>): Int {
        val ownership = resolveOwnership(rimeDir)
        if (ownership.isEmpty()) return 0
        // 文件夹名 → schema_id
        val folderToId = mutableMapOf<String, String>()
        ownership.keys.filter { it.name.endsWith(".schema.yaml") }.forEach { f ->
            folderToId[ownership.getValue(f)] = schemaIdOf(f)
        }
        val enabledFolders = enabledSchemaIds.mapNotNull { sid ->
            folderToId.entries.firstOrNull { it.value == sid }?.key
        }.toSet()

        var archived = 0
        ownership.forEach { (file, folder) ->
            if (folder in enabledFolders) return@forEach       // 启用方案的留在顶层
            if (!file.isFile) return@forEach
            // 文件夹不存在就**建**，而不是跳过。
            //
            // 曾经的写法是 `if (!dir.isDirectory) 跳过`，而 syncEnabledSchemeFolders
            // 只给"已启用"方案建文件夹 → 未启用方案的文件夹永远不存在 →
            // 它们的配置和词库永远归不了档（线上实测：顶层剩 43 个文件）。
            val dir = File(File(rimeDir, SCHEMES_DIR), folder)
            if (!dir.isDirectory && !dir.mkdirs()) {
                FileLogger.w(TAG, "跳过归档 ${file.name}：无法创建方案文件夹 $folder")
                return@forEach
            }
            val target = File(dir, file.name)
            val ok = runCatching {
                // ensureLinked 返回 false 表示"已一致、无需写入"，这不代表失败：
                // 文件夹里可能已经是同一份（硬链接或之前的副本）。
                // 真正要判断的是"目标是否就位"，所以先调用，再检查结果文件。
                ensureLinked(file, target)
                if (!target.isFile) return@runCatching false
                // 目标就位后才删源（顺序不能反：先删后写一旦失败就丢方案）
                file.delete()
            }.getOrDefault(false)
            if (ok) archived++
        }
        if (archived > 0) FileLogger.i(TAG, "归档未启用方案文件：$archived 个移入 schemes/")
        return archived
    }

    /**
     * 清理 rime 顶层的垃圾文件（编辑器备份、调试日志、旧版残留）。
     *
     * 真机上实测存在的：`xime.yaml.bak_width`（82KB）、`XGuai_flypy.schema.yaml.bak_dupkey`、
     * `probe_min.log`、`reverse_wildcard_debug.log`。它们不参与任何功能，
     * 但会让用户觉得目录很乱，也会被备份打包进去。
     *
     * **只删明确是垃圾的**：`*.bak*` / `*.log` / `*.tmp` / `*~`。
     * 不动 `*.yaml` / `*.txt` / `*.lua` / 任何目录。
     *
     * @return 删除的文件数
     */
    fun cleanJunkFiles(rimeDir: File): Int {
        if (!rimeDir.isDirectory) return 0
        var removed = 0
        rimeDir.listFiles().orEmpty().filter { it.isFile }.forEach { f ->
            if (!isJunkFileName(f.name)) return@forEach
            if (runCatching { f.delete() }.getOrDefault(false)) {
                removed++
                FileLogger.i(TAG, "清理垃圾文件 ${f.name}")
            }
        }
        return removed
    }

    /**
     * 是否垃圾文件（纯函数，单测锚定）。
     *
     * 覆盖真机上实测存在的形态：
     * - `xime.yaml.bak_width`          → 编辑器备份
     * - `XGuai_flypy.schema.yaml.bak_dupkey` → 重复键修复时的备份
     * - `probe_min.log` / `reverse_wildcard_debug.log` → 调试日志
     *
     * 判据：**先看最后一段扩展名**，只有它落在垃圾集合里才算。
     * 不能只看"名字里含 .bak"——`xime.yaml.bak_width` 最后一段是 width，
     * 但它确实是备份；所以再补一条「`.bak` 后跟下划线」的规则。
     *
     * 绝不能误伤：`*.yaml` / `*.txt` / `*.lua` 是方案本体，一律不碰。
     */
    internal fun isJunkFileName(name: String): Boolean {
        val lower = name.lowercase()
        // 正常方案/配置文件：即使名字里含 .bak 也不删（保护 data.bak.yaml 这类）
        val realExt = lower.substringAfterLast('.', "")
        if (realExt in setOf("yaml", "yml", "txt", "lua")) return false
        if (lower.endsWith(".log")) return true
        if (lower.endsWith(".tmp")) return true
        if (lower.endsWith("~")) return true
        if (lower.endsWith(".bak")) return true
        // xime.yaml.bak_width / xxx.bak_dupkey
        if (lower.contains(".bak_")) return true
        return false
    }

    /**
     * 清理旧版本按 schema_id 命名的方案目录。
     *
     * 3.4.25 建的目录名是 `english` / `wubi86`（schema_id），3.4.26 起改用显示名
     * `English` / `五笔86`。升级后如果不清理，同一方案会有两个目录、两份内容，
     * 镜像时互相覆盖，用户看到的是"乱"。
     *
     * 判定：目录名恰好等于某个 schema_id，且该方案的显示名与目录名不同。
     *
     * @return 删除的目录数
     */
    fun removeLegacyIdFolders(rimeDir: File): Int {
        val schemesDir = File(rimeDir, SCHEMES_DIR)
        if (!schemesDir.isDirectory) return 0
        val schemaIds = rimeDir.listFiles { f -> f.name.endsWith(".schema.yaml") }
            .orEmpty().map { schemaIdOf(it) }.toSet()
        var removed = 0
        schemesDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val id = dir.name
            if (id !in schemaIds) return@forEach
            // 该 schema_id 的正确目录名
            val correct = schemeFolderName(rimeDir, id)
            if (correct == id) return@forEach   // 显示名与 id 相同，无需迁移

            // 安全前提：**顶层必须已有该方案的完整内容**才能删旧目录。
            // 旧目录可能是唯一副本（比如用户手改了 schemes/english/ 里的文件、
            // 而顶层还没镜像），此时删掉就丢数据了。
            // 判据：顶层存在 <id>.schema.yaml 且大小与旧目录里的一致。
            val topSchema = File(rimeDir, "$id.schema.yaml")
            val dirSchema = dir.listFiles { f -> f.name == "$id.schema.yaml" }?.firstOrNull()
            if (!topSchema.isFile) {
                FileLogger.w(TAG, "旧目录 schemes/$id 保留：顶层没有 $id.schema.yaml，删了会丢方案")
                return@forEach
            }
            if (dirSchema != null && !sameContent(topSchema, dirSchema)) {
                FileLogger.w(TAG, "旧目录 schemes/$id 保留：与顶层内容不一致（顶层未镜像最新版）")
                return@forEach
            }
            if (dir.deleteRecursively()) {
                removed++
                FileLogger.i(TAG, "移除旧方案目录 schemes/$id（改用显示名 $correct）")
            }
        }
        return removed
    }

    /**
     * 把 `schemes/<方案>/` 里的文件镜像到 rime 根，让 librime 能扫到。
     *
     * 用硬链接而不是复制：引擎运行时可能改文件（如 user.yaml），
     * 硬链接保证「根目录」与「schemes 里的源文件」永远是同一份内容，
     * 不会出现两边不一致却看不出哪份是对的。跨文件系统或文件系统不支持硬链接时回退复制。
     *
     * 幂等：目标已存在且大小一致就跳过（硬链接场景下 mtime 天然相同）。
     */
    fun mirrorToRoot(rimeDir: File): MirrorStats {
        val schemes = discoverSchemeFiles(rimeDir)
        if (schemes.isEmpty()) return MirrorStats()
        var linked = 0
        var copied = 0
        var skipped = 0

        schemes.forEach { (schemeName, files) ->
            files.forEach { src ->
                // 在目录内的相对路径，保持子结构（如 opencc/xxx.ocd2）
                val rel = src.relativeTo(File(File(rimeDir, SCHEMES_DIR), schemeName)).path
                val target = File(rimeDir, rel)
                when (mirrorOne(src, target)) {
                    MirrorResult.LINKED -> linked++
                    MirrorResult.COPIED -> copied++
                    MirrorResult.SKIPPED -> skipped++
                    MirrorResult.FAILED -> Unit
                }
            }
        }
        val stats = MirrorStats(linked, copied, skipped)
        if (stats.total > 0) FileLogger.i(TAG, "方案镜像完成：$stats")
        return stats
    }

    private enum class MirrorResult { LINKED, COPIED, SKIPPED, FAILED }

    private fun mirrorOne(src: File, target: File): MirrorResult {
        return try {
            if (target.exists()) {
                // 已是同一文件（硬链接）或内容大小一致 → 跳过
                if (isSameFile(src, target) || (sameContent(src, target) && !target.isDirectory)) {
                    return MirrorResult.SKIPPED
                }
                // 内容不同：以 schemes/ 为准（它是权威副本）
                target.delete()
            }
            target.parentFile?.mkdirs()
            try {
                Files.createLink(target.toPath(), src.toPath())
                MirrorResult.LINKED
            } catch (_: Exception) {
                // 跨文件系统 / 不支持硬链接：退回复制
                Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                MirrorResult.COPIED
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "镜像失败: ${src.path} -> ${target.path} (${e.message})")
            MirrorResult.FAILED
        }
    }

    private fun isSameFile(a: File, b: File): Boolean = try {
        Files.isSameFile(a.toPath(), b.toPath())
    } catch (_: Exception) {
        false
    }

    /**
     * 内容是否逐字节相同。
     *
     * 绝不能用 `length()` 判等代替：真机上出现过两份 1.3MB 的词典大小相同但内容不同，
     * 用 size 判等会把"独立副本"误当成"同一份"，导致该建的硬链接没建、该保留的文件被删。
     * 流式比较，不把整份词典读进堆；先比 size 快速否定，再逐字节比。
     */
    internal fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile) return false
        if (a.length() != b.length()) return false
        if (isSameFile(a, b)) return true
        return try {
            a.inputStream().use { ai ->
                b.inputStream().use { bi ->
                    val ba = ByteArray(64 * 1024)
                    val bb = ByteArray(64 * 1024)
                    var equal = true
                    comparison@ while (true) {
                        val na = readFully(ai, ba)
                        val nb = readFully(bi, bb)
                        if (na != nb) { equal = false; break@comparison }
                        if (na == 0) break@comparison
                        for (i in 0 until na) {
                            if (ba[i] != bb[i]) { equal = false; break@comparison }
                        }
                    }
                    equal
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 尽量读满 [buf]，返回实际读到的字节数（0 表示 EOF）。 */
    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * 反镜像：把「从 schemes/ 镜像出来」的根文件删掉，保留共享文件与引擎产物。
     *
     * 只删**确实镜像出来的**：根文件与 schemes 里同名文件是同一 inode 或大小一致。
     * 共享文件不在 schemes 里，所以天然不受影响——这是本函数安全性的基础。
     *
     * @return 删除的文件数
     */
    fun unmirrorFromRoot(rimeDir: File): Int {
        val schemes = discoverSchemeFiles(rimeDir)
        if (schemes.isEmpty()) return 0
        // 收集「镜像关系」：根相对路径 → 源文件
        val mirrored = LinkedHashMap<String, File>()
        schemes.forEach { (schemeName, files) ->
            val base = File(File(rimeDir, SCHEMES_DIR), schemeName)
            files.forEach { mirrored.putIfAbsent(it.relativeTo(base).path, it) }
        }
        var removed = 0
        mirrored.forEach { (rel, src) ->
            val target = File(rimeDir, rel)
            if (!target.isFile) return@forEach
            if (isSameFile(src, target) || sameContent(src, target)) {
                if (runCatching { target.delete() }.getOrDefault(false)) removed++
            }
        }
        if (removed > 0) FileLogger.i(TAG, "反镜像：删除 $removed 个顶层镜像文件")
        return removed
    }

    // ------------------------------------------------------------------
    // 从顶层重建（备份恢复后用）
    // ------------------------------------------------------------------

    /**
     * 从 rime **顶层**方案文件重建 `schemes/` 目录。
     *
     * 备份去掉 schemes/ 中与顶层相同的镜像，但保留唯一归档文件；旧备份仍可能只有顶层文件。
     * 本方法从顶层重建可镜像的方案目录，不会删除唯一归档内容。
     *
     * **顶层文件必须原样保留**——引擎读的是顶层，删了输入法就没方案了。
     * 所以这里只是把独占文件再链接一份进 schemes/，不动顶层。
     *
     * @return 重建的方案数
     */
    fun rebuildSchemesFromRoot(rimeDir: File): Int {
        val ownership = resolveOwnership(rimeDir)
        if (ownership.isEmpty()) return 0
        var schemes = 0
        ownership.entries.groupBy({ it.value }, { it.key }).forEach { (schemeName, files) ->
            val dir = File(File(rimeDir, SCHEMES_DIR), schemeName)
            var wrote = 0
            files.forEach { top ->
                // 顶层相对路径
                val rel = top.relativeTo(rimeDir).path
                val target = File(dir, rel)
                if (existsSame(target, top)) return@forEach
                target.parentFile?.mkdirs()
                try {
                    Files.createLink(target.toPath(), top.toPath())
                    wrote++
                } catch (_: Exception) {
                    runCatching {
                        Files.copy(top.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        wrote++
                    }
                }
            }
            if (wrote > 0) schemes++
        }
        if (schemes > 0) FileLogger.i(TAG, "从顶层重建 schemes/：$schemes 个方案")
        return schemes
    }

    private fun existsSame(target: File, src: File): Boolean =
        target.isFile && (isSameFile(src, target) || sameContent(src, target))

    private fun safeReadText(file: File): String =
        runCatching { file.readText() }.getOrDefault("")
}
