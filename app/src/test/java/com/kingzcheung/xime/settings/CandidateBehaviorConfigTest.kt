package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import com.kingzcheung.xime.ui.keyboard.punctuationCandidateIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第六项：候选词行为（第三项第 2 条标点直选、第五项第 1 条清空按键）的配置定义。
 *
 * 两条行为原先硬编码在 UI 里，现统一写入 `xime.yaml keyboard.candidates`：
 * - `show_clear_button`：编码态候选栏「清空」按键显隐；
 * - `shortcuts`：标点键直选候选下标（0 = 首选），缺省 逗号 → 1（次选）、句号 → 2（三选）。
 *
 * 默认值必须与改动前的硬编码行为逐字一致，否则「只加配置」就变成了改行为。
 */
class CandidateBehaviorConfigTest {

    @Test
    fun `内置默认值与改动前的硬编码行为一致`() {
        val defaults = KeyboardCandidatesConfig()
        assertTrue("清空按键默认应显示", defaults.showClearButton)
        assertEquals(1, defaults.shortcutIndex("comma"))
        assertEquals(2, defaults.shortcutIndex("period"))
        assertNull("未配置的键不做直选", defaults.shortcutIndex("space"))
    }

    @Test
    fun `解析 keyboard_candidates 段的两个字段`() {
        val parsed = KeysConfigHelper.parseKeyboardCandidatesYamlText(
            """
            keyboard:
              candidates:
                show_clear_button: false
                shortcuts:
                  comma: 0
                  period: 4
            """.trimIndent()
        )
        assertEquals(false, parsed?.showClearButton)
        assertEquals(0, parsed?.shortcutIndex("comma"))
        assertEquals(4, parsed?.shortcutIndex("period"))
    }

    @Test
    fun `未配置 candidates 段时返回 null 交由上层回退默认`() {
        assertNull(
            KeysConfigHelper.parseKeyboardCandidatesYamlText(
                """
                keyboard:
                  key:
                    corner_radius: 8
                """.trimIndent()
            )
        )
    }

    @Test
    fun `只配一个字段时其余字段回退默认`() {
        val parsed = KeysConfigHelper.parseKeyboardCandidatesYamlText(
            """
            keyboard:
              candidates:
                show_clear_button: false
            """.trimIndent()
        )
        assertEquals(false, parsed?.showClearButton)
        assertEquals(
            "未写 shortcuts 应沿用默认直选映射",
            KeyboardCandidatesConfig.DEFAULT_SHORTCUTS,
            parsed?.shortcuts,
        )
    }

    @Test
    fun `custom 只覆盖写出的键其余键沿用内置`() {
        val merged = KeysConfigHelper.mergeCandidatesConfigs(
            custom = KeyboardCandidatesPartial(shortcuts = linkedMapOf("comma" to 0)),
            builtIn = KeyboardCandidatesPartial(shortcuts = linkedMapOf("comma" to 1, "period" to 2)),
        )
        assertEquals(0, merged.shortcutIndex("comma"))
        assertEquals("未覆盖的 period 应保留内置值", 2, merged.shortcutIndex("period"))
    }

    @Test
    fun `非法下标按未配置处理`() {
        val config = KeyboardCandidatesConfig(shortcuts = linkedMapOf("comma" to -1, "period" to 0))
        assertNull("负数下标应视为未配置", config.shortcutIndex("comma"))
        assertEquals(0, config.shortcutIndex("period"))
    }

    @Test
    fun `候选数不足该下标时退回标点输入`() {
        val shortcuts = mapOf("comma" to 1, "period" to 2)
        // 只有 1 个候选：逗号（下标 1）与句号（下标 2）都选不到，返回 null
        assertNull(punctuationCandidateIndex("comma", 1, shortcuts))
        assertNull(punctuationCandidateIndex("period", 1, shortcuts))
        // 2 个候选：逗号能选到次选，句号仍不够
        assertEquals(1, punctuationCandidateIndex("comma", 2, shortcuts))
        assertNull(punctuationCandidateIndex("period", 2, shortcuts))
        // 3 个候选：两个都能选到
        assertEquals(1, punctuationCandidateIndex("comma", 3, shortcuts))
        assertEquals(2, punctuationCandidateIndex("period", 3, shortcuts))
    }

    @Test
    fun `未配置直选的键不参与候选直选`() {
        assertNull(punctuationCandidateIndex("space", 9, mapOf("comma" to 1)))
    }

    @Test
    fun `assets 内置 xime_yaml 已写入 candidates 段`() {
        val asset = RepoFiles.file("src/main/assets/xime.yaml").readText()
        assertTrue("内置配置缺少 keyboard.candidates 段", asset.contains("\n  candidates:"))
        assertTrue("缺少 show_clear_button", asset.contains("show_clear_button:"))
        assertTrue("缺少 shortcuts", asset.contains("shortcuts:"))
        // 解析内置资产必须真的读出默认行为，否则文档与实现会悄悄分叉
        val parsed = KeysConfigHelper.parseKeyboardCandidatesYamlText(asset)
        assertEquals(true, parsed?.showClearButton)
        assertEquals(1, parsed?.shortcutIndex("comma"))
        assertEquals(2, parsed?.shortcutIndex("period"))
    }
}
