package com.kingzcheung.xime.ai.data

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 服务商凭证复用与文本润色开关的源码契约。
 */
class AiProviderReuseContractTest {
    private val pipelineEngine by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/ai/network/AiPipelineEngine.kt").readText()
    }
    private val uiScreen by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/ui/settings/AiSettingsScreen.kt").readText()
    }
    private val models by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/ai/data/Models.kt").readText()
    }
    private val composer by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/ai/ime/StreamingComposer.kt").readText()
    }

    @Test
    fun `stt fallback reuses any provider vault credential`() {
        assertTrue(
            "resolveEffectiveSttConfig 必须从 providerVault 通用复用凭证",
            pipelineEngine.contains("resolveSttFromVault")
        )
    }

    @Test
    fun `text polish switch exists in AI settings screen`() {
        assertTrue(
            "AI 设置页必须有文本润色开关",
            uiScreen.contains("文本润色") && uiScreen.contains("llmConfig.copy(enabled")
        )
    }

    @Test
    fun `llm disabled routes dictation to direct STT`() {
        assertTrue("LlmConfig.enabled 默认 true", models.contains("val enabled: Boolean = true"))
        assertTrue(
            "关闭润色时口述净写走 STT 直出",
            composer.contains("!settings.llmConfig.enabled")
        )
    }
}
