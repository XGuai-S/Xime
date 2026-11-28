#include "VoiceService.h"

#include "../ai/AiHttp.h"
#include "../ai/AiProtocol.h"

#include <atomic>
#include <cstdio>
#include <map>

namespace xime {
namespace voice {

namespace {

std::string MakeBoundary() {
    static std::atomic<unsigned> counter{0};
    const unsigned n = ++counter;
    char buf[64];
    std::snprintf(buf, sizeof(buf), "----ximevoice%u", n);
    return buf;
}

// 构造请求 + 解析响应（无网络的公共部分）。
ai::AiHttpRequest BuildRequest(const std::string& wavBytes,
                               const SttConfig& config) {
    const std::string model = config.model.empty() ? "whisper-1" : config.model;
    const std::string boundary = MakeBoundary();
    std::string contentType;
    const std::string body = ai::BuildSttMultipartBody(
        boundary, model, "audio.wav", wavBytes, contentType);

    ai::AiHttpRequest req;
    req.method = "POST";
    req.url = ai::ResolveSttEndpoint(config.baseUrl);
    req.body = body;
    req.headers["Content-Type"] = contentType;
    if (!config.apiKey.empty()) {
        req.headers["Authorization"] = "Bearer " + config.apiKey;
    }
    return req;
}

SttResult ParseResponse(const ai::AiHttpResponse& http) {
    SttResult result;
    if (http.status < 200 || http.status >= 300) {
        result.error = "http " + std::to_string(http.status);
        return result;
    }
    result.text = ai::ExtractSttText(http.body);
    if (result.text.empty()) {
        result.error = "empty stt text";
        return result;
    }
    result.ok = true;
    return result;
}

} // namespace

SttResult TranscribeWith(const std::string& wavBytes, const SttConfig& config,
                         const ai::HttpPostFn& http) {
    SttResult result;
    if (wavBytes.empty()) {
        result.error = "empty audio";
        return result;
    }
    const ai::AiHttpRequest req = BuildRequest(wavBytes, config);
    if (!http) {
        result.error = "no http";
        return result;
    }
    ai::AiHttpResponse resp;
    if (!http(req, resp)) {
        result.error = "http_failed";
        return result;
    }
    return ParseResponse(resp);
}

SttResult Transcribe(const std::string& wavBytes, const SttConfig& config,
                     int timeoutMs) {
    SttResult result;
    if (wavBytes.empty()) {
        result.error = "empty audio";
        return result;
    }
    const ai::AiHttpRequest req = BuildRequest(wavBytes, config);
    const ai::HttpPostResult http =
        ai::HttpPost(req.url, req.headers, req.body, timeoutMs);
    if (!http.ok) {
        result.error = http.error;
        return result;
    }
    ai::AiHttpResponse resp;
    resp.status = http.status;
    resp.body = http.body;
    return ParseResponse(resp);
}

} // namespace voice
} // namespace xime
