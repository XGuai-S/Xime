package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate

/**
 * 候选栏显示规则 —— 从 [ImeSessionController] 抽出的纯函数。
 *
 * 抽出动机有两个：
 * 1. 这些规则原先内联在两个入口（applyComposition / updateUIWithResult）里各写一遍，
 *    改一处漏一处就会让两条路径的候选栏表现不一致；
 * 2. 它们不依赖 Android 运行时，是这套显示逻辑里唯一能直接单测的部分
 *    （ImeSessionController 需要 InputMethodService 实例，而 T9 分支还依赖已加载的键盘配置）。
 */

/**
 * 汉字区间（CJK 统一表意文字）。
 *
 * `internal` 而非 `private`：ImeKeyRouter 的英文联想过滤走的是同一区间，
 * 此前在那里又写了一遍字面量。
 */
internal val CJK_IDEOGRAPH_RANGE = 0x4E00..0x9FFF

/**
 * 按显示模式挑选候选。
 *
 * ASCII 模式下隐藏含汉字的候选：中英混排时避免弹出中文候选。
 * 返回的文本与注释列表保持平行（同索引对应同一候选）。
 */
internal fun filterCandidatesForDisplay(
    isAsciiMode: Boolean,
    candidates: Array<RimeCandidate>,
): Pair<List<String>, List<String>> {
    val visible = if (isAsciiMode) {
        candidates.filterNot { candidate -> candidate.text.any { it.code in CJK_IDEOGRAPH_RANGE } }
    } else {
        candidates.toList()
    }
    return visible.map { it.text } to visible.map { it.comment }
}

/**
 * 候选栏显示文本：preedit 非空时用引擎回显（带音节分隔符，如全拼 ni'hao），
 * 否则回退原始键入串。
 */
internal fun resolveDisplayText(preeditText: String, inputText: String): String =
    if (preeditText.isNotEmpty()) preeditText else inputText

/**
 * T9 编码显示永不回退原始数字 input。
 *
 * preedit 缺失，或含未转换数字（合成切不出词时引擎把原始输入当编码回退，
 * 如全拼+简拼混合态）时，保留上一帧字母编码 —— 用户看到的编码必须是字母。
 */
internal fun resolveT9RawPreedit(preeditText: String, inputText: String, lastPreedit: String): String =
    when {
        preeditText.isNotEmpty() && preeditText.none { it.isDigit() } -> preeditText
        inputText.isEmpty() -> ""
        else -> lastPreedit
    }

/**
 * 候选排序是否可应用（用户手动词频重排的前置条件）。
 *
 * 只作用于**纯引擎候选**路径：T9（索引不可信）、ASCII 模式（引擎候选被过滤，
 * 显示索引 ≠ 引擎索引）、带插件/组合键动作（已有显示→引擎映射）都不参与重排——
 * 这三类路径要么点选靠自身映射、要么重排会破坏平行关系。
 */
internal fun shouldApplyCandidateOrder(
    isT9: Boolean,
    isAsciiMode: Boolean,
    candidateActions: List<CandidateAction>,
): Boolean = !isT9 && !isAsciiMode && candidateActions.isEmpty()
