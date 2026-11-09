package com.kingzcheung.xime.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.keyboard.KeyActionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * xime.yaml（含 xime.custom.yaml 合并）已实现语法的解析覆盖测试。
 *
 * 与 [KeyboardGestureConfigTest]（手势语法细节）、KeyboardMergedLayoutTest（真实资产行布局）
 * 互补，聚焦三条此前没有防护的链路：
 * 1. `keyboard.actions` 预设 + `{ use: }` 引用：预设与引用同文件、跨 default/custom 文件、
 *    long_press.values 引用（用户真实场景），以及预设与 keys 的合并覆盖语义；
 * 2. `button_layout` 按键布局模式（此前零覆盖）；
 * 3. 内置资产与 docs/config_examples 示例的语法冒烟 + use 引用悬空检查，
 *    防止资产/文档改坏语法或引用不存在的预设。
 * 另固化「未知字段静默忽略」的现行行为（如 hint 类配置写了不生效的哨兵用例）。
 */
class KeyboardConfigSyntaxCoverageTest {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    // ═══ 1. 动作预设 + use 引用全链路 ═══

    @Test
    fun `use 引用于 long_press values（用户真实场景）`() {
        // 预设与引用写在同一文件（xime.custom.yaml 自含场景），
        // tap 字符串简写与 long_press.values 引用混用
        val text = """
            keyboard:
              actions:
                do_paste: { label: "粘贴", action: paste }
              qwerty:
                keys:
                  z:
                    tap: "z"
                    long_press:
                      values:
                        - { use: do_paste }
        """.trimIndent()
        val presets = KeysConfigHelper.parseKeyboardActionsYamlText(text)
        val binding = KeysConfigHelper.parseKeyboardYamlSection(text, "qwerty", presets)!!["z"]!!
        assertEquals(GestureAction.SEND_RIME, binding.tap!!.action)
        assertEquals("z", binding.tap!!.value)
        val item = binding.longPress!!.values.single()
        assertEquals(GestureAction.PASTE, item.action)
        assertEquals("粘贴", item.label)
    }

    @Test
    fun `use 引用于 swipe 与 double_tap 槽位`() {
        val text = """
            keyboard:
              actions:
                do_copy: { label: "复制", action: copy }
              qwerty:
                keys:
                  x:
                    tap: "x"
                    double_tap: { use: do_copy }
                    swipe_down: { use: do_copy }
        """.trimIndent()
        val presets = KeysConfigHelper.parseKeyboardActionsYamlText(text)
        val binding = KeysConfigHelper.parseKeyboardYamlSection(text, "qwerty", presets)!!["x"]!!
        assertEquals(GestureAction.COPY, binding.doubleTap!!.action)
        assertEquals(GestureAction.COPY, binding.swipeDown!!.action)
    }

    @Test
    fun `default 定义的预设可被 custom 文件引用`() {
        val default = """
            keyboard:
              actions:
                builtin_paste: { label: "粘贴", action: paste }
              qwerty:
                keys:
                  a: { tap: "a" }
        """.trimIndent()
        val custom = """
            keyboard:
              qwerty:
                keys:
                  z:
                    tap: "z"
                    long_press:
                      values:
                        - { use: builtin_paste }
        """.trimIndent()
        val pair = KeysConfigHelper.parseKeyboardGestureTexts(default, custom)
        val item = pair!!.first["z"]!!.longPress!!.values.single()
        assertEquals(GestureAction.PASTE, item.action)
        assertEquals("粘贴", item.label)
    }

    @Test
    fun `custom 同名预设覆盖 default`() {
        val default = """
            keyboard:
              actions:
                act: { label: "默认", action: paste }
              qwerty:
                keys:
                  a: { tap: "a" }
        """.trimIndent()
        val custom = """
            keyboard:
              actions:
                act: { label: "覆盖", action: copy }
              qwerty:
                keys:
                  z:
                    tap: "z"
                    swipe_down: { use: act }
        """.trimIndent()
        val pair = KeysConfigHelper.parseKeyboardGestureTexts(default, custom)
        val swipe = pair!!.first["z"]!!.swipeDown!!
        assertEquals(GestureAction.COPY, swipe.action)
        assertEquals("覆盖", swipe.label)
    }

    @Test
    fun `custom keys 整体覆盖 default 同名键且未覆盖键保留`() {
        val default = """
            keyboard:
              qwerty:
                keys:
                  q: { tap: "q", swipe_up: { value: "1" } }
                  a: { tap: "a" }
        """.trimIndent()
        val custom = """
            keyboard:
              qwerty:
                keys:
                  q: { tap: "q" }
        """.trimIndent()
        val pair = KeysConfigHelper.parseKeyboardGestureTexts(default, custom)
        val zh = pair!!.first
        // 同名键整体替换：custom 的 q 无 swipe_up，合并后不应残留 default 的
        assertNull(zh["q"]!!.swipeUp)
        // 未覆盖键保留 default
        assertEquals(GestureAction.SEND_RIME, zh["a"]!!.tap!!.action)
    }

    @Test
    fun `use 命中预设后内联字段不覆盖预设值`() {
        // 现行行为：use 命中即整体返回预设，引用处再写其他字段会被忽略
        val text = """
            keyboard:
              actions:
                do_paste: { label: "粘贴", action: paste }
              qwerty:
                keys:
                  z:
                    tap: "z"
                    swipe_down: { use: do_paste, label: "覆盖" }
        """.trimIndent()
        val presets = KeysConfigHelper.parseKeyboardActionsYamlText(text)
        val swipe = KeysConfigHelper.parseKeyboardYamlSection(text, "qwerty", presets)!!["z"]!!.swipeDown!!
        assertEquals("粘贴", swipe.label)
        assertEquals(GestureAction.PASTE, swipe.action)
    }

    @Test
    fun `预设引用了未注册的 action 时引用不生效`() {
        // 预设存在但其 action 拼错：解析告警，动作置空，引用处表现为无动作
        val text = """
            keyboard:
              actions:
                bad: { label: "坏", action: pasteee }
              qwerty:
                keys:
                  z:
                    tap: "z"
                    swipe_down: { use: bad }
        """.trimIndent()
        val presets = KeysConfigHelper.parseKeyboardActionsYamlText(text)
        val swipe = KeysConfigHelper.parseKeyboardYamlSection(text, "qwerty", presets)!!["z"]!!.swipeDown!!
        assertNull(swipe.action)
    }

    @Test
    fun `default 段缺 qwerty keys 时合并解析返回 null`() {
        val pair = KeysConfigHelper.parseKeyboardGestureTexts("metadata: {}", null)
        assertNull(pair)
    }

    // ═══ 1.5 平板设备档变体（<段>_tablet）═══

    @Test
    fun `平板且存在 tablet 段时整段替换基础段`() {
        val default = """
            keyboard:
              qwerty:
                keys:
                  a: { tap: "a" }
              qwerty_tablet:
                keys:
                  b: { tap: "b" }
        """.trimIndent()
        val pair = KeysConfigHelper.parseKeyboardGestureTexts(default, null, isTablet = true)
        val zh = pair!!.first
        assertNull("平板上不应残留基础段的键", zh["a"])
        assertEquals(GestureAction.SEND_RIME, zh["b"]!!.tap!!.action)
    }

    @Test
    fun `平板但无 tablet 段时保持基础段`() {
        val default = """
            keyboard:
              qwerty:
                keys:
                  a: { tap: "a" }
        """.trimIndent()
        val pair = KeysConfigHelper.parseKeyboardGestureTexts(default, null, isTablet = true)
        assertEquals(GestureAction.SEND_RIME, pair!!.first["a"]!!.tap!!.action)
    }

    @Test
    fun `非平板不启用 tablet 段`() {
        val default = """
            keyboard:
              qwerty:
                keys:
                  a: { tap: "a" }
              qwerty_tablet:
                keys:
                  b: { tap: "b" }
        """.trimIndent()
        val pair = KeysConfigHelper.parseKeyboardGestureTexts(default, null, isTablet = false)
        assertEquals(GestureAction.SEND_RIME, pair!!.first["a"]!!.tap!!.action)
        assertNull(pair.first["b"])
    }

    @Test
    fun `tablet 段只在自定义层定义时 default 层回退基础段`() {
        val default = """
            keyboard:
              qwerty:
                keys:
                  a: { tap: "a" }
        """.trimIndent()
        val custom = """
            keyboard:
              qwerty_tablet:
                keys:
                  c: { tap: "c" }
        """.trimIndent()
        val pair = KeysConfigHelper.parseKeyboardGestureTexts(default, custom, isTablet = true)
        val zh = pair!!.first
        // default 层基础段键保留，custom 层 tablet 键按键级合并
        assertEquals(GestureAction.SEND_RIME, zh["a"]!!.tap!!.action)
        assertEquals(GestureAction.SEND_RIME, zh["c"]!!.tap!!.action)
    }

    @Test
    fun `tablet 段行布局与按键布局同步变体`() {
        val default = """
            keyboard:
              qwerty:
                button_layout: compact
                layout:
                  rows:
                    - [q, w, e]
              qwerty_tablet:
                button_layout: standard
                layout:
                  rows:
                    - [q, w, e, r]
        """.trimIndent()
        val layout = KeysConfigHelper.parseKeyboardLayoutYamlText(default, "qwerty_tablet")
        assertEquals(listOf(listOf("q", "w", "e", "r")), layout)
        val (zhLayout, _) = KeysConfigHelper.parseButtonLayoutTexts(default, null, isTablet = true)
        assertEquals(ButtonLayout.STANDARD, zhLayout)
        val (zhLayoutPhone, _) = KeysConfigHelper.parseButtonLayoutTexts(default, null, isTablet = false)
        assertEquals(ButtonLayout.COMPACT, zhLayoutPhone)
    }

    // ═══ 2. button_layout（此前零覆盖）═══

    @Test
    fun `button_layout standard 与 compact 解析`() {
        val compact = """
            keyboard:
              qwerty: { button_layout: compact }
        """.trimIndent()
        val (zh, en) = KeysConfigHelper.parseButtonLayoutTexts(compact, null)
        assertEquals(ButtonLayout.COMPACT, zh)
        assertEquals(ButtonLayout.STANDARD, en)
    }

    @Test
    fun `button_layout 非法值静默回退 standard`() {
        // 固化现行行为：fromValue 对未知值回退 STANDARD（不报错）
        val text = """
            keyboard:
              qwerty: { button_layout: compct }
        """.trimIndent()
        val (zh, _) = KeysConfigHelper.parseButtonLayoutTexts(text, null)
        assertEquals(ButtonLayout.STANDARD, zh)
    }

    @Test
    fun `button_layout 未配置回退 standard`() {
        val (zh, en) = KeysConfigHelper.parseButtonLayoutTexts("keyboard: {}", null)
        assertEquals(ButtonLayout.STANDARD, zh)
        assertEquals(ButtonLayout.STANDARD, en)
    }

    @Test
    fun `qwerty 与 qwerty_en 的 button_layout 相互独立`() {
        val text = """
            keyboard:
              qwerty: { button_layout: compact }
              qwerty_en: { button_layout: standard }
        """.trimIndent()
        val (zh, en) = KeysConfigHelper.parseButtonLayoutTexts(text, null)
        assertEquals(ButtonLayout.COMPACT, zh)
        assertEquals(ButtonLayout.STANDARD, en)
    }

    @Test
    fun `button_layout custom 覆盖 default`() {
        val default = """
            keyboard:
              qwerty: { button_layout: standard }
        """.trimIndent()
        val custom = """
            keyboard:
              qwerty: { button_layout: compact }
        """.trimIndent()
        val (zh, en) = KeysConfigHelper.parseButtonLayoutTexts(default, custom)
        assertEquals(ButtonLayout.COMPACT, zh)
        assertEquals(ButtonLayout.STANDARD, en)
    }

    @Test
    fun `button_layout 合并键段可独立配置`() {
        // 自定义合并键段（由 schemas 绑定发现）的 button_layout 现已生效
        val text = """
            keyboard:
              qwerty: { button_layout: compact }
              qwerty_30:
                schemas: [my_dvorak]
                button_layout: standard
        """.trimIndent()
        assertEquals(ButtonLayout.STANDARD, KeysConfigHelper.parseButtonLayoutYamlText(text, "qwerty_30"))
    }

    @Test
    fun `button_layout 合并键段未配置返回 null 回退全局值`() {
        val text = """
            keyboard:
              qwerty: { button_layout: compact }
              qwerty_30:
                schemas: [my_dvorak]
        """.trimIndent()
        assertNull(KeysConfigHelper.parseButtonLayoutYamlText(text, "qwerty_30"))
    }

    @Test
    fun `resolveButtonLayout 段配置优先且英文态恒用全局值`() {
        val sectionLayouts = mapOf("qwerty_30" to ButtonLayout.COMPACT)
        // 中文态 + 活动段已配置 → 段值优先
        assertEquals(
            ButtonLayout.COMPACT,
            KeysConfigHelper.resolveButtonLayout(false, "qwerty_30", sectionLayouts, ButtonLayout.STANDARD, ButtonLayout.STANDARD),
        )
        // 中文态 + 活动段未配置 → 回退中文全局值
        assertEquals(
            ButtonLayout.STANDARD,
            KeysConfigHelper.resolveButtonLayout(false, "qwerty_17", sectionLayouts, ButtonLayout.STANDARD, ButtonLayout.STANDARD),
        )
        // 英文态 → 恒用英文全局值（合并键段与行布局同语义，仅中文态生效）
        assertEquals(
            ButtonLayout.STANDARD,
            KeysConfigHelper.resolveButtonLayout(true, "qwerty_30", sectionLayouts, ButtonLayout.COMPACT, ButtonLayout.STANDARD),
        )
        // 无活动合并段 → 全局值
        assertEquals(
            ButtonLayout.STANDARD,
            KeysConfigHelper.resolveButtonLayout(false, null, sectionLayouts, ButtonLayout.STANDARD, ButtonLayout.COMPACT),
        )
    }

    // ═══ 3. 内置资产冒烟 ═══

    @Test
    fun `内置资产 qwerty keys 可解析且动作均已注册`() {
        val text = assetXimeYaml()
        val zh = KeysConfigHelper.parseKeyboardYamlSection(text, "qwerty")
        assertNotNull("xime.yaml 缺少 qwerty keys", zh)
        assertTrue("qwerty keys 不应为空", zh!!.isNotEmpty())
        for ((id, binding) in zh) {
            for (action in allSlotActions(binding)) {
                if (action.action != null) {
                    assertEquals(
                        "键 $id 的动作 ${action.action} 未在注册表登记（注册项被删或改名？）",
                        action.action, KeyActionRegistry.fromId(action.action!!.value)?.action,
                    )
                }
            }
            val longPress = binding.longPress
            if (longPress != null) {
                for (item in longPress.values) {
                    assertTrue(
                        "键 $id 的长按候选既无 label 也无 value，无法展示",
                        item.label.isNotEmpty() || item.value.isNotEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun `内置资产其余 keyboard 段解析不抛异常`() {
        val text = assetXimeYaml()
        val en = KeysConfigHelper.parseKeyboardYamlSection(text, "qwerty_en")
        assertNotNull("xime.yaml 缺少 qwerty_en keys", en)
        // stroke/handwriting 可只有 schemas 无 keys，解析不抛即可
        KeysConfigHelper.parseKeyboardYamlSection(text, "stroke")
        KeysConfigHelper.parseKeyboardYamlSection(text, "handwriting")
        // 面板段（数字/常用符号，布局 + 手势）解析不抛；段结构与键 id 契约见 KeyboardPanelConfigTest
        for (section in KeysConfigHelper.PANEL_SECTIONS) {
            assertNotNull("xime.yaml 缺少 $section layout", KeysConfigHelper.parsePanelLayoutYamlText(text, section))
            KeysConfigHelper.parseKeyboardYamlSection(text, section)
        }
        KeysConfigHelper.parseKeyboardActionsYamlText(text)
        val (zhLayout, enLayout) = KeysConfigHelper.parseButtonLayoutTexts(text, null)
        assertTrue(zhLayout in ButtonLayout.entries)
        assertTrue(enLayout in ButtonLayout.entries)
    }

    @Test
    fun `内置资产 use 引用不悬空`() {
        val text = assetXimeYaml()
        val presets = KeysConfigHelper.parseKeyboardActionsYamlText(text)
        assertNoDanglingUseRefs(text, presets, "xime.yaml")
    }

    // ═══ 4. 未知字段行为固化（哨兵）═══

    @Test
    fun `键级未知字段（如 hint）被静默忽略`() {
        // 固化现行行为：键级只认 tap/double_tap/long_press/swipe_*/width，
        // hint 之类字段写了不生效也无警告。若将来实现 hint，请同步更新本用例。
        val text = """
            keyboard:
              qwerty:
                keys:
                  z: { tap: "z", hint: "粘贴" }
        """.trimIndent()
        val binding = KeysConfigHelper.parseKeyboardYamlSection(text, "qwerty")!!["z"]!!
        assertEquals(GestureAction.SEND_RIME, binding.tap!!.action)
        assertEquals("z", binding.tap!!.value)
    }

    // ═══ 6. 段类型 type 声明 ═══

    @Test
    fun `type 声明优先于段名推断且可解救撞保留名的全键盘段`() {
        val text = """
            keyboard:
              t9:
                type: full
                schemas: [some_full_schema]
                layout:
                  rows:
                    - [q, w, e]
        """.trimIndent()
        val types = KeysConfigHelper.parseSectionTypesYamlText(text)
        assertEquals("full", KeysConfigHelper.sectionKindOf("t9", types))
        val bindings = KeysConfigHelper.parseSchemaBindingsYamlText(text)
        // 段名撞了保留名，但显式 type: full 使其作为全键盘进入合并键解析
        assertEquals("t9", KeysConfigHelper.resolveMergedSection("some_full_schema", bindings, types))
    }

    @Test
    fun `type t9 使任意段名成为九键代码布局`() {
        val text = """
            keyboard:
              my_t9:
                type: t9
                schemas: [my_9key]
        """.trimIndent()
        val types = KeysConfigHelper.parseSectionTypesYamlText(text)
        val bindings = KeysConfigHelper.parseSchemaBindingsYamlText(text)
        assertEquals("t9", KeysConfigHelper.sectionKindOf("my_t9", types))
        // 代码布局段不进入合并键行布局解析
        assertNull(KeysConfigHelper.resolveMergedSection("my_9key", bindings, types))
    }

    @Test
    fun `未声明 type 时按段名推断`() {
        assertEquals("t9", KeysConfigHelper.sectionKindOf("t9", emptyMap()))
        assertEquals("stroke", KeysConfigHelper.sectionKindOf("stroke", emptyMap()))
        assertEquals("handwriting", KeysConfigHelper.sectionKindOf("handwriting", emptyMap()))
        assertEquals("full", KeysConfigHelper.sectionKindOf("qwerty_30", emptyMap()))
    }

    @Test
    fun `未知 type 回退按段名推断`() {
        val text = """
            keyboard:
              my_kb:
                type: nine_key
        """.trimIndent()
        val types = KeysConfigHelper.parseSectionTypesYamlText(text)
        assertTrue("未知 type 不产生声明", types.isEmpty())
        assertEquals("full", KeysConfigHelper.sectionKindOf("my_kb", types))
    }

    // ═══ 5. docs/config_examples 示例完整性 ═══

    @Test
    fun `docs 配置示例经产品合并函数解析且 use 引用不悬空`() {
        val assetText = assetXimeYaml()
        val dir = repoFile("docs/config_examples")
        val examples = dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { File(it, "xime.custom.yaml").takeIf { f -> f.exists() } }
            .orEmpty()
        assertTrue("应找到 docs/config_examples 示例", examples.isNotEmpty())
        for (example in examples) {
            val name = example.parentFile?.name ?: example.path
            val text = example.readText()
            // 镜像运行时合并：default 资产 + custom 示例
            val pair = KeysConfigHelper.parseKeyboardGestureTexts(assetText, text)
            assertNotNull("$name 经合并解析返回 null", pair)
            val presets = KeysConfigHelper.parseKeyboardActionsYamlText(assetText) +
                KeysConfigHelper.parseKeyboardActionsYamlText(text)
            assertNoDanglingUseRefs(text, presets, name)
            val (zhLayout, _) = KeysConfigHelper.parseButtonLayoutTexts(assetText, text)
            assertTrue("$name 的 button_layout 非法", zhLayout in ButtonLayout.entries)
        }
    }

    // ── helpers ──

    /** 定位仓库内文件（单测 workingDir 可能是模块目录或仓库根目录，逐级向上查找）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("file not found: $rel")
    }

    private fun assetXimeYaml(): String = repoFile("src/main/assets/xime.yaml").readText()

    /** 一个键绑定表上的全部动作槽位（含长按候选）。 */
    private fun allSlotActions(binding: KeyBinding): List<KeyAction> = buildList {
        binding.tap?.let { add(it) }
        binding.doubleTap?.let { add(it) }
        binding.swipeUp?.let { add(it) }
        binding.swipeDown?.let { add(it) }
        binding.swipeLeft?.let { add(it) }
        binding.swipeRight?.let { add(it) }
        binding.longPress?.values?.let { addAll(it) }
    }

    /** 递归收集 YAML 中所有 `{ use: X }` 引用的预设名。 */
    private fun collectUseRefs(node: YamlNode, into: MutableSet<String>) {
        when (node) {
            is YamlMap -> for ((k, v) in node.entries) {
                val key = (k as? YamlScalar)?.content
                if (key == "use" && v is YamlScalar && v.content.isNotBlank()) {
                    into += v.content
                } else {
                    collectUseRefs(v, into)
                }
            }
            is YamlList -> for (item in node.items) collectUseRefs(item, into)
            else -> {}
        }
    }

    /** 断言 [text] 中所有 use 引用都能在 [presets]（default+custom 合并后）解析到。 */
    private fun assertNoDanglingUseRefs(text: String, presets: Map<String, KeyAction>, source: String) {
        val refs = mutableSetOf<String>()
        collectUseRefs(yaml.parseToYamlNode(text), refs)
        for (ref in refs) {
            assertTrue("$source 引用了未定义的动作预设 \"$ref\"", ref in presets)
        }
    }
}
