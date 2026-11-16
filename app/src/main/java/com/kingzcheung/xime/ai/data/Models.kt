package com.kingzcheung.xime.ai.data

import java.util.UUID

enum class PipelineMode {
    DUAL_STAGE,         // STT 语音转文字 + LLM 文本润色
    MULTIMODAL_DIRECT   // 多模态大模型音频一步直出（如 Gemini / GPT-4o Audio）
}

enum class MultimodalProtocol {
    GEMINI_NATIVE,      // Google Gemini generateContent (inline_data audio/wav)
    OPENAI_AUDIO_CHAT   // OpenAI /v1/chat/completions (input_audio base64 wav)
}

enum class SttProtocol(val label: String, val shortBadge: String) {
    OPENAI_HTTP("OpenAI 兼容 HTTP 整包", "HTTP"),
    ALIYUN_DASHSCOPE_WS("阿里云百炼 WebSocket 实时流式", "百炼流式"),
    VOLCENGINE_SAUC_WS("火山豆包 WebSocket 实时流式", "豆包流式"),
    GEMINI_LIVE_WS("Google Gemini Live 智能流式", "Gemini 流式")
}

enum class ThemePreference(val label: String) {
    LIGHT("浅色单色 (TypeFree 经典)"),
    DARK("深色曜石"),
    SYSTEM("跟随系统")
}

enum class VoiceIntentMode(val label: String) {
    DICTATE("口述净写"),
    HELP_ME_WRITE("帮我写"),
    SPEAK_TO_EDIT("语音编辑"),
    TRANSLATE("实时快译"),
    JEV_COPILOT("Jev 军师")
}

enum class HistoryRetention(val label: String, val maxAgeMs: Long?) {
    FOREVER("永久保留", null),
    DAYS_30("保留 30 天", 30L * 24L * 3600L * 1000L),
    DAYS_7("保留 7 天", 7L * 24L * 3600L * 1000L),
    HOURS_24("保留 24 小时", 24L * 3600L * 1000L),
    NEVER("从不保留", 0L)
}

enum class DictionarySource(val label: String) {
    MANUAL("手动添加"),
    AUTO_LEARNED("自动学习")
}

enum class ModelCapabilitySlot(val label: String) {
    STT("STT 语音识别"),
    LLM("LLM 语义润色"),
    MULTIMODAL("多模态音频直出"),
    JEV_COPILOT("Jev 军师专用")
}

enum class ThinkingMode(val label: String, val description: String) {
    DISABLED("关闭思考 (极速)", "显式下发非思考指令，砍掉隐藏 CoT 推理空转，语音上屏最快"),
    AUTO("模型默认", "不额外注入思考开关字段，遵循服务端与模型原生默认行为"),
    ENABLED("开启思考", "显式启用深度推理，适合长文「帮我写」但首字延迟会增加")
}

/**
 * Keyboard height adjustment mode.
 * - [STRETCH]: scales the key area vertically (keys get taller/shorter).
 * - [LIFT]: keeps keys at standard size and raises the whole keyboard with a blank bottom cushion.
 */
enum class KeyboardHeightMode(val label: String) {
    STRETCH("拉伸"),
    LIFT("垫高")
}

enum class InputScheme(
    val label: String,
    val shortBadge: String,
    val usesRime: Boolean,
    val defaultFixedOrder: Boolean,
    val rimeSchemaId: String = ""
) {
    RIME_PINYIN(
        label = "全拼（雾凇拼音）",
        shortBadge = "拼",
        usesRime = true,
        defaultFixedOrder = false,
        rimeSchemaId = "rime_ice"
    ),
    RIME_DOUBLE_PINYIN_FLYPY(
        label = "小鹤双拼（整句）",
        shortBadge = "拼",
        usesRime = true,
        defaultFixedOrder = false,
        rimeSchemaId = "double_pinyin_flypy"
    ),
    FLYPY_TABLE(
        label = "小鹤音形（四码）",
        shortBadge = "鹤",
        usesRime = false,
        defaultFixedOrder = true,
        rimeSchemaId = ""
    ),
    RIME_CUSTOM(
        label = "自定义 Rime 方案",
        shortBadge = "拼",
        usesRime = true,
        defaultFixedOrder = false,
        rimeSchemaId = "custom"
    )
}

data class DiscoveredModelItem(
    val id: String,
    val displayName: String = "",
    val ownedBy: String = "",
    val isRecommended: Boolean = false,
    val badge: String = ""
)

data class ModelProbeResult(
    val modelId: String,
    val latencyMs: Long? = null,
    val errorSummary: String? = null
) {
    val isSuccess: Boolean get() = latencyMs != null && errorSummary == null
}

data class SttConfig(
    val presetId: String = "siliconflow",
    val protocol: SttProtocol = SttProtocol.OPENAI_HTTP,
    val baseUrl: String = "https://api.siliconflow.cn/v1",
    val apiKey: String = "",
    val appId: String = "",
    val resourceId: String = "volc.seedasr.sauc.duration",
    val model: String = "FunAudioLLM/SenseVoiceSmall",
    val language: String = "" // "" = 自动识别, "zh", "en", "ja"
)

data class LlmConfig(
    val enabled: Boolean = true,
    val presetId: String = "deepseek",
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val temperature: Float = 0.2f,
    val thinkingMode: ThinkingMode = ThinkingMode.DISABLED
)

data class MultimodalConfig(
    val presetId: String = "gemini",
    val protocol: MultimodalProtocol = MultimodalProtocol.GEMINI_NATIVE,
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    val apiKey: String = "",
    val model: String = "gemini-3.5-flash-lite",
    val temperature: Float = 0.2f,
    val thinkingMode: ThinkingMode = ThinkingMode.DISABLED
)

data class ProviderCredential(
    val providerId: String,
    val apiKey: String = "",
    val sttApiKey: String = "",
    val appId: String = "",
    val customBaseUrl: String = ""
)

data class SnippetEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val voiceTriggers: String, // 逗号分隔的语音触发口令（如：发我的邮箱,插入邮箱）
    val flypyCode: String = "", // 小鹤/键盘快捷码（如：;yx）
    val content: String,
    val enabled: Boolean = true
)

data class ReplyAttitudePreset(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val instruction: String
)

data class ApiPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val baseUrl: String,
    val defaultModel: String,
    val protocol: MultimodalProtocol? = null,
    val sttProtocol: SttProtocol? = null,
    val defaultResourceId: String = ""
)

object ApiPresets {
    val sttPresets = listOf(
        ApiPreset(
            id = "gemini_live_ws",
            name = "Google Gemini Live 智能流式",
            subtitle = "实测松手 ~0.5s 直出，原生去口癖+合并改口+热词偏置",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            defaultModel = "gemini-3.5-transcribe-live",
            sttProtocol = SttProtocol.GEMINI_LIVE_WS
        ),
        ApiPreset(
            id = "siliconflow",
            name = "硅基流动 SenseVoice",
            subtitle = "国内直连免费极速，中英混输与流式兜底首选",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "FunAudioLLM/SenseVoiceSmall",
            sttProtocol = SttProtocol.OPENAI_HTTP
        ),
        ApiPreset(
            id = "deepseek_asr",
            name = "DeepSeek 官方语音识别",
            subtitle = "与 DeepSeek 文本共用单 Key，自动检测中/粤/英/日/韩",
            baseUrl = "https://api.deepseek.com/v1/tools/asr",
            defaultModel = "deepseek-asr",
            sttProtocol = SttProtocol.OPENAI_HTTP
        ),
        ApiPreset(
            id = "aliyun_asr_ws",
            name = "阿里云百炼实时流式",
            subtitle = "单 sk-Key 边说边识别，松手百毫秒直出",
            baseUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            defaultModel = "paraformer-realtime-v2",
            sttProtocol = SttProtocol.ALIYUN_DASHSCOPE_WS
        ),
        ApiPreset(
            id = "volcengine_sauc_ws",
            name = "火山豆包流式 SeedASR 2.0",
            subtitle = "豆包输入法同源，口音/吞音/中英混输旗舰",
            baseUrl = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
            defaultModel = "bigmodel",
            sttProtocol = SttProtocol.VOLCENGINE_SAUC_WS,
            defaultResourceId = "volc.seedasr.sauc.duration"
        ),
        ApiPreset(
            id = "groq",
            name = "Groq Whisper Turbo",
            subtitle = "海外延迟极低 (<0.3s)",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "whisper-large-v3-turbo",
            sttProtocol = SttProtocol.OPENAI_HTTP
        ),
        ApiPreset(
            id = "openai",
            name = "OpenAI Whisper",
            subtitle = "官方标准语音转写接口",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini-transcribe",
            sttProtocol = SttProtocol.OPENAI_HTTP
        ),
        ApiPreset(
            id = "custom_stt",
            name = "自定义 OpenAI 兼容",
            subtitle = "OneAPI / NewAPI / 本地 whisper.cpp 等",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "whisper-1",
            sttProtocol = SttProtocol.OPENAI_HTTP
        )
    )

    val llmPresets = listOf(
        ApiPreset(
            id = "gemini_llm",
            name = "Google Gemini 极速文本",
            subtitle = "与 Gemini Live 共用单 Key，帮我写/翻译/改写极速响应",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            defaultModel = "gemini-3.5-flash-lite"
        ),
        ApiPreset(
            id = "doubao",
            name = "火山方舟豆包",
            subtitle = "Seed-2.1-Lite / Flash 极速润色，自动关思考超低延迟",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            defaultModel = "doubao-seed-2-1-lite-260915"
        ),
        ApiPreset(
            id = "aliyun_bailian",
            name = "阿里云百炼 (可与流式 STT 共用 Key)",
            subtitle = "Qwen-Turbo / Qwen3-8B 国内直连极速",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-turbo-latest"
        ),
        ApiPreset(
            id = "siliconflow_llm",
            name = "硅基流动 (可与 STT 共用 Key)",
            subtitle = "Qwen3-8B / Qwen2.5-7B 免费高速推理",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct"
        ),
        ApiPreset(
            id = "deepseek",
            name = "DeepSeek (可与官方 ASR 共用单 Key)",
            subtitle = "单 Key 跑通语音识别 + 中文去口癖润色 + 军师/帮写",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat"
        ),
        ApiPreset(
            id = "groq_llm",
            name = "Groq Llama 3.3",
            subtitle = "百毫秒级超快润色输出",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile"
        ),
        ApiPreset(
            id = "zhipu",
            name = "智谱 GLM",
            subtitle = "GLM-4-Flash 免费/高速润色",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-flash"
        ),
        ApiPreset(
            id = "openai_llm",
            name = "OpenAI / 兼容中转",
            subtitle = "GPT-4o-mini 或任意兼容接口",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini"
        ),
        ApiPreset(
            id = "ollama",
            name = "局域网 Ollama",
            subtitle = "本地自托管免云端 API 费用",
            baseUrl = "http://192.168.1.100:11434/v1",
            defaultModel = "qwen2.5:7b"
        )
    )

    val multimodalPresets = listOf(
        ApiPreset(
            id = "gemini",
            name = "Google Gemini",
            subtitle = ".m4a 硬件压缩直传，一步完成识别+排版",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            defaultModel = "gemini-3.5-flash-lite",
            protocol = MultimodalProtocol.GEMINI_NATIVE
        ),
        ApiPreset(
            id = "newapi_gemini",
            name = "NewAPI / Gemini 反代",
            subtitle = "支持自建网关 /v1beta 透传与 GCP Vertex 渠道",
            baseUrl = "https://your-gateway.example.com/v1beta",
            defaultModel = "gemini-3.5-flash-lite",
            protocol = MultimodalProtocol.GEMINI_NATIVE
        ),
        ApiPreset(
            id = "openai_audio",
            name = "OpenAI GPT-4o Audio",
            subtitle = "OpenAI input_audio 多模态直传",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini-audio-preview",
            protocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
        ),
        ApiPreset(
            id = "openrouter_audio",
            name = "OpenRouter / 兼容多模态",
            subtitle = "支持 input_audio 的聚合网关或 Qwen-Omni",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "google/gemini-2.5-flash",
            protocol = MultimodalProtocol.OPENAI_AUDIO_CHAT
        )
    )
}

data class ToneStyle(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String,
    val summary: String,
    val promptInstruction: String,
    val isBuiltIn: Boolean = false
)

data class AppToneRule(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val appName: String,
    val styleId: String,
    val enabled: Boolean = true
)

data class DictionaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val term: String,
    val wrongVariants: String = "", // 逗号分隔的常见同音误识别词（可选）
    val note: String = "",
    val enabled: Boolean = true,
    val source: DictionarySource = DictionarySource.MANUAL,
    val createdAt: Long = System.currentTimeMillis()
)

data class HistoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val targetAppName: String = "",
    val targetPackage: String = "",
    val styleName: String = "",
    val isSpeakToEdit: Boolean = false,
    val intentMode: VoiceIntentMode = if (isSpeakToEdit) VoiceIntentMode.SPEAK_TO_EDIT else VoiceIntentMode.DICTATE,
    val translateTargetLanguage: String = "",
    val originalSelectedText: String = "",
    val rawTranscript: String = "",
    val polishedText: String = "",
    val durationMs: Long = 0L,
    val latencyMs: Long = 0L,
    val modeLabel: String = "",
    val audioFilePath: String = ""
)

data class ClipboardEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false
)

data class UsageStatsSummary(
    val totalDictations: Int,
    val totalCharacters: Int,
    val averageWpm: Int,
    val savedMinutes: Int,
    val overallPersonalizationPercent: Int,
    val categoryProgress: Map<String, Float>
)

data class AppSettings(
    val pipelineMode: PipelineMode = PipelineMode.DUAL_STAGE,
    val sttConfig: SttConfig = SttConfig(),
    val llmConfig: LlmConfig = LlmConfig(),
    val multimodalConfig: MultimodalConfig = MultimodalConfig(),
    val themePreference: ThemePreference = ThemePreference.LIGHT,
    val appAwareToneEnabled: Boolean = true,
    val selectedManualStyleId: String = "auto", // "auto" 表示开启应用感知自动匹配，否则固定某风格
    val hapticFeedback: Boolean = true,
    val interactionSounds: Boolean = true,
    val useSurroundingContext: Boolean = true,
    val autoLearnDictionary: Boolean = true,
    val flypyEnabled: Boolean = true,
    val flypyCommaPeriodQuickSelect: Boolean = true,
    val rememberLastKeyboardMode: Boolean = true,
    val lastKeyboardTab: String = "VOICE",
    val lastQwertyIsChinese: Boolean = true,
    val qwertyShowNumberRow: Boolean = false,
    val qwertyKeyPopupPreview: Boolean = true,
    val clipboardEnabled: Boolean = true,
    val clipboardQuickPasteSuggestion: Boolean = true,
    val pauseMediaWhileRecording: Boolean = true,
    val whisperModeEnabled: Boolean = false,
    val equalEqualAskAiEnabled: Boolean = true,
    val keyboardHeightLevel: Int = 0, // -2..+2，0 = 标准 252dp
    val keyboardHeightMode: KeyboardHeightMode = KeyboardHeightMode.LIFT, // 统一底部垫高体系（0/8/16/24/32dp）
    val keyboardLiftLevel: Int = 0, // 垫高档位 0..4 → 0 / 8 / 16 / 24 / 32dp
    val liveWriteToEditor: Boolean = true, // 实时写入输入框（v3.0 默认开启）
    // TypeFree 有「音形专享版/标准版」两套构建，用 BuildConfig.USE_PRIVATE_DICT 区分默认方案。
    // Xime 的方案由 Rime 自己的 schema 管理（xime.yaml 的 schema_list），这里只用于
    // AI 模块内部判断"是否为顶功类方案"，固定取 RIME_PINYIN（等价 TypeFree 标准版）。
    val inputScheme: InputScheme = InputScheme.RIME_PINYIN,
    val fixedCandidateOrder: Boolean? = null, // null = 按方案自动
    val predictionEnabled: Boolean = true,
    val aiCompletionEnabled: Boolean = false,
    /** 工作台语音优先用本地离线引擎（Zipformer int8）识别，再按需云端 LLM 润色。 */
    val workbenchLocalSttEnabled: Boolean = false,
    val chatContextReplyEnabled: Boolean = false, // 读屏上下文「AI 帮回」（默认关闭，需首次披露同意后开启）
    val chatContextDisclosureAccepted: Boolean = false, // 是否已阅读并同意「AI 帮回」首次显著披露
    val chatContextDisclosureDismissed: Boolean = false, // 是否已在键盘首次披露卡点过「暂不开启」（避免反复打扰，但不代表已同意披露）
    val jevCopilotEnabled: Boolean = true, // 启用「✦ Jev 军师」模式（默认开启）
    val jevUseDedicatedModel: Boolean = false, // 是否为「✦ Jev 军师」启用独立模型覆盖（false = 跟随默认模型）
    val jevLlmConfig: LlmConfig = LlmConfig(
        enabled = true,
        presetId = "gemini_llm",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        apiKey = "",
        model = "gemini-3.8-flash",
        temperature = 0.35f,
        thinkingMode = ThinkingMode.AUTO
    ),
    val chatContextSendScreenshot: Boolean = true, // 读屏时允许附带脱敏视口截图（关闭或遇高危机密时仅传脱敏纯文本）
    val manualIncognitoMode: Boolean = false, // 手动无痕模式开关（开启后强制阻断个人词库/预测/剪贴板/历史音频/AI读屏）
    val replyAttitudePresets: List<ReplyAttitudePreset> = DefaultPresets.defaultReplyAttitudes(),
    val providerVault: Map<String, ProviderCredential> = emptyMap(),
    val snippets: List<SnippetEntry> = DefaultPresets.defaultSnippets(),
    val historyRetention: HistoryRetention = HistoryRetention.FOREVER,
    val bubbleEnabled: Boolean = false,
    val bubbleDockLeft: Boolean = false,
    val bubbleYRatio: Float = 0.48f,
    val translationTargets: List<String> = DefaultPresets.defaultTranslationTargets(),
    val activeTranslateTarget: String = "English (US)",
    val styles: List<ToneStyle> = DefaultPresets.defaultStyles(),
    val appRules: List<AppToneRule> = DefaultPresets.defaultAppRules(),
    val dictionary: List<DictionaryEntry> = DefaultPresets.defaultDictionary()
)

object DefaultPresets {
    fun defaultTranslationTargets(): List<String> = listOf(
        "English (US)",
        "日本語",
        "简体中文",
        "Español",
        "Français",
        "한국어"
    )

    val allSupportedLanguages: List<String> = listOf(
        "English (US)",
        "English (UK)",
        "简体中文",
        "繁體中文",
        "日本語",
        "한국어",
        "Español",
        "Français",
        "Deutsch",
        "Português",
        "Русский",
        "Italiano",
        "العربية",
        "ไทย",
        "Tiếng Việt",
        "Bahasa Indonesia"
    )

    fun defaultStyles(): List<ToneStyle> = listOf(
        ToneStyle(
            id = "smart_default",
            name = "通用灵动",
            emoji = "✨",
            summary = "去口癖、识别口误自我纠正、智能标点与分点排版",
            promptInstruction = "保持说话人原本的语气与核心意图。清理所有无意义口头禅，自动修正中途改口的内容，补全自然准确的标点符号；若口述了多个并列事项（如第一、第二），自动整理为清晰易读的序号列表。",
            isBuiltIn = true
        ),
        ToneStyle(
            id = "casual_chat",
            name = "社交聊天",
            emoji = "💬",
            summary = "适合微信/QQ/小红书，自然松弛、口语亲切不过度书面化",
            promptInstruction = "当前处于即时社交聊天场景（如微信/QQ）。请保持自然、亲切、轻松的日常交流感，不要改写成生硬的公文体；去除啰嗦重复与口误，长句可适当按聊天习惯自然断句，句末不必每句都生硬加句号（保留问号、感叹号或自然停顿）。",
            isBuiltIn = true
        ),
        ToneStyle(
            id = "work_collab",
            name = "职场干练",
            emoji = "💼",
            summary = "适合飞书/钉钉/企微，重点清晰、专业得体、高效沟通",
            promptInstruction = "当前处于职场办公协作场景（如飞书/钉钉/企业微信）。请将口语整理得干练、清晰、专业且礼貌得体，突出结论与行动项；若包含多项任务或问题，自动使用简洁的数字序号分点呈现。",
            isBuiltIn = true
        ),
        ToneStyle(
            id = "formal_doc",
            name = "正式邮件",
            emoji = "📝",
            summary = "适合邮箱/文档/Notion，书面语严谨、结构规范",
            promptInstruction = "当前处于正式邮件或文档写作场景。请将口语转换为规范、得体、逻辑严密的书面语，修正口语化病句，使用完整规范的标点符号与段落结构。",
            isBuiltIn = true
        ),
        ToneStyle(
            id = "code_prompt",
            name = "编程与 Prompt",
            emoji = "💻",
            summary = "保留代码/API 技术术语大小写，整理为结构化开发表达",
            promptInstruction = "当前处于编程开发或向 AI Agent 下达指令的场景。请准确还原中英文技术术语的标准拼写与大小写（例如 API、JSON、TypeScript、React、Git、Kotlin、Prompt 等），在中英文之间保留规范空格，并将口述需求整理为逻辑清晰、无歧义的技术指令。",
            isBuiltIn = true
        ),
        ToneStyle(
            id = "translate_en",
            name = "直出地道英文",
            emoji = "🌐",
            summary = "说中文或中英夹杂，直接润色输出地道母语级英文",
            promptInstruction = "无论说话人使用中文、英文还是中英混杂表达，请识别其最终真实意图（自动剔除中途改口和口癖），直接输出自然、地道、符合母语习惯的英文文本，不要附带中文原文或解释。",
            isBuiltIn = true
        )
    )

    fun defaultAppRules(): List<AppToneRule> = listOf(
        // 社交聊天
        AppToneRule(packageName = "com.tencent.mm", appName = "微信", styleId = "casual_chat"),
        AppToneRule(packageName = "com.tencent.mobileqq", appName = "QQ", styleId = "casual_chat"),
        AppToneRule(packageName = "com.xingin.xhs", appName = "小红书", styleId = "casual_chat"),
        AppToneRule(packageName = "org.telegram.messenger", appName = "Telegram", styleId = "casual_chat"),
        AppToneRule(packageName = "com.whatsapp", appName = "WhatsApp", styleId = "casual_chat"),
        // 职场干练
        AppToneRule(packageName = "com.ss.android.lark", appName = "飞书", styleId = "work_collab"),
        AppToneRule(packageName = "com.larksuite.suite", appName = "Lark", styleId = "work_collab"),
        AppToneRule(packageName = "com.alibaba.android.rimet", appName = "钉钉", styleId = "work_collab"),
        AppToneRule(packageName = "com.tencent.wework", appName = "企业微信", styleId = "work_collab"),
        AppToneRule(packageName = "com.Slack", appName = "Slack", styleId = "work_collab"),
        // 正式邮件与文档
        AppToneRule(packageName = "com.google.android.gm", appName = "Gmail", styleId = "formal_doc"),
        AppToneRule(packageName = "com.microsoft.office.outlook", appName = "Outlook", styleId = "formal_doc"),
        AppToneRule(packageName = "com.tencent.androidqqmail", appName = "QQ 邮箱", styleId = "formal_doc"),
        AppToneRule(packageName = "com.netease.mobimail", appName = "网易邮箱", styleId = "formal_doc"),
        AppToneRule(packageName = "notion.id", appName = "Notion", styleId = "formal_doc"),
        AppToneRule(packageName = "cn.wps.moffice_eng", appName = "WPS Office", styleId = "formal_doc"),
        // 编程与 AI
        AppToneRule(packageName = "com.termux", appName = "Termux 终端", styleId = "code_prompt"),
        AppToneRule(packageName = "com.github.android", appName = "GitHub", styleId = "code_prompt"),
        AppToneRule(packageName = "com.openai.chatgpt", appName = "ChatGPT", styleId = "code_prompt"),
        AppToneRule(packageName = "com.anthropic.claude", appName = "Claude", styleId = "code_prompt")
    )

    fun defaultDictionary(): List<DictionaryEntry> = listOf(
        DictionaryEntry(
            term = "TypeFree",
            wrongVariants = "太普利斯, type less",
            note = "AI 语音输入产品名",
            source = DictionarySource.MANUAL
        ),
        DictionaryEntry(
            term = "DeepSeek",
            wrongVariants = "迪普西克, deep seek",
            note = "大模型名称",
            source = DictionarySource.MANUAL
        ),
        DictionaryEntry(
            term = "Claude Code",
            wrongVariants = "克劳德扣, claude code",
            note = "AI 编程工具",
            source = DictionarySource.MANUAL
        )
    )

    fun defaultSnippets(): List<SnippetEntry> = listOf(
        SnippetEntry(
            id = "builtin_reply_ack",
            title = "职场礼貌收到",
            voiceTriggers = "回复收到稍后处理,礼貌收到模板",
            flypyCode = ";sd",
            content = "收到，我先核对一下细节，稍后给您准确答复。",
            enabled = true
        ),
        SnippetEntry(
            id = "builtin_email_tpl",
            title = "常用邮箱 (示例)",
            voiceTriggers = "插入我的邮箱,发我的邮箱",
            flypyCode = ";yx",
            content = "your.name@example.com",
            enabled = false
        ),
        SnippetEntry(
            id = "builtin_addr_tpl",
            title = "收货地址 (示例)",
            voiceTriggers = "插入我的收货地址,发我的地址",
            flypyCode = ";dz",
            content = "请在此修改为您的常用收货地址与联系电话",
            enabled = false
        )
    )

    fun defaultReplyAttitudes(): List<ReplyAttitudePreset> = listOf(
        ReplyAttitudePreset(
            id = "reply_ok",
            label = "👌 行 / 好的",
            instruction = "结合聊天画面或<chat_context>中对方的最新话语，用自然简短、平辈日常的语气痛快答应（如“好”、“行，没问题”、“可以的”），不客套、不卑微"
        ),
        ReplyAttitudePreset(
            id = "reply_decline",
            label = "🙅 算了 / 婉拒",
            instruction = "结合聊天画面或<chat_context>中对方的最新话语，用自然得体、不卑不亢的语气直接拒绝或推掉，简短干脆，不要编造冗长借口或过度道歉"
        ),
        ReplyAttitudePreset(
            id = "reply_ask",
            label = "🤔 啥情况 / 追问",
            instruction = "结合聊天画面或<chat_context>中对方的最新消息或图片内容，用自然直接的日常口吻追问核心情况、具体细节或目前进展，简短接地气"
        ),
        ReplyAttitudePreset(
            id = "reply_humor",
            label = "😂 接梗 / 调侃",
            instruction = "结合聊天画面或<chat_context>中的群聊氛围，用轻松幽默、朋友闲聊的语气顺着对方的话接梗、调侃或有趣地附和一句，自然好玩、绝不说教"
        ),
        ReplyAttitudePreset(
            id = "reply_firm",
            label = "⚡ 硬气回怼 / 催办",
            instruction = "针对聊天画面或<chat_context>中对方的推诿、敷衍、不合理要求或维权场景（如催物业管家、催客服、群内据理力争），用态度鲜明、逻辑硬朗、不惯着的语气有理有据地回击或要求给出明确解决时间"
        ),
        ReplyAttitudePreset(
            id = "reply_pro",
            label = "💼 正式得体",
            instruction = "结合聊天画面或<chat_context>中对方的最新话语，用平等、专业、干脆利落的工作沟通语气回复重点与结论，不卑微、不废话"
        )
    )
}
