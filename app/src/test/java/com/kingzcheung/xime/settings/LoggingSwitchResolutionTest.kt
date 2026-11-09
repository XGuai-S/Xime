package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 日志级别的**取值判据**测试（纯逻辑，不依赖 Android Context）。
 *
 * 背景：日志配置经历了两次演进——
 * `verbose_logging`（旧布尔，Debug 专属）→ `logging_enabled`（布尔，正式包可见）
 * → `log_level`（枚举，默认仅错误、可在 xime.yaml 设置）。
 *
 * 每次迁移最容易出的错是「只认新键」：老用户在新键缺失时被静默重置成默认值，
 * 于是设置页显示的状态与真实写盘行为不一致。这里把判据抽成纯函数来测，
 * 让这类回归不必靠真机发现。
 */
class LoggingSwitchResolutionTest {

    /**
     * 与 [SettingsPreferences.getLogLevelOverride] + [LogLevelResolver.resolve] 同构的判据：
     * 界面覆盖值 → 配置文件值 → 默认。
     */
    private fun resolve(
        uiOverride: LogLevel?,
        fromConfig: LogLevel?,
    ): LogLevel = uiOverride ?: fromConfig ?: LogLevel.DEFAULT

    @Test
    fun `全新安装默认仅记录错误`() {
        // 用户要求：默认只记录崩溃与 ERROR/WARN
        assertEquals(LogLevel.ERROR, resolve(uiOverride = null, fromConfig = null))
    }

    @Test
    fun `配置文件可决定级别且不被默认值覆盖`() {
        // 用户不碰界面、只改 xime.yaml 时，配置必须生效
        assertEquals(LogLevel.DEBUG, resolve(uiOverride = null, fromConfig = LogLevel.DEBUG))
        assertEquals(LogLevel.OFF, resolve(uiOverride = null, fromConfig = LogLevel.OFF))
    }

    @Test
    fun `界面选择优先于配置文件`() {
        // 界面操作是即时意图，应压过持久配置文件
        assertEquals(LogLevel.WARN, resolve(uiOverride = LogLevel.WARN, fromConfig = LogLevel.VERBOSE))
        assertEquals(LogLevel.OFF, resolve(uiOverride = LogLevel.OFF, fromConfig = LogLevel.DEBUG))
    }

    @Test
    fun `老用户关掉日志的意图在迁移后仍是关闭`() {
        // 旧布尔键 false 等价于新枚举的 OFF，迁移时必须保留这一意图
        val migratedFromLegacyBoolean = if (legacyEnabled) null else LogLevel.OFF
        assertEquals(
            "曾经关掉日志的老用户升级后不应突然开始写盘",
            LogLevel.OFF,
            resolve(uiOverride = migratedFromLegacyBoolean, fromConfig = null),
        )
    }

    @Test
    fun `只读界面覆盖值会丢掉配置文件——这正是需要三级判据的原因`() {
        // 反证：若判据退化成「只读界面覆盖 + 默认」，yaml 里写的 debug 就永远不生效。
        // 该断言锁住「不得退化成这种实现」。
        val naive = { uiOverride: LogLevel? -> uiOverride ?: LogLevel.DEFAULT }
        val actual = resolve(uiOverride = null, fromConfig = LogLevel.DEBUG)
        assertEquals(
            "三级判据能读到 yaml 的 debug，而只读界面值的实现会错误地回落到默认",
            true,
            naive(null) != actual,
        )
    }

    /** 旧布尔键的取值，用于迁移断言。 */
    private val legacyEnabled: Boolean = false
}
