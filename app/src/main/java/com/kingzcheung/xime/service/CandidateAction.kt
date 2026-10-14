package com.kingzcheung.xime.service

/**
 * 候选词变换后单个显示候选的上屏动作（与 CandidateState.candidates 平行）。
 *
 * - [engineIndex] >= 0：引擎候选引用（插件可能仅覆盖了显示注释），点击走引擎选词
 * - [engineIndex] < 0：自有候选，点击直接上屏 [commitText]（绕过引擎，所见即所得）
 * - [command] 非空：命令候选（组合键的「重新部署」），点选执行命令而不上屏文本
 *
 * 空列表 = 纯引擎语义（显示 index 即引擎 index），所有既有路径零影响。
 */
data class CandidateAction(
    val engineIndex: Int,
    val commitText: String,
    /** 非空表示这是一个**命令**候选（组合键）：点选执行命令而不上屏文本。 */
    val command: String? = null,
    /**
     * 是否来自组合键（keyboard.combos）。
     *
     * 组合键候选虽然也绕过引擎直接上屏，但**不依赖引擎组合态存在**：
     * 触发码（如 orq）可能完全不在 Rime 词库里，引擎没有候选是常态，
     * 不能沿用插件候选那条「引擎无组合就回落原生路径」的防御。
     */
    val fromCombo: Boolean = false,
) {
    val isPluginCandidate: Boolean get() = engineIndex < 0

    /** 是否为命令候选（组合键的「重新部署」这类）。 */
    val isCommand: Boolean get() = !command.isNullOrEmpty()

    companion object {
        fun engine(index: Int) = CandidateAction(engineIndex = index, commitText = "")
        fun plugin(text: String) = CandidateAction(engineIndex = -1, commitText = text)

        /** 组合键的文本候选：直接上屏求值结果，不依赖引擎组合态。 */
        fun combo(text: String) = CandidateAction(engineIndex = -1, commitText = text, fromCombo = true)

        /** 组合键的命令候选：点选执行 [name]，不上屏文本。 */
        fun command(name: String) =
            CandidateAction(engineIndex = -1, commitText = "", command = name, fromCombo = true)
    }
}
