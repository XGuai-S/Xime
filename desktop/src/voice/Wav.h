// WAV（PCM 16-bit 单声道）编码，供语音走 AI STT 上传使用。纯逻辑。
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace xime {
namespace voice {

// 把 int16 PCM 采样编码为 WAV 字节（44 字节头 + little-endian 数据）。
std::string EncodeWav16Mono(const std::vector<int16_t>& samples, int sampleRate);

// 解析 WAV 头，返回采样率与数据字节数（测试用）；非 WAV 返回 false。
bool ParseWavInfo(const std::string& wav, int* sampleRate, int* dataBytes,
                  int* channels, int* bitsPerSample);

} // namespace voice
} // namespace xime
