package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「重复上一次输入」必须只重复**最近一笔**上屏内容，而不是整段历史。
 *
 * 回归背景（真机反馈）：换行键上滑最初接的是 `PredictionManager.lastCommittedText`，
 * 那是最近 25 字的滚动上下文缓冲区；上滑会把之前输入过的所有内容再打一遍。
 * 正确数据源是 `lastCommitChunk`——每次 `commitText` 单独记录的一笔。
 *
 * 这里的断言分两层：
 * 1. 状态机语义（纯逻辑）：单次记录只保留最近一笔、退格同步缩短、换会话作废；
 * 2. 接线：`repeatLastInput` 用 `lastCommitChunk` 而不是 `lastCommittedText`。
 */
class RepeatLastCommitTest {

    private val serviceSource: String =
        RepoFiles.mainKotlinSources.single { it.name == "XimeInputMethodService.kt" }.readText()

    @Test
    fun `重复上一次输入用的是单次上屏记录`() {
        val body = serviceSource.substringAfter("override fun repeatLastInput()")
            .substringBefore("override fun dispatchKey(")
        assertTrue(
            "重复动作必须读 lastCommitChunk（单次上屏），不能读 lastCommittedText（滚动上下文）",
            body.contains("predictionManager.lastCommitChunk"),
        )
        assertTrue(
            "重复动作不应再读 lastCommittedText：那是 25 字上下文，会把前文重复上屏",
            !body.contains("lastCommittedText"),
        )
    }

    @Test
    fun `单次上屏记录在中英两模式都维护`() {
        // 英文模式下 predictionManager.lastCommittedText 不维护，
        // 若单次记录也只放在 isChineseMode 分支里，英文键盘上滑就永远没反应。
        val silentCommit = serviceSource.substringAfter("internal fun commitTextSilently(")
            .substringBefore("private fun rememberCommitChunk(")
        assertTrue(
            "单次上屏记录必须在中英两模式下都执行（不能在 isChineseMode 分支内）",
            silentCommit.contains("rememberCommitChunk(text)"),
        )
        val chunkRecorder = serviceSource.substringAfter("private fun rememberCommitChunk(")
        assertTrue(
            "单次记录应委托给 PredictionManager.appendCommittedChunk",
            chunkRecorder.contains("predictionManager.appendCommittedChunk(text)"),
        )
    }

    @Test
    fun `退格同步缩短待重复内容`() {
        // 行为语义：删掉刚上屏的最后一个字后，上滑不应把它又打出来
        val manager = PredictionManager(
            context = org.mockito.kotlin.mock(),
            serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            onPredictionResult = {},
        )
        manager.appendCommittedChunk("你好世界")
        assertEquals("你好世界", manager.lastCommitChunk)
        manager.deleteLastChar()
        assertEquals("你好世", manager.lastCommitChunk)
    }

    @Test
    fun `单次记录只保留最近一笔而不累积`() {
        val manager = PredictionManager(
            context = org.mockito.kotlin.mock(),
            serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            onPredictionResult = {},
        )
        manager.appendCommittedChunk("第一笔")
        manager.appendCommittedChunk("第二笔")
        assertEquals(
            "上滑重复的必须只是最近一笔，不能把两笔拼在一起",
            "第二笔", manager.lastCommitChunk,
        )
    }

    @Test
    fun `换输入会话后作废待重复内容`() {
        val manager = PredictionManager(
            context = org.mockito.kotlin.mock(),
            serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            onPredictionResult = {},
        )
        manager.appendCommittedChunk("上一个编辑框")
        manager.clearCommittedText()
        assertTrue(
            "切换输入框后旧内容不可重复，应清空",
            manager.lastCommitChunk.isEmpty(),
        )
    }

    // ── 有候选未上屏时，上滑改为上屏候选 ──

    @Test
    fun `有候选未上屏时应上屏候选而不是重复上一笔`() {
        // 用户正在打编码，候选栏上摆着的才是他想要的东西；
        // 此时重复上一笔对他没有意义
        assertTrue(
            RepeatCommitPolicy.shouldCommitFirstCandidate(isComposing = true, candidateCount = 3),
        )
    }

    @Test
    fun `组合态刚起还没出词时不上屏候选`() {
        // 引擎还没出词，没有候选可上屏；此时应落回重复上一笔
        assertFalse(
            RepeatCommitPolicy.shouldCommitFirstCandidate(isComposing = true, candidateCount = 0),
        )
    }

    @Test
    fun `非组合态的候选栏不由重复键代劳`() {
        // 计算器 / 大写金额 / 剪贴板的候选栏各有自己的点选语义，
        // 不能因为「候选栏有内容」就让重复键去选词
        assertFalse(
            RepeatCommitPolicy.shouldCommitFirstCandidate(isComposing = false, candidateCount = 3),
        )
    }

    @Test
    fun `重复键优先上屏第一个候选并再重复一次`() {
        val body = serviceSource.substringAfter("override fun repeatLastInput()")
            .substringBefore("override fun dispatchKey(")
        assertTrue(
            "未接入候选优先策略",
            body.contains("RepeatCommitPolicy.shouldCommitFirstCandidate("),
        )
        assertTrue("必须上屏第一个候选", body.contains("keyRouter.selectCandidate(0)"))
        // 候选分支必须排在「重复上一笔」分支之前，否则候选永远轮不到
        val repeatBranch = "val lastText = predictionManager.lastCommitChunk"
        val selectAt = body.indexOf("keyRouter.selectCandidate(0)")
        val repeatAt = body.indexOf(repeatBranch)
        assertTrue("候选分支必须在重复分支之前", selectAt >= 0 && repeatAt > selectAt)
        // 候选分支：上屏候选后必须再补一次重复（一次上滑 = 该候选上屏两次），
        // 并在分支内 return 收口，不能落到下面的「重复上一笔」分支
        val candidateBranch = body.substring(selectAt, repeatAt)
        assertTrue(
            "上屏候选后必须再重复上屏一次（一次上滑打两遍）",
            candidateBranch.contains("commitText("),
        )
        assertTrue("候选分支必须 return 收口", candidateBranch.contains("return"))
    }
}
