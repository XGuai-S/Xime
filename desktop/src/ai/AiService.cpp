#include "AiService.h"

#include "AiProtocol.h"

namespace xime {
namespace ai {

namespace {

constexpr const char* kPolishSystemPrompt =
    "你是中文输入法的文本润色助手。请在保持原意和语气的前提下，修正错别字、"
    "补全标点、理顺语序，只输出润色后的正文，不要解释。";

} // namespace

DictationResult FinalizeDictation(const AppSettings& settings,
                                  const ProviderVault& vault,
                                  const std::string& rawStt,
                                  const HttpPostFn& http) {
    DictationResult result;
    result.text = rawStt;

    // 润色关闭：直接输出原始 STT，绝不发起 LLM 请求。
    if (!ShouldPolish(settings)) {
        result.polished = false;
        return result;
    }

    // 润色开启：需要 LLM 服务商凭据。
    const std::string providerId = settings.llm.providerId;
    const std::string apiKey = vault.KeyOf(providerId);
    if (apiKey.empty()) {
        // 无凭据：降级为直出原文（仍算成功，但标记未润色）。
        result.polished = false;
        result.error = "no_llm_credential";
        return result;
    }

    ChatRequestOptions opts;
    opts.model = settings.llm.model;
    opts.systemPrompt = kPolishSystemPrompt;
    opts.userText = rawStt;
    opts.temperature = 0.2;

    AiHttpRequest req;
    req.method = "POST";
    req.url = ResolveChatEndpoint(vault.BaseUrlOf(providerId));
    req.body = BuildChatRequestBody(opts);
    req.headers["Content-Type"] = "application/json";
    req.headers["Authorization"] = "Bearer " + apiKey;

    AiHttpResponse resp;
    if (!http || !http(req, resp)) {
        result.polished = false;
        result.error = "http_failed";
        return result;
    }
    if (resp.status < 200 || resp.status >= 300) {
        result.polished = false;
        result.error = "http_status_" + std::to_string(resp.status);
        return result;
    }

    const std::string polished = ExtractChatContent(resp.body);
    // 统一走 DecideDictationOutput：非空用润色，空回退原文。
    result.text = DecideDictationOutput(true, rawStt, polished);
    result.polished = !polished.empty();
    return result;
}

HelpWriteResult HelpWrite(const AppSettings& settings,
                          const ProviderVault& vault,
                          const std::string& selectedText,
                          const std::string& instruction,
                          const HttpPostFn& http) {
    HelpWriteResult result;
    result.text = selectedText;
    if (selectedText.empty()) {
        result.error = "empty_text";
        return result;
    }

    const std::string providerId = settings.llm.providerId;
    const std::string apiKey = vault.KeyOf(providerId);
    if (apiKey.empty()) {
        result.error = "no_llm_credential";
        return result;
    }

    const std::string systemPrompt =
        "你是中文输入法的 AI 帮写助手。严格按用户指令改写给定文本，"
        "只输出改写后的正文，不要解释、不要复述指令。";
    const std::string userText = "指令：" + instruction + "\n\n文本：" + selectedText;

    ChatRequestOptions opts;
    opts.model = settings.llm.model;
    opts.systemPrompt = systemPrompt;
    opts.userText = userText;
    opts.temperature = 0.35;

    AiHttpRequest req;
    req.method = "POST";
    req.url = ResolveChatEndpoint(vault.BaseUrlOf(providerId));
    req.body = BuildChatRequestBody(opts);
    req.headers["Content-Type"] = "application/json";
    req.headers["Authorization"] = "Bearer " + apiKey;

    AiHttpResponse resp;
    if (!http || !http(req, resp)) {
        result.error = "http_failed";
        return result;
    }
    if (resp.status < 200 || resp.status >= 300) {
        result.error = "http_status_" + std::to_string(resp.status);
        return result;
    }
    const std::string rewritten = ExtractChatContent(resp.body);
    if (rewritten.empty()) {
        result.error = "empty_result";
        return result;
    }
    result.text = rewritten;
    result.ok = true;
    return result;
}

} // namespace ai
} // namespace xime
