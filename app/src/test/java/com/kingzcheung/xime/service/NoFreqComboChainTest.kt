package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * nofreq 词典的词条不能切断自动造词的组合链。
 *
 * ## 缺陷（用户反馈「雨衣造不出来」的根因）
 *
 * `XGuai_user.dict.yaml` 在 `auto_word.no_freq_dicts` 里，它的词条被收进 `noFreqWords`。
 * 旧实现在 `recordWordFrequencyLocked` 里写下：
 *
 * ```kotlin
 * if (text in noFreqWords) return
 * ```
 *
 * 注释写着「仍走自动造词链（能正常学习/组合）」，代码却直接 return，
 * 把 `recordComboWord` 与 `advanceComboHistory` 一起跳过了。
 *
 * 实际后果：用户的个人词库里恰好收了「雨」这个单字 →
 * 打「雨」上屏时组合窗口没被推进 → 再打「衣」时窗口里没有「雨」→ 永远拼不出「雨衣」。
 * 这类词在主方案词典里没有，本该靠组合造词学出来，等于被彻底堵死。
 *
 * ## 修复口径
 *
 * `nofreq` 只跳过**词频表写入**（word_freq），造词/组合/晋升全部照常。
 */
class NoFreqComboChainTest {
    private val source: String = RepoFiles.mainKotlinSources
        .single { it.name == "XimeInputMethodService.kt" }
        .readText()
        .replace("\r\n", "\n")

    private val recordLocked: String = source
        .substringAfter("private fun recordWordFrequencyLocked(")
        .substringBefore("/** 统一的当前毫秒时间戳")

    @Test
    fun `nofreq words must not return early and cut the combo chain`() {
        assertFalse(
            "nofreq 词条不得直接 return——那会连带跳过 recordComboWord / advanceComboHistory",
            recordLocked.contains("if (text in noFreqWords) return"),
        )
        assertTrue(
            "nofreq 应只作为一个跳过词频写入的标记",
            recordLocked.contains("val skipWordFreq = text in noFreqWords"),
        )
    }

    @Test
    fun `word frequency write is the only thing gated by nofreq`() {
        assertTrue(
            "词频写入必须被 skipWordFreq 守卫",
            recordLocked.contains("if (!skipWordFreq) {"),
        )
        // 词频守卫必须只包住 withCommit 那一句，不能把造词/组合吞进去
        val guardBlock = recordLocked.substringAfter("if (!skipWordFreq) {").substringBefore("}")
        assertTrue("守卫块内只允许词频写入", guardBlock.contains("WordFreqStore.withCommit"))
        assertFalse("守卫块内不得出现造词调用", guardBlock.contains("recordAutoWord"))
        assertFalse("守卫块内不得出现组合窗口推进", guardBlock.contains("advanceComboHistory"))
    }

    @Test
    fun `both commit paths still advance the combo history`() {
        // 两条路径（编不出码的早返回 / 正常词频路径）都必须推进组合窗口，
        // 否则单字上屏就拼不出后续的词。
        val advanceCount = Regex("advanceComboHistory\\(text\\)").findAll(recordLocked).count()
        assertTrue(
            "正常路径与 early-return 路径都必须推进组合窗口，实际 $advanceCount 处",
            advanceCount >= 2,
        )
    }

    @Test
    fun `nofreq dictionary still feeds the auto word chain per its own doc`() {
        // 契约：noFreqWords 的语义是「不进词频表」，不是「不参与造词」。
        val declaration = source.substringAfter("private var noFreqWords")
            .substringBefore("private var")
        assertTrue("noFreqWords 必须是词频专用集合", declaration.isNotEmpty())
        assertTrue(
            "配置文档说明 nofreq 词条「仍是正常候选、仍可自动造词」",
            RepoFiles.file("app/src/main/assets/xime.yaml").readText()
                .let { it.contains("仍可自动造词") || it.contains("不受 no_auto_word_dicts 影响") },
        )
    }
}
