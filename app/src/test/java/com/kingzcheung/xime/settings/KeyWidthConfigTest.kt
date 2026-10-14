package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 「装完后改配置文件即可自定义键宽」的验收测试。
 *
 * 生效链路：`Documents/Xime/xime.txt` 的 `keyboard.<布局>.keys.<键id>.width`
 *   → [KeysConfigHelper.parseKeyboardYamlSection] 解析出 width
 *   → `builtIn + custom` 整键覆盖
 *   → [KeysConfigHelper.getKeyGesture] 返回带 width 的 [KeyBinding]
 *   → 渲染层 `Modifier.weight(width)`
 *
 * 这里钉住最容易断的一环：width 必须穿过 YAML 解析与合并，存活到最终绑定。
 * 用的是**出厂 xime.yaml 原文**，因此真实文件里任何格式变动都会在这里暴露。
 */
class KeyWidthConfigTest {

    private val presets = emptyMap<String, KeyAction>()

    /** 定位仓库内文件（单测 workingDir 可能是模块目录或仓库根目录，逐级向上查找）。 */
    private fun repoFile(rel: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile ?: error("file not found: $rel")
        }
    }

    private fun realYaml(): String = repoFile("src/main/assets/xime.yaml").readText()

    private fun section(text: String, name: String = "qwerty"): Map<String, KeyBinding> =
        KeysConfigHelper.parseKeyboardYamlSection(text, name, presets)
            ?: error("解析 keyboard.$name.keys 失败")

    /** Float 宽度断言：走对象比较，避免 JUnit 基本类型重载带来的歧义。 */
    private fun assertWidth(expected: Float?, actual: Float?) = assertEquals(expected, actual)

    @Test
    fun `出厂配置功能键宽度为显式值、字母键留空走内置默认`() {
        val zh = section(realYaml())
        assertWidth(2.5f, zh["space"]!!.width)
        assertWidth(1.4f, zh["delete"]!!.width)
        assertWidth(1.4f, zh["shift"]!!.width)
        assertWidth(1.1f, zh["enter"]!!.width)
        assertWidth(1.1f, zh["mode_change"]!!.width)
        // 字母键不写 width → 渲染层按 1 等宽处理
        assertWidth(null, zh["q"]!!.width)
    }

    @Test
    fun `就地改 xime_txt 里的 width 数字即生效且不丢同键其他手势`() {
        val builtIn = section(realYaml())
        // 模拟用户在 Documents/Xime/xime.txt 里把空格宽度 2.5 改成 5，同一行其余内容原样保留
        val edited = realYaml().replace("space: { width: 2.5,", "space: { width: 5,")
        assertNotEquals("xime.yaml 的空格行格式已变，本测试的替换落空（否则会假通过）", realYaml(), edited)

        val merged = builtIn + section(edited)
        assertWidth(5f, merged["space"]!!.width)
        // 就地改数字不会丢同键其他手势
        assertEquals(GestureAction.SPACE, merged["space"]!!.tap?.action)
        assertNotNull(merged["space"]!!.longPress)
        assertNotNull(merged["space"]!!.swipeUp)
        // 同布局其他键不受影响
        assertWidth(1.4f, merged["delete"]!!.width)
        assertWidth(2.5f, builtIn["space"]!!.width)
    }

    @Test
    fun `英文布局 qwerty_en 同样支持改键宽`() {
        val en = section(realYaml(), "qwerty_en")
        assertWidth(2.5f, en["space"]!!.width)
        val edited = realYaml().replace("space: { width: 2.5,", "space: { width: 2,")
        val merged = en + section(edited, "qwerty_en")
        assertWidth(2f, merged["space"]!!.width)
    }

    @Test
    fun `keys 条目是整键覆盖——最小化 custom 只写 width 会丢手势`() {
        // 记录当前语义（custom 键级覆盖 = 整键替换，不是字段级合并）：
        // xime.txt 是完整快照，就地改数字不会踩到；但新建一个只写 width 的
        // 最小化 custom 文件会把该键的 tap/long_press/swipe_* 全部清空。
        val minimal = """
            keyboard:
              qwerty:
                keys:
                  space: { width: 4 }
        """.trimIndent()
        val merged = section(realYaml()) + section(minimal)
        assertWidth(4f, merged["space"]!!.width)
        assertNull("整键覆盖语义变了：请同步更新本断言与文档说明", merged["space"]!!.tap)
    }
}
