package com.kingzcheung.xime.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlContentPolymorphicSerializer
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

enum class DisplayMode(val value: String) {
    KEY("key"), BUBBLE("bubble"), BOTH("both");

    companion object {
        fun fromValue(value: String): DisplayMode = entries.firstOrNull { it.value == value } ?: BOTH
    }
}

/** 按键布局模式，从 xime.yaml keyboard.button_layout 加载。 */
enum class ButtonLayout(val value: String) {
    STANDARD("standard"),
    COMPACT("compact");

    companion object {
        fun fromValue(value: String): ButtonLayout =
            entries.firstOrNull { it.value == value } ?: STANDARD
    }
}

/**
 * 键盘行布局配置，从 xime.yaml keyboard.<section>.layout 加载。
 * rows[i] 为第 i 行的按键 ID 列表。
 */

data class KeyboardLayoutConfig(
    val rows: List<List<String>> = emptyList(),
)

/**
 * 键盘阴影配置，从 xime.yaml keyboard.shadow 加载。
 */
data class KeyboardShadowConfig(
    val enabled: Boolean = true,
    val elevation: Int = 1,
    val shapeRadius: Int = 8,
)

// ── 原有配置类 ──

/**
 * 背景配置，支持纯色/渐变/图片三种类型。
 *
 * YAML 示例：
 * ```yaml
 * # 纯色
 * keyboard_background:
 *   type: solid
 *   color: 0x8F73E2
 *   color_dark: 0x4A3F7A   # 可选，不指定则自动暗化
 *
 * # 渐变
 * keyboard_background:
 *   type: gradient
 *   colors: [0x8F73E2, 0xE8DEF8]        # 亮色渐变断点
 *   colors_dark: [0x4A3F7A, 0x2D2040]    # 暗色渐变断点（可选）
 *   angle: 135  # 角度制，0=左→右，90=下→上，180=右→左，270=上→下
 *
 * # 图片
 * keyboard_background:
 *   type: image
 *   src: "themes/lavender_bg.png"          # 相对于 assets/
 *   src_dark: "themes/lavender_bg_dark.png" # 暗色变体（可选）
 *   fit: cover  # cover | contain | fill | fit_width | fit_height | none
 * ```
 */
@Serializable
data class BackgroundConfig(
    val type: String = "solid",
    // solid
    val color: Long? = null,
    @SerialName("color_dark")
    val colorDark: Long? = null,
    // gradient
    val colors: List<Long>? = null,
    @SerialName("colors_dark")
    val colorsDark: List<Long>? = null,
    val angle: Int? = null,
    // image
    val src: String? = null,
    @SerialName("src_dark")
    val srcDark: String? = null,
    val fit: String? = null,
    /** 图片背景遮罩：半透明黑色覆盖层降低背景亮度（0~1，如 0.35 表示压暗 35%）。 */
    @SerialName("overlay_alpha")
    val overlayAlpha: Float? = null,
    /** 暗色模式下的遮罩强度，未配置时沿用 [overlayAlpha]。 */
    @SerialName("overlay_alpha_dark")
    val overlayAlphaDark: Float? = null,
)

@Serializable
data class ColorSchemeEntry(
    val name: String = "",
    @SerialName("primary_color")
    val primaryColor: Long = 0,
    @SerialName("keyboard_bg_color")
    val keyboardBgColor: Long? = null,
    @SerialName("key_bg_color")
    val keyBgColor: Long? = null,
    @SerialName("key_bg_color_dark")
    val keyBgColorDark: Long? = null,
    @SerialName("special_key_bg_color")
    val specialKeyBgColor: Long? = null,
    @SerialName("special_key_bg_color_dark")
    val specialKeyBgColorDark: Long? = null,
    @SerialName("candidate_bar_bg_color")
    val candidateBarBgColor: Long? = null,
    @SerialName("key_text_color")
    val keyTextColor: Long? = null,
    @SerialName("key_text_color_dark")
    val keyTextColorDark: Long? = null,
    @SerialName("candidate_text_color")
    val candidateTextColor: Long? = null,
    @SerialName("candidate_text_color_dark")
    val candidateTextColorDark: Long? = null,
    @SerialName("candidate_selected_text_color")
    val candidateSelectedTextColor: Long? = null,
    @SerialName("candidate_selected_text_color_dark")
    val candidateSelectedTextColorDark: Long? = null,
    @SerialName("keyboard_background")
    val keyboardBackground: BackgroundConfig? = null,
    @SerialName("key_background")
    val keyBackground: BackgroundConfig? = null,
    @SerialName("candidate_bar_background")
    val candidateBarBackground: BackgroundConfig? = null,
    /** Material You 动态配色：忽略静态颜色字段，运行时从壁纸调色板取色（仅 Android 12+）。 */
    @SerialName("dynamic_color")
    val dynamicColor: Boolean = false,
)

@Serializable
data class MetadataConfig(
    @SerialName("app_name")
    val appName: String = "Xime",
    @SerialName("app_version")
    val appVersion: String = "",
    @SerialName("platform")
    val platform: String = "android",
    @SerialName("config_version")
    val configVersion: Int = 1,
    @SerialName("generator")
    val generator: String = "",
    @SerialName("modified_time")
    val modifiedTime: String = "",
)

@Serializable
data class ColorSchemeModeConfig(
    @SerialName("light")
    val light: String? = null,
    @SerialName("dark")
    val dark: String? = null,
)

/** 兼容 color_scheme 的两种写法：对象（{light, dark}）或标量字符串。 */
@OptIn(ExperimentalSerializationApi::class)
object ColorSchemeModeConfigSerializer :
    YamlContentPolymorphicSerializer<ColorSchemeModeConfig>(ColorSchemeModeConfig::class) {

    override fun selectDeserializer(node: YamlNode): DeserializationStrategy<ColorSchemeModeConfig> {
        // 标量写法（config generator 输出）：color_scheme: mu_shan_zi → light 使用该值。
        return if (node is YamlScalar) ScalarColorSchemeDeserializer else ColorSchemeModeConfig.serializer()
    }

    /** 把单个字符串的 color_scheme 反序列化为 light=该字符串。 */
    private object ScalarColorSchemeDeserializer : KSerializer<ColorSchemeModeConfig> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ScalarColorScheme", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): ColorSchemeModeConfig =
            ColorSchemeModeConfig(light = decoder.decodeString())

        override fun serialize(encoder: Encoder, value: ColorSchemeModeConfig) {
            encoder.encodeString(value.light ?: "")
        }
    }
}

/** 字体配置，从 xime.yaml style 节点加载。字体文件放在 rime/fonts/ 目录下。 */
data class KeyboardFontConfig(
    val keyFont: String = "",
    val keyLabelFont: String = "",
    val candidateFont: String = "",
    val commentFont: String = "",
)

/**
 * 九键（T9）布局配置，从 xime.yaml keyboard.t9.layout 加载。
 *
 * 九键为三列结构：左列（候选面板占位 + 键）、主区（按行的按键网格）、右列（功能键列）。
 * 键 id 分三类：
 * - 数字 "0"~"9"：九键输入键（点按固定为九键数字输入；键面字母 keys.<id>.tap.label、
 *   长按候选 keys.<id>.long_press、手势 keys.<id> 均可配置）；
 * - [KeysConfigHelper.T9_FUNCTION_KEY_IDS] 中的功能键：渲染内置组件，位置可任意安排；
 * - 其余 id：自定义键，行为在 keys.<id> 定义（无配置时键面为键名、点按提交键名）。
 */
data class KeyboardT9LayoutConfig(
    /** 左列（自上而下）。candidates = 拼音候选面板占位（候选态显示音节，空闲态显示 side_symbols）。 */
    val left: List<String> = DEFAULT_T9_LEFT,
    /** 主区行：每个子列表一行，从左到右。 */
    val rows: List<List<String>> = DEFAULT_T9_ROWS,
    /** 右列（自上而下）。 */
    val right: List<String> = DEFAULT_T9_RIGHT,
) {
    companion object {
        val DEFAULT_T9_LEFT: List<String> = listOf("candidates", "symbol")
        val DEFAULT_T9_ROWS: List<List<String>> = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("number", "space", "earth"),
        )
        val DEFAULT_T9_RIGHT: List<String> = listOf("delete", "clear", "enter")
    }
}

/**
 * 九键（T9）键盘配置，从 xime.yaml keyboard.t9 加载。
 */
data class KeyboardT9Config(
    /** 左侧快捷符号栏（空闲态），列表长度不限，超过 4 个时键盘侧滚动显示。 */
    val sideSymbols: List<String>? = null,
    /** 布局（左列/主区行/右列）。 */
    val layout: KeyboardT9LayoutConfig = KeyboardT9LayoutConfig(),
)

/**
 * 笔画键盘配置，从 xime.yaml keyboard.stroke 加载。
 */
data class KeyboardStrokeConfig(
    /** 左侧快捷符号列，列表长度不限，超过 3 个时键盘侧滚动显示。 */
    val sideSymbols: List<String>? = null,
)

/**
 * 候选栏行为配置，从 xime.yaml keyboard.candidates 加载。
 *
 * - [showClearButton]：编码状态下是否在候选栏右侧显示「清空」按键；
 * - [shortcuts]：标点键直选候选的下标映射（0 = 首选），存在候选词时按下标点键直接选词。
 */
data class KeyboardCandidatesConfig(
    val showClearButton: Boolean = true,
    val shortcuts: Map<String, Int> = DEFAULT_SHORTCUTS,
) {
    /**
     * 该键直选候选的下标（0 = 首选）。
     * @return null 表示该键未配置直选，或配置值非法（负数）。
     */
    fun shortcutIndex(keyId: String): Int? = shortcuts[keyId]?.takeIf { it >= 0 }

    companion object {
        /** 逗号选第 2 个候选（次选）、句号选第 3 个候选（三选）。 */
        val DEFAULT_SHORTCUTS: Map<String, Int> = linkedMapOf("comma" to 1, "period" to 2)
    }
}

/**
 * 手势灵敏度配置，从 xime.yaml keyboard.gestures 加载。
 *
 * [longPressMs]：长按判定时间（毫秒），数值越小越灵敏。
 * 空格键用它决定「按住不动多久进入语音输入」；一旦手指位移超过 touch slop
 * 就取消长按改判滑动，所以左右滑动移动光标不会误开语音。
 */
data class KeyboardGesturesConfig(
    val longPressMs: Int = DEFAULT_LONG_PRESS_MS,
) {
    companion object {
        /** 默认长按判定时间（毫秒）：比系统 400ms 更灵敏，同时仍能区分「点一下」与「按住」。 */
        const val DEFAULT_LONG_PRESS_MS: Int = 300

        /** 允许的下限：再小会把正常点按误判成长按。 */
        const val MIN_LONG_PRESS_MS: Int = 120

        /** 允许的上限：再大长按会显得没反应。 */
        const val MAX_LONG_PRESS_MS: Int = 1500
    }
}

/**
 * 部分配置：仅包含 YAML 中显式配置的字段，null = 未配置。
 * 用于 custom → builtIn → 代码默认值的字段级一路 fallback 合并。
 */
internal data class KeyboardFontPartial(
    val keyFont: String? = null,
    val keyLabelFont: String? = null,
    val candidateFont: String? = null,
    val commentFont: String? = null,
)

internal data class KeyboardColorsPartial(
    val keyBgColor: Long? = null,
    val keyBgColorDark: Long? = null,
    val specialKeyBgColor: Long? = null,
    val specialKeyBgColorDark: Long? = null,
    val keyTextColor: Long? = null,
    val keyTextColorDark: Long? = null,
    val candidateTextColor: Long? = null,
    val candidateTextColorDark: Long? = null,
)

internal data class KeyboardShadowPartial(
    val enabled: Boolean? = null,
    val elevation: Int? = null,
    val shapeRadius: Int? = null,
)

internal data class KeyboardKeyPartial(
    val cornerRadius: Int? = null,
    val spacingX: Float? = null,
    val spacingY: Float? = null,
    val spacingOverrides: Map<String, KeyboardSpacingConfig>? = null,
)

internal data class KeyboardT9Partial(
    val sideSymbols: List<String>? = null,
    val layout: KeyboardT9LayoutPartial? = null,
)

internal data class KeyboardT9LayoutPartial(
    val left: List<String>? = null,
    val rows: List<List<String>>? = null,
    val right: List<String>? = null,
)

internal data class KeyboardStrokePartial(
    val sideSymbols: List<String>? = null,
)

internal data class KeyboardCandidatesPartial(
    val showClearButton: Boolean? = null,
    val shortcuts: Map<String, Int>? = null,
)

internal data class KeyboardGesturesPartial(
    val longPressMs: Int? = null,
)

/** 字段级一路 fallback：custom → builtIn → 代码默认值。 */
internal inline fun <T> tiered(custom: T?, builtIn: T?, default: T): T = custom ?: builtIn ?: default

@Serializable
data class StyleConfig(
    @SerialName("color_scheme")
    @Serializable(with = ColorSchemeModeConfigSerializer::class)
    val colorScheme: ColorSchemeModeConfig? = null,
    @SerialName("dark_mode")
    val darkMode: Int? = null,
    @SerialName("key_font")
    val keyFont: String? = null,
    @SerialName("key_label_font")
    val keyLabelFont: String? = null,
    @SerialName("candidate_font")
    val candidateFont: String? = null,
    @SerialName("comment_font")
    val commentFont: String? = null,
)

@Serializable
data class XimeConfig(
    @SerialName("xime_index")
    val ximeIndex: XimeIndexConfig? = null,
    @SerialName("color_schemes")
    val colorSchemes: Map<String, ColorSchemeEntry>? = null,
    @SerialName("style")
    val style: StyleConfig? = null,
    @SerialName("metadata")
    val metadata: MetadataConfig? = null,
    @SerialName("backup")
    val backup: BackupConfig? = null,
    @SerialName("auto_word")
    val autoWord: AutoWordConfig? = null,
)

/**
 * 自动造词配置，从 xime.yaml `auto_word` 段加载。
 *
 * 控制上屏词自动收录（临时词库 → 固定词库）与词频统计的开关与晋升参数。
 */
@Serializable
data class AutoWordConfig(
    /** 是否启用自动造词（上屏词收录与词频统计）。默认 true。 */
    @SerialName("enabled")
    val enabled: Boolean = true,
    /** 晋升窗口（天）：该窗口内同词上屏满 [promoteCount] 次晋升固定词库。默认 30。 */
    @SerialName("promote_days")
    val promoteDays: Int = 30,
    /** 窗口内需达到的上屏次数。默认 2。 */
    @SerialName("promote_count")
    val promoteCount: Int = 2,
    /** 是否把相邻两次上屏的词拼接成生词（如「现在」+「目录」→「现在目录」）。默认 true。 */
    @SerialName("combo_enabled")
    val comboEnabled: Boolean = true,
    /**
     * 组合生词的字数下限/上限（含）。拼接后的词字数落在此区间才收录，
     * 避免 1 字碎片与超长句子污染词库。默认 2–4。
     */
    @SerialName("combo_min_chars")
    val comboMinChars: Int = 2,
    @SerialName("combo_max_chars")
    val comboMaxChars: Int = 4,
)

/**
 * 备份范围配置，从 xime.yaml `backup` 段加载。
 *
 * 用户在此声明「哪些内容需要备份」：备份引擎（本地导出 / 云备份）打包与恢复时
 * 按此清单取舍，默认全部开启（与历史行为一致）。
 */
@Serializable
data class BackupConfig(
    /**
     * 是否备份候选排序词频表（candidate_order.txt，与 xime.yaml 同级）。
     * 默认 true：用户手动拖拽出来的候选顺序值得换机/恢复后保留。
     */
    @SerialName("candidate_order")
    val candidateOrder: Boolean = true,
    /**
     * 是否备份自动造词词库（auto_word_temp.txt 临时词库 + auto_word.txt 固定词库，
     * 与 xime.yaml 同级）。默认 true：造词是长期积累，换机后不该从零开始。
     * 关闭只影响「积累」，功能本身照常（重新开始学词）。
     */
    @SerialName("auto_word")
    val autoWord: Boolean = true,
    /**
     * 是否备份上屏词频表（word_freq.txt，与 xime.yaml 同级）。
     * 默认 true：与候选排序同理，恢复后候选顺序不退化。
     */
    @SerialName("word_freq")
    val wordFreq: Boolean = true,
    /**
     * 是否备份黑名单词库（word_blacklist.txt，与 xime.yaml 同级）。
     * 默认 true：长按删掉的词是用户明确的选择，换机/恢复后不该重新冒出来。
     */
    @SerialName("blacklist")
    val blacklist: Boolean = true,
)

@Serializable
data class XimeIndexConfig(
    @SerialName("base_urls")
    val baseUrls: List<String> = listOf("https://index.ximei.me/")
)

data class KeysConfig(
    val swipeUp: Map<String, String> = emptyMap(),
    val swipeDownEnglish: Map<String, String> = emptyMap()
)


