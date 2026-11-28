// 语音转写服务：把 WAV 音频走 AI STT 上传（OpenAI 兼容 /v1/audio/transcriptions）。
#pragma once

#include <string>

namespace xime {
namespace voice {

struct SttConfig {
    std::string baseUrl;
    std::string apiKey;
    std::string model;
};

struct SttResult {
    bool ok = false;
    std::string text;
    std::string error;
};

// 上传 wav 音频到 STT 端点并解析 text。baseUrl 为空时用 OpenAI 默认。
SttResult Transcribe(const std::string& wavBytes, const SttConfig& config,
                     int timeoutMs = 30000);

} // namespace voice
} // namespace xime
