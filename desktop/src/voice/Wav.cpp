#include "Wav.h"

#include <cstring>

namespace xime {
namespace voice {

namespace {
void PutU32(std::string& s, uint32_t v) {
    s.push_back(static_cast<char>(v & 0xFF));
    s.push_back(static_cast<char>((v >> 8) & 0xFF));
    s.push_back(static_cast<char>((v >> 16) & 0xFF));
    s.push_back(static_cast<char>((v >> 24) & 0xFF));
}
void PutU16(std::string& s, uint16_t v) {
    s.push_back(static_cast<char>(v & 0xFF));
    s.push_back(static_cast<char>((v >> 8) & 0xFF));
}
uint32_t ReadU32(const std::string& s, size_t o) {
    return static_cast<uint8_t>(s[o]) | (static_cast<uint8_t>(s[o + 1]) << 8) |
           (static_cast<uint8_t>(s[o + 2]) << 16) |
           (static_cast<uint8_t>(s[o + 3]) << 24);
}
uint16_t ReadU16(const std::string& s, size_t o) {
    return static_cast<uint8_t>(s[o]) | (static_cast<uint8_t>(s[o + 1]) << 8);
}
} // namespace

std::string EncodeWav16Mono(const std::vector<int16_t>& samples, int sampleRate) {
    const uint32_t dataBytes = static_cast<uint32_t>(samples.size() * 2);
    std::string out;
    out.reserve(44 + dataBytes);
    out += "RIFF";
    PutU32(out, 36 + dataBytes);
    out += "WAVE";
    out += "fmt ";
    PutU32(out, 16);                 // fmt chunk size
    PutU16(out, 1);                  // PCM
    PutU16(out, 1);                  // mono
    PutU32(out, static_cast<uint32_t>(sampleRate));
    PutU32(out, static_cast<uint32_t>(sampleRate) * 2); // byte rate
    PutU16(out, 2);                  // block align
    PutU16(out, 16);                 // bits per sample
    out += "data";
    PutU32(out, dataBytes);
    for (int16_t s : samples) {
        PutU16(out, static_cast<uint16_t>(s));
    }
    return out;
}

bool ParseWavInfo(const std::string& wav, int* sampleRate, int* dataBytes,
                  int* channels, int* bitsPerSample) {
    if (wav.size() < 44 || wav.compare(0, 4, "RIFF") != 0 ||
        wav.compare(8, 4, "WAVE") != 0) {
        return false;
    }
    if (wav.compare(12, 4, "fmt ") != 0) {
        return false;
    }
    const uint16_t ch = ReadU16(wav, 22);
    const uint32_t sr = ReadU32(wav, 24);
    const uint16_t bits = ReadU16(wav, 34);
    if (wav.compare(36, 4, "data") != 0) {
        return false;
    }
    const uint32_t db = ReadU32(wav, 40);
    if (sampleRate) *sampleRate = static_cast<int>(sr);
    if (dataBytes) *dataBytes = static_cast<int>(db);
    if (channels) *channels = ch;
    if (bitsPerSample) *bitsPerSample = bits;
    return true;
}

} // namespace voice
} // namespace xime
