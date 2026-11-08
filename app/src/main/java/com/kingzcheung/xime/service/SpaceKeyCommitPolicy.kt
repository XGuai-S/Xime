package com.kingzcheung.xime.service

/**
 * 空格键上屏决策（纯逻辑，便于单测）。
 *
 * 空格在空闲态有三种可能的上屏对象，优先级固定：
 * 1. 剪贴板候选栏（[shouldSpaceCommitClipboard]）：空格 = 上屏第一条，文本走粘贴、
 *    图片走发送，不插入空格字符；
 * 2. 验证码（[shouldSpaceCommitAssociation] 的 `firstAssociationIsOtp` 分支）：候选栏
 *    借用联想通道展示，但它不是联想词——空格上屏不受「空格上屏联想候选」开关与
 *    英文模式限制，否则默认设置下按空格只会打出一个空格；
 * 3. 普通联想词：仍按原开关（默认关闭）且仅中文模式生效，保持既有行为。
 */

/** 剪贴板候选栏在展示且有条目时，空格应上屏第一条而非空格字符。 */
internal fun shouldSpaceCommitClipboard(
    isShowingClipboard: Boolean,
    clipboardItemCount: Int,
): Boolean = isShowingClipboard && clipboardItemCount > 0

/**
 * 空格是否应上屏联想通道里的第一个候选。
 *
 * @param isComposing      处于组字态（走 Rime 选词，不适用本判断）
 * @param associationCount 联想候选数量
 * @param firstAssociationIsOtp 第一项是待用验证码（短信/通知收集）
 * @param isAsciiMode      英文模式（普通联想不参与空格上屏）
 * @param spaceCommitAssociationEnabled 用户设置「空格上屏联想候选」
 */
internal fun shouldSpaceCommitAssociation(
    isComposing: Boolean,
    associationCount: Int,
    firstAssociationIsOtp: Boolean,
    isAsciiMode: Boolean,
    spaceCommitAssociationEnabled: Boolean,
): Boolean {
    if (isComposing) return false
    if (associationCount <= 0) return false
    if (firstAssociationIsOtp) return true
    return !isAsciiMode && spaceCommitAssociationEnabled
}
