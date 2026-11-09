package com.kingzcheung.xime.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预编辑文本合并逻辑测试
 *
 * 验证 mergePartialCommitText 在各种场景下正确拼接 partial commit 文本与 RIME 返回的 displayText。
 * 核心问题：startsWith 无法检测部分重叠，导致重复拼接（如 "测披" + "披h" → "测披披h"）。
 */
class PreeditMergeTest {

    // ── 核心 bug 场景 ──

    @Test
    fun `partial commit text suffix overlaps with display text prefix`() {
        // 场景：输入序列"23744" → 选"测" → 选"pi" → 选"披"
        // t9PartialCommitTexts = ["测", "披"], displayText = "披h"
        // 期望："测披h"，实际（bug）："测披披h"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("测", "披"),
            displayText = "披h"
        )
        assertEquals("测披h", result)
    }

    @Test
    fun `single char suffix overlap`() {
        // partialText = "abc", displayText = "bcd" → 期望 "abcd"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("abc"),
            displayText = "bcd"
        )
        assertEquals("abcd", result)
    }

    @Test
    fun `multi char suffix overlap`() {
        // partialText = "你好世界", displayText = "世界和平" → 期望 "你好世界和平"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("你好", "世界"),
            displayText = "世界和平"
        )
        assertEquals("你好世界和平", result)
    }

    // ── startsWith 完全匹配（不应重复拼接）──

    @Test
    fun `display text starts with partial text should not duplicate`() {
        // partialText = "测披", displayText = "测披h" → 期望 "测披h"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("测", "披"),
            displayText = "测披h"
        )
        assertEquals("测披h", result)
    }

    @Test
    fun `single partial starts with display text should not duplicate`() {
        // partialText = "测", displayText = "测pi h" → 期望 "测pi h"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("测"),
            displayText = "测pi h"
        )
        assertEquals("测pi h", result)
    }

    // ── 无重叠场景 ──

    @Test
    fun `no overlap should prepend all partial text`() {
        // partialText = "测", displayText = "shi" → 期望 "测shi"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("测"),
            displayText = "shi"
        )
        assertEquals("测shi", result)
    }

    @Test
    fun `no overlap multiple partials`() {
        // partialText = "你好", displayText = "world" → 期望 "你好world"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("你", "好"),
            displayText = "world"
        )
        assertEquals("你好world", result)
    }

    // ── 边界场景 ──

    @Test
    fun `empty partial texts returns display text as is`() {
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = emptyList(),
            displayText = "hello"
        )
        assertEquals("hello", result)
    }

    @Test
    fun `empty display text returns partial text as is`() {
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("测"),
            displayText = ""
        )
        assertEquals("测", result)
    }

    @Test
    fun `both empty returns empty`() {
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = emptyList(),
            displayText = ""
        )
        assertEquals("", result)
    }

    @Test
    fun `full overlap display text is substring of partial text`() {
        // partialText = "测披", displayText = "披" → 期望 "测披"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("测", "披"),
            displayText = "披"
        )
        assertEquals("测披", result)
    }

    @Test
    fun `partial text equals display text`() {
        // partialText = "测", displayText = "测" → 期望 "测"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("测"),
            displayText = "测"
        )
        assertEquals("测", result)
    }

    // ── Full commit 文本合并场景 ──
    //
    // 当 RIME 执行 full commit 时，commit() 返回的文本可能仅包含最近 partial commit 的部分，
    // 不包含更早的 partial commit 文本。需要通过 mergePartialCommitText 确保完整拼接。

    @Test
    fun `full commit with partial overlap - ce pi he produces ce pi he`() {
        // 场景：输入"23744" → 选"测"(partial) → 选"pi" → 选"披"(partial) → 选"和"(full commit)
        // t9PartialCommitTexts = ["测", "披"], RIME committedText = "披和"
        // 期望上屏："测披和"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("测", "披"),
            displayText = "披和"
        )
        assertEquals("测披和", result)
    }

    @Test
    fun `full commit with no overlap in committed text`() {
        // 场景：t9PartialCommitTexts = ["测", "披"], RIME committedText = "和"（不含任何 partial 文本）
        // 期望上屏："测披和"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("测", "披"),
            displayText = "和"
        )
        assertEquals("测披和", result)
    }

    @Test
    fun `full commit with single partial and overlap`() {
        // 场景：t9PartialCommitTexts = ["测"], RIME committedText = "测和"
        // 期望上屏："测和"（不重复）
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("测"),
            displayText = "测和"
        )
        assertEquals("测和", result)
    }

    @Test
    fun `full commit with empty partial texts returns committed text as is`() {
        // 无 partial commit 时，直接使用 RIME committedText
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = emptyList(),
            displayText = "你好"
        )
        assertEquals("你好", result)
    }

    @Test
    fun `space commit merges earlier partial commit ce with candidate shi`() {
        // 场景：T9 输入"23744" → 右侧选"策"(partial) → 空格上屏第一候选"是"
        // t9PartialCommitTexts = ["策"], RIME committedText = "是"
        // 期望上屏："策是"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("策"),
            displayText = "是"
        )
        assertEquals("策是", result)
    }

    // ── 以下测试从 PreeditMergeHelperTest.kt 合并至此 ──

    @Test
    fun `merge multiple partial texts with empty entries joins them in order`() {
        // partialTexts = ["策", ""], displayText = "ce" → 期望 "策ce"
        val result = PreeditMergeHelper.mergePartialCommitText(
            partialTexts = listOf("策", ""),
            displayText = "ce"
        )
        assertEquals("策ce", result)
    }
}