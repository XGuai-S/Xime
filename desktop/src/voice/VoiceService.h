// 语音转写服务：把 WAV 音频走 AI STT 上传（OpenAI 兼容 /v1/audio/transcriptions）。
#pragma once

#include <string>

#include "../ai/AiService.h"

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

// 可注入传输的实现（便于离线/回环验证全链路）；http 为空时回退到真实网络。
SttResult TranscribeWith(const std::string& wavBytes, const SttConfig& config,
                         const ai::HttpPostFn& http);

} // namespace voice
} // namespace xime
