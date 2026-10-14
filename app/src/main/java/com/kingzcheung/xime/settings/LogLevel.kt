package com.kingzcheung.xime.settings

/**
 * 日志级别。
 *
 * 语义按**严重度递增**排列，[LogLevel.ERROR] 是默认值：日常只记录崩溃与错误，
 * 需要排查问题时才在 `xime.yaml` 里调低到 WARN/INFO/DEBUG/VERBOSE。
 *
 * 为什么默认只记错误：输入法常驻后台，VERBOSE 级在正式包里会持续写盘，
 * 既耗电又产生用户根本不会看的垃圾数据。错误级信息量最高、体积最小。
 */
enum class LogLevel(val label: String, val description: String) {
    /** 关闭文件日志（连错误也不写；仅 logcat 可见）。 */
    OFF("关闭", "不写任何日志文件"),

    /** 仅错误及其以上（即应用崩溃、异常）。**默认值**。 */
    ERROR("仅错误", "记录崩溃与 ERROR，日常推荐"),

    /** 错误 + 警告。 */
    WARN("错误与警告", "额外记录 WARN，含可恢复的异常"),

    /** 错误 + 警告 + 一般信息。 */
    INFO("信息", "额外记录主要流程节点，体积适中"),

    /** 上述全部 + 调试细节。 */
    DEBUG("调试", "记录详细过程，用于定位具体问题"),

    /** 全部（含最啰嗦的逐键事件）。 */
    VERBOSE("全部", "记录每一次按键与候选，最占空间");

    /** 是否应当把 [level] 级日志写入文件。 */
    fun allows(level: LogLevel): Boolean = when (this) {
        OFF -> false
        else -> level.ordinal <= this.ordinal && level != OFF
    }

    companion object {
        /** 默认级别：只记错误与崩溃。 */
        val DEFAULT = ERROR

        /**
         * 解析 `xime.yaml` 中的日志级别取值。
         *
         * 容错优先：无法识别的值一律回落到 [DEFAULT]，而不是抛异常——
         * 配置写错不该让输入法起不来。同时接受常见同义词，降低用户写错的概率。
         */
        fun parse(raw: String?): LogLevel {
            val key = raw?.trim()?.lowercase()?.replace("_", "")?.replace("-", "") ?: return DEFAULT
            return when (key) {
                "off", "none", "disable", "disabled", "关闭", "无" -> OFF
                "error", "err", "crash", "crashonly", "错误", "仅错误", "崩溃" -> ERROR
                "warn", "warning", "warnings", "警告", "错误与警告" -> WARN
                "info", "information", "信息" -> INFO
                "debug", "调试" -> DEBUG
                "verbose", "trace", "all", "全部", "verboseall" -> VERBOSE
                else -> DEFAULT
            }
        }
    }
}
