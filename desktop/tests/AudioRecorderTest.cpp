// 真实麦克风采集器 headless 验证：无设备/无权限时优雅失败（不崩溃），
// 设备计数与 Start/Stop 生命周期安全。真机录音在有设备环境另行验证。
#include "../src/voice/AudioRecorder.h"
#include "../src/voice/Wav.h"

#include <windows.h>

#include <cstdio>
#include <string>

using namespace xime::voice;

int main() {
    const int count = AudioRecorder::InputDeviceCount();
    std::printf("RECORDER_DEVICES=%d\n", count);

    AudioRecorder rec;
    std::string err;
    const bool started = rec.Start(16000, &err);

    if (!started) {
        // headless/无设备：必须给出明确错误且不可进入录音态。
        if (err.empty()) {
            std::printf("FAIL: no error on failed start\n");
            return 1;
        }
        if (rec.IsRecording()) {
            std::printf("FAIL: recording flag set on failure\n");
            return 1;
        }
        if (!rec.Stop().empty()) {
            std::printf("FAIL: samples on failed start\n");
            return 1;
        }
        std::printf("RECORDER_NODEV_OK err=%s\n", err.c_str());
        return 0;
    }

    // 有设备：短录一段再停，校验采样非空且能编码为合法 WAV。
    if (!rec.IsRecording()) {
        std::printf("FAIL: recording flag not set\n");
        return 1;
    }
    Sleep(200);
    auto pcm = rec.Stop();
    if (rec.IsRecording()) {
        std::printf("FAIL: still recording after stop\n");
        return 1;
    }
    if (pcm.empty()) {
        std::printf("RECORDER_NO_SAMPLES (device present but silent)\n");
        return 0; // 设备存在但无输入也算合理
    }
    const std::string wav = EncodeWav16Mono(pcm, 16000);
    int sr = 0, bytes = 0, ch = 0, bits = 0;
    if (!ParseWavInfo(wav, &sr, &bytes, &ch, &bits)) {
        std::printf("FAIL: invalid wav\n");
        return 1;
    }
    if (sr != 16000 || ch != 1 || bits != 16) {
        std::printf("FAIL: wav format\n");
        return 1;
    }
    std::printf("RECORDER_CAPTURE_OK samples=%zu\n", pcm.size());
    return 0;
}
