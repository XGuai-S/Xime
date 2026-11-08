package com.kingzcheung.xime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空格键上屏决策：
 * - 剪贴板候选栏展示中 → 空格上屏第一条（不插空格字符）
 * - 验证码（借联想通道展示）→ 空格总能上屏，不受「空格上屏联想候选」开关与英文模式影响
 * - 普通联想词 → 仍按原开关（默认关闭）+ 仅中文模式
 */
class SpaceKeyCommitPolicyTest {

    @Test
    fun `剪贴板展示且有条目时空格上屏剪贴板`() {
        assertTrue(shouldSpaceCommitClipboard(isShowingClipboard = true, clipboardItemCount = 3))
    }

    @Test
    fun `剪贴板未展示时空格不接管`() {
        assertFalse(shouldSpaceCommitClipboard(isShowingClipboard = false, clipboardItemCount = 3))
    }

    @Test
    fun `剪贴板展示但条目为空时空格保留空格字符`() {
        assertFalse(shouldSpaceCommitClipboard(isShowingClipboard = true, clipboardItemCount = 0))
    }

    @Test
    fun `验证码在开关关闭且英文模式时仍可空格上屏`() {
        assertTrue(
            shouldSpaceCommitAssociation(
                isComposing = false,
                associationCount = 1,
                firstAssociationIsOtp = true,
                isAsciiMode = true,
                spaceCommitAssociationEnabled = false,
            )
        )
    }

    @Test
    fun `普通联想在开关关闭时不参与空格上屏`() {
        assertFalse(
            shouldSpaceCommitAssociation(
                isComposing = false,
                associationCount = 2,
                firstAssociationIsOtp = false,
                isAsciiMode = false,
                spaceCommitAssociationEnabled = false,
            )
        )
    }

    @Test
    fun `普通联想在开关开启且中文模式时空格上屏联想词`() {
        assertTrue(
            shouldSpaceCommitAssociation(
                isComposing = false,
                associationCount = 2,
                firstAssociationIsOtp = false,
                isAsciiMode = false,
                spaceCommitAssociationEnabled = true,
            )
        )
    }

    @Test
    fun `英文模式下普通联想不上屏`() {
        assertFalse(
            shouldSpaceCommitAssociation(
                isComposing = false,
                associationCount = 2,
                firstAssociationIsOtp = false,
                isAsciiMode = true,
                spaceCommitAssociationEnabled = true,
            )
        )
    }

    @Test
    fun `组字态由 Rime 选词接管`() {
        assertFalse(
            shouldSpaceCommitAssociation(
                isComposing = true,
                associationCount = 2,
                firstAssociationIsOtp = true,
                isAsciiMode = false,
                spaceCommitAssociationEnabled = true,
            )
        )
    }

    @Test
    fun `无联想候选时空格保持空格字符`() {
        assertFalse(
            shouldSpaceCommitAssociation(
                isComposing = false,
                associationCount = 0,
                firstAssociationIsOtp = false,
                isAsciiMode = false,
                spaceCommitAssociationEnabled = true,
            )
        )
    }
}
