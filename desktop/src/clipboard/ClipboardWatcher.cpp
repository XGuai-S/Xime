#include "ClipboardWatcher.h"

#include "ClipboardStore.h"
#include "WinClipboard.h"

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace xime {
namespace clipboard {

namespace {

std::string HashBytes(const std::vector<uint8_t>& bytes) {
    uint64_t h = 1469598103934665603ULL;
    for (uint8_t b : bytes) {
        h ^= b;
        h *= 1099511628211ULL;
    }
    char buf[24];
    std::snprintf(buf, sizeof(buf), "%016llx",
                  static_cast<unsigned long long>(h));
    return buf;
}

} // namespace

void ClipboardWatcher::Reset() {
    lastText_.clear();
    lastImageHash_.clear();
    haveText_ = false;
    haveImage_ = false;
}

bool ClipboardWatcher::PollOnce(ClipboardStore& store, long long nowMs) {
    bool changed = false;

    // 文本
    const std::string text = ReadClipboardText();
    if (!text.empty() && (!haveText_ || text != lastText_)) {
        store.AddText(text, nowMs);
        lastText_ = text;
        haveText_ = true;
        changed = true;
    }

    // 图片
    if (captureImages_) {
        const std::vector<uint8_t> dib = ReadClipboardImage();
        if (!dib.empty()) {
            const std::string hash = HashBytes(dib);
            if (!haveImage_ || hash != lastImageHash_) {
                int w = 0, h = 0, bpp = 0;
                size_t off = 0;
                if (ParseDibHeader(dib, w, h, bpp, off)) {
                    store.AddImage(dib, w, h, nowMs);
                    changed = true;
                }
                lastImageHash_ = hash;
                haveImage_ = true;
            }
        }
    }
    return changed;
}

} // namespace clipboard
} // namespace xime
