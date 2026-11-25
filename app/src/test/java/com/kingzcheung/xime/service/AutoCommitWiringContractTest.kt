package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶屏对齐与词频编码记账的**接线**契约。
 *
 * 纯函数本身在 [CandidateDisplayRulesTest] 里钉住了行为；这里守的是另一件事：
 * 生产代码必须真的**调用**它们。两处修复都曾被写成「函数在、没人调」或
 * 「按旧口径拼编码」，用户侧表现分别是「顶屏顶次选」和「排序不更新」——
 * 而这种退化不会让任何单测变红，只能靠源码契约拦住。
 */
class AutoCommitWiringContractTest {

    private val router = RepoFiles.mainKotlinSources
        .single { it.name == "ImeKeyRouter.kt" }
        .readText()
        .replace("\r\n", "\n")

    /** 主键路径（handleNormalEngineKey 的引擎提交分支）。 */
    private val normalKeyBranch = router.substringAfter("private suspend fun handleNormalEngineKey(")
        .substringBefore("private suspend fun handleEnterKey(")

    /** 工具面板/搜索框路径。 */
    private val toolPanelBranch = router.substringAfter("private fun handleToolPanelKey(")
        .substringBefore("private fun handleQuickSendFormKey(")

    // ---------- 顶屏对齐 ----------

    @Test
    fun `主键路径必须用候选栏首词对齐引擎提交词`() {
        assertTrue(
            "引擎自动上屏必须经过 resolveAutoCommitTop —— 否则引擎顺序的首选会顶掉用户看到的次选",
            normalKeyBranch.contains("resolveAutoCommitTop("),
        )
        assertTrue(
            "顶屏对齐的候选必须是按键前的快照",
            normalKeyBranch.contains("displayedCandidates = displayedBeforeKey"),
        )
        assertTrue(
            "上屏必须用对齐后的词，而不是直接提交引擎返回值",
            normalKeyBranch.contains("service.commitText(committedWord)"),
        )
        assertTrue(
            "词选中标记也必须记对齐后的词",
            normalKeyBranch.contains("markCandidateSelection(committedWord, commitCodeHint)"),
        )
    }

    @Test
    fun `工具面板路径与主路径同口径对齐顶屏`() {
        assertTrue(
            "面板/搜索态同样会触发满码顶屏，必须同样对齐",
            toolPanelBranch.contains("resolveAutoCommitTop("),
        )
        assertTrue(
            "面板路径也不能直接提交引擎返回值",
            toolPanelBranch.contains("service.commitText(committedWord)"),
        )
    }

    // ---------- 词频记账编码 ----------

    @Test
    fun `上屏编码必须经 resolveConsumedCode 判定 不能直接拼接`() {
        assertTrue(
            "满码顶字时消费的是上一键的编码，必须经 resolveConsumedCode 判定",
            normalKeyBranch.contains("resolveConsumedCode(preKeyInput, key)"),
        )
        assertTrue(
            "面板路径同口径",
            toolPanelBranch.contains("resolveConsumedCode(preKeyInput, key)"),
        )
        // 旧口径（恒拼接）必须彻底消失：它会把 4 码记成 5 码。
        // 只看**可执行代码**，跳过注释——警示注释里会引用旧写法作为反面例子。
        for ((name, branch) in listOf("主键" to normalKeyBranch, "面板" to toolPanelBranch)) {
            val codeOnly = branch.lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
            assertFalse(
                "$name 路径不得再用 preKeyInput + key.lowercase() 直接拼编码",
                codeOnly.contains("preKeyInput + key.lowercase()"),
            )
            assertFalse(
                "$name 路径不得再把 commitCodeHint 恒赋为拼接结果",
                codeOnly.contains("commitCodeHint = preKeyInput +"),
            )
        }
    }

    // ---------- 边界 ----------

    @Test
    fun `顶屏对齐用的快照必须取自按键之前`() {
        // displayedBeforeKey 必须在 processKeyAndGetResult 之前赋值：
        // 放在之后拿到的是新一屏（已经换过的候选），对齐就失去意义。
        val snapshotAt = normalKeyBranch.indexOf("val displayedBeforeKey = ")
        val processAt = normalKeyBranch.indexOf("processKeyAndGetResult(")
        assertTrue("找不到 displayedBeforeKey 快照", snapshotAt >= 0)
        assertTrue("找不到 processKeyAndGetResult", processAt >= 0)
        assertTrue(
            "顶屏候选快照必须早于 processKey —— 之后读到的是新一屏",
            snapshotAt < processAt,
        )
    }

    @Test
    fun `两个判定函数各只有一处实现 避免分叉`() {
        val sources = RepoFiles.mainKotlinSources
        val filesWithAutoCommitTop = sources.filter { it.readText().contains("fun resolveAutoCommitTop(") }
        val filesWithConsumedCode = sources.filter { it.readText().contains("fun resolveConsumedCode(") }
        assertEquals("resolveAutoCommitTop 应只在 CandidateDisplayRules.kt 中实现", 1, filesWithAutoCommitTop.size)
        assertEquals("resolveConsumedCode 应只在 CandidateDisplayRules.kt 中实现", 1, filesWithConsumedCode.size)
        assertEquals("CandidateDisplayRules.kt", filesWithAutoCommitTop.single().name)
        assertEquals("CandidateDisplayRules.kt", filesWithConsumedCode.single().name)
    }
}