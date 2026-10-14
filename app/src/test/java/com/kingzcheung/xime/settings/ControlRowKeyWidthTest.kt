package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第五项第 2 条：控制行（末行）5 个按键各自支持单独自定义宽度。
 *
 * 控制行键位固定为 `[mode_change, comma, space, period, enter]`（英文键盘第四键为 `earth`），
 * 宽度走 `keys.<id>.width`（显式值优先，见 `functionKeyWidth`）。本测试钉住三点：
 * 1. 出厂配置里 5 个键都写了显式宽度，用户知道该改哪一行；
 * 2. 出厂比例：四个侧键等宽 1.1、空格 2.5（用户指定的目标比例，中英键盘一致）；
 * 3. 改其中一个键的宽度只影响它自己，其余 4 键保持原值。
 */
class ControlRowKeyWidthTest {

    private val controlRow = listOf("mode_change", "comma", "space", "period", "enter")

    private fun section(text: String, layout: String = "qwerty"): Map<String, KeyBinding> =
        KeysConfigHelper.parseKeyboardYamlSection(text, layout, emptyMap())
            ?: error("解析 keyboard.$layout.keys 失败")

    private fun realYaml(): String = RepoFiles.file("src/main/assets/xime.yaml").readText()

    private fun assertWidth(expected: Float?, actual: Float?) = assertEquals(expected, actual)

    @Test
    fun `出厂配置控制行 5 键均有显式宽度`() {
        for (layout in listOf("qwerty", "qwerty_en")) {
            val keys = section(realYaml(), layout)
            for (id in controlRow) {
                val width = keys[id]?.width
                requireNotNull(width) { "$layout 的控制行键 $id 未配置显式 width" }
                require(width > 0f) { "$layout 的控制行键 $id 宽度必须为正数，实际 $width" }
            }
        }
    }

    @Test
    fun `改单个控制行键宽度不影响其余 4 键`() {
        val builtIn = section(realYaml())
        val factoryWidths = controlRow.associateWith { builtIn[it]!!.width }

        for (target in controlRow) {
            // 只覆盖目标键的宽度，其余键不写 → 应保持出厂值
            val custom = """
                keyboard:
                  qwerty:
                    keys:
                      $target: { width: 2.5 }
            """.trimIndent()
            val merged = builtIn + section(custom)

            assertWidth(2.5f, merged[target]!!.width)
            for (other in controlRow - target) {
                assertWidth(
                    factoryWidths[other],
                    merged[other]!!.width,
                )
            }
        }
    }

    @Test
    fun `控制行四侧键等宽 1dot1 且空格为 2dot5`() {
        // 用户指定比例：四个侧键等宽 1.1，空格 2.5，中英键盘一致。
        // 侧键等宽意味着逗号/句号不再比 123/回车窄。
        for (layout in listOf("qwerty", "qwerty_en")) {
            val keys = section(realYaml(), layout)
            for (id in controlRow - "space") {
                assertWidth(1.1f, keys[id]?.width)
            }
            assertWidth(2.5f, keys["space"]?.width)
        }
    }

    @Test
    fun `英文键盘控制行第四键 earth 靠内置默认取到侧键宽度`() {
        // qwerty_en 的控制行是 [mode_change, comma, space, earth, enter]，earth 不写 width，
        // 必须由 functionKeyWidth 兜底为 1.1；否则该键会与左右邻键不等宽，比例就破了。
        val keys = section(realYaml(), "qwerty_en")
        assertNull("earth 应靠内置默认而不是显式 width", keys["earth"]?.width)
        val source = RepoFiles.file("src/main/java/com/kingzcheung/xime/ui/keyboard/KeyboardLayout.kt")
            .readText()
        val body = source.substringAfter("private fun functionKeyWidth(").substringBefore("\n}")
        assertTrue("空格内置默认应为 2.5", body.contains("\"space\" -> 2.5f"))
        assertTrue("其余功能键内置默认应为 1.1", body.contains("else -> 1.1f"))
        // 侧键不再各自特判：合并键布局（14/17/18 键）补齐的控制行也走同一默认
        assertFalse("侧键不应再有 1.2 的默认宽度", body.contains("1.2f"))
        assertFalse("侧键不应再有 0.8 的默认宽度", body.contains("0.8f"))
    }
}
