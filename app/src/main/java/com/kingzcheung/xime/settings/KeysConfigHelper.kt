package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.ui.TABLET_SMALLEST_WIDTH_DP
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

// ── 键盘手势配置 ──

/**
 * 把滑动手势定义转换为布局层回调（九键/笔画布局共用，26 键在 KeyboardLayout 内联处理）。
 *
 * - COMMIT 走 [onCommitText]：落点由布局决定（九键直接上屏数字——T9 模式 onKeyPress(数字)
 *   会进拼音数字码组合，笔画则沿用按键路由保持原语义）；
 * - NONE 视为未绑定；
 * - 其余动作（复制/粘贴/光标移动/面板切换等）走 [onGestureAction]（UI 拦截层 → 动作分发）。
 */

internal fun swipeHandlerFor(
    def: KeyAction?,
    onCommitText: (String) -> Unit,
    onGestureAction: ((GestureAction, String) -> Unit)?,
): (() -> Unit)? {
    val action = def?.action ?: return null
    if (action == GestureAction.NONE) return null
    val value = def.value.ifEmpty { def.label }
    return {
        if (action == GestureAction.COMMIT) onCommitText(value)
        else onGestureAction?.invoke(action, value)
    }
}


/**
 * 单个键盘的间距覆盖配置。
 */
data class KeyboardSpacingConfig(
    val spacingX: Float? = null,
    val spacingY: Float? = null,
    /** 该键盘的按键圆角覆盖（dp，null = 用 `keyboard.key.corner_radius` 全局值） */
    val cornerRadius: Int? = null,
)

/**
 * 键盘按键配置，从 xime.yaml keyboard.key 加载。
 */
data class KeyboardKeyConfig(
    val cornerRadius: Int = 8,
    /** 按键横向间距（dp），未配置时各布局使用自身默认值 */
    val spacingX: Float? = null,
    /** 按键竖向间距（dp），未配置时各布局使用自身默认值 */
    val spacingY: Float? = null,
    /** 各键盘单独覆盖的圆角/间距，key 为键盘名（qwerty/t9/number/stroke/symbol，
     *  平板档可写 `<名>_tablet`，加载时并到同名基础项） */
    val spacingOverrides: Map<String, KeyboardSpacingConfig> = emptyMap(),
) {
    /**
     * 获取指定键盘的有效间距（覆盖值优先，回退全局值）。
     * @return Pair(横向, 竖向)，null 表示未配置
     */
    fun spacingFor(keyboard: String): Pair<Float?, Float?> {
        val override = spacingOverrides[keyboard]
        val sx = override?.spacingX ?: spacingX
        val sy = override?.spacingY ?: spacingY
        return sx to sy
    }

    /**
     * 获取指定键盘的有效按键圆角（dp）：`keyboard.key.<键盘名>.corner_radius` 优先，
     * 未配置回退全局 `corner_radius`。
     */
    fun cornerRadiusFor(keyboard: String): Int =
        spacingOverrides[keyboard]?.cornerRadius ?: cornerRadius
}

/**
 * 键盘颜色配置，从 xime.yaml keyboard.colors 加载。
 * 所有颜色值为 0xRRGGBB 格式（不含 alpha）。
 */
/** 全局键盘颜色后备，背景色已由 color_schemes 的 keyboard_background 接管。 */
data class KeyboardColorsConfig(
    val keyBgColor: Long = 0xFFFFFF,
    val keyBgColorDark: Long = 0x4A4A4A,
    val specialKeyBgColor: Long? = null,
    val specialKeyBgColorDark: Long? = null,
    val keyTextColor: Long = 0x202124,
    val keyTextColorDark: Long = 0xE8EAED,
    val candidateTextColor: Long = 0x1A73E8,
    val candidateTextColorDark: Long = 0x8AB4F8,
) {
    companion object {
        /** 键盘背景后备色，仅当 color_schemes 无 keyboard_background 时使用。 */
        const val FALLBACK_BG_LIGHT: Long = 0xE3E4E8
        const val FALLBACK_BG_DARK: Long = 0x202020
    }
}

/**
 * 解析一个键的手势绑定表（`keyboard.<section>.keys.<keyId>`）。
 *
 * 槽位：tap / double_tap / long_press / swipe_up / swipe_down / swipe_left / swipe_right，
 * 另有键级 `width`（列宽权重）。
 *
 * @param presets `keyboard.actions` 定义的可复用动作预设
 */

internal fun parseKeyBinding(
    map: com.charleskorn.kaml.YamlMap,
    presets: Map<String, KeyAction> = emptyMap(),
): KeyBinding {
    var tap: KeyAction? = null
    var doubleTap: KeyAction? = null
    var longPress: LongPressAction? = null
    var swipeUp: KeyAction? = null
    var swipeDown: KeyAction? = null
    var swipeLeft: KeyAction? = null
    var swipeRight: KeyAction? = null
    var width: Float? = null
    for ((kNode, vNode) in map.entries) {
        val name = kNode.content
        when (name) {
            "tap" -> tap = parseKeyAction(vNode, GestureAction.SEND_RIME, presets)
            "double_tap" -> doubleTap = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "long_press" -> longPress = parseLongPress(vNode, presets)
            "swipe_up" -> swipeUp = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "swipe_down" -> swipeDown = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "swipe_left" -> swipeLeft = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "swipe_right" -> swipeRight = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "width" -> width = (vNode as? com.charleskorn.kaml.YamlScalar)?.content?.toFloatOrNull()
        }
    }
    return KeyBinding(tap, doubleTap, longPress, swipeUp, swipeDown, swipeLeft, swipeRight, width)
}

/**
 * 解析 long_press。支持两种写法：`{ display?, values: [...] }` 与单项直写 `{ action?, value?, ... }`。
 *
 * - [display] 缺省为 `bubble`（长按弹出气泡滑动选择）；设为 `key` 则显示在键面，不弹气泡；
 * - [values] 为候选动作列表（单动作也写成单元素列表）；
 * - 不写 `values` 而直接给 `action`/`value`/`label`/`icon`/`use` 时，等价于只含这一项的列表；
 *   配合 `bubble: false` 即为「不弹气泡、长按直接执行该动作」。
 */
/** 长按气泡最多展示的候选数（气泡按此宽度布局）。 */
private const val MAX_LONG_PRESS_VALUES = 10

/** long_press 单项直写形式（不写 `values`）认可的字段。 */
private val LONG_PRESS_DIRECT_FIELDS = setOf("action", "value", "label", "icon", "use")

private fun parseLongPress(
    node: com.charleskorn.kaml.YamlNode,
    presets: Map<String, KeyAction>,
): LongPressAction? {
    if (node !is com.charleskorn.kaml.YamlMap) {
        if (node is YamlList) {
            Log.w("KeysConfigHelper", "long_press 需写成 { display, values: [...] } 形式，数组简写已不再支持")
        }
        return null
    }
    val valuesNode = node.opt<YamlList>("values")
    if (valuesNode == null) {
        // 单项直写：long_press: { action: "voice", value: "5", bubble: false }
        val fields = node.entries.keys.mapNotNull { (it as? YamlScalar)?.content }.toSet()
        if (fields.none { it in LONG_PRESS_DIRECT_FIELDS }) return null
        return LongPressAction(
            display = DisplayMode.BUBBLE,
            values = listOf(parseKeyAction(node, GestureAction.COMMIT, presets)),
        )
    }
    var display = node.opt<YamlScalar>("display")?.content
        ?.let { DisplayMode.fromValue(it) }
        ?: DisplayMode.BUBBLE
    if (display != DisplayMode.BUBBLE) {
        // 长按的键面绘制尚未实现（v1 曾有「键面显示前 2 个候选」）：一律按气泡处理，
        // 否则 display: key/both 会让长按候选既不画键面、也不弹气泡 —— 完全不可达。
        Log.w("KeysConfigHelper", "long_press 的 display 只支持 bubble：键面绘制未实现，已按 bubble 处理")
        display = DisplayMode.BUBBLE
    }
    if (valuesNode.items.size > MAX_LONG_PRESS_VALUES) {
        Log.w(
            "KeysConfigHelper",
            "long_press.values 最多 $MAX_LONG_PRESS_VALUES 项，已忽略多余 ${valuesNode.items.size - MAX_LONG_PRESS_VALUES} 项"
        )
    }
    val values = valuesNode.items.map { parseKeyAction(it, GestureAction.COMMIT, presets) }.take(MAX_LONG_PRESS_VALUES)
    if (values.any { it.label.ifEmpty { it.value }.isEmpty() }) {
        Log.w("KeysConfigHelper", "long_press 存在既无 label 也无 value 的项：该项无法在气泡中展示，也不会生效")
    }
    if (values.size > 1 && values.any { !it.bubble }) {
        Log.w("KeysConfigHelper", "long_press 多项时不支持 bubble: false（会让该项无法选中），已忽略")
    }
    return LongPressAction(
        display = display,
        values = values,
    )
}

/**
 * 解析单个手势槽位为 [KeyAction]。
 *
 * 取值形式：
 * - 字符串 `"q"` → 字面简写（按槽位默认动作 + value=文本）
 * - 对象 `{ label: "复制", action: copy }` → 内联动作
 * - 对象 `{ use: 预设名 }` → 引用 `keyboard.actions` 预设
 *
 * [defaultAction] 为槽位语义默认：tap → SEND_RIME，swipe/double_tap/long_press → COMMIT。
 */
private fun parseKeyAction(
    node: com.charleskorn.kaml.YamlNode,
    defaultAction: GestureAction,
    presets: Map<String, KeyAction>,
): KeyAction {
    if (node is com.charleskorn.kaml.YamlScalar) {
        val text = node.content
        val icon = if (text.startsWith("@")) text.removePrefix("@") else ""
        val cleanLabel = if (icon.isNotEmpty()) "" else text
        return KeyAction(action = defaultAction, value = text, label = cleanLabel, icon = icon)
    }
    if (node is com.charleskorn.kaml.YamlMap) {
        // 预设引用：{ use: 名称 }
        val use = node.opt<YamlScalar>("use")?.content
        if (!use.isNullOrEmpty()) {
            val preset = presets[use]
            if (preset == null) {
                Log.w("KeysConfigHelper", "手势配置引用了未知动作预设: \"$use\"")
                return KeyAction()
            }
            return preset
        }
        var label = ""
        var iconName = ""
        var action: GestureAction? = defaultAction
        var value = ""
        var display = "key"
        var bubble = true
        for ((k, v) in node.entries) {
            val key = k.content
            when (key) {
                "label" -> {
                    if (v is YamlList) {
                        // 数组 label 按多行显示：join 后就是显示文本
                        label = v.items.mapNotNull { (it as? YamlScalar)?.content }.joinToString("\n")
                    } else {
                        label = (v as? YamlScalar)?.content ?: continue
                    }
                }
                "action" -> {
                    val vStr = (v as? YamlScalar)?.content
                    if (vStr == null || vStr == "null") {
                        action = null
                    } else {
                        action = GestureAction.fromValue(vStr)
                        if (action == null) {
                            Log.w("KeysConfigHelper", "手势配置包含未知 action: \"$vStr\"，该手势将不生效")
                        }
                    }
                }
                "value" -> (v as? YamlScalar)?.content?.let { value = it }
                "display" -> (v as? YamlScalar)?.content?.let { display = it }
                "bubble" -> bubble = (v as? YamlScalar)?.content?.toBooleanStrictOrNull() ?: true
                // 图标名：`icon: mic` 与历史写法 `label: "@mic"` 等价
                "icon" -> (v as? YamlScalar)?.content?.takeIf { it.isNotBlank() }?.let { iconName = it.removePrefix("@") }
                else -> Log.w("KeysConfigHelper", "手势配置含未知字段 \"$key\"（多为拼写错误），已忽略")
            }
        }
        val iconFromLabel = if (label.startsWith("@")) label.removePrefix("@") else ""
        val icon = iconName.ifEmpty { iconFromLabel }
        val cleanLabel = if (iconFromLabel.isNotEmpty()) "" else label
        return KeyAction(
            action = action,
            value = value,
            label = cleanLabel,
            icon = icon,
            display = DisplayMode.fromValue(display),
            bubble = bubble,
        )
    }
    return KeyAction(action = null)
}

/** 字符串字段的 fallback：空白视为未配置，路由到下一级。 */

internal fun tieredText(custom: String?, builtIn: String?, default: String): String =
    custom?.takeIf { it.isNotBlank() }
        ?: builtIn?.takeIf { it.isNotBlank() }
        ?: default

/**
 * kaml 0.104 的 [YamlMap.get] 是 reified 泛型取值，值类型与期望类型不符时抛
 * IncorrectTypeException（如 `fonts:` 段整体被注释时值为 YamlNull，`["fonts"] as? YamlMap`
 * 会在 get 内部直接抛异常，`as?` 无机会返回 null）。
 * 统一经 YamlNode 取值后再做真正的安全转换：段缺失/为 null/类型不符时返回 null 而非抛异常。
 */
private inline fun <reified T : YamlNode> YamlMap.opt(key: String): T? =
    get<YamlNode>(key) as? T

object KeysConfigHelper {
    private const val TAG = "KeysConfigHelper"
    private const val XIME_CONFIG_FILE = "xime.yaml"
    private const val XIME_CUSTOM_CONFIG_FILE = "xime.custom.yaml"

    /**
     * 由 `layout.rows` 引用的功能键 id（行为在其对应 `keys.<id>` 配置）。
     * 布局行里出现这些 id 时，渲染层按 id 选择功能键组件而非字母键。
     */
    val FUNCTION_KEY_IDS: Set<String> = setOf(
        "shift", "delete", "enter", "space", "mode_change", "symbol", "emoji", "earth", "voice", "comma", "period",
        "cursor_left", "cursor_right", "tab", "caps", "shift_left", "shift_right",
        // 面板专用功能键（数字/常用符号键盘，行为内置：返回主键盘/切数字/切常用符号/打开全量符号/中英切换/空格）
        // back_right：平板面板右端的「返回字母键盘」键（与左端 back 同行为、各自可配宽度）
        "back", "back_right", "number", "open_symbols", "space_bar", "space_key",
    )

    /**
     * 九键布局的功能键 id（keyboard.t9.layout 可把任意 id 安排到任意位置）：
     * candidates=拼音候选面板、symbol=符号、number=数字面板、space=空格、
     * earth=中英切换、delete=退格、clear=重输、enter=回车。
     * 组件与行为内置，不可经 keys.<id> 改绑；键在行/列中的相对宽度可用 keys.<id>.width 覆盖。
     */
    val T9_FUNCTION_KEY_IDS: Set<String> = setOf(
        "candidates", "symbol", "number", "space", "earth", "delete", "clear", "enter"
    )

    /** 九键左侧快捷符号栏内置默认值（T9KeyboardLayout 硬编码的历史行为）。 */
    val DEFAULT_T9_SIDE_SYMBOLS: List<String> = listOf("，", "。", "？", "！")

    /**
     * 当前设备是否为平板/大屏（smallestWidth ≥ 600dp，与 ui.isTablet() 的 sw600dp 档一致，
     * 不随横竖屏变化）。KeysConfigHelper 无 Compose 上下文，用 resources 配置直接判定。
     */
    internal fun isTabletDevice(context: Context): Boolean {
        // resources 可能取不到（单测 mock Context / 极端环境），此时按手机档处理而不是抛异常：
        // 该函数在 loadXimeConfig 内被调用，抛异常会让整份配置静默不生效（外层 catch 兜底）。
        val smallestWidthDp = context.resources?.configuration?.smallestScreenWidthDp ?: 0
        return smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP
    }

    /** 平板设备档变体段/覆盖名的后缀（`qwerty_tablet`、`number_tablet`、`key.number_tablet` 同此约定）。 */
    internal const val TABLET_SECTION_SUFFIX = "_tablet"

    /** 判断 YAML 文本是否定义了 `keyboard.<section>` 段。 */
    private fun hasKeyboardSection(yamlText: String, section: String): Boolean = try {
        val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return false
        val keyboardNode = root.opt<YamlMap>("keyboard") ?: return false
        keyboardNode.opt<YamlMap>(section) != null
    } catch (_: Exception) {
        false
    }

    /**
     * 平板设备档变体段名解析：平板上若 yaml 定义了 `<section>_tablet` 段（内置或用户自定义
     * 任一来源存在即可），QWERTY 全键盘整段改用平板变体；否则维持原段。
     *
     * 回退保证兼容：非平板不受影响；老用户自定义 yaml 没有 `_tablet` 段时也维持原行为；
     * 平板上只写了基础段自定义（无 `_tablet`）则走内置平板默认段。
     */
    internal fun resolveQwertySection(
        section: String,
        defaultText: String,
        customText: String?,
        isTablet: Boolean,
    ): String {
        if (!isTablet) return section
        val tabletSection = section + TABLET_SECTION_SUFFIX
        val hasTablet = hasKeyboardSection(defaultText, tabletSection) ||
            (customText != null && hasKeyboardSection(customText, tabletSection))
        return if (hasTablet) tabletSection else section
    }    /** 笔画键盘左侧符号列内置默认（与历史硬编码一致）。 */
    val DEFAULT_STROKE_SIDE_SYMBOLS: List<String> = listOf("。", "？", "！", "~")
    
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    
    private var config: KeysConfig = KeysConfig(
        swipeUp = getDefaultSwipeUp(),
        swipeDownEnglish = getDefaultSwipeDownEnglish()
    )

    // 手势配置缓存（mutableStateOf 让 Compose 直接观察变更）
    // 中文键盘（qwerty）手势配置缓存
    private val _keyGestureConfig = mutableStateOf<Map<String, KeyBinding>>(emptyMap())
    val keyGestureConfig: Map<String, KeyBinding> get() = _keyGestureConfig.value
    
    // 英文键盘（qwerty_en）手势配置缓存
    private val _keyGestureConfigEn = mutableStateOf<Map<String, KeyBinding>>(emptyMap())
    val keyGestureConfigEn: Map<String, KeyBinding> get() = _keyGestureConfigEn.value
    
    // 键盘颜色配置缓存
    private var keyboardColorsConfig: KeyboardColorsConfig = KeyboardColorsConfig()
    
    // 键盘阴影配置缓存
    private var keyboardShadowConfig: KeyboardShadowConfig = KeyboardShadowConfig()

    // 键盘按键配置缓存
    private var keyboardKeyConfig: KeyboardKeyConfig = KeyboardKeyConfig()

    // 候选栏行为配置缓存（清空按键显隐 + 标点键直选候选）
    private var keyboardCandidatesConfig: KeyboardCandidatesConfig = KeyboardCandidatesConfig()

    // 手势灵敏度配置缓存（长按判定时间）
    private var keyboardGesturesConfig: KeyboardGesturesConfig = KeyboardGesturesConfig()

    // 九键（T9）键盘配置缓存
    private var keyboardT9Config: KeyboardT9Config = KeyboardT9Config(sideSymbols = DEFAULT_T9_SIDE_SYMBOLS)
    private var keyboardStrokeConfig: KeyboardStrokeConfig = KeyboardStrokeConfig(sideSymbols = DEFAULT_STROKE_SIDE_SYMBOLS)

    // 面板段（数字/常用符号）配置缓存：keyboard.<section> 独立解析（布局 + 手势），custom 键级覆盖，
    // 支持 <section>_tablet 设备档变体（同 qwerty 机制）。布局缺失的段不入表，渲染层走内置兜底。
    private var _panelLayouts: Map<String, PanelLayoutConfig> = emptyMap()
    private var _panelGestureConfigs: Map<String, Map<String, KeyBinding>> = emptyMap()

    /** 面板段名清单（数字键盘 / 常用符号键盘；符号面板中英共用一套，无中/英两段）。 */
    val PANEL_SECTIONS: List<String> = listOf("number", "common_symbols")

    /** 面板布局：rows 模式（常用符号，行列表）或 columns 模式（数字键盘，左列/中行/右列）。 */
    fun getPanelLayout(section: String): PanelLayoutConfig? = _panelLayouts[section]

    /** 按段取键手势：qwerty 走主键盘缓存（含合并键段激活态），面板段走面板缓存。 */
    fun getGestureFor(section: String, key: String, isAsciiMode: Boolean = false): KeyBinding? =
        if (section == "qwerty") getKeyGesture(key, isAsciiMode) else _panelGestureConfigs[section]?.get(key)

    // 字体配置缓存
    private var keyboardFontConfig: KeyboardFontConfig = KeyboardFontConfig()
    fun getKeyboardFontConfig(): KeyboardFontConfig = keyboardFontConfig
    
    // 按键布局模式缓存（中文 qwerty / 英文 qwerty_en）
    private var _buttonLayoutZh: ButtonLayout = ButtonLayout.STANDARD
    private var _buttonLayoutEn: ButtonLayout = ButtonLayout.STANDARD
    // 合并键段级按键布局缓存：section（qwerty_14/17/18 及 custom 新增）→ 配置值（未配置的段不入表）
    private var _buttonLayoutBySection: Map<String, ButtonLayout> = emptyMap()
    fun getButtonLayout(isAsciiMode: Boolean): ButtonLayout =
        resolveButtonLayout(isAsciiMode, _activeMergedSection, _buttonLayoutBySection, _buttonLayoutZh, _buttonLayoutEn)

    // 键盘行布局默认值
    private val DEFAULT_ZH_ROWS: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )
    private val DEFAULT_EN_ROWS: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )

    // 键盘行布局缓存（中文 / 英文）
    private var _zhRows: List<List<String>> = DEFAULT_ZH_ROWS
    private var _enRows: List<List<String>> = DEFAULT_EN_ROWS

    // 标准 26 键的基线缓存（合并键布局切换退出时恢复）
    private var _zhRowsBase: List<List<String>> = DEFAULT_ZH_ROWS
    private var _keyGestureConfigZhBase: Map<String, KeyBinding> = emptyMap()

    // 合并键布局缓存：section（qwerty_14/17/18 及 custom 新增）→ 行布局 / 手势配置
    private var _mergedRows: Map<String, List<List<String>>> = emptyMap()
    private var _mergedGestureConfigs: Map<String, Map<String, KeyBinding>> = emptyMap()
    private var _activeMergedSection: String? = null
    private var _activeSchemaId: String = ""

    // 合并键绑定缓存：schemaId → 键盘 section（xime.yaml keyboard.<section>.schemas 声明）
    private var _schemaSectionBindings: Map<String, String> = emptyMap()
    // 段类型缓存：section → type（keyboard.<section>.type 显式声明，custom 覆盖 builtIn；缺省按段名推断）
    private var _sectionTypes: Map<String, String> = emptyMap()

    /**
     * 代码布局 section：由专属组件渲染（T9KeyboardLayout / StrokeKeyboardLayout /
     * HandwritingKeyboardLayout）。t9 有专属 layout{left,rows,right} 由 t9 配置解析器处理，
     * 不走通用 layout.rows；stroke/handwriting 无行数据。schemas 绑定到这些类型段的方案
     * 走 [codeLayoutForSchema] 查询，不进入合并键行布局缓存
     * （[mergedSectionForSchema] 对其返回 null）。
     * 段名为内置保留名；任意段名可通过 keyboard.<section>.type 显式声明类型（[sectionKindOf]）。
     */
    internal val CODE_LAYOUT_SECTIONS = setOf(SECTION_TYPE_T9, SECTION_TYPE_STROKE, SECTION_TYPE_HANDWRITING)

    /** 段类型取值：全键盘、三类代码布局与面板三列布局（数字键盘）。 */
    internal const val SECTION_TYPE_FULL = "full"
    internal const val SECTION_TYPE_T9 = "t9"
    internal const val SECTION_TYPE_STROKE = "stroke"
    internal const val SECTION_TYPE_HANDWRITING = "handwriting"
    internal const val SECTION_TYPE_COLUMNS = "columns"
    private val SECTION_TYPES_KNOWN = setOf(
        SECTION_TYPE_FULL, SECTION_TYPE_T9, SECTION_TYPE_STROKE, SECTION_TYPE_HANDWRITING,
        SECTION_TYPE_COLUMNS,
    )

    /** 段类型判定：显式 type 声明优先，未声明按段名推断（保留名为代码布局，其余全键盘）。 */
    internal fun sectionKindOf(section: String, sectionTypes: Map<String, String>): String {
        sectionTypes[section]?.let { return it }
        return if (section in CODE_LAYOUT_SECTIONS) section else SECTION_TYPE_FULL
    }

    // 九键/笔画手势配置缓存（keyboard.t9.keys / keyboard.stroke.keys，custom 键级覆盖）。
    // 键 id 不做大小写归一：九键为数字字符串 "1"~"9"，笔画为键面标签（一/丨/丿/丶/乛 等）。
    // 以下单例为「当前激活段」的数据：type 声明的多个代码布局段各存一份（下方 BySection 缓存），
    // 方案切换时由 [setActiveKeyboardSchema] 换入。
    private var _t9GestureConfigs: Map<String, KeyBinding> = emptyMap()
    private var _strokeGestureConfigs: Map<String, KeyBinding> = emptyMap()
    private var _t9LayoutsBySection: Map<String, KeyboardT9Config> = emptyMap()
    private var _t9GestureConfigsBySection: Map<String, Map<String, KeyBinding>> = emptyMap()
    private var _strokeLayoutsBySection: Map<String, KeyboardStrokeConfig> = emptyMap()
    private var _strokeGestureConfigsBySection: Map<String, Map<String, KeyBinding>> = emptyMap()
    private var _activeT9Section: String? = null
    private var _activeStrokeSection: String? = null

    /** 全键盘方案（含合并键布局）对应的 xime.yaml 键盘 section，代码布局方案返回 null。 */
    internal fun mergedSectionForSchema(schemaId: String): String? =
        resolveMergedSection(schemaId, _schemaSectionBindings, _sectionTypes)

    /**
     * 绑定解析：仅认 schemas 声明（xime.yaml / xime.custom.yaml keyboard.<section>.schemas），
     * 未声明的方案一律全键盘（26 键），不做 id 关键字猜测。
     * 代码布局 section（type 声明或段名为 t9/stroke/handwriting）不是行数据布局，不作为合并键解析结果。
     */
    internal fun resolveMergedSection(
        schemaId: String,
        bindings: Map<String, String>,
        sectionTypes: Map<String, String> = emptyMap(),
    ): String? =
        bindings[schemaId]?.takeIf { sectionKindOf(it, sectionTypes) == SECTION_TYPE_FULL }

    /**
     * 按键布局解析：活动合并键段配置了 button_layout 时优先，未配置回退 qwerty/qwerty_en 全局值。
     * 英文态恒用英文全局值（合并键段与行布局同语义：仅中文态生效）。
     */
    internal fun resolveButtonLayout(
        isAsciiMode: Boolean,
        activeSection: String?,
        sectionLayouts: Map<String, ButtonLayout>,
        zhLayout: ButtonLayout,
        enLayout: ButtonLayout,
    ): ButtonLayout {
        if (!isAsciiMode && activeSection != null) {
            sectionLayouts[activeSection]?.let { return it }
        }
        return if (isAsciiMode) enLayout else zhLayout
    }

    /** 查询方案声明的键盘 section（任意类型，含代码布局），未声明返回 null。 */
    fun boundSectionForSchema(schemaId: String): String? = _schemaSectionBindings[schemaId]

    /** 查询方案绑定段的类型（full/t9/stroke/handwriting，显式 type 优先于段名推断），未声明返回 null。 */
    fun schemaSectionKind(schemaId: String): String? =
        _schemaSectionBindings[schemaId]?.let { sectionKindOf(it, _sectionTypes) }

    /** 查询方案绑定的代码布局 section（t9 九键 / stroke 笔画 / handwriting 手写），未绑定返回 null。 */
    fun codeLayoutForSchema(schemaId: String): String? =
        _schemaSectionBindings[schemaId]?.takeIf { sectionKindOf(it, _sectionTypes) in CODE_LAYOUT_SECTIONS }

    /**
     * 按当前方案切换键盘缓存（合并键布局 ↔ 标准 26 键 ↔ 代码布局按段切换）。
     * 在 KeyboardViewModel.dispatch/resetKeyboard 收到 schemaId 时调用；
     * 英文键盘不受影响（合并键布局仅中文拼音方案使用）。
     */
    fun setActiveKeyboardSchema(schemaId: String) {
        _activeSchemaId = schemaId
        val bound = _schemaSectionBindings[schemaId]
        val kind = bound?.let { sectionKindOf(it, _sectionTypes) }
        val merged = bound?.takeIf { kind == SECTION_TYPE_FULL }
        val t9 = bound?.takeIf { kind == SECTION_TYPE_T9 }
        val stroke = bound?.takeIf { kind == SECTION_TYPE_STROKE }
        if (merged == _activeMergedSection && t9 == _activeT9Section && stroke == _activeStrokeSection) return
        _activeMergedSection = merged
        if (merged != null) {
            _zhRows = _mergedRows[merged] ?: DEFAULT_ZH_ROWS
            _keyGestureConfig.value = _mergedGestureConfigs[merged] ?: emptyMap()
        } else {
            _zhRows = _zhRowsBase
            _keyGestureConfig.value = _keyGestureConfigZhBase
        }
        // 代码布局按段切换：type 声明的任意段名可并存多份九键/笔画配置，激活哪个段就换入哪份
        t9?.let { s ->
            _t9LayoutsBySection[s]?.let { keyboardT9Config = it }
            _t9GestureConfigsBySection[s]?.let { _t9GestureConfigs = it }
            _activeT9Section = s
        }
        stroke?.let { s ->
            _strokeLayoutsBySection[s]?.let { keyboardStrokeConfig = it }
            _strokeGestureConfigsBySection[s]?.let { _strokeGestureConfigs = it }
            _activeStrokeSection = s
        }
    }

    /** 获取键盘行布局，每个子 List 为一行的按键 ID 列表，索引 0=第一行。
     *  合并键布局中合并键的 ID 为组内字母拼接（如 "qw"），渲染层按 ID 长度分配键宽。 */
    fun getKeyRows(isAsciiMode: Boolean): List<List<String>> =
        if (isAsciiMode) _enRows else _zhRows
    
    /** 配置版本号，每次 loadConfig 时递增，用于 Compose 感知配置变更。 */
    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion.asStateFlow()

    /**
     * 组合键表（keyboard.combos）：触发码 → 候选项。
     *
     * 内置默认来自 assets/xime.yaml，用户可在统一配置目录里增删；改动经 configVersion
     * 热重载（与其它键盘配置同一套 mtime 感知）。
     */
    private var keyboardCombos: List<KeyboardCombo> = emptyList()

    /** 当前生效的组合键表（只读）。 */
    fun getKeyboardCombos(): List<KeyboardCombo> = keyboardCombos
    /** 当前生效的自动造词配置（只读）；未配置时用默认值。 */
    fun getAutoWordConfig(): AutoWordConfig =
        mergedConfigCache?.autoWord ?: AutoWordConfig()
    /**
     * 按输入编码匹配组合键：返回编码与输入**完全相等**的条目。
     *
     * 用全等而不是前缀：`orq` 触发日期组合键后，用户继续输入 `orqx` 时不应再弹候选
     * （避免候选栏在组合键结果与引擎候选之间闪动）。
     */
    fun matchCombo(input: String): KeyboardCombo? {
        if (input.isEmpty()) return null
        return keyboardCombos.firstOrNull { it.code == input }
    }
    
    private var mergedConfigCache: XimeConfig? = null
    private var mergedConfigVersion = 0
    
    fun loadConfig(context: Context): KeysConfig {
        loadXimeConfig(context)
        config = config.copy(
            swipeUp = getDefaultSwipeUp(),
            swipeDownEnglish = getDefaultSwipeDownEnglish()
        )
        return config
    }
    
    // 进程内配置缓存戳（xime.custom.yaml 的 mtime+size）：全量 YAML 解析真机实测可阻塞
    // 主线程 ~1.8s，而输入法服务重建时配置通常未变。内置 xime.yaml 随 APK 更新（进程重启）
    // 天然失效；外部改动 custom 文件经 mtime 感知穿透；解析失败不缓存下次重试。
    private var _loadedCustomStamp: Pair<Long, Long>? = null

    // 纠错绑定列表及其缓存戳（correction.schemas，独立于全量解析——该查询在按键
    // 热路径调用，只解析顶层一个列表，开销远小于 loadXimeConfig 全量解析）。
    private var _correctionSchemas: Set<String> = emptySet()
    private var _correctionSchemasStamp: Pair<Long, Long>? = null

    /**
     * 启用邻键误触纠错的方案 id 集合（xime.yaml 顶层 correction.schemas，
     * xime.custom.yaml 同路径追加，与内置取并集）。解析失败/未配置时为空集。
     */
    fun correctionSchemas(context: Context): Set<String> {
        val customFile = File(context.filesDir, "rime/$XIME_CUSTOM_CONFIG_FILE")
        val stamp = if (customFile.exists()) {
            customFile.lastModified() to customFile.length()
        } else {
            0L to 0L
        }
        if (_correctionSchemasStamp == stamp) return _correctionSchemas
        _correctionSchemasStamp = stamp
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseTopLevelSchemasYamlText(it, "correction") } ?: emptySet()
        val custom = readCustomText(context)
            ?.let { parseTopLevelSchemasYamlText(it, "correction") } ?: emptySet()
        _correctionSchemas = builtIn + custom
        return _correctionSchemas
    }

    /** 从 YAML 文本提取顶层 `<section>.schemas` 列表（如 correction.schemas）。 */
    internal fun parseTopLevelSchemasYamlText(yamlText: String, section: String): Set<String> {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return emptySet()
            val sectionNode = root.opt<YamlMap>(section) ?: return emptySet()
            val schemas = sectionNode.opt<YamlList>("schemas") ?: return emptySet()
            schemas.items.mapNotNull { (it as? YamlScalar)?.content?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse $section schemas", e)
            emptySet()
        }
    }

    private fun loadXimeConfig(context: Context) {
        fun privateStamp(name: String): Pair<Long, Long>? =
            File(context.filesDir, "rime/$name").let { f ->
                if (f.exists()) f.lastModified() to f.length() else null
            }
        // 变更戳取「实际生效的那份」：可见目录 xime.yaml 优先，其次可见目录里的旧名文件，
        // 再次私有目录两份（writeCustomConfig 在可见目录不可用时会退回私有目录）。全部缺失时
        // 用 (0,0) 哨兵，保证首次必解析。
        val customStamp = UserConfigDir.stamp(context, UserConfigDir.UNIFIED_CONFIG_FILE)
            ?: UserConfigDir.stamp(context, UserConfigDir.LEGACY_UNIFIED_CONFIG_FILE)
            ?: UserConfigDir.stamp(context, XIME_CUSTOM_CONFIG_FILE)
            ?: privateStamp(UserConfigDir.UNIFIED_CONFIG_FILE)
            ?: privateStamp(XIME_CUSTOM_CONFIG_FILE)
            ?: (0L to 0L)
        if (_loadedCustomStamp == customStamp) return
        _loadedCustomStamp = customStamp
        try {
            // 键盘手势（从原始 YAML 手动解析）
            val parsed = parseKeyboardFromAssets(context)
            _keyGestureConfigZhBase = parsed?.first ?: emptyMap()
            _keyGestureConfigEn.value = parsed?.second ?: emptyMap()
            // 键盘颜色（从原始 YAML 手动解析）
            keyboardColorsConfig = parseKeyboardColorsFromAssets(context)
            // 键盘阴影（从原始 YAML 手动解析）
            keyboardShadowConfig = parseKeyboardShadowFromAssets(context)
            // 键盘按键（从原始 YAML 手动解析）
            keyboardKeyConfig = parseKeyboardKeyFromAssets(context)
            // 候选栏行为（从原始 YAML 手动解析）
            keyboardCandidatesConfig = parseKeyboardCandidatesFromAssets(context)
            // 手势灵敏度（从原始 YAML 手动解析）
            keyboardGesturesConfig = parseKeyboardGesturesFromAssets(context)
            // 字体配置（从原始 YAML 手动解析）
            keyboardFontConfig = parseKeyboardFontsFromAssets(context)
            // 组合键（keyboard.combos）：输入触发码后候选栏直接给出求值结果
            keyboardCombos = parseKeyboardCombosFromAssets(context)
            com.kingzcheung.xime.ui.keyboard.AppFonts.loadCustomFonts(keyboardFontConfig)
            // 按键布局（从原始 YAML 手动解析，中英文分开）
            val parsedLayouts = parseButtonLayoutFromAssets(context)
            _buttonLayoutZh = parsedLayouts.first
            _buttonLayoutEn = parsedLayouts.second
            // 键盘行布局
            val parsedRows = parseKeyboardLayoutFromAssets(context)
            _zhRowsBase = parsedRows.first
            _enRows = parsedRows.second
            // 合并键绑定（keyboard.<section>.schemas，custom 覆盖 builtIn 同名方案的绑定）
            // 内置/自定义两份 YAML 文本只读一次，后面几处复用（面板段装载也用同一份）
            val builtInText = readAssetText(context, XIME_CONFIG_FILE)
            val customTextForPanel = readCustomText(context)
            val builtInBindings = builtInText?.let { parseSchemaBindingsYamlText(it) } ?: emptyMap()
            val customBindings = customTextForPanel
                ?.let { parseSchemaBindingsYamlText(it) } ?: emptyMap()
            _schemaSectionBindings = builtInBindings + customBindings
            // 段类型声明（keyboard.<section>.type，custom 覆盖 builtIn）：显式声明优先于段名推断
            _sectionTypes = (builtInText?.let { parseSectionTypesYamlText(it) } ?: emptyMap()) +
                (customTextForPanel?.let { parseSectionTypesYamlText(it) } ?: emptyMap())
            // 合并键布局 sections：由 schemas 绑定动态发现（内置 qwerty_14/17/18 + custom 新增）；
            // 代码布局 section（type 声明或段名为 t9/stroke/handwriting）无行数据，走按段装载（下方）
            // （button_layout 同理：仅全键盘段支持，九键/笔画段不解析）
            val mergedRowsMap = mutableMapOf<String, List<List<String>>>()
            val mergedGesturesMap = mutableMapOf<String, Map<String, KeyBinding>>()
            val buttonLayoutsBySection = mutableMapOf<String, ButtonLayout>()
            for (section in _schemaSectionBindings.values
                .filter { sectionKindOf(it, _sectionTypes) == SECTION_TYPE_FULL }.distinct()) {
                parseLayoutSection(context, section)?.let { mergedRowsMap[section] = it }
                mergedGesturesMap[section] = parseGesturesSection(context, section)
                parseButtonLayoutSection(context, section)?.let { buttonLayoutsBySection[section] = it }
            }
            _mergedRows = mergedRowsMap
            _mergedGestureConfigs = mergedGesturesMap
            _buttonLayoutBySection = buttonLayoutsBySection
            // 代码布局按段装载：绑定的代码布局段 + 内置保留名（未绑定时配置仍可用），
            // 各段独立解析；方案切换时由 setActiveKeyboardSchema 换入活动单例
            val t9LayoutsBySection = mutableMapOf<String, KeyboardT9Config>()
            val t9GestureConfigsBySection = mutableMapOf<String, Map<String, KeyBinding>>()
            val strokeLayoutsBySection = mutableMapOf<String, KeyboardStrokeConfig>()
            val strokeGestureConfigsBySection = mutableMapOf<String, Map<String, KeyBinding>>()
            for (section in (_schemaSectionBindings.values.filter {
                    sectionKindOf(it, _sectionTypes) in CODE_LAYOUT_SECTIONS } + CODE_LAYOUT_SECTIONS).distinct()) {
                when (sectionKindOf(section, _sectionTypes)) {
                    SECTION_TYPE_T9 -> {
                        t9LayoutsBySection[section] = parseKeyboardT9FromAssets(context, section)
                        t9GestureConfigsBySection[section] = parseGesturesSection(context, section)
                    }
                    SECTION_TYPE_STROKE -> {
                        strokeLayoutsBySection[section] = parseKeyboardStrokeFromAssets(context, section)
                        strokeGestureConfigsBySection[section] = parseGesturesSection(context, section)
                    }
                }
            }
            _t9LayoutsBySection = t9LayoutsBySection
            _t9GestureConfigsBySection = t9GestureConfigsBySection
            _strokeLayoutsBySection = strokeLayoutsBySection
            _strokeGestureConfigsBySection = strokeGestureConfigsBySection
            // 活动单例先落内置保留段（段缺省时保留字段默认值）
            keyboardT9Config = t9LayoutsBySection[SECTION_TYPE_T9] ?: keyboardT9Config
            _t9GestureConfigs = t9GestureConfigsBySection[SECTION_TYPE_T9] ?: emptyMap()
            keyboardStrokeConfig = strokeLayoutsBySection[SECTION_TYPE_STROKE] ?: keyboardStrokeConfig
            _strokeGestureConfigs = strokeGestureConfigsBySection[SECTION_TYPE_STROKE] ?: emptyMap()
            _activeT9Section = SECTION_TYPE_T9
            _activeStrokeSection = SECTION_TYPE_STROKE
            // 面板段（数字/常用符号）装载：布局 + 手势，支持 <section>_tablet 设备档变体
            // （default/custom 文本已在上面读过，直接复用，避免逐段重复读资产 + 重复解析）
            val panelIsTablet = isTabletDevice(context)
            val panelLayoutsBySection = mutableMapOf<String, PanelLayoutConfig>()
            val panelGesturesBySection = mutableMapOf<String, Map<String, KeyBinding>>()
            for (section in PANEL_SECTIONS) {
                val resolved = resolveQwertySection(section, builtInText ?: "", customTextForPanel, panelIsTablet)
                parsePanelLayoutSection(builtInText, customTextForPanel, section, resolved)
                    ?.let { panelLayoutsBySection[section] = it }
                panelGesturesBySection[section] =
                    parsePanelGesturesSection(builtInText, customTextForPanel, section, resolved)
            }
            _panelLayouts = panelLayoutsBySection
            _panelGestureConfigs = panelGesturesBySection
            // 基线缓存已刷新，先落标准 26 键的行布局/手势，合并键方案再由
            // setActiveKeyboardSchema 覆盖。不能只依赖 setActiveKeyboardSchema：
            // 非合并键方案 section 为 null，与刚重置的 _activeMergedSection(null) 相等
            // 会被提前 return，导致 xime.custom.yaml 的行布局/手势不生效（重新部署也无效）。
            _zhRows = _zhRowsBase
            _keyGestureConfig.value = _keyGestureConfigZhBase
            // 重新应用当前方案对应的合并键布局（上面重置了基线缓存）
            _activeMergedSection = null
            setActiveKeyboardSchema(_activeSchemaId)
            // 校验配置版本兼容性
            val merged = try { loadMergedConfig(context) } catch (_: YamlException) { null }
            val meta = merged?.metadata
            if (meta != null && meta.appVersion.isNotBlank()) {
                if (!checkVersionConstraint(BuildConfig.VERSION_NAME, meta.appVersion)) {
                    Log.w(TAG, "Config requires app_version ${meta.appVersion}, current is ${BuildConfig.VERSION_NAME}")
                }
            }
            _configVersion.value++
        } catch (e: Exception) {
            _loadedCustomStamp = null
            Log.w(TAG, "Failed to load xime config", e)
        }
    }

    private fun checkVersionConstraint(current: String, constraint: String): Boolean {
        val operator = when {
            constraint.startsWith(">=") -> constraint.substring(0, 2)
            constraint.startsWith("<=") -> constraint.substring(0, 2)
            constraint.startsWith(">") -> constraint.substring(0, 1)
            constraint.startsWith("<") -> constraint.substring(0, 1)
            constraint.startsWith("^") -> constraint.substring(0, 1)
            constraint.startsWith("~") -> constraint.substring(0, 1)
            else -> return true
        }
        val targetVerStr = constraint.removePrefix(operator).trim('"', '\'', ' ')
        val targetParts = targetVerStr.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
        if (targetParts.size < 2 || currentParts.size < 2) return true
        val t = Triple(targetParts.getOrElse(0) { 0 }, targetParts.getOrElse(1) { 0 }, targetParts.getOrElse(2) { 0 })
        val c = Triple(currentParts[0], currentParts.getOrElse(1) { 0 }, currentParts.getOrElse(2) { 0 })
        val cmp = compareVersions(c, t)
        return when (operator) {
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            ">"  -> cmp > 0
            "<"  -> cmp < 0
            "^"  -> c.first == t.first && (c.first != 0 || cmp >= 0)
            "~"  -> c.first == t.first && c.second >= t.second
            else -> true
        }
    }

    private fun compareVersions(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        return when {
            a.first != b.first -> a.first.compareTo(b.first)
            a.second != b.second -> a.second.compareTo(b.second)
            else -> a.third.compareTo(b.third)
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘手势配置。 */
    private fun parseKeyboardFromAssets(context: Context): Pair<Map<String, KeyBinding>, Map<String, KeyBinding>>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return null
        // 支持两种来源：files/rime/（浏览器导入）或 assets/（内置），自动 fallback
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val customText = userData ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        return parseKeyboardGestureTexts(defaultText, customText, isTabletDevice(context))
    }

    /**
     * 纯文本合并解析键盘手势配置（default = xime.yaml，custom = xime.custom.yaml）。
     *
     * 预设合并为 default + custom（同名 custom 覆盖），合并结果供 custom keys 的
     * `{ use: }` 引用；keys 按键级合并（custom 同名键整体覆盖 default 键）。
     *
     * [isTablet] 时按设备档变体解析：yaml 存在 `<段>_tablet` 则整段改用平板变体
     * （见 [resolveQwertySection]）。
     */
    internal fun parseKeyboardGestureTexts(
        defaultText: String,
        customText: String?,
        isTablet: Boolean = false,
    ): Pair<Map<String, KeyBinding>, Map<String, KeyBinding>>? {
        val defaultPresets = parseKeyboardActionsYamlText(defaultText)
        val presets = defaultPresets + (customText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap())
        val zhSection = resolveQwertySection("qwerty", defaultText, customText, isTablet)
        val enSection = resolveQwertySection("qwerty_en", defaultText, customText, isTablet)
        val defaultZh = parseKeyboardYamlSection(defaultText, zhSection, defaultPresets)
            // 变体段只出现在自定义层时，default 层回退基础段
            ?: (if (zhSection != "qwerty") parseKeyboardYamlSection(defaultText, "qwerty", defaultPresets) else null)
            ?: return null
        val defaultEn = parseKeyboardYamlSection(defaultText, enSection, defaultPresets)
            ?: (if (enSection != "qwerty_en") parseKeyboardYamlSection(defaultText, "qwerty_en", defaultPresets) else emptyMap())
            ?: emptyMap()
        val customZh: Map<String, KeyBinding>? = customText?.let { parseKeyboardYamlSection(it, zhSection, presets) }
        val customEn: Map<String, KeyBinding>? = customText?.let { parseKeyboardYamlSection(it, enSection, presets) }
        val zh = if (customZh != null) defaultZh + customZh else defaultZh
        val en = if (customEn != null) defaultEn + customEn else defaultEn
        return Pair(zh, en)
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘颜色配置（字段级一路 fallback）。 */
    private fun parseKeyboardColorsFromAssets(context: Context): KeyboardColorsConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardColorsYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardColorsYamlText(it) }
        return mergeColorsConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并颜色配置：custom → builtIn → 代码默认值。 */
    internal fun mergeColorsConfigs(custom: KeyboardColorsPartial?, builtIn: KeyboardColorsPartial?): KeyboardColorsConfig =
        KeyboardColorsConfig(
            keyBgColor = tiered(custom?.keyBgColor, builtIn?.keyBgColor, 0xFFFFFF),
            keyBgColorDark = tiered(custom?.keyBgColorDark, builtIn?.keyBgColorDark, 0x4A4A4A),
            specialKeyBgColor = tiered(custom?.specialKeyBgColor, builtIn?.specialKeyBgColor, null),
            specialKeyBgColorDark = tiered(custom?.specialKeyBgColorDark, builtIn?.specialKeyBgColorDark, null),
            keyTextColor = tiered(custom?.keyTextColor, builtIn?.keyTextColor, 0x202124),
            keyTextColorDark = tiered(custom?.keyTextColorDark, builtIn?.keyTextColorDark, 0xE8EAED),
            candidateTextColor = tiered(custom?.candidateTextColor, builtIn?.candidateTextColor, 0x1A73E8),
            candidateTextColorDark = tiered(custom?.candidateTextColorDark, builtIn?.candidateTextColorDark, 0x8AB4F8),
        )

    /** 从 YAML 文本中提取 keyboard.colors 段（仅显式字段非 null）。 */
    internal fun parseKeyboardColorsYamlText(yamlText: String): KeyboardColorsPartial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val colorsNode = keyboardNode.opt<YamlMap>("colors") ?: return null
            var kBg: Long? = null
            var kBgDark: Long? = null
            var spKeyBg: Long? = null
            var spKeyBgDark: Long? = null
            var kTxt: Long? = null
            var kTxtDark: Long? = null
            var candTxt: Long? = null
            var candTxtDark: Long? = null
            for ((kNode, vNode) in colorsNode.entries) {
                val key = kNode.content
                val value = (vNode as? YamlScalar)?.content ?: continue
                val hex = value.removePrefix("0x").toLongOrNull(16) ?: continue
                when (key) {
                    "key_bg_color" -> kBg = hex
                    "key_bg_color_dark" -> kBgDark = hex
                    "special_key_bg_color" -> spKeyBg = hex
                    "special_key_bg_color_dark" -> spKeyBgDark = hex
                    "key_text_color" -> kTxt = hex
                    "key_text_color_dark" -> kTxtDark = hex
                    "candidate_text_color" -> candTxt = hex
                    "candidate_text_color_dark" -> candTxtDark = hex
                }
            }
            KeyboardColorsPartial(
                keyBgColor = kBg,
                keyBgColorDark = kBgDark,
                specialKeyBgColor = spKeyBg,
                specialKeyBgColorDark = spKeyBgDark,
                keyTextColor = kTxt,
                keyTextColorDark = kTxtDark,
                candidateTextColor = candTxt,
                candidateTextColorDark = candTxtDark,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard colors config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘阴影配置（字段级一路 fallback）。 */
    private fun parseKeyboardShadowFromAssets(context: Context): KeyboardShadowConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardShadowYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardShadowYamlText(it) }
        return mergeShadowConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并阴影配置：custom → builtIn → 代码默认值。 */
    internal fun mergeShadowConfigs(custom: KeyboardShadowPartial?, builtIn: KeyboardShadowPartial?): KeyboardShadowConfig =
        KeyboardShadowConfig(
            enabled = tiered(custom?.enabled, builtIn?.enabled, true),
            elevation = tiered(custom?.elevation, builtIn?.elevation, 1),
            shapeRadius = tiered(custom?.shapeRadius, builtIn?.shapeRadius, 8),
        )

    /** 从 YAML 文本中提取 keyboard.shadow 段（仅显式字段非 null）。 */
    internal fun parseKeyboardShadowYamlText(yamlText: String): KeyboardShadowPartial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val shadowNode = keyboardNode.opt<YamlMap>("shadow") ?: return null
            var enabled: Boolean? = null
            var elevation: Int? = null
            var shapeRadius: Int? = null
            for ((kNode, vNode) in shadowNode.entries) {
                val key = kNode.content
                val value = (vNode as? YamlScalar)?.content ?: continue
                when (key) {
                    "enabled" -> enabled = value.toBooleanStrictOrNull() ?: continue
                    "elevation" -> elevation = value.toIntOrNull() ?: continue
                    "shape_radius" -> shapeRadius = value.toIntOrNull() ?: continue
                }
            }
            KeyboardShadowPartial(enabled = enabled, elevation = elevation, shapeRadius = shapeRadius)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard shadow config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘按键配置（字段级一路 fallback）。 */
    private fun parseKeyboardKeyFromAssets(context: Context): KeyboardKeyConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardKeyYamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardKeyYamlPartial(it) }
        val merged = mergeKeyConfigs(custom, builtIn)
        // 平板设备档：keyboard.key.<名>_tablet 覆盖同名基础覆盖（圆角 + 间距，与键盘段 <section>_tablet 同一约定）
        return if (isTabletDevice(context)) applyTabletKeyOverrides(merged) else merged
    }

    /**
     * 平板设备档覆盖：把 `keyboard.key.<名>_tablet` 的圆角/间距并到 `<名>` 上
     * （例 `number_tablet: { spacing_x: 6, spacing_y: 6, corner_radius: 8 }` 在平板上即 number 面板的值），
     * 手机不受影响。与键盘段 `<section>_tablet` 的整段替换保持同一套命名约定。
     */
    internal fun applyTabletKeyOverrides(config: KeyboardKeyConfig): KeyboardKeyConfig {
        val tabletKeys = config.spacingOverrides.keys.filter { it.endsWith(TABLET_SECTION_SUFFIX) }
        if (tabletKeys.isEmpty()) return config
        val merged = LinkedHashMap(config.spacingOverrides)
        for (name in tabletKeys) {
            merged[name.removeSuffix(TABLET_SECTION_SUFFIX)] = config.spacingOverrides.getValue(name)
        }
        return config.copy(spacingOverrides = merged)
    }

    /** 字段级一路 fallback 合并按键配置：custom → builtIn → 代码默认值。 */
    internal fun mergeKeyConfigs(custom: KeyboardKeyPartial?, builtIn: KeyboardKeyPartial?): KeyboardKeyConfig =
        KeyboardKeyConfig(
            cornerRadius = tiered(custom?.cornerRadius, builtIn?.cornerRadius, 8),
            spacingX = tiered(custom?.spacingX, builtIn?.spacingX, null),
            spacingY = tiered(custom?.spacingY, builtIn?.spacingY, null),
            spacingOverrides = mergeSpacingOverrides(custom?.spacingOverrides, builtIn?.spacingOverrides),
        )

    /** 按键盘名合并圆角/间距覆盖：同名项字段级 fallback（custom 非空字段覆盖 builtIn）。 */
    internal fun mergeSpacingOverrides(
        custom: Map<String, KeyboardSpacingConfig>?,
        builtIn: Map<String, KeyboardSpacingConfig>?,
    ): Map<String, KeyboardSpacingConfig> {
        if (custom == null) return builtIn ?: emptyMap()
        if (builtIn == null) return custom
        val result = LinkedHashMap<String, KeyboardSpacingConfig>(custom)
        for ((key, builtInValue) in builtIn) {
            val customValue = result[key]
            result[key] = if (customValue == null) {
                builtInValue
            } else {
                KeyboardSpacingConfig(
                    spacingX = customValue.spacingX ?: builtInValue.spacingX,
                    spacingY = customValue.spacingY ?: builtInValue.spacingY,
                    cornerRadius = customValue.cornerRadius ?: builtInValue.cornerRadius,
                )
            }
        }
        return result
    }

    private fun parseKeyboardCandidatesFromAssets(context: Context): KeyboardCandidatesConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardCandidatesYamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardCandidatesYamlPartial(it) }
        return mergeCandidatesConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并候选栏配置：custom → builtIn → 代码默认值。 */
    internal fun mergeCandidatesConfigs(
        custom: KeyboardCandidatesPartial?,
        builtIn: KeyboardCandidatesPartial?,
    ): KeyboardCandidatesConfig = KeyboardCandidatesConfig(
        showClearButton = tiered(custom?.showClearButton, builtIn?.showClearButton, true),
        // 键级合并：custom 只需写要改的键，其余沿用内置默认（逗号/句号）
        shortcuts = mergeShortcuts(custom?.shortcuts, builtIn?.shortcuts),
    )

    /** 标点键直选映射的键级合并：custom 非空时以它为基，缺失的键回退 builtIn，再回退默认。 */
    internal fun mergeShortcuts(
        custom: Map<String, Int>?,
        builtIn: Map<String, Int>?,
    ): Map<String, Int> {
        val base = builtIn ?: KeyboardCandidatesConfig.DEFAULT_SHORTCUTS
        if (custom == null) return base
        val result = LinkedHashMap(base)
        result.putAll(custom)
        return result
    }

    /**
     * 兼容测试入口：从 YAML 文本中提取 keyboard.candidates 段并填充代码默认值。
     * > 合并（custom → builtIn → 默认）请使用 [parseKeyboardCandidatesYamlPartial]。
     */
    internal fun parseKeyboardCandidatesYamlText(yamlText: String): KeyboardCandidatesConfig? {
        val partial = parseKeyboardCandidatesYamlPartial(yamlText) ?: return null
        return KeyboardCandidatesConfig(
            showClearButton = partial.showClearButton ?: true,
            shortcuts = partial.shortcuts ?: KeyboardCandidatesConfig.DEFAULT_SHORTCUTS,
        )
    }

    /** 从 YAML 文本中提取 keyboard.candidates 段（仅显式字段非 null）。 */
    private fun parseKeyboardCandidatesYamlPartial(yamlText: String): KeyboardCandidatesPartial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val candidatesNode = keyboardNode.opt<YamlMap>("candidates") ?: return null
            var showClearButton: Boolean? = null
            var shortcuts: Map<String, Int>? = null
            for ((kNode, vNode) in candidatesNode.entries) {
                when (kNode.content) {
                    "show_clear_button" -> {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        showClearButton = value.toBooleanStrictOrNull()
                    }
                    "shortcuts" -> {
                        val shortcutsNode = vNode as? YamlMap ?: continue
                        val parsed = mutableMapOf<String, Int>()
                        for ((skNode, svNode) in shortcutsNode.entries) {
                            val value = (svNode as? YamlScalar)?.content ?: continue
                            value.toIntOrNull()?.let { parsed[skNode.content] = it }
                        }
                        shortcuts = parsed
                    }
                }
            }
            if (showClearButton == null && shortcuts == null) return null
            KeyboardCandidatesPartial(showClearButton = showClearButton, shortcuts = shortcuts)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard.candidates", e)
            null
        }
    }

    /**
     * 组合键（keyboard.combos）解析：内置默认与用户配置**按键级合并**。
     *
     * 合并语义与其它键盘配置一致：用户只需写要改/新增的触发码；同名触发码以用户为准，
     * 其余沿用内置。写成空列表即可禁用某个内置组合键。
     */
    private fun parseKeyboardCombosFromAssets(context: Context): List<KeyboardCombo> {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardCombosYamlText(it) }
            ?: emptyList()
        val custom = readCustomText(context)?.let { parseKeyboardCombosYamlText(it) }
        return mergeCombos(custom, builtIn)
    }

    /** 组合键按键级合并：用户同名覆盖内置；空列表 = 显式禁用该触发码。 */
    internal fun mergeCombos(custom: List<KeyboardCombo>?, builtIn: List<KeyboardCombo>): List<KeyboardCombo> {
        if (custom.isNullOrEmpty()) return builtIn
        val byCode = LinkedHashMap<String, KeyboardCombo>()
        builtIn.forEach { byCode[it.code] = it }
        custom.forEach { combo ->
            if (combo.items.isEmpty()) byCode.remove(combo.code) else byCode[combo.code] = combo
        }
        return byCode.values.toList()
    }

    /**
     * 从 YAML 文本解析 keyboard.combos。支持两种写法：`code: [项1, 项2]` 简写与
     * `code: { items: [...] }`；命令项写成 `{ command: reload, label: 重新部署 }`。
     */
    internal fun parseKeyboardCombosYamlText(yamlText: String): List<KeyboardCombo>? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val combosNode = keyboardNode.opt<YamlMap>("combos") ?: return null
            val result = mutableListOf<KeyboardCombo>()
            for ((kNode, vNode) in combosNode.entries) {
                val code = kNode.content.trim()
                if (code.isEmpty()) continue
                val items = when (vNode) {
                    is YamlList -> vNode.items.mapNotNull { parseComboItemNode(it) }
                    is YamlMap -> {
                        val listNode = vNode.opt<YamlList>("items")
                        listNode?.items?.mapNotNull { parseComboItemNode(it) }
                            ?: listOfNotNull(parseComboItemNode(vNode))
                    }
                    else -> listOfNotNull((vNode as? YamlScalar)?.content?.let { ComboItem(template = it) })
                }
                result += KeyboardCombo(code = code, items = items)
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard.combos", e)
            null
        }
    }

    /** 单个候选项节点：标量 = 模板；映射 = 模板或命令。 */
    private fun parseComboItemNode(node: YamlNode): ComboItem? {
        (node as? YamlScalar)?.let { scalar ->
            val text = scalar.content
            return text.takeIf { it.isNotEmpty() }?.let { ComboItem(template = it) }
        }
        val map = node as? YamlMap ?: return null
        val command = map.opt<YamlScalar>("command")?.content?.takeIf { it.isNotEmpty() }
        val template = map.opt<YamlScalar>("text")?.content?.takeIf { it.isNotEmpty() }
        val label = map.opt<YamlScalar>("label")?.content?.takeIf { it.isNotEmpty() }
        if (command == null && template == null) return null
        return ComboItem(template = template, command = command, label = label)
    }

    private fun parseKeyboardGesturesFromAssets(context: Context): KeyboardGesturesConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardGesturesYamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardGesturesYamlPartial(it) }
        return mergeGesturesConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并手势配置：custom → builtIn → 代码默认值。 */
    internal fun mergeGesturesConfigs(
        custom: KeyboardGesturesPartial?,
        builtIn: KeyboardGesturesPartial?,
    ): KeyboardGesturesConfig = KeyboardGesturesConfig(
        longPressMs = normalizeLongPressMs(
            tiered(custom?.longPressMs, builtIn?.longPressMs, KeyboardGesturesConfig.DEFAULT_LONG_PRESS_MS)
        ),
        zLongPressReverse = tiered(
            custom?.zLongPressReverse, builtIn?.zLongPressReverse, true,
        ),
    )
    /** 夹取长按判定时间到合理区间：配置写 0 或负数会让点按被误判成长按，必须兜住。 */
    internal fun normalizeLongPressMs(value: Int): Int =
        value.coerceIn(
            KeyboardGesturesConfig.MIN_LONG_PRESS_MS,
            KeyboardGesturesConfig.MAX_LONG_PRESS_MS,
        )
    /**
     * 兼容测试入口：从 YAML 文本中提取 keyboard.gestures 段并填充代码默认值。
     * > 合并（custom → builtIn → 默认）请使用 [parseKeyboardGesturesYamlPartial]。
     */
    internal fun parseKeyboardGesturesYamlText(yamlText: String): KeyboardGesturesConfig? {
        val partial = parseKeyboardGesturesYamlPartial(yamlText) ?: return null
        return KeyboardGesturesConfig(
            longPressMs = normalizeLongPressMs(
                partial.longPressMs ?: KeyboardGesturesConfig.DEFAULT_LONG_PRESS_MS
            ),
            zLongPressReverse = partial.zLongPressReverse ?: true,
        )
    }
    /** 从 YAML 文本中提取 keyboard.gestures 段（仅显式字段非 null）。 */
    private fun parseKeyboardGesturesYamlPartial(yamlText: String): KeyboardGesturesPartial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val gesturesNode = keyboardNode.opt<YamlMap>("gestures") ?: return null
            var longPressMs: Int? = null
            var zLongPressReverse: Boolean? = null
            for ((kNode, vNode) in gesturesNode.entries) {
                when (kNode.content) {
                    "long_press_ms" -> {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        longPressMs = value.toIntOrNull()
                    }
                    "z_long_press_reverse" -> {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        zLongPressReverse = when (value.trim().lowercase()) {
                            "true", "1", "yes" -> true
                            "false", "0", "no" -> false
                            else -> null
                        }
                    }
                }
            }
            // 只配置了其中一个字段也要生效（long_press_ms 未配置时用代码默认值）
            if (longPressMs == null && zLongPressReverse == null) return null
            KeyboardGesturesPartial(
                longPressMs = longPressMs,
                zLongPressReverse = zLongPressReverse,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard.gestures", e)
            null
        }
    }

    /**
     * 兼容测试入口：从 YAML 文本中提取 keyboard.key 段并填充代码默认值。
     * > 合并（custom → builtIn → 默认）请使用 [parseKeyboardKeyYamlPartial]。
     */
    internal fun parseKeyboardKeyYamlText(yamlText: String): KeyboardKeyConfig? {
        val partial = parseKeyboardKeyYamlPartial(yamlText) ?: return null
        return KeyboardKeyConfig(
            cornerRadius = partial.cornerRadius ?: 8,
            spacingX = partial.spacingX,
            spacingY = partial.spacingY,
            spacingOverrides = partial.spacingOverrides ?: emptyMap(),
        )
    }

    /** 从 YAML 文本中提取 keyboard.key 段（仅显式字段非 null）。 */
    private fun parseKeyboardKeyYamlPartial(yamlText: String): KeyboardKeyPartial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            var cornerRadius: Int? = null
            var spacingX: Float? = null
            var spacingY: Float? = null
            val spacingOverrides = mutableMapOf<String, KeyboardSpacingConfig>()
            // 优先读取 key.corner_radius
            val keyNode = keyboardNode.opt<YamlMap>("key")
            if (keyNode != null) {
                for ((kNode, vNode) in keyNode.entries) {
                    val key = kNode.content
                    if (key == "corner_radius") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        cornerRadius = value.toIntOrNull()
                    } else if (key == "spacing_x") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        spacingX = value.toFloatOrNull()
                    } else if (key == "spacing_y") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        spacingY = value.toFloatOrNull()
                    } else if (vNode is YamlMap) {
                        // 键盘级覆盖：keyboard.key.<键盘名>.corner_radius/spacing_x/spacing_y
                        var overrideX: Float? = null
                        var overrideY: Float? = null
                        var overrideRadius: Int? = null
                        for ((skNode, svNode) in vNode.entries) {
                            val skey = skNode.content
                            val svalue = (svNode as? YamlScalar)?.content ?: continue
                            if (skey == "spacing_x") {
                                overrideX = svalue.toFloatOrNull()
                            } else if (skey == "spacing_y") {
                                overrideY = svalue.toFloatOrNull()
                            } else if (skey == "corner_radius") {
                                overrideRadius = svalue.toIntOrNull()
                            }
                        }
                        spacingOverrides[key] = KeyboardSpacingConfig(overrideX, overrideY, overrideRadius)
                    }
                }
            }
            // 未设置 key.corner_radius 时，回退读取 shadow.shape_radius
            if (cornerRadius == null) {
                val shadowNode = keyboardNode.opt<YamlMap>("shadow")
                if (shadowNode != null) {
                    for ((kNode, vNode) in shadowNode.entries) {
                        val key = kNode.content
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        if (key == "shape_radius") {
                            cornerRadius = value.toIntOrNull()
                        }
                    }
                }
            }
            KeyboardKeyPartial(
                cornerRadius = cornerRadius,
                spacingX = spacingX,
                spacingY = spacingY,
                spacingOverrides = spacingOverrides.ifEmpty { null },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard key config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析九键键盘配置。 */
    private fun parseKeyboardT9FromAssets(context: Context, section: String): KeyboardT9Config {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardT9YamlPartial(it, section) }
        val custom = readCustomText(context)?.let { parseKeyboardT9YamlPartial(it, section) }
        return mergeT9Configs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并九键配置：custom → builtIn → 代码默认值。 */
    internal fun mergeT9Configs(custom: KeyboardT9Partial?, builtIn: KeyboardT9Partial?): KeyboardT9Config =
        KeyboardT9Config(
            sideSymbols = tiered(custom?.sideSymbols?.takeIf { it.isNotEmpty() },
                builtIn?.sideSymbols?.takeIf { it.isNotEmpty() }, DEFAULT_T9_SIDE_SYMBOLS),
            layout = mergeT9Layouts(custom?.layout, builtIn?.layout),
        )

    /** 字段级一路 fallback 合并九键布局：custom → builtIn → 代码默认值（列表整段覆盖）。 */
    internal fun mergeT9Layouts(custom: KeyboardT9LayoutPartial?, builtIn: KeyboardT9LayoutPartial?): KeyboardT9LayoutConfig =
        KeyboardT9LayoutConfig(
            left = tiered(custom?.left?.takeIf { it.isNotEmpty() },
                builtIn?.left?.takeIf { it.isNotEmpty() }, KeyboardT9LayoutConfig.DEFAULT_T9_LEFT),
            rows = tiered(custom?.rows?.takeIf { it.isNotEmpty() },
                builtIn?.rows?.takeIf { it.isNotEmpty() }, KeyboardT9LayoutConfig.DEFAULT_T9_ROWS),
            right = tiered(custom?.right?.takeIf { it.isNotEmpty() },
                builtIn?.right?.takeIf { it.isNotEmpty() }, KeyboardT9LayoutConfig.DEFAULT_T9_RIGHT),
        )

    /** 从 YAML 文本中提取 keyboard.<section> 九键段（仅显式字段非 null；缺省内置保留名 t9）。 */
    internal fun parseKeyboardT9YamlPartial(yamlText: String, section: String = SECTION_TYPE_T9): KeyboardT9Partial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val t9Node = keyboardNode.opt<YamlMap>(section) ?: return null
            val sideSymbols = t9Node.opt<YamlList>("side_symbols")
                ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            val layout = t9Node.opt<YamlMap>("layout")?.let { parseT9LayoutNode(it) }
            KeyboardT9Partial(
                sideSymbols = sideSymbols?.ifEmpty { null },
                layout = layout,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard t9 config", e)
            null
        }
    }

    /** 解析 keyboard.t9.layout 节点（left/rows/right；rows 支持合并键嵌套与标量行兜底）。 */
    private fun parseT9LayoutNode(layoutNode: YamlMap): KeyboardT9LayoutPartial? {
        val left = layoutNode.opt<YamlList>("left")
            ?.items?.mapNotNull { (it as? YamlScalar)?.content }?.filter { it.isNotBlank() }
        val right = layoutNode.opt<YamlList>("right")
            ?.items?.mapNotNull { (it as? YamlScalar)?.content }?.filter { it.isNotBlank() }
        val rows = layoutNode.opt<YamlList>("rows")?.let { parseLayoutRowsNode(it) }
        if (left.isNullOrEmpty() && right.isNullOrEmpty() && rows.isNullOrEmpty()) return null
        return KeyboardT9LayoutPartial(
            left = left?.takeIf { it.isNotEmpty() },
            rows = rows?.takeIf { it.isNotEmpty() },
            right = right?.takeIf { it.isNotEmpty() },
        )
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析笔画键盘配置。 */
    private fun parseKeyboardStrokeFromAssets(context: Context, section: String): KeyboardStrokeConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardStrokeYamlPartial(it, section) }
        val custom = readCustomText(context)?.let { parseKeyboardStrokeYamlPartial(it, section) }
        return mergeStrokeConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并笔画配置：custom → builtIn → 代码默认值。 */
    internal fun mergeStrokeConfigs(custom: KeyboardStrokePartial?, builtIn: KeyboardStrokePartial?): KeyboardStrokeConfig =
        KeyboardStrokeConfig(
            sideSymbols = tiered(custom?.sideSymbols?.takeIf { it.isNotEmpty() },
                builtIn?.sideSymbols?.takeIf { it.isNotEmpty() }, DEFAULT_STROKE_SIDE_SYMBOLS),
        )

    /** 从 YAML 文本中提取 keyboard.<section> 笔画段（仅显式字段非 null；缺省内置保留名 stroke）。 */
    internal fun parseKeyboardStrokeYamlPartial(yamlText: String, section: String = SECTION_TYPE_STROKE): KeyboardStrokePartial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val strokeNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val sideSymbols = strokeNode.opt<YamlList>("side_symbols")
                ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            KeyboardStrokePartial(sideSymbols = sideSymbols?.ifEmpty { null })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard stroke config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析字体配置（字段级一路 fallback）。 */
    private fun parseKeyboardFontsFromAssets(context: Context): KeyboardFontConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardFontsYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardFontsYamlText(it) }
        return mergeFontConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并字体配置：custom → builtIn → 代码默认值（空字符串视为未配置）。 */
    internal fun mergeFontConfigs(custom: KeyboardFontPartial?, builtIn: KeyboardFontPartial?): KeyboardFontConfig =
        KeyboardFontConfig(
            keyFont = tieredText(custom?.keyFont, builtIn?.keyFont, ""),
            keyLabelFont = tieredText(custom?.keyLabelFont, builtIn?.keyLabelFont, ""),
            candidateFont = tieredText(custom?.candidateFont, builtIn?.candidateFont, ""),
            commentFont = tieredText(custom?.commentFont, builtIn?.commentFont, ""),
        )

    /** 从 YAML 文本中提取 keyboard.fonts 字体配置段（仅显式字段非 null）。 */
    internal fun parseKeyboardFontsYamlText(yamlText: String): KeyboardFontPartial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val fontsNode = keyboardNode.opt<YamlMap>("fonts") ?: return null
            var keyFont: String? = null
            var keyLabelFont: String? = null
            var candidateFont: String? = null
            var commentFont: String? = null
            for ((kNode, vNode) in fontsNode.entries) {
                val key = kNode.content
                val value = (vNode as? YamlScalar)?.content ?: continue
                when (key) {
                    "key_font" -> keyFont = value
                    "key_label_font" -> keyLabelFont = value
                    "candidate_font" -> candidateFont = value
                    "comment_font" -> commentFont = value
                }
            }
            KeyboardFontPartial(
                keyFont = keyFont,
                keyLabelFont = keyLabelFont,
                candidateFont = candidateFont,
                commentFont = commentFont,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard fonts config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析按键布局模式（中英文分开）。 */
    private fun parseButtonLayoutFromAssets(context: Context): Pair<ButtonLayout, ButtonLayout> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return Pair(ButtonLayout.STANDARD, ButtonLayout.STANDARD)
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        return parseButtonLayoutTexts(defaultText, customText, isTabletDevice(context))
    }

    /** 纯文本合并解析按键布局：custom 段级覆盖 default，中英文独立，缺省回退 STANDARD。 */
    internal fun parseButtonLayoutTexts(
        defaultText: String,
        customText: String?,
        isTablet: Boolean = false,
    ): Pair<ButtonLayout, ButtonLayout> {
        val zhSection = resolveQwertySection("qwerty", defaultText, customText, isTablet)
        val enSection = resolveQwertySection("qwerty_en", defaultText, customText, isTablet)
        val defaultZh = parseButtonLayoutYamlText(defaultText, zhSection)
        val defaultEn = parseButtonLayoutYamlText(defaultText, enSection)
        val customZh = customText?.let { parseButtonLayoutYamlText(it, zhSection) }
        val customEn = customText?.let { parseButtonLayoutYamlText(it, enSection) }
        val result = Pair(
            customZh ?: defaultZh ?: ButtonLayout.STANDARD,
            customEn ?: defaultEn ?: ButtonLayout.STANDARD,
        )
        return result
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘行布局。 */
    private fun parseKeyboardLayoutFromAssets(context: Context): Pair<List<List<String>>, List<List<String>>> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return Pair(DEFAULT_ZH_ROWS, DEFAULT_EN_ROWS)

        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)

        val isTablet = isTabletDevice(context)
        val zhSection = resolveQwertySection("qwerty", defaultText, customText, isTablet)
        val enSection = resolveQwertySection("qwerty_en", defaultText, customText, isTablet)

        val defaultZh = parseKeyboardLayoutYamlText(defaultText, zhSection)
        val defaultEn = parseKeyboardLayoutYamlText(defaultText, enSection)

        val customZh = customText?.let { parseKeyboardLayoutYamlText(it, zhSection) }
        val customEn = customText?.let { parseKeyboardLayoutYamlText(it, enSection) }

        return Pair(
            customZh ?: defaultZh ?: DEFAULT_ZH_ROWS,
            customEn ?: defaultEn ?: DEFAULT_EN_ROWS,
        )
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析指定 section 的键盘行布局（custom 整段覆盖 built-in）。 */
    private fun parseLayoutSection(context: Context, section: String): List<List<String>>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val default = defaultText?.let { parseKeyboardLayoutYamlText(it, section) }
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val custom = customText?.let { parseKeyboardLayoutYamlText(it, section) }
        return custom ?: default
    }

    /** 解析指定合并键段（custom 覆盖 built-in）的按键布局模式；未配置返回 null（回退 qwerty 全局值）。 */
    private fun parseButtonLayoutSection(context: Context, section: String): ButtonLayout? {
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        customText?.let { parseButtonLayoutYamlText(it, section) }?.let { return it }
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        return defaultText?.let { parseButtonLayoutYamlText(it, section) }
    }
    private fun parseGesturesSection(context: Context, section: String): Map<String, KeyBinding> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val defaultPresets = defaultText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap()
        val default = defaultText?.let { parseKeyboardYamlSection(it, section, defaultPresets) } ?: emptyMap()
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val customText = userData ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val customPresets = customText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap()
        val presets = defaultPresets + customPresets
        val custom = customText?.let { parseKeyboardYamlSection(it, section, presets) }
        return if (custom != null) default + custom else default
    }

    /**
     * 从 YAML 文本提取合并键绑定：keyboard.<section>.schemas 列出的每个方案 id 映射到该 section。
     * 无 schemas 声明的 section 忽略；同一方案 id 在多个 section 声明时以后出现的为准。
     */
    internal fun parseSchemaBindingsYamlText(yamlText: String): Map<String, String> {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return emptyMap()
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return emptyMap()
            val bindings = mutableMapOf<String, String>()
            for ((kNode, vNode) in keyboardNode.entries) {
                val section = kNode.content
                val schemas = (vNode as? YamlMap)?.opt<YamlList>("schemas") ?: continue
                for (item in schemas.items) {
                    val schemaId = (item as? YamlScalar)?.content?.trim().orEmpty()
                    if (schemaId.isNotEmpty()) bindings[schemaId] = section
                }
            }
            bindings
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse schema bindings", e)
            emptyMap()
        }
    }

    /**
     * 从 YAML 文本提取段类型声明：keyboard.<section>.type（full/t9/stroke/handwriting）。
     * 未知取值忽略并告警（回退按段名推断）；无 type 声明的 section 不产生条目。
     */
    internal fun parseSectionTypesYamlText(yamlText: String): Map<String, String> {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return emptyMap()
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return emptyMap()
            val types = mutableMapOf<String, String>()
            for ((kNode, vNode) in keyboardNode.entries) {
                val section = (kNode as? YamlScalar)?.content ?: continue
                val declared = ((vNode as? YamlMap)?.opt<YamlScalar>("type")?.content ?: continue).trim()
                if (declared in SECTION_TYPES_KNOWN) {
                    types[section] = declared
                } else {
                    Log.w(TAG, "keyboard.$section.type 取值未知: \"$declared\"，已回退按段名推断")
                }
            }
            types
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse section types", e)
            emptyMap()
        }
    }

    /**
     * 从 YAML 文本中提取 keyboard.<section>.layout 面板布局：
     * layout 有 left/right 节点 → columns 模式（数字键盘三列，列项支持键柱子数组）；
     * 否则 rows → rows 模式（常用符号，行列表）。
     */
    internal fun parsePanelLayoutYamlText(yamlText: String, section: String): PanelLayoutConfig? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val layoutNode = sectionNode.opt<YamlMap>("layout") ?: return null
            val rowsNode = layoutNode.opt<YamlList>("rows")
            val leftNode = layoutNode.opt<YamlList>("left")
            val rightNode = layoutNode.opt<YamlList>("right")
            if (leftNode != null || rightNode != null) {
                PanelLayoutConfig(
                    columns = true,
                    rows = rowsNode?.let { parseLayoutRowsNode(it) } ?: emptyList(),
                    left = leftNode?.let { parsePanelColumnItems(it) } ?: emptyList(),
                    right = rightNode?.let { parsePanelColumnItems(it) } ?: emptyList(),
                )
            } else {
                PanelLayoutConfig(columns = false, rows = rowsNode?.let { parseLayoutRowsNode(it) } ?: emptyList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse panel layout config", e)
            null
        }
    }

    /** 列节点 → 列项列表：子数组 = 纵向键柱（如加减乘除四键一柱），标量 = 单键。 */
    private fun parsePanelColumnItems(node: YamlList): List<List<String>> =
        node.items.mapNotNull { item ->
            when (item) {
                is YamlList -> item.items.mapNotNull { (it as? YamlScalar)?.content?.takeIf { s -> s.isNotBlank() } }
                    .takeIf { it.isNotEmpty() }
                is YamlScalar -> item.content.takeIf { it.isNotBlank() }?.let { listOf(it) }
                else -> null
            }
        }

    /** 合并解析面板段布局（custom 整段覆盖 built-in；平板变体段缺失时回退基础段）。 */
    private fun parsePanelLayoutSection(
        defaultText: String?,
        customText: String?,
        baseSection: String,
        resolvedSection: String,
    ): PanelLayoutConfig? {
        customText?.let { parsePanelLayoutYamlText(it, resolvedSection) }?.let { return it }
        defaultText?.let { parsePanelLayoutYamlText(it, resolvedSection) }?.let { return it }
        return if (resolvedSection != baseSection) {
            defaultText?.let { parsePanelLayoutYamlText(it, baseSection) }
        } else null
    }

    /** 合并解析面板段手势（default + custom 键级覆盖；平板变体段缺失时回退基础段）。 */
    private fun parsePanelGesturesSection(
        defaultText: String?,
        customText: String?,
        baseSection: String,
        resolvedSection: String,
    ): Map<String, KeyBinding> {
        val defaultPresets = defaultText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap()
        val customPresets = customText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap()
        val presets = defaultPresets + customPresets
        fun parse(text: String?, section: String): Map<String, KeyBinding>? =
            text?.let { parseKeyboardYamlSection(it, section, presets) }
        val custom = customText?.let { parse(it, resolvedSection) }
        if (custom != null) {
            val default = parse(defaultText, resolvedSection)
                ?: parse(defaultText, baseSection)
                ?: emptyMap()
            return default + custom
        }
        return parse(defaultText, resolvedSection)
            ?: parse(defaultText, baseSection)
            ?: emptyMap()
    }

    /**
     * 从 YAML 文本中提取 keyboard.<section>.layout.rows。
     * 行元素支持嵌套子数组表示合并键：[[q, w], [e, r], t] → ["qw", "er", "t"]，
     * 合并键 ID 为组内字母拼接，渲染层按 ID 长度分配键宽。
     */
    internal fun parseKeyboardLayoutYamlText(yamlText: String, section: String): List<List<String>>? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val layoutNode = sectionNode.opt<YamlMap>("layout") ?: return null
            val rowsNode = layoutNode.opt<YamlList>("rows") ?: return null
            parseLayoutRowsNode(rowsNode).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard layout config", e)
            null
        }
    }

    /** rows 节点 → 行列表：子数组为合并键（组内字母拼接为 ID），标量行手动拆分兜底。 */
    private fun parseLayoutRowsNode(rowsNode: YamlList): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (rowNode in rowsNode.items) {
            val row = when (rowNode) {
                is YamlList -> rowListToKeyIds(rowNode)
                is YamlScalar -> scalarRowToKeyIds(rowNode.content)
                else -> emptyList()
            }
            if (row.isNotEmpty()) {
                rows.add(row)
            }
        }
        return rows
    }

    /** 行节点 → 按键 ID 列表：子数组为合并键（组内字母拼接为 ID），标量为单字母键。 */
    private fun rowListToKeyIds(rowList: YamlList): List<String> =
        rowList.items.mapNotNull { item ->
            when (item) {
                is YamlScalar -> item.content.takeIf { it.isNotBlank() }
                is YamlList -> item.items
                    .mapNotNull { (it as? YamlScalar)?.content }
                    .filter { it.isNotBlank() }
                    .joinToString("")
                    .takeIf { it.isNotEmpty() }
                else -> null
            }
        }

    /**
     * 标量行兜底：YAML 中以普通字母开头的行（如 "- z, [x, c], v"）不会成为 flow 序列，
     * 而是整行解析为单个标量。此处手动拆分：逗号为键分隔，方括号内为合并键组。
     */
    private fun scalarRowToKeyIds(content: String): List<String> {
        val text = content.trim()
        if (text.isEmpty()) return emptyList()
        if (!text.contains(',') && !text.contains('[')) return listOf(text)
        val items = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        fun flushItem() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) items.add(t)
            sb.clear()
        }
        for (ch in text) {
            when {
                ch == '[' -> { depth++; if (depth == 1) sb.clear() else sb.append(ch) }
                ch == ']' -> {
                    depth--
                    if (depth == 0) {
                        flushItem()
                    } else if (depth < 0) {
                        depth = 0
                    } else {
                        sb.append(ch)
                    }
                }
                ch == ',' && depth == 0 -> flushItem()
                ch == ',' && depth > 0 -> {} // 组内逗号仅分隔字母，拼接时丢弃
                ch == ' ' && depth > 0 -> {} // 组内空格一并丢弃（[x, c] → xc）
                else -> sb.append(ch)
            }
        }
        flushItem()
        return items
    }

    /** 从 YAML 文本中提取 keyboard.<section>.button_layout（section 名任意，含合并键段）。 */
    internal fun parseButtonLayoutYamlText(yamlText: String, section: String): ButtonLayout? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val layoutNode = sectionNode.opt<YamlScalar>("button_layout") ?: return null
            ButtonLayout.fromValue(layoutNode.content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse button layout config", e)
            null
        }
    }

    /** 解析 `keyboard.actions` 可复用动作预设。 */
    internal fun parseKeyboardActionsYamlText(yamlText: String): Map<String, KeyAction> {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return emptyMap()
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return emptyMap()
            val actionsNode = keyboardNode.opt<YamlMap>("actions") ?: return emptyMap()
            val result = mutableMapOf<String, KeyAction>()
            for ((kNode, vNode) in actionsNode.entries) {
                val name = kNode.content
                result[name] = parseKeyAction(vNode, GestureAction.COMMIT, emptyMap())
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard actions presets", e)
            emptyMap()
        }
    }

    /** 从 YAML 文本中提取 keyboard.<section>.keys 段。 */
    internal fun parseKeyboardYamlSection(
        yamlText: String,
        section: String,
        presets: Map<String, KeyAction> = emptyMap(),
    ): Map<String, KeyBinding>? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val keysNode = sectionNode.opt<YamlMap>("keys") ?: return null
            val result = mutableMapOf<String, KeyBinding>()
            for ((kNode, vNode) in keysNode.entries) {
                val key = kNode.content
                val gestureMap = vNode as? YamlMap ?: continue
                result[key] = parseKeyBinding(gestureMap, presets)
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard gesture section", e)
            null
        }
    }

    private fun loadMergedConfig(context: Context): XimeConfig {
        val currentVersion = _configVersion.value
        if (mergedConfigCache != null && mergedConfigVersion == currentVersion) {
            return mergedConfigCache!!
        }
        val default = parseConfig(readAssetText(context, XIME_CONFIG_FILE))
        val custom = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?.let { parseConfig(it) }
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
                ?.let { parseConfig(it) }
        val config = mergeConfig(default, custom)
        mergedConfigCache = config
        mergedConfigVersion = currentVersion
        return config
    }

    private fun parseConfig(content: String?): XimeConfig? {
        if (content == null) return null
        return try {
            yaml.decodeFromString(XimeConfig.serializer(), content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse xime config", e)
            null
        }
    }

    private fun mergeConfig(default: XimeConfig?, custom: XimeConfig?): XimeConfig {
        if (custom == null) return default ?: XimeConfig()
        if (default == null) return custom
        return XimeConfig(
            ximeIndex = custom.ximeIndex ?: default.ximeIndex,
            // 合并而非替换：custom 覆盖同名字题，其余保留内置主题，
            // 这样用户只需在 xime.custom.yaml 中添加自定义背景主题而不会丢失内置主题。
            colorSchemes = mergeColorSchemes(default.colorSchemes, custom.colorSchemes),
            style = mergeStyle(default.style, custom.style),
            metadata = custom.metadata ?: default.metadata,
            // backup 段：custom 显式配置时优先，否则沿用内置默认（默认全开，与历史行为一致）
            backup = custom.backup ?: default.backup,
            // auto_word 段：custom 显式配置时优先，否则沿用内置默认
            autoWord = custom.autoWord ?: default.autoWord,
        )
    }

    /** style 字段级深合并：custom 只覆盖显式配置的字段。 */
    private fun mergeStyle(default: StyleConfig?, custom: StyleConfig?): StyleConfig? {
        if (custom == null) return default
        if (default == null) return custom
        return StyleConfig(
            colorScheme = mergeColorSchemeMode(default.colorScheme, custom.colorScheme),
            darkMode = custom.darkMode ?: default.darkMode,
        )
    }

    /** color_scheme 的 light/dark 字段级合并：custom 只覆盖显式配置的模式。 */
    private fun mergeColorSchemeMode(
        default: ColorSchemeModeConfig?,
        custom: ColorSchemeModeConfig?,
    ): ColorSchemeModeConfig? {
        if (custom == null) return default
        if (default == null) return custom
        return ColorSchemeModeConfig(
            light = custom.light ?: default.light,
            dark = custom.dark ?: default.dark,
        )
    }

    /** 仅供单元测试验证 style 合并逻辑。 */
    internal fun mergeStyleForTest(default: StyleConfig?, custom: StyleConfig?): StyleConfig? =
        mergeStyle(default, custom)

    /** 仅供单元测试验证 color_schemes 合并逻辑。 */
    internal fun mergeColorSchemesForTest(
        default: Map<String, ColorSchemeEntry>?,
        custom: Map<String, ColorSchemeEntry>?,
    ): Map<String, ColorSchemeEntry>? = mergeColorSchemes(default, custom)

    /** 仅供单元测试设置手势配置缓存（免加载真实 assets）。 */
    internal fun setKeyGestureConfigForTest(
        config: Map<String, KeyBinding>,
        isAsciiMode: Boolean = false,
    ) {
        if (isAsciiMode) _keyGestureConfigEn.value = config else _keyGestureConfig.value = config
    }

    private fun mergeColorSchemes(
        default: Map<String, ColorSchemeEntry>?,
        custom: Map<String, ColorSchemeEntry>?,
    ): Map<String, ColorSchemeEntry>? {
        if (custom == null) return default
        if (default == null) return custom
        // 字段级深合并：custom 只覆盖显式配置的字段，同名主题其余字段保留内置值。
        // 例如 custom 只写 primary_color 时，内置主题的 keyboard_background 不会被清掉。
        val result = default.toMutableMap()
        for ((id, customEntry) in custom) {
            val base = default[id] ?: run {
                result[id] = customEntry
                continue
            }
            result[id] = base.copy(
                name = customEntry.name.ifEmpty { base.name },
                primaryColor = customEntry.primaryColor.takeIf { it != 0L } ?: base.primaryColor,
                keyboardBgColor = customEntry.keyboardBgColor ?: base.keyboardBgColor,
                keyBgColor = customEntry.keyBgColor ?: base.keyBgColor,
                keyBgColorDark = customEntry.keyBgColorDark ?: base.keyBgColorDark,
                specialKeyBgColor = customEntry.specialKeyBgColor ?: base.specialKeyBgColor,
                specialKeyBgColorDark = customEntry.specialKeyBgColorDark ?: base.specialKeyBgColorDark,
                candidateBarBgColor = customEntry.candidateBarBgColor ?: base.candidateBarBgColor,
                keyTextColor = customEntry.keyTextColor ?: base.keyTextColor,
                keyTextColorDark = customEntry.keyTextColorDark ?: base.keyTextColorDark,
                candidateTextColor = customEntry.candidateTextColor ?: base.candidateTextColor,
                candidateTextColorDark = customEntry.candidateTextColorDark ?: base.candidateTextColorDark,
                candidateSelectedTextColor = customEntry.candidateSelectedTextColor ?: base.candidateSelectedTextColor,
                candidateSelectedTextColorDark = customEntry.candidateSelectedTextColorDark ?: base.candidateSelectedTextColorDark,
                keyboardBackground = customEntry.keyboardBackground ?: base.keyboardBackground,
                keyBackground = customEntry.keyBackground ?: base.keyBackground,
                candidateBarBackground = customEntry.candidateBarBackground ?: base.candidateBarBackground,
                dynamicColor = customEntry.dynamicColor || base.dynamicColor,
            )
        }
        return result
    }

    private fun readAssetText(context: Context, fileName: String): String? {
        return try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            inputStream.close()
            content
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从用户数据目录读取文件。
     *
     * 配置类文件额外**优先**读 `/storage/emulated/0/rime/xime/`：该目录用户可用系统「文件」App、
     * 电脑 USB 或第三方编辑器直接打开修改，是首选的编辑入口；私有目录 `files/rime/` 保留给
     * 内置文件管理器与无线导入写入。两者同时存在时以可见目录为准，保证「用户在文件管理器里
     * 改的那份」一定生效。
     *
     * 统一配置 `xime.yaml`（完整快照）优先于旧名 `xime.txt`，两者又都优先于更早的
     * `xime.custom.yaml`：前者是用户唯一的配置入口，旧文件在首次生成统一配置时已被折叠并
     * 删除，此处保留读取只为兼容未完成迁移的设备。
     */
    private fun readUserDataText(context: Context, fileName: String): String? {
        if (fileName == XIME_CUSTOM_CONFIG_FILE) {
            UserConfigDir.readText(context, UserConfigDir.UNIFIED_CONFIG_FILE)?.let { return it }
            UserConfigDir.readText(context, UserConfigDir.LEGACY_UNIFIED_CONFIG_FILE)?.let { return it }
            UserConfigDir.readText(context, fileName)?.let { return it }
        }
        val file = File(context.filesDir, "rime/$fileName")
        if (!file.exists()) return null
        return try {
            val text = file.readText().trimStart('\uFEFF')
            text
        } catch (e: Exception) {
            Log.w(TAG, "readUserDataText: failed", e)
            null
        }
    }

    /**
     * 读取自定义配置文本，优先级：
     * `/storage/emulated/0/rime/xime/xime.yaml` → 同目录 `xime.txt` → 同目录 `xime.custom.yaml`
     * → `files/rime/`（内置文件管理器/无线导入） → assets 内置。各级都不存在时返回 null，
     * 调用方沿用内置默认值。
     */
    private fun readCustomText(context: Context): String? =
        readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)

    fun loadXimeIndexConfig(context: Context): XimeIndexConfig {
        val merged = loadMergedConfig(context)
        return merged.ximeIndex ?: XimeIndexConfig()
    }

    /** 从 xime.yaml 加载 color_schemes 配置。 */
    fun loadColorSchemes(context: Context): Map<String, ColorSchemeEntry> {
        val merged = loadMergedConfig(context)
        return merged.colorSchemes ?: emptyMap()
    }

    /** 从 xime.yaml 加载默认主题 ID（style.color_scheme 的 light 字段）。 */
    fun loadDefaultThemeId(context: Context): String {
        val merged = loadMergedConfig(context)
        return merged.style?.colorScheme?.light ?: "lavender_purple"
    }

    /** 根据显示模式加载对应的默认主题 ID。 */
    fun loadThemeIdForMode(context: Context, isDark: Boolean): String {
        val merged = loadMergedConfig(context)
        val cs = merged.style?.colorScheme ?: return "lavender_purple"
        return if (isDark) (cs.dark ?: cs.light ?: "lavender_purple")
               else cs.light ?: "lavender_purple"
    }

    /** 从 xime.yaml 加载默认显示模式（style.dark_mode）。 */
    fun loadDefaultDarkMode(context: Context): Int {
        val merged = loadMergedConfig(context)
        return merged.style?.darkMode ?: 2
    }

    /**
     * 从 xime.yaml 加载备份范围配置（`backup` 段）。
     *
     * 备份引擎（本地导出 / 云备份）按此决定打包哪些内容（如候选排序词频表）。
     * 读取的是合并后的生效配置：用户可见目录 xime.yaml → 私有 → 内置默认。
     */
    fun loadBackupConfig(context: Context): BackupConfig {
        val merged = loadMergedConfig(context)
        return merged.backup ?: BackupConfig()
    }

    // ── 新公开 API ──

    /** 获取键盘颜色配置（从 xime.yaml keyboard.colors 加载）。 */
    fun getKeyboardColors(): KeyboardColorsConfig = keyboardColorsConfig

    /** 获取键盘阴影配置（从 xime.yaml keyboard.shadow 加载）。 */
    fun getKeyboardShadow(): KeyboardShadowConfig = keyboardShadowConfig

    /** 获取键盘按键配置（从 xime.yaml keyboard.key 加载）。 */
    fun getKeyboardKeyConfig(): KeyboardKeyConfig = keyboardKeyConfig

    /** 获取候选栏行为配置（从 xime.yaml keyboard.candidates 加载）。 */
    fun getKeyboardCandidates(): KeyboardCandidatesConfig = keyboardCandidatesConfig

    /** 获取手势灵敏度配置（从 xime.yaml keyboard.gestures 加载）。 */
    fun getKeyboardGestures(): KeyboardGesturesConfig = keyboardGesturesConfig

    /** 获取九键左侧快捷符号栏配置（xime.custom.yaml → xime.yaml → 内置默认值）。 */
    fun getT9SideSymbols(): List<String> =
        keyboardT9Config.sideSymbols ?: DEFAULT_T9_SIDE_SYMBOLS

    /** 获取九键布局配置（keyboard.t9.layout，custom → builtIn → 内置默认，字段级 fallback）。 */
    fun getT9Layout(): KeyboardT9LayoutConfig = keyboardT9Config.layout

    /** 九键键面标签（keyboard.t9.keys.<id>.tap.label，未配置返回 null，由调用方回退内置默认）。 */
    fun getT9KeyLabel(id: String): String? =
        _t9GestureConfigs[id]?.tap?.label?.takeIf { it.isNotEmpty() }

    /** 九键长按候选显示文本（keyboard.t9.keys.<id>.long_press.values 的 label，缺省 value；未配置返回 null）。 */
    fun getT9KeyLongPressLabels(id: String): List<String>? =
        longPressDisplayItems(_t9GestureConfigs[id]?.longPress)

    /**
     * 长按气泡的显示项文本：`label` 为空时回退 `value`；两者皆空的项无法展示，丢弃。
     *
     * 单项且该项标了 `bubble: false` 时返回 null：不弹气泡，长按直接执行该项动作；
     * 多项时不支持 `bubble: false`（会让该项无法选中），一律按气泡展示。
     *
     * 26 键字母区（标准/横屏紧凑）、逗号键、中英键、九键共用，保证各处 label 处理一致。
     */
    fun longPressDisplayItems(config: LongPressAction?): List<String>? {
        val values = config?.values ?: return null
        if (isBubbleOffSingleValue(values)) return null
        return values.map { longPressItemKey(it) }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }

    /** 长按气泡的「显示项文本 → 动作」映射；键与 [longPressDisplayItems] 完全一致（不可展示的项不进入映射）。 */
    fun longPressActionMap(config: LongPressAction?): Map<String, KeyAction>? {
        val values = config?.values ?: return null
        if (isBubbleOffSingleValue(values)) return emptyMap()
        return values.filter { longPressItemKey(it).isNotEmpty() }.associateBy { longPressItemKey(it) }
    }

    /** 单项 long_press 标了 `bubble: false`：不弹气泡，该项作为直接长按动作。 */
    private fun isBubbleOffSingleValue(values: List<KeyAction>): Boolean =
        values.size == 1 && !values.first().bubble

    /** 长按气泡条目的显示文本与查找键（label 优先，缺省 value）。 */
    private fun longPressItemKey(action: KeyAction): String = action.label.ifEmpty { action.value }

    /** 获取九键数字键手势配置（xime.yaml keyboard.t9.keys，custom 键级覆盖）。
     *  键 id 为数字字符串 "1"~"9"；无配置返回 null（布局不启用滑动）。 */
    fun getT9KeyGesture(key: String): KeyBinding? = _t9GestureConfigs[key]

    /** 获取笔画左侧快捷符号列（keyboard.stroke.side_symbols，可自定义）。 */
    fun getStrokeSideSymbols(): List<String> =
        keyboardStrokeConfig.sideSymbols ?: DEFAULT_STROKE_SIDE_SYMBOLS

    /** 获取笔画键手势配置（xime.yaml keyboard.stroke.keys，custom 键级覆盖）。
     *  键 id 为键面标签：一/丨/丿/丶/乛、*、分词、，、英。 */
    fun getStrokeKeyGesture(key: String): KeyBinding? = _strokeGestureConfigs[key]

    /** 获取某个按键的手势配置。 */
    fun getKeyGesture(key: String): KeyBinding? = keyGestureConfig[key.lowercase()]

    /** 根据输入模式获取某个按键的手势配置。 */
    fun getKeyGesture(key: String, isAsciiMode: Boolean): KeyBinding? {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return config[key.lowercase()]
    }

    fun getKeyDisplayLabel(key: String, isAsciiMode: Boolean = false): String {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val label = config[key.lowercase()]?.tap?.label
        if (label.isNullOrEmpty()) return key.uppercase()
        return if (label.any { it in 'a'..'z' || it in 'A'..'Z' }) label.uppercase() else label
    }

    fun getKeyCommitValue(key: String, isAsciiMode: Boolean = false): String {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val value = config[key.lowercase()]?.tap?.value
        return value?.takeIf { it.isNotEmpty() }
            // 合并键 ID（如 "qw"）无手势配置时回退为代表字母（组内第一个字母）
            ?: key.takeIf { it.length > 1 }?.first()?.toString()
            ?: key
    }

    // ── 旧公开 API（兼容） ──
    
    fun getConfig(): KeysConfig = config
    
    fun getSwipeUpText(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
        }
        return config.swipeUp[key.lowercase()]
    }

    fun getSwipeUpAction(key: String, isAsciiMode: Boolean = false): GestureAction? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeUp?.action
    }

    /** 获取上滑显示文本（优先 label，fallback value；仅未配置 swipe_up 时用旧默认表） */
    fun getSwipeUpLabel(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.label.isNotEmpty()) return gesture.label
            if (gesture.value.isNotEmpty()) return gesture.value
            // 显式配置了 swipe_up（如只写了 action）时不再回退旧默认符号表，
            // 否则键面/气泡会显示与该手势无关的默认符号（q→"1"）。
            return null
        }
        return config.swipeUp[key.lowercase()]
    }

    /** 获取上滑提交值（优先 value，fallback label；仅未配置 swipe_up 时用旧默认表） */
    fun getSwipeUpCommitValue(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
            return null
        }
        return config.swipeUp[key.lowercase()]
    }
    
    fun getSwipeDownEnglishText(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val fromYaml = configMap[key.lowercase()]?.swipeDown?.label
        if (fromYaml != null && fromYaml.isNotEmpty()) return fromYaml
        return config.swipeDownEnglish[key.lowercase()]
    }

    /** 获取下滑动作类型 */
    fun getSwipeDownAction(key: String, isAsciiMode: Boolean = false): GestureAction? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeDown?.action
    }

    /** 获取下滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeDownDisplay(key: String, isAsciiMode: Boolean = false): DisplayMode {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeDown?.display ?: DisplayMode.BOTH
    }

    /** 获取上滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeUpDisplay(key: String, isAsciiMode: Boolean = false): DisplayMode {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeUp?.display ?: DisplayMode.BOTH
    }

    /** 上滑运行时是否弹出内容气泡（与 [getSwipeUpDisplay] 无关）。 */
    fun getSwipeUpBubble(key: String, isAsciiMode: Boolean = false): Boolean {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeUp?.bubble ?: true
    }

    /** 下滑运行时是否弹出内容气泡（与 [getSwipeDownDisplay] 无关）。 */
    fun getSwipeDownBubble(key: String, isAsciiMode: Boolean = false): Boolean {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeDown?.bubble ?: true
    }

    private fun getDefaultSwipeUp(): Map<String, String> = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "a" to "!", "s" to "@", "d" to "#", "f" to "$", "g" to "%",
        "h" to "^", "j" to "&", "k" to "(", "l" to ")",
        "z" to "|", "x" to "*", "c" to "\\", "v" to "?", "b" to "_",
        "n" to "-", "m" to "+"
    )
    
    private fun getDefaultSwipeDownEnglish(): Map<String, String> = mapOf(
        "q" to "Q", "w" to "W", "e" to "E", "r" to "R", "t" to "T",
        "y" to "Y", "u" to "U", "i" to "I", "o" to "O", "p" to "P",
        "a" to "A", "s" to "S", "d" to "D", "f" to "F", "g" to "G",
        "h" to "H", "j" to "J", "k" to "K", "l" to "L",
        "z" to "Z", "x" to "X", "c" to "C", "v" to "V", "b" to "B",
        "n" to "N", "m" to "M"
    )
    

}

