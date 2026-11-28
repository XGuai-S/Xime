// AI 服务商配置与策略（移植安卓 UnifiedProviderCatalog / SttConfig / LlmConfig 的核心语义）：
//   1. 语音转文本（STT）服务商可复用 AI 服务商的密钥；
//   2. 文本润色（LLM）开关关闭时，语音转文本直接输出原始识别结果。
// 纯逻辑，无网络，便于单测。
#pragma once

#include <map>
#include <string>

namespace xime {
namespace ai {

struct ProviderCredential {
    std::string id;
    std::string apiKey;
    std::string baseUrl;
};

struct LlmConfig {
    bool enabled = false;       // 文本润色开关
    std::string providerId;     // AI 服务商
    std::string model;
};

struct AppSettings {
    LlmConfig llm;
    std::string speechProviderId; // 单独的语音服务商（可空 → 复用 AI 服务商）
    std::string sttApiKey;        // 专用语音密钥（可空 → 复用 AI 服务商密钥）
};

struct SttConfig {
    bool valid = false;
    std::string providerId;
    std::string apiKey;
    std::string baseUrl;
    std::string model;
    std::string protocol;
};

class ProviderVault {
public:
    void SetKey(const std::string& id, const std::string& apiKey);
    void SetBaseUrl(const std::string& id, const std::string& baseUrl);
    bool HasKey(const std::string& id) const;
    std::string KeyOf(const std::string& id) const;
    std::string BaseUrlOf(const std::string& id) const;

private:
    std::map<std::string, ProviderCredential> creds_;
};

// 解析 STT 配置：优先专用语音服务商/密钥；缺省复用 AI 服务商与其密钥。
// 返回 valid=false 表示无可用凭据（不降级为无鉴权调用）。
SttConfig ResolveSttFromVault(const AppSettings& settings,
                              const ProviderVault& vault,
                              const std::string& defaultSttProtocol,
                              const std::string& defaultSttModel);

// 是否需要文本润色（即 LLM 开关是否开启）。
bool ShouldPolish(const AppSettings& settings);

// 语音转文本的最终输出：润色关闭时直接返回原始识别文本；
// 润色开启时返回润色结果（润色结果为空则回退原始文本）。
std::string DecideDictationOutput(bool polishEnabled, const std::string& rawStt,
                                  const std::string& polished);

} // namespace ai
} // namespace xime
