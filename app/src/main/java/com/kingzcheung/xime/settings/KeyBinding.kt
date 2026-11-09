package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction

/**
 * 单个动作定义（对应 YAML 中一个手势槽位的取值）。
 *
 * @param action 动作类型；null 表示无动作
 * @param value 动作参数（如 commit 的上屏文本、command 的命令名）
 * @param label 显示标签
 * @param icon 图标名（标签以 `@` 前缀书写时提取）
 * @param display 标签/气泡显示位置（key：画在键面；bubble：不画键面，留给气泡；both：都画）
 * @param bubble 运行时是否弹出该手势的内容气泡（与 display 无关，display 只管静态提示位置）
 */
class KeyAction(
    val action: GestureAction? = null,
    val value: String = "",
    val label: String = "",
    val icon: String = "",
    val display: DisplayMode = DisplayMode.BOTH,
    val bubble: Boolean = true,
)

/**
 * 长按配置。
 *
 * 单动作对象 → [values] 仅一项；数组 → 气泡列表多项。
 *
 * @param display 显示模式；目前只支持 `bubble`（`key` / `both` 会在解析时回退为 `bubble` 并告警，
 *   长按候选的键面绘制尚未实现）
 * @param values 长按候选动作
 */
class LongPressAction(
    val display: DisplayMode = DisplayMode.KEY,
    val values: List<KeyAction> = emptyList(),
) {
    /**
     * 长按气泡的显示项文本（惰性缓存）。
     *
     * 之前由 `KeysConfigHelper.longPressDisplayItems(config)` 在**每个 @Composable 函数体里
     * 直接调用**计算——键盘重组（候选每键变化就会触发）时逐键重建 List，是打字时
     * 重组开销的来源之一。`values` 构造后不可变，故结果天然可缓存。
     *
     * 语义与原实现完全一致：单项且 `bubble: false` → null（不弹气泡，长按直接执行）；
     * 其余按 label 回退 value 取显示文本，空项丢弃。
     */
    val displayItems: List<String>? by lazy {
        if (values.size == 1 && !values[0].bubble) return@lazy null
        values.asSequence()
            .map { it.label.ifEmpty { it.value } }
            .filter { it.isNotEmpty() }
            .toList()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * 显示项文本 → 动作的映射（惰性缓存），键与 [displayItems] 完全一致。
     *
     * 单项 `bubble: false` 返回**空 Map**（与原 `longPressActionMap` 一致：
     * 该场景无气泡候选，但调用方仍拿到非 null 的空表以区分「无长按配置」）。
     */
    val actionMap: Map<String, KeyAction>? by lazy {
        if (values.size == 1 && !values[0].bubble) return@lazy emptyMap()
        val out = LinkedHashMap<String, KeyAction>(values.size)
        for (v in values) {
            val key = v.label.ifEmpty { v.value }
            if (key.isNotEmpty()) out[key] = v
        }
        out
    }
}

/**
 * 一个键的手势绑定表。
 *
 * @param width 布局宽度权重；null 时功能键用内置默认宽度、字母键为 1
 */
class KeyBinding(
    val tap: KeyAction? = null,
    val doubleTap: KeyAction? = null,
    val longPress: LongPressAction? = null,
    val swipeUp: KeyAction? = null,
    val swipeDown: KeyAction? = null,
    val swipeLeft: KeyAction? = null,
    val swipeRight: KeyAction? = null,
    val width: Float? = null,
)

/**
 * 面板段（数字/常用符号键盘）的布局描述。
 *
 * @param columns true = 三列模式（数字键盘：[left] 键柱列 / [rows] 数字区 / [right] 功能列）；
 *   false = 行模式（常用符号：[rows] 逐行渲染）
 * @param rows 行模式的全部行；三列模式的中列（数字区）行
 * @param left 三列模式左列：每项 = 单键（单元素列表）或纵向键柱（多元素列表，如加减乘除四键一柱）
 * @param right 三列模式右列：每项 = 单键
 */
data class PanelLayoutConfig(
    val columns: Boolean,
    val rows: List<List<String>> = emptyList(),
    val left: List<List<String>> = emptyList(),
    val right: List<List<String>> = emptyList(),
)
