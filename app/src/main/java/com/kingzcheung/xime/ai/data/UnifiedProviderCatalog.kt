package com.kingzcheung.xime.ai.data

import com.kingzcheung.xime.ai.network.EndpointResolver

data class UnifiedProviderSpec(
    val id: String,
    val name: String,
    val badge: String,
    val summary: String,
    val defaultBaseUrl: String,
    val supportsStt: Boolean,
    val supportsLlm: Boolean,
    val supportsMultimodal: Boolean,
    val hasSeparateSpeechToken: Boolean = false,
    val supportsCustomBaseUrl: Boolean = false,
    val defaultSttModel: String = "",
    val defaultSttProtocol: SttProtocol = SttProtocol.OPENAI_HTTP,
    val defaultLlmModel: String = "",
    val defaultMultimodalModel: String = "",
    val defaultMultimodalProtocol: MultimodalProtocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
)

object UnifiedProviderCatalog {

    const val ID_GOOGLE = "google_gemini"
    const val ID_VOLCENGINE = "volcengine"
    const val ID_ALIYUN = "aliyun_bailian"
    const val ID_SILICONFLOW = "siliconflow"
    const val ID_DEEPSEEK = "deepseek"
    const val ID_CUSTOM = "custom_gateway"
    const val ID_LOCAL_SENSEVOICE = "local_sensevoice"

    val providers: List<UnifiedProviderSpec> = listOf(
        UnifiedProviderSpec(
            id = ID_GOOGLE,
            name = "Google Gemini",
            badge = "实测 0.5s 旗舰推荐",
            summary = "单 Key 通吃：Live 流式自带去口癖与改口合并 (~0.5s)，帮我写/快译自动调 Flash-Lite",
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            supportsStt = true,
            supportsLlm = true,
            supportsMultimodal = true,
            supportsCustomBaseUrl = true,
            defaultSttModel = "gemini-3.5-transcribe-live",
            defaultSttProtocol = SttProtocol.GEMINI_LIVE_WS,
            defaultLlmModel = "gemini-3.5-flash-lite",
            defaultMultimodalModel = "gemini-3.5-flash-lite",
            defaultMultimodalProtocol = MultimodalProtocol.GEMINI_NATIVE
        ),
        UnifiedProviderSpec(
            id = ID_ALIYUN,
            name = "阿里云百炼",
            badge = "国内直连单 Key",
            summary = "单个 sk-Key 同时驱动 Paraformer 实时流式识别与 Qwen 极速润色",
            defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            supportsStt = true,
            supportsLlm = true,
            supportsMultimodal = false,
            defaultSttModel = "paraformer-realtime-v2",
            defaultSttProtocol = SttProtocol.ALIYUN_DASHSCOPE_WS,
            defaultLlmModel = "qwen-turbo-latest"
        ),
        UnifiedProviderSpec(
            id = ID_VOLCENGINE,
            name = "火山引擎豆包",
            badge = "国内直连流式",
            summary = "豆包同源 SeedASR 2.0 实时流式 + 方舟 Seed-2.1-Lite 极速关思考润色",
            defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            supportsStt = true,
            supportsLlm = true,
            supportsMultimodal = false,
            hasSeparateSpeechToken = true,
            defaultSttModel = "bigmodel",
            defaultSttProtocol = SttProtocol.VOLCENGINE_SAUC_WS,
            defaultLlmModel = "doubao-seed-2-1-lite-260915"
        ),
        UnifiedProviderSpec(
            id = ID_SILICONFLOW,
            name = "硅基流动",
            badge = "免费高速",
            summary = "国内直连免费 SenseVoiceSmall 语音识别 + Qwen 系列高速推理",
            defaultBaseUrl = "https://api.siliconflow.cn/v1",
            supportsStt = true,
            supportsLlm = true,
            supportsMultimodal = false,
            defaultSttModel = "FunAudioLLM/SenseVoiceSmall",
            defaultSttProtocol = SttProtocol.OPENAI_HTTP,
            defaultLlmModel = "Qwen/Qwen2.5-7B-Instruct"
        ),
        UnifiedProviderSpec(
            id = ID_DEEPSEEK,
            name = "DeepSeek",
            badge = "单 Key 闭环 / 润色标杆",
            summary = "单 Key 通吃：官方语音识别 (/v1/tools/asr) + 二阶段中文润色/快译 + ✦ 军师/帮写/问AI",
            defaultBaseUrl = "https://api.deepseek.com/v1",
            supportsStt = true,
            supportsLlm = true,
            supportsMultimodal = false,
            defaultSttModel = "deepseek-asr",
            defaultSttProtocol = SttProtocol.OPENAI_HTTP,
            defaultLlmModel = "deepseek-chat"
        ),
        UnifiedProviderSpec(
            id = ID_CUSTOM,
            name = "自定义 / 聚合网关",
            badge = "NewAPI / OpenAI / 本地",
            summary = "接入 OneAPI / NewAPI / OpenRouter / Groq / 智谱 GLM / 局域网 Ollama",
            defaultBaseUrl = "https://api.openai.com/v1",
            supportsStt = true,
            supportsLlm = true,
            supportsMultimodal = true,
            supportsCustomBaseUrl = true,
            defaultSttModel = "whisper-1",
            defaultSttProtocol = SttProtocol.OPENAI_HTTP,
            defaultLlmModel = "gpt-4o-mini",
            defaultMultimodalModel = "gpt-4o-mini-audio-preview",
            defaultMultimodalProtocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
        ),
        UnifiedProviderSpec(
            id = ID_LOCAL_SENSEVOICE,
            name = "本地 / 开源 SenseVoice",
            badge = "离线 · 自建源码",
            summary = "接自建的开源 SenseVoice 服务（FunASR 封装 / sherpa-onnx），OpenAI 兼容 /v1/audio/transcriptions；填本机或局域网地址即可离线识别",
            defaultBaseUrl = "http://127.0.0.1:8000/v1",
            supportsStt = true,
            supportsLlm = false,
            supportsMultimodal = false,
            supportsCustomBaseUrl = true,
            defaultSttModel = "FunAudioLLM/SenseVoiceSmall",
            defaultSttProtocol = SttProtocol.OPENAI_HTTP
        )
    )

    val sttSuggestedModelsByProvider: Map<String, List<String>> = mapOf(
        ID_GOOGLE to listOf("gemini-3.5-transcribe-live"),
        ID_ALIYUN to listOf("paraformer-realtime-v2", "fun-asr-realtime", "gummy-realtime-v1"),
        ID_VOLCENGINE to listOf("bigmodel"),
        ID_SILICONFLOW to listOf("FunAudioLLM/SenseVoiceSmall", "TeleAI/TeleSpeechASR"),
        ID_DEEPSEEK to listOf("deepseek-asr"),
        ID_CUSTOM to listOf("gpt-4o-mini-transcribe", "whisper-large-v3-turbo", "whisper-1")
    )

    val llmSuggestedModelsByProvider: Map<String, List<String>> = mapOf(
        ID_GOOGLE to listOf("gemini-3.5-flash-lite", "gemini-2.5-flash", "gemini-3.6-flash", "gemini-3.8-flash"),
        ID_ALIYUN to listOf("qwen-turbo-latest", "qwen-plus-latest", "qwen3-8b"),
        ID_VOLCENGINE to listOf(
            "doubao-seed-2-1-lite-260915",
            "doubao-seed-1-6-flash-250828",
            "doubao-1-5-lite-32k-250115"
        ),
        ID_SILICONFLOW to listOf(
            "Qwen/Qwen2.5-7B-Instruct",
            "Qwen/Qwen3-8B",
            "THUDM/GLM-4-9B-0414"
        ),
        ID_DEEPSEEK to listOf("deepseek-chat", "deepseek-reasoner"),
        ID_CUSTOM to listOf("gpt-4o-mini", "llama-3.3-70b-versatile", "glm-4-flash", "qwen2.5:7b")
    )

    val multimodalSuggestedModelsByProvider: Map<String, List<String>> = mapOf(
        ID_GOOGLE to listOf("gemini-3.5-flash-lite", "gemini-2.5-flash", "gemini-3.6-flash", "gemini-3.8-flash"),
        ID_CUSTOM to listOf("gpt-4o-mini-audio-preview", "google/gemini-3.5-flash-lite")
    )

    val jevSuggestedModelsByProvider: Map<String, List<String>> = mapOf(
        ID_GOOGLE to listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-2.5-pro", "gemini-2.5-flash"),
        ID_VOLCENGINE to listOf(
            "doubao-seed-2-1-pro-260915",
            "doubao-seed-2-0-pro-260215",
            "doubao-seed-1-6-250615",
            "doubao-seed-2-1-lite-260915"
        ),
        ID_ALIYUN to listOf("qwen-max-latest", "qwen-plus-latest", "qwen-vl-max-latest"),
        ID_DEEPSEEK to listOf("deepseek-chat", "deepseek-reasoner"),
        ID_SILICONFLOW to listOf(
            "deepseek-ai/DeepSeek-V3",
            "Qwen/Qwen2.5-72B-Instruct",
            "Qwen/Qwen2.5-VL-72B-Instruct"
        ),
        ID_CUSTOM to listOf("gemini-3.8-flash", "claude-sonnet-4-5", "gpt-4o", "deepseek-chat")
    )

    fun specFor(providerId: String): UnifiedProviderSpec {
        return providers.firstOrNull { it.id == providerId } ?: providers.last()
    }

    fun inferSttProviderId(stt: SttConfig): String {
        val url = stt.baseUrl.trim().trimEnd('/').lowercase()
        return when {
            stt.protocol == SttProtocol.GEMINI_LIVE_WS || stt.presetId == "gemini_live_ws" -> ID_GOOGLE
            stt.protocol == SttProtocol.ALIYUN_DASHSCOPE_WS || url.contains("aliyuncs.com") || url.contains("dashscope") -> ID_ALIYUN
            stt.protocol == SttProtocol.VOLCENGINE_SAUC_WS || url.contains("bytedance.com") || url.contains("volces.com") -> ID_VOLCENGINE
            stt.presetId == "siliconflow" || url.contains("siliconflow.cn") -> ID_SILICONFLOW
            stt.presetId == "deepseek" || stt.presetId == "deepseek_asr" ||
                url.contains("deepseek.com") || url.endsWith("/tools/asr") -> ID_DEEPSEEK
            else -> ID_CUSTOM
        }
    }

    fun inferLlmProviderId(llm: LlmConfig): String {
        val url = llm.baseUrl.lowercase()
        return when {
            llm.presetId == "gemini_llm" || url.contains("googleapis.com") || url.endsWith("/v1beta") -> ID_GOOGLE
            llm.presetId == "aliyun_bailian" || url.contains("aliyuncs.com") || url.contains("dashscope") -> ID_ALIYUN
            llm.presetId == "doubao" || url.contains("volces.com") || url.contains("bytedance.com") -> ID_VOLCENGINE
            llm.presetId == "siliconflow_llm" || url.contains("siliconflow.cn") -> ID_SILICONFLOW
            llm.presetId == "deepseek" || url.contains("deepseek.com") -> ID_DEEPSEEK
            else -> ID_CUSTOM
        }
    }

    fun inferMultimodalProviderId(mm: MultimodalConfig): String {
        val url = mm.baseUrl.lowercase()
        return when {
            mm.protocol == MultimodalProtocol.GEMINI_NATIVE || url.contains("googleapis.com") -> ID_GOOGLE
            else -> ID_CUSTOM
        }
    }

    fun isCredentialReady(providerId: String, cred: ProviderCredential?): Boolean {
        if (cred == null) return false
        return when (providerId) {
            ID_VOLCENGINE -> cred.apiKey.isNotBlank() || cred.sttApiKey.isNotBlank()
            ID_CUSTOM -> cred.apiKey.isNotBlank() || isLocalUrl(cred.customBaseUrl)
            else -> cred.apiKey.isNotBlank()
        }
    }

    private fun isLocalUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.contains("localhost") ||
            lower.contains("127.0.0.1") ||
            lower.contains("10.0.2.2") ||
            lower.contains("192.168.")
    }

    fun hasConfiguredSttCredential(stt: SttConfig): Boolean {
        if (stt.apiKey.isNotBlank()) return true
        return stt.protocol == SttProtocol.OPENAI_HTTP && isLocalUrl(stt.baseUrl)
    }

    fun resolveFallbackSttProviderFromVault(
        vault: Map<String, ProviderCredential>,
        excludeProviderId: String? = null
    ): String? {
        val priority = listOf(ID_SILICONFLOW, ID_ALIYUN, ID_VOLCENGINE, ID_GOOGLE, ID_DEEPSEEK, ID_CUSTOM)
        return priority.firstOrNull { id ->
            id != excludeProviderId && specFor(id).supportsStt && isCredentialReady(id, vault[id])
        }
    }

    /**
     * Automatically populates [AppSettings.providerVault] from legacy [SttConfig], [LlmConfig],
     * and [MultimodalConfig] so upgrading users keep every single key without re-entering anything.
     */
    fun hydrateVaultFromSettings(settings: AppSettings): Map<String, ProviderCredential> {
        val merged = LinkedHashMap<String, ProviderCredential>()
        providers.forEach { spec ->
            val existing = settings.providerVault[spec.id]
            merged[spec.id] = existing ?: ProviderCredential(providerId = spec.id)
        }

        // 1. Absorb Multimodal config
        val mmProvider = inferMultimodalProviderId(settings.multimodalConfig)
        val mmCurr = merged[mmProvider] ?: ProviderCredential(mmProvider)
        if (settings.multimodalConfig.apiKey.isNotBlank() && mmCurr.apiKey.isBlank()) {
            merged[mmProvider] = mmCurr.copy(
                apiKey = settings.multimodalConfig.apiKey,
                customBaseUrl = if (
                    settings.multimodalConfig.baseUrl.isNotBlank() &&
                    settings.multimodalConfig.baseUrl != specFor(mmProvider).defaultBaseUrl
                ) {
                    settings.multimodalConfig.baseUrl
                } else {
                    mmCurr.customBaseUrl
                }
            )
        }

        // 2. Absorb LLM config
        val llmProvider = inferLlmProviderId(settings.llmConfig)
        val llmCurr = merged[llmProvider] ?: ProviderCredential(llmProvider)
        if (settings.llmConfig.apiKey.isNotBlank() || llmProvider == ID_CUSTOM) {
            val nextKey = llmCurr.apiKey.ifBlank { settings.llmConfig.apiKey }
            val nextCustomUrl = if (
                llmCurr.customBaseUrl.isBlank() &&
                settings.llmConfig.baseUrl.isNotBlank() &&
                settings.llmConfig.baseUrl != specFor(llmProvider).defaultBaseUrl
            ) {
                settings.llmConfig.baseUrl
            } else {
                llmCurr.customBaseUrl
            }
            merged[llmProvider] = llmCurr.copy(apiKey = nextKey, customBaseUrl = nextCustomUrl)
        }

        // 3. Absorb STT config
        val sttProvider = inferSttProviderId(settings.sttConfig)
        val sttCurr = merged[sttProvider] ?: ProviderCredential(sttProvider)
        if (sttProvider == ID_VOLCENGINE) {
            val speechTok = sttCurr.sttApiKey.ifBlank { settings.sttConfig.apiKey }
            val appId = sttCurr.appId.ifBlank { settings.sttConfig.appId }
            merged[ID_VOLCENGINE] = sttCurr.copy(
                sttApiKey = speechTok,
                appId = appId
            )
        } else if (settings.sttConfig.apiKey.isNotBlank() || sttProvider == ID_CUSTOM) {
            val nextKey = sttCurr.apiKey.ifBlank { settings.sttConfig.apiKey }
            val isDefaultProviderSttUrl = settings.sttConfig.baseUrl == specFor(sttProvider).defaultBaseUrl ||
                (sttProvider == ID_DEEPSEEK && EndpointResolver.isOfficialDeepSeekHost(settings.sttConfig.baseUrl))
            val nextUrl = if (
                sttCurr.customBaseUrl.isBlank() &&
                settings.sttConfig.baseUrl.isNotBlank() &&
                !settings.sttConfig.baseUrl.startsWith("wss://", ignoreCase = true) &&
                !isDefaultProviderSttUrl
            ) {
                settings.sttConfig.baseUrl
            } else {
                sttCurr.customBaseUrl
            }
            merged[sttProvider] = sttCurr.copy(apiKey = nextKey, customBaseUrl = nextUrl)
        }

        // 4. Absorb Jev dedicated LLM config
        val jevProvider = inferLlmProviderId(settings.jevLlmConfig)
        val jevCurr = merged[jevProvider] ?: ProviderCredential(jevProvider)
        if (settings.jevLlmConfig.apiKey.isNotBlank() || (settings.jevUseDedicatedModel && jevProvider == ID_CUSTOM)) {
            val nextKey = jevCurr.apiKey.ifBlank { settings.jevLlmConfig.apiKey }
            val nextCustomUrl = if (
                jevCurr.customBaseUrl.isBlank() &&
                settings.jevLlmConfig.baseUrl.isNotBlank() &&
                settings.jevLlmConfig.baseUrl != specFor(jevProvider).defaultBaseUrl
            ) {
                settings.jevLlmConfig.baseUrl
            } else {
                jevCurr.customBaseUrl
            }
            merged[jevProvider] = jevCurr.copy(apiKey = nextKey, customBaseUrl = nextCustomUrl)
        }

        return merged
    }

    /**
     * Updates a provider's credential in the unified vault and immediately syncs active
     * STT / LLM / Multimodal / Jev configs that point to this provider.
     */
    fun updateProviderCredential(
        settings: AppSettings,
        updated: ProviderCredential
    ): AppSettings {
        val vault = hydrateVaultFromSettings(settings).toMutableMap()
        vault[updated.providerId] = updated

        val activeSttProvider = inferSttProviderId(settings.sttConfig)
        val nextStt = if (activeSttProvider == updated.providerId) {
            syncSttWithVault(settings.sttConfig, updated.providerId, updated)
        } else {
            settings.sttConfig
        }

        val activeLlmProvider = inferLlmProviderId(settings.llmConfig)
        val nextLlm = if (activeLlmProvider == updated.providerId) {
            syncLlmWithVault(settings.llmConfig, updated.providerId, updated)
        } else {
            settings.llmConfig
        }

        val activeMmProvider = inferMultimodalProviderId(settings.multimodalConfig)
        val nextMm = if (activeMmProvider == updated.providerId) {
            syncMultimodalWithVault(settings.multimodalConfig, updated.providerId, updated)
        } else {
            settings.multimodalConfig
        }

        val activeJevProvider = inferLlmProviderId(settings.jevLlmConfig)
        val nextJev = if (activeJevProvider == updated.providerId) {
            syncLlmWithVault(settings.jevLlmConfig, updated.providerId, updated)
        } else {
            settings.jevLlmConfig
        }

        return settings.copy(
            providerVault = vault,
            sttConfig = nextStt,
            llmConfig = nextLlm,
            multimodalConfig = nextMm,
            jevLlmConfig = nextJev
        )
    }

    /**
     * 1-tap "设为当前主力方案" button inside a provider card: configures the optimal verified
     * workflow for that provider using its stored Vault credentials.
     */
    fun applyQuickProviderSetup(
        settings: AppSettings,
        providerId: String,
        keepExistingThirdPartyStt: Boolean = false
    ): AppSettings {
        val vault = hydrateVaultFromSettings(settings)
        val cred = vault[providerId] ?: ProviderCredential(providerId)

        return when (providerId) {
            ID_GOOGLE -> {
                val baseUrl = cred.customBaseUrl.ifBlank { specFor(ID_GOOGLE).defaultBaseUrl }
                settings.copy(
                    providerVault = vault,
                    pipelineMode = PipelineMode.DUAL_STAGE,
                    sttConfig = settings.sttConfig.copy(
                        presetId = "gemini_live_ws",
                        protocol = SttProtocol.GEMINI_LIVE_WS,
                        baseUrl = baseUrl,
                        apiKey = cred.apiKey,
                        model = "gemini-3.5-transcribe-live"
                    ),
                    // SMART mode handles dictation cleanup in ~0.5s; keep Gemini Flash-Lite configured for Help-Me-Write / Edit / Translate!
                    llmConfig = settings.llmConfig.copy(
                        enabled = false,
                        presetId = "gemini_llm",
                        baseUrl = baseUrl,
                        apiKey = cred.apiKey,
                        model = "gemini-3.5-flash-lite",
                        thinkingMode = ThinkingMode.DISABLED
                    ),
                    multimodalConfig = settings.multimodalConfig.copy(
                        presetId = "gemini",
                        protocol = MultimodalProtocol.GEMINI_NATIVE,
                        baseUrl = baseUrl,
                        apiKey = cred.apiKey,
                        model = "gemini-3.5-flash-lite",
                        thinkingMode = ThinkingMode.DISABLED
                    )
                )
            }

            ID_ALIYUN -> {
                settings.copy(
                    providerVault = vault,
                    pipelineMode = PipelineMode.DUAL_STAGE,
                    sttConfig = settings.sttConfig.copy(
                        presetId = "aliyun_asr_ws",
                        protocol = SttProtocol.ALIYUN_DASHSCOPE_WS,
                        baseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                        apiKey = cred.apiKey,
                        model = "paraformer-realtime-v2"
                    ),
                    llmConfig = settings.llmConfig.copy(
                        enabled = true,
                        presetId = "aliyun_bailian",
                        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        apiKey = cred.apiKey,
                        model = "qwen-turbo-latest",
                        thinkingMode = ThinkingMode.DISABLED
                    )
                )
            }

            ID_VOLCENGINE -> {
                val speechKey = cred.sttApiKey.ifBlank { cred.apiKey }
                val arkKey = cred.apiKey.ifBlank { cred.sttApiKey }
                settings.copy(
                    providerVault = vault,
                    pipelineMode = PipelineMode.DUAL_STAGE,
                    sttConfig = settings.sttConfig.copy(
                        presetId = "volcengine_sauc_ws",
                        protocol = SttProtocol.VOLCENGINE_SAUC_WS,
                        baseUrl = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
                        apiKey = speechKey,
                        appId = cred.appId,
                        resourceId = settings.sttConfig.resourceId.ifBlank { "volc.seedasr.sauc.duration" },
                        model = "bigmodel"
                    ),
                    llmConfig = settings.llmConfig.copy(
                        enabled = true,
                        presetId = "doubao",
                        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
                        apiKey = arkKey,
                        model = "doubao-seed-2-1-lite-260915",
                        thinkingMode = ThinkingMode.DISABLED
                    )
                )
            }

            ID_SILICONFLOW -> {
                settings.copy(
                    providerVault = vault,
                    pipelineMode = PipelineMode.DUAL_STAGE,
                    sttConfig = settings.sttConfig.copy(
                        presetId = "siliconflow",
                        protocol = SttProtocol.OPENAI_HTTP,
                        baseUrl = "https://api.siliconflow.cn/v1",
                        apiKey = cred.apiKey,
                        model = "FunAudioLLM/SenseVoiceSmall"
                    ),
                    llmConfig = settings.llmConfig.copy(
                        enabled = true,
                        presetId = "siliconflow_llm",
                        baseUrl = "https://api.siliconflow.cn/v1",
                        apiKey = cred.apiKey,
                        model = "Qwen/Qwen2.5-7B-Instruct",
                        thinkingMode = ThinkingMode.DISABLED
                    )
                )
            }

            ID_DEEPSEEK -> {
                val effectiveBaseUrl = cred.customBaseUrl
                    .removeSuffix("/tools/asr")
                    .trimEnd('/')
                    .ifBlank { "https://api.deepseek.com/v1" }
                val asrEndpointUrl = EndpointResolver.resolveSttEndpoint(
                    cred.customBaseUrl.ifBlank { EndpointResolver.DEFAULT_DEEPSEEK_ASR_URL }
                )
                val syncedJev = if (!settings.jevUseDedicatedModel &&
                    (settings.jevLlmConfig.apiKey.isBlank() || inferLlmProviderId(settings.jevLlmConfig) == ID_DEEPSEEK)
                ) {
                    settings.jevLlmConfig.copy(
                        enabled = true,
                        presetId = "deepseek",
                        baseUrl = effectiveBaseUrl,
                        apiKey = cred.apiKey,
                        model = "deepseek-chat",
                        temperature = 0.35f,
                        thinkingMode = ThinkingMode.AUTO
                    )
                } else {
                    settings.jevLlmConfig
                }
                val baseUpdated = settings.copy(
                    providerVault = vault,
                    pipelineMode = PipelineMode.DUAL_STAGE,
                    llmConfig = settings.llmConfig.copy(
                        enabled = true,
                        presetId = "deepseek",
                        baseUrl = effectiveBaseUrl,
                        apiKey = cred.apiKey,
                        model = "deepseek-chat",
                        thinkingMode = ThinkingMode.DISABLED
                    ),
                    jevLlmConfig = syncedJev
                )
                if (!keepExistingThirdPartyStt) {
                    baseUpdated.copy(
                        sttConfig = baseUpdated.sttConfig.copy(
                            presetId = "deepseek_asr",
                            protocol = SttProtocol.OPENAI_HTTP,
                            baseUrl = asrEndpointUrl,
                            apiKey = cred.apiKey,
                            model = "deepseek-asr"
                        )
                    )
                } else {
                    val hasReadyThirdPartyStt = hasConfiguredSttCredential(baseUpdated.sttConfig) &&
                        inferSttProviderId(baseUpdated.sttConfig) != ID_DEEPSEEK
                    if (hasReadyThirdPartyStt) {
                        baseUpdated
                    } else {
                        val fallbackSttId = resolveFallbackSttProviderFromVault(
                            vault = vault,
                            excludeProviderId = ID_DEEPSEEK
                        )
                        if (fallbackSttId != null) {
                            selectSttProvider(baseUpdated, fallbackSttId)
                        } else {
                            baseUpdated
                        }
                    }
                }
            }

            else -> {
                val baseUrl = cred.customBaseUrl.ifBlank { "https://api.openai.com/v1" }
                settings.copy(
                    providerVault = vault,
                    pipelineMode = PipelineMode.DUAL_STAGE,
                    sttConfig = settings.sttConfig.copy(
                        presetId = "custom_stt",
                        protocol = SttProtocol.OPENAI_HTTP,
                        baseUrl = baseUrl,
                        apiKey = cred.apiKey
                    ),
                    llmConfig = settings.llmConfig.copy(
                        enabled = true,
                        presetId = "openai_llm",
                        baseUrl = baseUrl,
                        apiKey = cred.apiKey
                    )
                )
            }
        }
    }

    fun selectSttProvider(settings: AppSettings, providerId: String): AppSettings {
        val vault = hydrateVaultFromSettings(settings)
        val cred = vault[providerId] ?: ProviderCredential(providerId)
        val spec = specFor(providerId)
        val nextStt = when (providerId) {
            ID_GOOGLE -> settings.sttConfig.copy(
                presetId = "gemini_live_ws",
                protocol = SttProtocol.GEMINI_LIVE_WS,
                baseUrl = cred.customBaseUrl.ifBlank { spec.defaultBaseUrl },
                apiKey = cred.apiKey,
                model = spec.defaultSttModel
            )
            ID_ALIYUN -> settings.sttConfig.copy(
                presetId = "aliyun_asr_ws",
                protocol = SttProtocol.ALIYUN_DASHSCOPE_WS,
                baseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                apiKey = cred.apiKey,
                model = spec.defaultSttModel
            )
            ID_VOLCENGINE -> settings.sttConfig.copy(
                presetId = "volcengine_sauc_ws",
                protocol = SttProtocol.VOLCENGINE_SAUC_WS,
                baseUrl = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
                apiKey = cred.sttApiKey.ifBlank { cred.apiKey },
                appId = cred.appId,
                resourceId = settings.sttConfig.resourceId.ifBlank { "volc.seedasr.sauc.duration" },
                model = spec.defaultSttModel
            )
            ID_SILICONFLOW -> settings.sttConfig.copy(
                presetId = "siliconflow",
                protocol = SttProtocol.OPENAI_HTTP,
                baseUrl = spec.defaultBaseUrl,
                apiKey = cred.apiKey,
                model = spec.defaultSttModel
            )
            ID_DEEPSEEK -> {
                val rawUrl = cred.customBaseUrl.ifBlank { EndpointResolver.DEFAULT_DEEPSEEK_ASR_URL }
                settings.sttConfig.copy(
                    presetId = "deepseek_asr",
                    protocol = SttProtocol.OPENAI_HTTP,
                    baseUrl = EndpointResolver.resolveSttEndpoint(rawUrl),
                    apiKey = cred.apiKey,
                    model = spec.defaultSttModel.ifBlank { "deepseek-asr" }
                )
            }
            else -> settings.sttConfig.copy(
                presetId = "custom_stt",
                protocol = SttProtocol.OPENAI_HTTP,
                baseUrl = cred.customBaseUrl.ifBlank { spec.defaultBaseUrl },
                apiKey = cred.apiKey,
                model = spec.defaultSttModel
            )
        }
        val nextLlm = when {
            providerId == ID_GOOGLE && settings.llmConfig.apiKey.isBlank() -> {
                settings.llmConfig.copy(
                    enabled = false,
                    presetId = "gemini_llm",
                    baseUrl = cred.customBaseUrl.ifBlank { spec.defaultBaseUrl },
                    apiKey = cred.apiKey,
                    model = "gemini-3.5-flash-lite",
                    thinkingMode = ThinkingMode.DISABLED
                )
            }
            providerId == ID_DEEPSEEK && settings.llmConfig.apiKey.isBlank() -> {
                settings.llmConfig.copy(
                    enabled = true,
                    presetId = "deepseek",
                    baseUrl = cred.customBaseUrl.removeSuffix("/tools/asr").trimEnd('/').ifBlank { spec.defaultBaseUrl },
                    apiKey = cred.apiKey,
                    model = "deepseek-chat",
                    thinkingMode = ThinkingMode.DISABLED
                )
            }
            else -> settings.llmConfig
        }
        return settings.copy(providerVault = vault, sttConfig = nextStt, llmConfig = nextLlm)
    }

    fun selectLlmProvider(settings: AppSettings, providerId: String): AppSettings {
        val vault = hydrateVaultFromSettings(settings)
        val cred = vault[providerId] ?: ProviderCredential(providerId)
        val spec = specFor(providerId)
        val presetId = when (providerId) {
            ID_GOOGLE -> "gemini_llm"
            ID_ALIYUN -> "aliyun_bailian"
            ID_VOLCENGINE -> "doubao"
            ID_SILICONFLOW -> "siliconflow_llm"
            ID_DEEPSEEK -> "deepseek"
            else -> "openai_llm"
        }
        val baseUrl = if (spec.supportsCustomBaseUrl && cred.customBaseUrl.isNotBlank()) {
            cred.customBaseUrl
        } else {
            spec.defaultBaseUrl
        }
        val key = if (providerId == ID_VOLCENGINE) {
            cred.apiKey.ifBlank { cred.sttApiKey }
        } else {
            cred.apiKey
        }
        val nextLlm = settings.llmConfig.copy(
            presetId = presetId,
            baseUrl = baseUrl,
            apiKey = key,
            model = spec.defaultLlmModel
        )
        return settings.copy(providerVault = vault, llmConfig = nextLlm)
    }

    fun selectMultimodalProvider(settings: AppSettings, providerId: String): AppSettings {
        val vault = hydrateVaultFromSettings(settings)
        val cred = vault[providerId] ?: ProviderCredential(providerId)
        val spec = specFor(providerId)
        val baseUrl = if (spec.supportsCustomBaseUrl && cred.customBaseUrl.isNotBlank()) {
            cred.customBaseUrl
        } else {
            spec.defaultBaseUrl
        }
        val nextMm = settings.multimodalConfig.copy(
            presetId = if (providerId == ID_GOOGLE) "gemini" else "openai_audio",
            protocol = spec.defaultMultimodalProtocol,
            baseUrl = baseUrl,
            apiKey = cred.apiKey,
            model = spec.defaultMultimodalModel
        )
        return settings.copy(providerVault = vault, multimodalConfig = nextMm)
    }

    fun selectJevProvider(settings: AppSettings, providerId: String): AppSettings {
        val vault = hydrateVaultFromSettings(settings)
        val cred = vault[providerId] ?: ProviderCredential(providerId)
        val spec = specFor(providerId)
        val presetId = when (providerId) {
            ID_GOOGLE -> "gemini_llm"
            ID_ALIYUN -> "aliyun_bailian"
            ID_VOLCENGINE -> "doubao"
            ID_SILICONFLOW -> "siliconflow_llm"
            ID_DEEPSEEK -> "deepseek"
            else -> "openai_llm"
        }
        val baseUrl = if (spec.supportsCustomBaseUrl && cred.customBaseUrl.isNotBlank()) {
            cred.customBaseUrl
        } else {
            spec.defaultBaseUrl
        }
        val key = if (providerId == ID_VOLCENGINE) {
            cred.apiKey.ifBlank { cred.sttApiKey }
        } else {
            cred.apiKey
        }
        val defaultJevModel = jevSuggestedModelsByProvider[providerId]?.firstOrNull()
            ?: spec.defaultLlmModel.ifBlank { "gemini-3.8-flash" }
        val nextJev = settings.jevLlmConfig.copy(
            enabled = true,
            presetId = presetId,
            baseUrl = baseUrl,
            apiKey = key,
            model = defaultJevModel,
            temperature = 0.35f,
            thinkingMode = ThinkingMode.AUTO
        )
        return settings.copy(
            providerVault = vault,
            jevUseDedicatedModel = true,
            jevLlmConfig = nextJev
        )
    }

    private fun syncSttWithVault(
        stt: SttConfig,
        providerId: String,
        cred: ProviderCredential
    ): SttConfig {
        return when (providerId) {
            ID_VOLCENGINE -> stt.copy(
                apiKey = cred.sttApiKey.ifBlank { cred.apiKey },
                appId = cred.appId
            )
            ID_GOOGLE -> stt.copy(
                apiKey = cred.apiKey,
                baseUrl = cred.customBaseUrl.ifBlank { specFor(ID_GOOGLE).defaultBaseUrl }
            )
            ID_DEEPSEEK -> stt.copy(
                apiKey = cred.apiKey,
                baseUrl = EndpointResolver.resolveSttEndpoint(
                    cred.customBaseUrl.ifBlank { EndpointResolver.DEFAULT_DEEPSEEK_ASR_URL }
                ),
                model = stt.model.ifBlank { "deepseek-asr" }
            )
            ID_CUSTOM -> stt.copy(
                apiKey = cred.apiKey,
                baseUrl = cred.customBaseUrl.ifBlank { stt.baseUrl }
            )
            else -> stt.copy(apiKey = cred.apiKey)
        }
    }

    private fun syncLlmWithVault(
        llm: LlmConfig,
        providerId: String,
        cred: ProviderCredential
    ): LlmConfig {
        return when (providerId) {
            ID_VOLCENGINE -> llm.copy(apiKey = cred.apiKey.ifBlank { cred.sttApiKey })
            ID_GOOGLE -> llm.copy(
                apiKey = cred.apiKey,
                baseUrl = cred.customBaseUrl.ifBlank { specFor(ID_GOOGLE).defaultBaseUrl }
            )
            ID_DEEPSEEK -> llm.copy(
                apiKey = cred.apiKey,
                baseUrl = cred.customBaseUrl.removeSuffix("/tools/asr").trimEnd('/').ifBlank {
                    specFor(ID_DEEPSEEK).defaultBaseUrl
                }
            )
            ID_CUSTOM -> llm.copy(
                apiKey = cred.apiKey,
                baseUrl = cred.customBaseUrl.ifBlank { llm.baseUrl }
            )
            else -> llm.copy(apiKey = cred.apiKey)
        }
    }

    private fun syncMultimodalWithVault(
        mm: MultimodalConfig,
        providerId: String,
        cred: ProviderCredential
    ): MultimodalConfig {
        val spec = specFor(providerId)
        return mm.copy(
            apiKey = cred.apiKey,
            baseUrl = if (spec.supportsCustomBaseUrl && cred.customBaseUrl.isNotBlank()) {
                cred.customBaseUrl
            } else {
                mm.baseUrl
            }
        )
    }
}
