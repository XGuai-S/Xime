#include "AiProviderConfig.h"

namespace xime {
namespace ai {

void ProviderVault::SetKey(const std::string& id, const std::string& apiKey) {
    if (id.empty()) {
        return;
    }
    creds_[id].id = id;
    creds_[id].apiKey = apiKey;
}

void ProviderVault::SetBaseUrl(const std::string& id, const std::string& baseUrl) {
    if (id.empty()) {
        return;
    }
    creds_[id].id = id;
    creds_[id].baseUrl = baseUrl;
}

bool ProviderVault::HasKey(const std::string& id) const {
    const auto it = creds_.find(id);
    return it != creds_.end() && !it->second.apiKey.empty();
}

std::string ProviderVault::KeyOf(const std::string& id) const {
    const auto it = creds_.find(id);
    return it == creds_.end() ? "" : it->second.apiKey;
}

std::string ProviderVault::BaseUrlOf(const std::string& id) const {
    const auto it = creds_.find(id);
    return it == creds_.end() ? "" : it->second.baseUrl;
}

SttConfig ResolveSttFromVault(const AppSettings& settings,
                              const ProviderVault& vault,
                              const std::string& defaultSttProtocol,
                              const std::string& defaultSttModel) {
    SttConfig cfg;
    // 语音服务商优先用专用设置，缺省复用 AI 服务商。
    const std::string providerId = settings.speechProviderId.empty()
                                       ? settings.llm.providerId
                                       : settings.speechProviderId;
    if (providerId.empty()) {
        return cfg;
    }
    // 密钥：专用语音密钥优先；否则复用该服务商的 AI 密钥。
    std::string apiKey = settings.sttApiKey;
    if (apiKey.empty()) {
        apiKey = vault.KeyOf(providerId);
    }
    if (apiKey.empty()) {
        return cfg; // 无凭据：不返回半配置
    }
    cfg.valid = true;
    cfg.providerId = providerId;
    cfg.apiKey = apiKey;
    cfg.baseUrl = vault.BaseUrlOf(providerId);
    cfg.model = defaultSttModel;
    cfg.protocol = defaultSttProtocol;
    return cfg;
}

bool ShouldPolish(const AppSettings& settings) {
    return settings.llm.enabled;
}

std::string DecideDictationOutput(bool polishEnabled, const std::string& rawStt,
                                  const std::string& polished) {
    if (!polishEnabled) {
        return rawStt; // 关闭润色：直接输出原始 STT
    }
    return polished.empty() ? rawStt : polished;
}

} // namespace ai
} // namespace xime
