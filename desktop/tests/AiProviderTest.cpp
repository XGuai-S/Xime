// AI 配置策略单测：STT 复用 AI 密钥 + 润色开关直出原始文本。纯逻辑。
#include "../src/ai/AiProviderConfig.h"

#include <cstdio>
#include <string>

using namespace xime::ai;

namespace {
int fails = 0;
void Check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++fails;
    }
}
} // namespace

int main() {
    // 1) STT 复用 AI 服务商密钥：只配 AI key，语音不单独配 → 复用。
    {
        ProviderVault vault;
        vault.SetKey("openai", "sk-ai-123");
        vault.SetBaseUrl("openai", "https://api.openai.com/v1");
        AppSettings s;
        s.llm.providerId = "openai";
        s.llm.model = "gpt";
        const SttConfig cfg = ResolveSttFromVault(s, vault, "openai-whisper", "whisper-1");
        Check(cfg.valid, "复用: valid");
        Check(cfg.providerId == "openai", "复用: provider");
        Check(cfg.apiKey == "sk-ai-123", "复用: key = AI key");
        Check(cfg.baseUrl == "https://api.openai.com/v1", "复用: baseUrl");
        Check(cfg.model == "whisper-1", "复用: default model");
        Check(cfg.protocol == "openai-whisper", "复用: protocol");
    }
    // 2) 专用语音密钥优先于 AI 密钥。
    {
        ProviderVault vault;
        vault.SetKey("openai", "sk-ai-123");
        AppSettings s;
        s.llm.providerId = "openai";
        s.speechProviderId = "siliconflow";
        s.sttApiKey = "sk-speech-999";
        const SttConfig cfg = ResolveSttFromVault(s, vault, "siliconflow", "SenseVoice");
        Check(cfg.valid, "专用: valid");
        Check(cfg.providerId == "siliconflow", "专用: provider");
        Check(cfg.apiKey == "sk-speech-999", "专用: speech key wins");
    }
    // 3) 无任何凭据 → valid=false（不降级为无鉴权）。
    {
        ProviderVault vault;
        AppSettings s;
        s.llm.providerId = "openai"; // 有 provider 但无 key
        const SttConfig cfg = ResolveSttFromVault(s, vault, "openai-whisper", "whisper-1");
        Check(!cfg.valid, "无凭据: invalid");
    }
    // 4) 润色开关关闭 → 直接输出原始 STT。
    {
        AppSettings s;
        s.llm.enabled = false;
        Check(!ShouldPolish(s), "润色关: ShouldPolish=false");
        Check(DecideDictationOutput(false, "你好世界", "你好，世界。") == "你好世界",
              "润色关: 直出原始");
    }
    // 5) 润色开启 → 输出润色结果；润色为空回退原文。
    {
        AppSettings s;
        s.llm.enabled = true;
        Check(ShouldPolish(s), "润色开: ShouldPolish=true");
        Check(DecideDictationOutput(true, "你好世界", "你好，世界。") == "你好，世界。",
              "润色开: 用润色");
        Check(DecideDictationOutput(true, "你好世界", "") == "你好世界",
              "润色开空结果: 回退原文");
    }

    if (fails == 0) {
        std::printf("AI_PROVIDER_TEST_OK\n");
        return 0;
    }
    std::printf("AI_PROVIDER_TEST_FAILS=%d\n", fails);
    return 1;
}
