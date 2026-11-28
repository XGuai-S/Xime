#include "AiProtocol.h"

#include "Json.h"

#include <cstdio>

namespace xime {
namespace ai {

namespace {

std::string FormatDouble(double v) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%g", v);
    return buf;
}

void AppendMessage(std::string& out, bool& first, const std::string& role,
                   const std::string& content) {
    if (!first) {
        out += ",";
    }
    first = false;
    out += "{\"role\":";
    out += JsonEscape(role);
    out += ",\"content\":";
    out += JsonEscape(content);
    out += "}";
}

} // namespace

std::string BuildChatRequestBody(const ChatRequestOptions& opts) {
    std::string out = "{";
    out += "\"model\":";
    out += JsonEscape(opts.model);
    out += ",\"messages\":[";
    bool first = true;
    if (!opts.systemPrompt.empty()) {
        AppendMessage(out, first, "system", opts.systemPrompt);
    }
    AppendMessage(out, first, "user", opts.userText);
    out += "]";
    if (opts.includeTemperature) {
        out += ",\"temperature\":";
        out += FormatDouble(opts.temperature);
    }
    if (opts.maxTokens > 0) {
        out += ",\"max_tokens\":";
        out += std::to_string(opts.maxTokens);
    }
    out += ",\"stream\":false}";
    return out;
}

std::string ExtractChatContent(const std::string& responseJson) {
    const JsonValue root = JsonParse(responseJson);
    const JsonValue* choices = root.Get("choices");
    if (!choices || !choices->IsArray() || choices->arrayValue.empty()) {
        return "";
    }
    const JsonValue* message = choices->At(0)->Get("message");
    if (!message) {
        return "";
    }
    return message->GetString("content");
}

namespace {

// 去掉尾部 '/'。
std::string TrimTrailingSlash(const std::string& s) {
    std::string out = s;
    while (!out.empty() && out.back() == '/') {
        out.pop_back();
    }
    return out;
}

} // namespace

std::string ResolveChatEndpoint(const std::string& baseUrl) {
    if (baseUrl.empty()) {
        return "https://api.openai.com/v1/chat/completions";
    }
    const std::string base = TrimTrailingSlash(baseUrl);
    if (base.size() >= 17 && base.substr(base.size() - 17) == "/chat/completions") {
        return base;
    }
    if (base.size() >= 3 && base.substr(base.size() - 3) == "/v1") {
        return base + "/chat/completions";
    }
    return base + "/v1/chat/completions";
}

std::string ResolveSttEndpoint(const std::string& baseUrl) {
    if (baseUrl.empty()) {
        return "https://api.openai.com/v1/audio/transcriptions";
    }
    const std::string base = TrimTrailingSlash(baseUrl);
    if (base.size() >= 22 &&
        base.substr(base.size() - 22) == "/audio/transcriptions") {
        return base;
    }
    if (base.size() >= 3 && base.substr(base.size() - 3) == "/v1") {
        return base + "/audio/transcriptions";
    }
    return base + "/v1/audio/transcriptions";
}

std::string BuildSttMultipartBody(const std::string& boundary,
                                  const std::string& model,
                                  const std::string& filename,
                                  const std::string& audioData,
                                  std::string& outContentType) {
    outContentType = "multipart/form-data; boundary=" + boundary;
    std::string out;
    out += "--" + boundary + "\r\n";
    out += "Content-Disposition: form-data; name=\"model\"\r\n\r\n";
    out += model + "\r\n";
    out += "--" + boundary + "\r\n";
    out += "Content-Disposition: form-data; name=\"file\"; filename=\"" +
           filename + "\"\r\n";
    out += "Content-Type: application/octet-stream\r\n\r\n";
    out += audioData;
    out += "\r\n--" + boundary + "--\r\n";
    return out;
}

std::string ExtractSttText(const std::string& responseJson) {
    const JsonValue root = JsonParse(responseJson);
    return root.GetString("text");
}

} // namespace ai
} // namespace xime
