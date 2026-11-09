package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志级别与 `xime.yaml` 解析测试。
 *
 * 背景：用户要求「日志只记录崩溃、ERROR/WARN 错误，可在 xime.yaml 中设置」。
 * 这里覆盖两件事：
 * 1. 级别门槛语义（默认只记 ERROR，各档允许范围正确）；
 * 2. `logging.level` 的解析（含容错与「只在该段内查找」这两个易错点）。
 */
class LogLevelTest {

    // ---------- 门槛语义 ----------

    @Test
    fun `默认级别是仅错误`() {
        // 用户明确要求默认只记崩溃与错误，不是全开
        assertEquals(LogLevel.ERROR, LogLevel.DEFAULT)
    }

    @Test
    fun `仅错误档只放行错误`() {
        val level = LogLevel.ERROR
        assertTrue("ERROR 应放行 ERROR", level.allows(LogLevel.ERROR))
        assertFalse("ERROR 不应放行 WARN", level.allows(LogLevel.WARN))
        assertFalse("ERROR 不应放行 INFO", level.allows(LogLevel.INFO))
        assertFalse("ERROR 不应放行 DEBUG", level.allows(LogLevel.DEBUG))
        assertFalse("ERROR 不应放行 VERBOSE", level.allows(LogLevel.VERBOSE))
    }

    @Test
    fun `错误与警告档放行警告但不放行信息`() {
        val level = LogLevel.WARN
        assertTrue(level.allows(LogLevel.ERROR))
        assertTrue(level.allows(LogLevel.WARN))
        assertFalse(level.allows(LogLevel.INFO))
        assertFalse(level.allows(LogLevel.DEBUG))
    }

    @Test
    fun `全部档放行所有级别`() {
        val level = LogLevel.VERBOSE
        LogLevel.entries.filter { it != LogLevel.OFF }.forEach {
            assertTrue("VERBOSE 应放行 $it", level.allows(it))
        }
    }

    @Test
    fun `关闭档不放行任何级别`() {
        // 注意：w/e 的落盘也受此约束。用户显式选「关闭」时应当真的不写文件。
        val level = LogLevel.OFF
        LogLevel.entries.forEach {
            assertFalse("OFF 不应放行 $it", level.allows(it))
        }
    }

    @Test
    fun `关闭档自身不被任何档放行`() {
        // OFF 是开关而非严重度，不应被当作"最严重"而总是通过
        LogLevel.entries.forEach {
            assertFalse("$it 不应把 OFF 当作一条日志级别放行", it.allows(LogLevel.OFF))
        }
    }

    // ---------- 取值解析 ----------

    @Test
    fun `解析常见同义词`() {
        assertEquals(LogLevel.OFF, LogLevel.parse("off"))
        assertEquals(LogLevel.OFF, LogLevel.parse("关闭"))
        assertEquals(LogLevel.ERROR, LogLevel.parse("error"))
        assertEquals(LogLevel.ERROR, LogLevel.parse("crash"))
        assertEquals(LogLevel.ERROR, LogLevel.parse("崩溃"))
        assertEquals(LogLevel.WARN, LogLevel.parse("warning"))
        assertEquals(LogLevel.WARN, LogLevel.parse("警告"))
        assertEquals(LogLevel.INFO, LogLevel.parse("info"))
        assertEquals(LogLevel.DEBUG, LogLevel.parse("debug"))
        assertEquals(LogLevel.VERBOSE, LogLevel.parse("verbose"))
        assertEquals(LogLevel.VERBOSE, LogLevel.parse("trace"))
    }

    @Test
    fun `解析忽略大小写与分隔符`() {
        assertEquals(LogLevel.WARN, LogLevel.parse("WARN"))
        assertEquals(LogLevel.WARN, LogLevel.parse("  Warn  "))
        // 下划线与连字符都会被归一掉，因此 crash_only / crash-only 都命中 ERROR 别名
        assertEquals(LogLevel.ERROR, LogLevel.parse("crash_only"))
        assertEquals(LogLevel.ERROR, LogLevel.parse("crash-only"))
    }

    @Test
    fun `无法识别的值回落到默认而不是抛异常`() {
        // 配置写错不该让输入法起不来
        assertEquals(LogLevel.DEFAULT, LogLevel.parse("bogus"))
        assertEquals(LogLevel.DEFAULT, LogLevel.parse(""))
        assertEquals(LogLevel.DEFAULT, LogLevel.parse(null))
        assertEquals(LogLevel.DEFAULT, LogLevel.parse("   "))
    }

    // ---------- xime.yaml 解析 ----------

    @Test
    fun `从配置中读取 logging level`() {
        val yaml = """
            metadata:
              app_name: Xime
            logging:
              level: warn
            style:
              color_scheme: default
        """.trimIndent()
        assertEquals(LogLevel.WARN, LogLevelResolver.parseLevelFromYaml(yaml))
    }

    @Test
    fun `配置中带注释与引号也能解析`() {
        val yaml = """
            logging:
              # 排查问题时改成 debug
              level: "debug"  # 行尾注释
        """.trimIndent()
        assertEquals(LogLevel.DEBUG, LogLevelResolver.parseLevelFromYaml(yaml))
    }

    @Test
    fun `段外的同名键不应被误读`() {
        // 这是本解析器最容易写错的地方：全文件搜 "level:" 会命中别的段。
        // rime 配置里多处出现同名键，必须限定在 logging 段内。
        val yaml = """
            style:
              level: verbose
            logging:
              enabled: true
        """.trimIndent()
        assertNull(
            "logging 段内没有 level，就不该把 style 段的 level 当成日志级别",
            LogLevelResolver.parseLevelFromYaml(yaml),
        )
    }

    @Test
    fun `logging 段结束后的同名键不应被误读`() {
        val yaml = """
            logging:
              level: error
            keyboard:
              level: verbose
        """.trimIndent()
        assertEquals(LogLevel.ERROR, LogLevelResolver.parseLevelFromYaml(yaml))
    }

    @Test
    fun `没有 logging 段时返回空`() {
        val yaml = """
            metadata:
              app_name: Xime
        """.trimIndent()
        assertNull(LogLevelResolver.parseLevelFromYaml(yaml))
    }
}
