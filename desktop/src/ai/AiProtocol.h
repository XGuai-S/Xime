// AI 协议层（OpenAI 兼容）：请求体构造 + 响应解析。纯逻辑，无网络。
#pragma once

#include <string>

namespace xime {
namespace ai {

struct ChatRequestOptions {
    std::string model;
    std::string systemPrompt;
    std::string userText;
    double temperature = 0.2;
    bool includeTemperature = true;
    int maxTokens = 0; // >0 时附加 max_tokens
};

// 构造 /v1/chat/completions 请求体（JSON 字符串）。
// messages 顺序：system（非空时）在前，user 在后。
std::string BuildChatRequestBody(const ChatRequestOptions& opts);

// 从 chat 响应 JSON 提取 choices[0].message.content；缺失返回空串。
std::string ExtractChatContent(const std::string& responseJson);

// 端点解析（对齐安卓 EndpointResolver）。
std::string ResolveChatEndpoint(const std::string& baseUrl);
std::string ResolveSttEndpoint(const std::string& baseUrl);

} // namespace ai
} // namespace xime
