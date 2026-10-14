package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import java.security.MessageDigest

/**
 * 统一配置文件 `/storage/emulated/0/Xime/xime.yaml` 的生成。
 *
 * 目标（用户需求「配置数据整合到一个文件」）：把键盘布局、手势、配色、字体等全部配置
 * 收敛到一个用户可见、可直接编辑的 YAML 文件里，改完重载即生效。
 *
 * 文件形态是**完整快照**：首次生成时把内置 `assets/xime.yaml` 的全量默认值逐字写入（连同
 * 其中大段说明注释），所以用户打开就能看到每一项的真实生效值，不必去猜默认值是多少。
 *
 * 读取语义上它是**覆盖层**而非替代层——[KeysConfigHelper] 仍以 `assets/xime.yaml` 为基线做
 * 字段级合并。这样即使某个工具（如市场布局）只写入 `keyboard:` 一段，其余配置依旧沿用内置
 * 默认值，不会出现「写一个片段就把别的配置全清空」。
 */
object UnifiedConfigFile {

    private const val TAG = "UnifiedConfigFile"

    /** 内置基线配置（assets），同时是统一配置的内容来源。 */
    private const val ASSET_BASE = "xime.yaml"

    /**
     * 快照里记录「本文件基于哪一版内置基线生成」的标记前缀。
     *
     * 用**内容指纹**而不是 versionCode 作为判据：本项目的 `versionCode` 是硬编码常量，
     * 每次构建都一样，拿它比对永远得出「同版本」——那正是「装了新版本，新默认值却没生效」
     * 的根因。只要 `assets/xime.yaml` 内容变了，指纹必然变，判据就一定成立。
     */
    private const val BASELINE_MARKER_PREFIX = "# xime-baseline: "

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    /** 匹配顶层键（列 0 且形如 `key:`），用于切分与定位顶层块。 */
    private val TOP_LEVEL_KEY = Regex("^[A-Za-z_][A-Za-z0-9_-]*:")

    /**
     * 生成统一配置。幂等：文件已存在就直接返回，**不覆盖**用户后来的编辑。
     *
     * @return 文件最终是否存在且可读。
     */
    fun ensureGenerated(context: Context): Boolean {
        if (!UserConfigDir.isSupported) return false
        // 未授权时不必白跑一遍生成：写不进去，而且每次启动都会刷一条失败日志。
        // 授权后重启即会生成，设置页会提示用户去授权（ReleaseState.PERMISSION_REQUIRED）。
        if (!UserConfigDir.hasWritePermission(context)) return false
        if (UserConfigDir.exists(context, UserConfigDir.UNIFIED_CONFIG_FILE)) return true
        return generate(context)
    }

    /**
     * 无条件按「内置基线 + 旧自定义配置」重新生成。
     * 供用户主动要求把文件重置为完整快照时调用（会覆盖现有编辑）。
     */
    fun generate(context: Context): Boolean {
        val base = readAssetText(context, ASSET_BASE) ?: run {
            Log.w(TAG, "asset $ASSET_BASE missing, skip unified config generation")
            return false
        }
        val legacy = readLegacyCustom(context)
        var legacyFolded = false
        val body = if (legacy.isNullOrBlank()) {
            base
        } else {
            val folded = foldLegacy(base, legacy)
            if (folded != null) {
                legacyFolded = true
                folded
            } else {
                // 折叠失败时写基线保证文件可用，但**保留**旧文件，用户的自定义不会丢
                base
            }
        }
        val text = buildSnapshot(context, body)
        val ok = UserConfigDir.writeText(context, UserConfigDir.UNIFIED_CONFIG_FILE, text)
        if (ok && legacyFolded) {
            // 折叠成功后删除旧文件：留着会让用户改错地方——读取优先级下 xime.yaml 更高，
            // 用户编辑 xime.custom.yaml 会毫无效果。
            UserConfigDir.delete(context, UserConfigDir.CUSTOM_CONFIG_FILE)
            runCatching { File(context.filesDir, "rime/${UserConfigDir.CUSTOM_CONFIG_FILE}").delete() }
            Log.i(TAG, "folded legacy custom config into ${UserConfigDir.UNIFIED_CONFIG_FILE}")
        }
        if (!ok) Log.w(TAG, "generate unified config failed")
        return ok
    }

    /**
     * 拼装快照文本：头部说明 + 基线指纹标记 + 内容。
     *
     * 指纹标记是「这份快照对应哪一版内置默认值」的唯一凭据，见 [isSnapshotStale]。
     */
    private fun buildSnapshot(context: Context, body: String): String {
        val hash = baselineHash(context) ?: "unknown"
        return snapshotText(body, hash)
    }

    /** 快照文本拼装（纯函数，便于单测校验标记格式与可回读性）。 */
    internal fun snapshotText(body: String, hash: String): String =
        HEADER + "\n" + BASELINE_MARKER_PREFIX + hash + "\n\n" + body.trimEnd() + "\n"

    /**
     * 当前内置基线（`assets/xime.yaml`）的内容指纹，取 SHA-256 前 16 位十六进制。
     *
     * 该指纹随内置配置内容变化，替代「永远不变」的 versionCode 作为快照新鲜度判据。
     * 读不到 assets 时返回 null。
     */
    fun baselineHash(context: Context): String? {
        val bytes = readAssetBytes(context, ASSET_BASE) ?: return null
        return baselineHashOf(bytes)
    }

    /**
     * 基线字节的内容指纹（SHA-256 前 8 字节的十六进制）。
     *
     * 抽成纯函数以便单测直接覆盖：判据的正确性不该只靠「源码里有这行字符串」来保证。
     */
    internal fun baselineHashOf(bytes: ByteArray): String? = try {
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(8)
            .joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "baselineHashOf failed", t)
        null
    }

    /**
     * 可见目录里的 `xime.yaml` 是否落后于当前内置基线。
     *
     * 判据是快照内记录的基线指纹与当前 assets 指纹不一致。**没有指纹标记**的旧快照
     * （本次改动之前生成的）一律视为落后——它们的按键宽度、手势等默认值确实还是旧的。
     *
     * 文件不存在（首次安装）或未授权时返回 false：没有可覆盖的东西，不该打扰用户。
     */
    fun isSnapshotStale(context: Context): Boolean {
        if (!UserConfigDir.hasWritePermission(context)) return false
        val text = UserConfigDir.readText(context, UserConfigDir.UNIFIED_CONFIG_FILE) ?: return false
        val current = baselineHash(context) ?: return false
        return isSnapshotStale(text, current)
    }

    /**
     * 落后判定（纯函数）：快照里记录的指纹与 [currentHash] 不同即视为落后，
     * 没有记录指纹的旧快照同样视为落后。
     */
    internal fun isSnapshotStale(snapshotText: String, currentHash: String): Boolean =
        snapshotBaseline(snapshotText) != currentHash

    /** 从快照文本里取出指纹标记；没有标记（旧版生成）返回 null。 */
    internal fun snapshotBaseline(text: String): String? =
        text.lineSequence()
            .firstOrNull { it.startsWith(BASELINE_MARKER_PREFIX) }
            ?.removePrefix(BASELINE_MARKER_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** 读取旧的用户自定义配置：可见目录优先，其次私有目录（内置文件管理器/无线导入写入）。 */
    private fun readLegacyCustom(context: Context): String? {
        UserConfigDir.readText(context, UserConfigDir.CUSTOM_CONFIG_FILE)?.let { return it }
        val privateFile = File(context.filesDir, "rime/${UserConfigDir.CUSTOM_CONFIG_FILE}")
        if (!privateFile.exists()) return null
        return runCatching { privateFile.readText().trimStart('\uFEFF') }.getOrNull()
    }

    /**
     * 把旧自定义配置折叠进基线文本：按**顶层键整块替换**，未被覆盖的部分连同注释逐字保留。
     *
     * 全程按文本处理，不做 YAML 往返序列化——既保住基线里其余小节的说明注释，也原样保留用户
     * 旧文件里的写法与注释。折叠结果会过一遍 YAML 解析校验，不通过则返回 null（调用方保留旧
     * 文件、退回基线），绝不写出一个坏掉的主配置文件。
     */
    private fun foldLegacy(baseText: String, legacyText: String): String? {
        val overlayBlocks = splitTopLevelBlocks(legacyText)
        if (overlayBlocks.isEmpty()) {
            Log.w(TAG, "legacy custom config has no top-level block, skipping fold")
            return null
        }
        var lines: List<String> = baseText.lines()
        for ((key, blockLines) in overlayBlocks) {
            lines = replaceTopLevelBlock(lines, key, blockLines)
        }
        val folded = lines.joinToString("\n")
        val parsed = runCatching { yaml.parseToYamlNode(folded) }.isSuccess
        if (!parsed) {
            Log.w(TAG, "folded config does not parse, keeping base + legacy file")
            return null
        }
        return folded
    }

    /**
     * 按顶层键把文本切成若干块，保留每块原始行（含注释与空行）。
     * 顶层键之前的文件头注释会被丢弃：它们是旧文件的说明，不该搬进主配置。
     */
    private fun splitTopLevelBlocks(text: String): List<Pair<String, List<String>>> {
        val blocks = mutableListOf<Pair<String, List<String>>>()
        var currentKey: String? = null
        var current = mutableListOf<String>()
        for (line in text.lines()) {
            if (TOP_LEVEL_KEY.find(line) != null) {
                currentKey?.let { blocks += it to current }
                currentKey = line.substringBefore(':').trim()
                current = mutableListOf(line)
            } else if (currentKey != null) {
                current.add(line)
            }
        }
        currentKey?.let { blocks += it to current }
        // 去掉每块尾部多余空行，避免拼接后出现大片空白
        return blocks.map { (key, body) ->
            key to body.dropLastWhile { it.isBlank() }
        }
    }

    /** 用 [blockLines] 替换 [lines] 里 `key:` 这个顶层块的整段（含其下的缩进内容）。 */
    private fun replaceTopLevelBlock(
        lines: List<String>,
        key: String,
        blockLines: List<String>
    ): List<String> {
        val start = lines.indexOfFirst { topLevelKeyOf(it) == key }
        if (start < 0) {
            // 基线没有这个顶层键：追加到末尾，保证用户的自定义不丢
            return lines + listOf("", "# ↓↓↓ 以下小节来自你之前的自定义配置 ↓↓↓") + blockLines
        }
        // 块的范围：从键行到下一个顶层键行之前。列 0 的注释留给下一个块，避免把小节的
        // 说明注释一起吃掉。
        var end = start + 1
        while (end < lines.size && TOP_LEVEL_KEY.find(lines[end]) == null) end++
        return lines.subList(0, start) + blockLines + lines.subList(end, lines.size)
    }

    /** 该行若是顶层键行，返回键名；否则返回 null。 */
    private fun topLevelKeyOf(line: String): String? =
        if (TOP_LEVEL_KEY.find(line) != null) line.substringBefore(':').trim() else null

    private fun readAssetText(context: Context, fileName: String): String? =
        readAssetBytes(context, fileName)?.toString(Charsets.UTF_8)

    private fun readAssetBytes(context: Context, fileName: String): ByteArray? = try {
        context.assets.open(fileName).use { it.readBytes() }
    } catch (t: Throwable) {
        Log.w(TAG, "read asset $fileName failed", t)
        null
    }

    private val HEADER: String = """
        # ============================================================================
        # Xime 输入法 · 统一配置（完整快照）
        #
        # 这个文件就是当前生效的全部键盘配置：内置默认值已全量写在这里，你看到的每一项都是
        # 真实生效的值。改完在键盘工具栏菜单里点「部署方案」即可生效，无需重装。
        #
        # 只改你需要改的地方即可；把某个键删掉就会退回应用内置默认值。
        # 改坏了不会让输入法崩溃：解析失败会自动回退内置默认配置。
        # 实在改不回来，直接删掉本文件再重启应用即可恢复默认。
        # ============================================================================
    """.trimIndent()
}
