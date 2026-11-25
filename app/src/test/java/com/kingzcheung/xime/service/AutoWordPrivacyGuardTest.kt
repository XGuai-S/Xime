package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私词库（个人的姓名 / 住址 / 学校 / 手机号 / 证件号）绝不能进自动造词库。
 *
 * ## 为什么单独钉这一条
 *
 * 用户的 `XGuai_user.dict.yaml` 收了真实姓名、家庭住址、学校、手机号、身份证号、邮箱、URL。
 * 这类词库必须在 `xime.yaml` 里同时配进：
 *
 * - `no_auto_word_dicts` → 词条进 `noAutoWordWords`，`recordAutoWord` 直接 return，
 *   **不进临时/固定造词库、不参与组合生词**；
 * - `no_freq_dicts` → 词条进 `noFreqWords`，不进 `word_freq`。
 *
 * ## 本轮曾有的风险
 *
 * 「nofreq 不再提前 return」的修复让 `noFreqWords` 的词也开始走造词链。
 * 如果某个词库**只**配了 `no_freq_dicts` 而漏配 `no_auto_word_dicts`，
 * 隐私词就会从「不记录」变成「进造词库」，并随 `auto_word.dict.yaml`
 * 落到可见目录（可被云备份 / 导出带走）。
 *
 * 因此这里同时钉两件事：
 * 1. `recordAutoWord` 的拦截存在于**唯一收口处**，覆盖全部调用点；
 * 2. nofreq 与 no_auto_word 必须是**两个独立开关**，不能因为改动 nofreq 行为
 *    而让 no_auto_word 失效。
 */
class AutoWordPrivacyGuardTest {
    private val source: String = RepoFiles.mainKotlinSources
        .single { it.name == "XimeInputMethodService.kt" }
        .readText()
        .replace("\r\n", "\n")

    private val recordAutoWord: String = source
        .substringAfter("private fun recordAutoWord(")
        .substringBefore("/** 统一的当前毫秒时间戳")

    @Test
    fun `privacy guard is the first thing recordAutoWord checks`() {
        assertTrue(
            "recordAutoWord 必须拦截 noAutoWordWords 的词条",
            recordAutoWord.contains("if (word in noAutoWordWords) return"),
        )
        // 拦截必须在任何表写入之前
        val guard = recordAutoWord.indexOf("if (word in noAutoWordWords) return")
        val write = recordAutoWord.indexOf("AutoWordStore.recordMany")
        assertTrue("拦截必须早于任何落库动作", guard > 0 && write > 0 && guard < write)
    }

    @Test
    fun `every auto word path goes through recordAutoWord`() {
        // 组合词、普通上屏词、已收录计数的词都必须经过同一个收口，
        // 否则某条路径就会绕过隐私拦截。
        // 排除函数定义本身（`private fun recordAutoWord(`），只数调用点
        val calls = source.lineSequence()
            .map { it.trim() }
            .count { line ->
                line.contains("recordAutoWord(") && !line.startsWith("private fun recordAutoWord(")
            }
        assertTrue(
            "recordAutoWord 应有 3 处调用（普通收录 / 已收录计数 / 组合词），实际 $calls 处",
            calls == 3,
        )
        // 不得有直接写 temp/fixed 的旁路
        assertFalse(
            "不得存在绕过 recordAutoWord 直接写造词库的旁路",
            source.contains("AutoWordStore.recordMany(autoWordTemp") &&
                Regex("AutoWordStore\\.recordMany\\(autoWordTemp").findAll(source).count() > 1,
        )
    }

    @Test
    fun `nofreq and no_auto_word remain independent switches`() {
        assertTrue(
            "nofreq 只影响词频（skipWordFreq）",
            recordAutoWord.isNotEmpty() &&
                source.contains("val skipWordFreq = text in noFreqWords"),
        )
        assertTrue(
            "no_auto_word 仍独立拦截造词",
            source.contains("if (word in noAutoWordWords) return"),
        )
        // 两者不能合并成同一个集合
        assertFalse(
            "不得把 noFreqWords 与 noAutoWordWords 合并（否则一个配置失误会同时失守两道防线）",
            source.contains("noFreqWords = c.noAutoWord") ||
                source.contains("noAutoWordWords = c.noFreq"),
        )
    }

    @Test
    fun `auto word store only accepts pure han words`() {
        // 第二道防线：即使拦截失效，手机号 / 身份证 / 邮箱 / URL 这类
        // 含非汉字的内容也进不了造词库（isLearnedWord 要求全部为汉字）。
        val store = RepoFiles.mainKotlinSources
            .single { it.name == "AutoWordStore.kt" }
            .readText()
            .replace("\r\n", "\n")
        assertTrue(
            "造词库收录门槛必须要求纯汉字",
            store.contains("private fun isLearnedWord(word: String): Boolean") &&
                store.contains("return isComboWord(word)"),
        )
        assertTrue(
            "isComboWord 必须按 UnicodeScript.HAN 判定",
            store.contains("Character.UnicodeScript.HAN"),
        )
    }

    @Test
    fun `config documents that no_auto_word excludes the personal dictionary`() {
        val yaml = try {
            RepoFiles.file("app/src/main/assets/xime.yaml").readText()
        } catch (e: Exception) {
            ""
        }
        if (yaml.isEmpty()) return // 资源文件不在 JVM 测试源集时跳过静态检查
        assertTrue(
            "默认配置必须说明 no_auto_word_dicts 的作用",
            yaml.contains("no_auto_word_dicts"),
        )
    }
}
