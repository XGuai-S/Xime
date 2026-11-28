#include "VoiceService.h"

#include "../ai/AiHttp.h"
#include "../ai/AiProtocol.h"

#include <atomic>
#include <cstdio>

namespace xime {
namespace voice {

namespace {
std::string MakeBoundary() {
    // 进程内递增，避免同进程多次请求重复。
    static std::atomic<unsigned> counter{0};
    const unsigned n = ++counter;
    char buf[64];
    std::snprintf(buf, sizeof(buf), "----ximevoice%u", n);
    return buf;
}
} // namespace

SttResult Transcribe(const std::string& wavBytes, const SttConfig& config,
                     int timeoutMs) {
    SttResult result;
    if (wavBytes.empty()) {
        result.error = "empty audio";
        return result;
    }
    const std::string model = config.model.empty() ? "whisper-1" : config.model;
    const std::string boundary = MakeBoundary();
    std::string contentType;
    const std::string body = ai::BuildSttMultipartBody(
        boundary, model, "audio.wav", wavBytes, contentType);

    std::map<std::string, std::string> headers;
    headers["Content-Type"] = contentType;
    if (!config.apiKey.empty()) {
        headers["Authorization"] = "Bearer " + config.apiKey;
    }

    const std::string url = ai::ResolveSttEndpoint(config.baseUrl);
    const ai::HttpPostResult http = ai::HttpPost(url, headers, body, timeoutMs);
    if (!http.ok) {
        result.error = http.error;
        return result;
    }
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

} // namespace voice
} // namespace xime
