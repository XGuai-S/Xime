package com.kingzcheung.xime.ai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 服务商凭证复用逻辑单测：
 * 只配 AI 服务商 Key 时，语音转文本应能复用其密钥与接口。
 */
class SttProviderVaultReuseTest {

    @Test
    fun `stt reuses siliconflow credential from provider vault`() {
        val settings = AppSettings(
            sttConfig = SttConfig(apiKey = ""),
            providerVault = mapOf(
                UnifiedProviderCatalog.ID_SILICONFLOW to ProviderCredential(
                    providerId = UnifiedProviderCatalog.ID_SILICONFLOW,
                    apiKey = "sk-test-siliconflow"
                )
            )
        )
        val stt = UnifiedProviderCatalog.resolveSttFromVault(settings)
        assertNotNull("应从 providerVault 复用硅基流动凭证", stt)
        assertEquals("sk-test-siliconflow", stt!!.apiKey)
        assertTrue("baseUrl 应为硅基流动默认地址", stt.baseUrl.contains("siliconflow"))
    }

    @Test
    fun `stt uses provider specific sttApiKey when present`() {
        val settings = AppSettings(
            sttConfig = SttConfig(apiKey = ""),
            providerVault = mapOf(
                UnifiedProviderCatalog.ID_VOLCENGINE to ProviderCredential(
                    providerId = UnifiedProviderCatalog.ID_VOLCENGINE,
                    apiKey = "llm-key",
                    sttApiKey = "speech-token"
                )
            )
        )
        val stt = UnifiedProviderCatalog.resolveSttFromVault(settings)
        assertNotNull(stt)
        assertEquals("语音专用 Key 优先", "speech-token", stt!!.apiKey)
    }

    @Test
    fun `no credential returns null`() {
        val settings = AppSettings(sttConfig = SttConfig(apiKey = ""))
        assertNull("无任何服务商凭证时返回 null", UnifiedProviderCatalog.resolveSttFromVault(settings))
    }
}
