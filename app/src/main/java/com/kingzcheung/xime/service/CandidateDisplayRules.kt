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
 * 顶屏时「提交的词」与候选栏首词对齐。
 *
 * 满码顶屏（`speller/max_code_length` + `auto_select`，小鹤等顶功方案）由 librime
 * 直接提交**引擎顺序**的首选；候选栏展示的是按手动顺序/词频重排后的顺序。两者不一致时，
 * 用户看到的首词是 A，顶出去的却是 B —— 反馈「自动顶屏没有顶候选栏首码，顶的是次选」。
 *
 * 只对齐「打满码后把该段顶出去」这一类提交：
 * - 此时 [displayedCandidates] 就是被顶出的那一整段候选，首词正是用户眼中的首选；
 * - 编码还没满时引擎提交的是**新算出来的更长编码**的词，上一屏候选与它无关，
 *   拿上一屏首词替换会把用户没打完的词顶出去（前缀命中会造成这种巧合）。
 *
 * ## ⚠️ 改这个函数前先读
 *
 * 三道守卫缺一不可，删任何一条都会引入一类「顶错词」：
 * 1. `preKeyCodeLength < PUSH_OUT_MIN_CODE_LENGTH` —— 去掉它，3 码时前缀命中会让
 *    上一屏首词顶掉引擎刚算出的正解（用户还没打完就被强行上屏）；
 * 2. `committed !in displayedCandidates` —— 去掉它，引擎因更长编码/简繁转换等原因
 *    提交的词会被上一屏首词覆盖；
 * 3. `isAsciiMode` —— 英文候选不参与重排，「引擎提交即所见」，改写反而错。
 *
 * 也不要扩大成「引擎提交的词与首词不同就一律对齐」：那等于把引擎的所有自动上屏
 * 都改成「取候选栏首词」，第一码唯一、简繁转换等场景会全部出错。
 *
 * @param committed 引擎本次提交的词
 * @param displayedCandidates 按键前候选栏展示的词（与用户所见完全一致）
 * @param isAsciiMode 英文模式不改写：候选含中文时才重排，引擎提交即所见
 * @param preKeyCodeLength 按键前的编码长度；小于满码长度说明不是顶屏，原样返回
 * @return 实际应上屏的词
 */
internal fun resolveAutoCommitTop(
    committed: String,
    displayedCandidates: List<String>,
    isAsciiMode: Boolean,
    preKeyCodeLength: Int,
): String {
    if (isAsciiMode || committed.isEmpty()) return committed
    if (preKeyCodeLength < PUSH_OUT_MIN_CODE_LENGTH) return committed
    if (committed !in displayedCandidates) return committed
    val top = displayedCandidates.firstOrNull().orEmpty()
    if (top.isEmpty() || top == committed) return committed
    return top
}

/**
 * 顶屏判定用的最小满码长度。
 *
 * 内置方案（小鹤/五笔系）的 `speller/max_code_length` 都是 4，再长的编码（`os…` 扩展、
 * 自定义短语）不参与顶屏。这里只用来区分「顶屏」与「更长编码的新提交」，
 * 取 4 是保守下界：宁可漏对齐（保持引擎原样），也不误把没打完的词顶出去。
 */
internal const val PUSH_OUT_MIN_CODE_LENGTH = 4

/**
 * 本次上屏**实际消费的编码**（用于词频记账）。
 *
 * 两种情形必须分开，否则词频挂到错的编码上、排序永远不更新（用户反馈
 * 「排序后没有更新状态」）：
 * - 编码未满（`preKeyInput` 短于满码）：本键是编码的最后一码，消费的是 `preKey + 本键`；
 * - 编码已满：本键触发**顶字上屏**，被顶出去的是**上一键就已成型的整段编码**，
 *   本键的字符属于新一段编码，不能拼进去（拼进去会记成 5 码 `hckja`，
 *   而真实编码是 4 码 `hckj`，该编码的词频始终涨不上去）。
 *
 * ## ⚠️ 改这个函数前先读
 *
 * 返回值直接决定词频记在哪个编码上，改错的后果是**静默的**：候选照常出，
 * 只是顺序永远不按使用频次变化（用户反馈「用了不更新排序」）。
 * - 别为了「补齐」把满码时的本键也拼进去 —— 那一键属于下一段编码；
 * - 别返回 `null` 表示「不知道」以外的语义：调用方会回退 UI 快照编码，
 *   而顶屏瞬间 UI 快照**少一个键**，于是又回到记错编码的老问题。
 *
 * @param preKeyInput 按键前的引擎编码
 * @param key 本键字符（非单字符键传空串）
 * @return 实际消费的编码；无法判定时返回 null（调用方回退 UI 快照）
 */
internal fun resolveConsumedCode(preKeyInput: String, key: String): String? {
    if (preKeyInput.isEmpty()) return key.ifEmpty { null }
    if (preKeyInput.length >= PUSH_OUT_MIN_CODE_LENGTH) return preKeyInput
    if (key.length != 1) return preKeyInput
    return preKeyInput + key.lowercase()
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
