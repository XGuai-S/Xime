// WinMM 麦克风采集（PCM16 单声道），产出 int16 采样交给 Wav::EncodeWav16Mono。
// 仅 Windows；无设备/无权限时 Start 返回 false，不崩溃。
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

struct HWAVEIN__; // 仅占位，实际用 void* 句柄

namespace xime {
namespace voice {

class AudioRecorder {
public:
    // 采集线程上下文（需在 .cpp 定义；公开仅为线程函数可访问）。
    struct CaptureCtx {
        void* hwi = nullptr;
        std::atomic<bool>* stop = nullptr;
        std::vector<int16_t>* out = nullptr;
        int sampleRate = 16000;
    };

    AudioRecorder() = default;
    ~AudioRecorder();

    AudioRecorder(const AudioRecorder&) = delete;
    AudioRecorder& operator=(const AudioRecorder&) = delete;

    // 用默认输入设备开始录音；sampleRate 常用 16000。失败写 err 返回 false。
    bool Start(int sampleRate, std::string* err);
    // 停止并取回采集到的 PCM16 采样（阻塞至内部线程结束）。
    std::vector<int16_t> Stop();
    bool IsRecording() const { return recording_; }

    // 列举报废：枚举系统中的音频输入设备数量（0=无设备）。
    static int InputDeviceCount();

private:
    void* device_ = nullptr;   // HWAVEIN
    void* thread_ = nullptr;   // 采集线程句柄
    bool recording_ = false;
    int sampleRate_ = 16000;
    std::vector<int16_t> samples_; // 线程写，Stop 读（停止后安全）
    std::shared_ptr<std::atomic<bool>> stopFlag_;
    std::unique_ptr<CaptureCtx> ctx_;
};

} // namespace voice
} // namespace xime
