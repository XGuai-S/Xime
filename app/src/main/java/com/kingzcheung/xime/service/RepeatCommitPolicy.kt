package com.kingzcheung.xime.service

/**
 * 回车键上滑「重复」动作的决策。
 *
 * 拆成纯函数是为了让这条分支能被单测直接跑：真机上「上滑 → 候选上屏 → 重复」这条序列
 * 验证成本高，而它一旦接错（漏了组合态判断、或候选为空时也去选词），
 * 表现是候选被打错次数、或者按键完全没反应，很难从表面看出来。
 */
internal object RepeatCommitPolicy {

    /**
     * 此刻应当上屏第一个候选，而不是重复上一笔吗？
     *
     * 有候选未上屏时优先上屏候选：用户正在打编码，候选栏上摆着的才是他想要的东西，
     * 此时重复上一笔对他没有意义。命中后「重复」键会把这个候选上屏**两次**
     * （打上去一次、紧接着再重复一次），随后组合态清空、候选消失，
     * 之后的上滑自然落回「重复上一次上屏内容」分支。
     *
     * 两个条件缺一不可：
     * - [isComposing] 排除计算器 / 大写金额 / 剪贴板这类「候选栏有内容、但用户并非在打编码」
     *   的场景，那些模式下的候选点选各有自己的语义，不能由「重复」键代劳；
     * - [candidateCount] 排除组合态刚起、引擎还没出词的瞬间，此时没有候选可上屏。
     */
    fun shouldCommitFirstCandidate(isComposing: Boolean, candidateCount: Int): Boolean =
        isComposing && candidateCount > 0
}
