// 语音全链路真机验证：麦克风录音 → WAV → multipart → AI STT → 润色/直出。
// 本机回环 STT 服务器（不连外网）；录音用真实 WinMM 设备。
#include "../src/voice/AudioRecorder.h"
#include "../src/voice/Wav.h"
#include "../src/voice/VoiceService.h"
#include "../src/ai/AiService.h"

#include <windows.h>

#include <cstdio>
#include <string>

using namespace xime::voice;
using namespace xime::ai;

int main() {
    // 1. 真实录音
    AudioRecorder rec;
    std::string err;
    if (!rec.Start(16000, &err)) {
        std::printf("VOICECHAIN_SKIP: no mic (%s)\n", err.c_str());
        return 0;
    }
    Sleep(300);
    auto pcm = rec.Stop();
    if (pcm.empty()) {
        std::printf("VOICECHAIN_SKIP: no samples\n");
        return 0;
    }
    const std::string wav = EncodeWav16Mono(pcm, 16000);
    std::printf("VOICECHAIN_WAV=%zu samples=%zu\n", wav.size(), pcm.size());

    // 2. STT（注入回环 http：收到 multipart 返回固定文本）
    xime::voice::SttConfig stt;
    stt.model = "whisper-1";
    stt.baseUrl = "http://127.0.0.1:0";
    stt.apiKey = "TESTKEY";
    auto http = [&](const AiHttpRequest& req, AiHttpResponse& resp) {
        auto ct = req.headers.find("Content-Type");
        if (ct == req.headers.end() ||
            ct->second.find("multipart/form-data") == std::string::npos) {
            resp.status = 400;
            return true;
        }
        if (req.body.find("name=\"model\"") == std::string::npos ||
            req.body.find("name=\"file\"") == std::string::npos) {
            resp.status = 400;
            return true;
        }
        resp.status = 200;
        resp.body = "{\"text\":\"今天天气不错\"}";
        return true;
    };
    auto sr = TranscribeWith(wav, stt, http);
    if (!sr.ok || sr.text != "今天天气不错") {
        std::printf("VOICECHAIN_FAIL: stt ok=%d text=%s err=%s\n", sr.ok, sr.text.c_str(), sr.error.c_str());
        return 1;
    }
    std::printf("VOICECHAIN_STT=%s\n", sr.text.c_str());

    // 3. 润色关 → 直出原文（与安卓一致）
    AppSettings settings;
    settings.llm.enabled = false;
    ProviderVault vault;
    vault.SetKey("siliconflow", "TESTKEY");
    int llmCalls = 0;
    auto llmHttp = [&](const AiHttpRequest&, AiHttpResponse&) {
        ++llmCalls; return true;
    };
    auto final = FinalizeDictation(settings, vault, sr.text, llmHttp);
    if (!final.ok || final.text != "今天天气不错" || final.polished || llmCalls != 0) {
        std::printf("VOICECHAIN_FAIL: finalize\n");
        return 1;
    }
    std::printf("VOICECHAIN_FINAL=%s calls=%d\n", final.text.c_str(), llmCalls);
    std::printf("VOICECHAIN_OK\n");
    return 0;
}
