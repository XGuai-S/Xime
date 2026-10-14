package com.kingzcheung.xime.settings

import android.content.Context

/**
 * 解析生效的日志级别。
 *
 * 优先级（高 → 低）：
 * 1. 设置页写入的偏好（用户点过就是明确意图）；
 * 2. `xime.yaml` 的 `logging.level`（用户手改配置，便于排查问题时临时打开）；
 * 3. [LogLevel.DEFAULT]（只听错误）。
 *
 * 为什么设置页优先：设置的界面操作是**即时意图**，指向"现在就要什么"；而
 * xime.yaml 是持久配置文件。用户在界面关掉日志后，不该因为配置文件里还留着
 * `debug` 就被继续写盘。反过来，只改 yaml 的用户（不碰界面）也能生效——两条路都通。
 */
object LogLevelResolver {

    /** `xime.yaml` 中日志级别的键路径。 */
    private const val LOGGING_LEVEL_KEY = "logging"

    fun resolve(context: Context): LogLevel {
        SettingsPreferences.getLogLevelOverride(context)?.let { return it }
        return parseFromConfig(context) ?: LogLevel.DEFAULT
    }

    /**
     * 从 `xime.yaml` 读取 `logging: { level: <值> }`。
     *
     * 用手写的极简解析而非引入 YAML 依赖：这里只需要取一个位于固定两层的标量，
     * 而该文件可能被用户改坏（缩进错误、注释掉整段）。容错优先——任何异常或
     * 读不到的情况都返回 null，由调用方回落到默认值，绝不让配置问题阻断输入法启动。
     *
     * 支持的写法（缩进无关紧要，只看键名）：
     * ```yaml
     * logging:
     *   level: warn        # 或 debug / info / verbose / off
     * ```
     */
    fun parseFromConfig(context: Context): LogLevel? {
        val text = runCatching { readConfigText(context) }.getOrNull() ?: return null
        return parseLevelFromYaml(text)
    }

    /**
     * 从配置文本中解析 `logging.level`。纯函数，便于单测。
     *
     * 实现要点：先定位 `logging:` 段，再在其**后续缩进块内**找 `level:`。
     * 不能全文件搜 `level:`——rime 配置里其它段也可能有同名键。
     */
    internal fun parseLevelFromYaml(text: String): LogLevel? {
        val lines = text.lines()
        var loggingIndent: Int? = null
        for (raw in lines) {
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            val content = line.trim()

            val currentIndent = loggingIndent
            if (currentIndent == null) {
                // 尚未进入 logging 段：找到它的起始行
                if (content.startsWith("$LOGGING_LEVEL_KEY:") || content == LOGGING_LEVEL_KEY) {
                    loggingIndent = indent
                }
                continue
            }

            // 已进入 logging 段：缩进回到同级或更浅说明该段结束
            if (indent <= currentIndent) {
                loggingIndent = null
                // 同级下可能是另一个顶格键，重新判断一次
                if (content.startsWith("$LOGGING_LEVEL_KEY:")) loggingIndent = indent
                continue
            }

            // 段内找 level:
            if (content.startsWith("level:")) {
                val value = content.removePrefix("level:").trim()
                    .trim('"', '\'')
                if (value.isEmpty()) return null
                return LogLevel.parse(value)
            }
        }
        return null
    }

    private fun readConfigText(context: Context): String? {
        UserConfigDir.readText(context, UserConfigDir.UNIFIED_CONFIG_FILE)?.let { return it }
        UserConfigDir.readText(context, UserConfigDir.LEGACY_UNIFIED_CONFIG_FILE)?.let { return it }
        return null
    }
}
