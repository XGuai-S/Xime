#include "AudioRecorder.h"

#include <windows.h>
#include <mmsystem.h>

#include <atomic>

namespace xime {
namespace voice {

namespace {

DWORD WINAPI CaptureThread(LPVOID param) {
    auto* ctx = static_cast<AudioRecorder::CaptureCtx*>(param);
    const HWAVEIN hwi = reinterpret_cast<HWAVEIN>(ctx->hwi);
    constexpr DWORD kBufferMs = 100;
    constexpr int kChannels = 1;
    constexpr int kBits = 16;

    // 采样率由 Start 通过 ctx 传入（避免部分 MinGW 缺失 waveInGetFormat）。
    const DWORD perBuf =
        static_cast<DWORD>(ctx->sampleRate) * kBufferMs / 1000 *
        kChannels * (kBits / 8);
    const int buffers = 4;

    std::vector<std::vector<uint8_t>> chunks(buffers,
                                             std::vector<uint8_t>(perBuf, 0));
    std::vector<WAVEHDR> headers(buffers);
    for (int i = 0; i < buffers; ++i) {
        headers[i].lpData = reinterpret_cast<LPSTR>(chunks[i].data());
        headers[i].dwBufferLength = perBuf;
        headers[i].dwFlags = 0;
        waveInPrepareHeader(hwi, &headers[i], sizeof(WAVEHDR));
        waveInAddBuffer(hwi, &headers[i], sizeof(WAVEHDR));
    }
    waveInStart(hwi);

    while (!ctx->stop->load()) {
        bool anyDone = false;
        for (int i = 0; i < buffers; ++i) {
            if ((headers[i].dwFlags & WHDR_DONE) && headers[i].dwBufferLength > 0) {
                const size_t n = headers[i].dwBufferLength / sizeof(int16_t);
                auto* pcm = reinterpret_cast<int16_t*>(headers[i].lpData);
                ctx->out->insert(ctx->out->end(), pcm, pcm + n);
                headers[i].dwFlags &= ~WHDR_DONE;
                waveInUnprepareHeader(hwi, &headers[i], sizeof(WAVEHDR));
                waveInPrepareHeader(hwi, &headers[i], sizeof(WAVEHDR));
                headers[i].dwBufferLength = perBuf;
                waveInAddBuffer(hwi, &headers[i], sizeof(WAVEHDR));
                anyDone = true;
            }
        }
        if (!anyDone) {
            Sleep(10);
        }
    }

    waveInStop(hwi);
    waveInReset(hwi);
    for (int i = 0; i < buffers; ++i) {
        waveInUnprepareHeader(hwi, &headers[i], sizeof(WAVEHDR));
    }
    return 0;
}

} // namespace

int AudioRecorder::InputDeviceCount() {
    return static_cast<int>(waveInGetNumDevs());
}

bool AudioRecorder::Start(int sampleRate, std::string* err) {
    if (recording_) {
        if (err) {
            *err = "already recording";
        }
        return false;
    }
    if (waveInGetNumDevs() == 0) {
        if (err) {
            *err = "no input device";
        }
        return false;
    }
    sampleRate_ = sampleRate > 0 ? sampleRate : 16000;

    WAVEFORMATEX fmt = {};
    fmt.wFormatTag = WAVE_FORMAT_PCM;
    fmt.nChannels = 1;
    fmt.nSamplesPerSec = static_cast<DWORD>(sampleRate_);
    fmt.wBitsPerSample = 16;
    fmt.nBlockAlign = static_cast<WORD>(fmt.nChannels * fmt.wBitsPerSample / 8);
    fmt.nAvgBytesPerSec = fmt.nSamplesPerSec * fmt.nBlockAlign;
    fmt.cbSize = 0;

    HWAVEIN hwi = nullptr;
    const MMRESULT mr = waveInOpen(&hwi, WAVE_MAPPER, &fmt, 0, 0, CALLBACK_NULL);
    if (mr != MMSYSERR_NOERROR || !hwi) {
        if (err) {
            *err = "waveInOpen failed";
        }
        return false;
    }

    device_ = hwi;
    samples_.clear();
    stopFlag_ = std::make_shared<std::atomic<bool>>(false);
    ctx_ = std::make_unique<CaptureCtx>();
    ctx_->hwi = hwi;
    ctx_->stop = stopFlag_.get();
    ctx_->out = &samples_;
    ctx_->sampleRate = sampleRate_;
    thread_ = CreateThread(nullptr, 0, CaptureThread, ctx_.get(), 0, nullptr);
    if (!thread_) {
        waveInClose(hwi);
        device_ = nullptr;
        ctx_.reset();
        stopFlag_.reset();
        if (err) {
            *err = "CreateThread failed";
        }
        return false;
    }
    recording_ = true;
    return true;
}

std::vector<int16_t> AudioRecorder::Stop() {
    if (!recording_) {
        return {};
    }
    stopFlag_->store(true);
    if (thread_) {
        WaitForSingleObject(thread_, 3000);
        CloseHandle(thread_);
        thread_ = nullptr;
    }
    waveInClose(static_cast<HWAVEIN>(device_));
    device_ = nullptr;
    recording_ = false;
    ctx_.reset();
    stopFlag_.reset();
    return samples_;
}

AudioRecorder::~AudioRecorder() {
    if (recording_) {
        Stop();
    }
}

} // namespace voice
} // namespace xime
