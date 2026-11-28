// Win32 剪贴板读写往返测试（真实 OS 剪贴板）：文本 + 位图(DIB)。
#include "../src/clipboard/WinClipboard.h"

#include <windows.h>

#include <cstdio>
#include <string>
#include <vector>

using namespace xime::clipboard;

int main() {
    // 1. 文本往返
    const std::string payload = "你好 Xime 剪贴板 123 😀";
    if (!WriteClipboardText(payload)) {
        std::printf("WINCLIP_FAIL: text write\n");
        return 1;
    }
    if (ReadClipboardText() != payload) {
        std::printf("WINCLIP_FAIL: text mismatch\n");
        return 1;
    }

    // 2. 位图往返：3x2 纯色（RGB=10,20,30），DIB 内应为 BGR。
    const int w = 3, h = 2;
    std::vector<uint8_t> rgb(static_cast<size_t>(w) * h * 3);
    for (size_t i = 0; i < rgb.size(); i += 3) {
        rgb[i] = 10; rgb[i + 1] = 20; rgb[i + 2] = 30;
    }
    const std::vector<uint8_t> dib = BuildDib24(w, h, rgb);
    if (dib.size() < sizeof(BITMAPINFOHEADER)) {
        std::printf("WINCLIP_FAIL: build dib\n");
        return 1;
    }
    if (!WriteClipboardImage(dib)) {
        std::printf("WINCLIP_FAIL: image write\n");
        return 1;
    }
    const std::vector<uint8_t> back = ReadClipboardImage();
    if (back.empty()) {
        std::printf("WINCLIP_FAIL: image read empty\n");
        return 1;
    }
    int rw = 0, rh = 0, bpp = 0;
    size_t off = 0;
    if (!ParseDibHeader(back, rw, rh, bpp, off)) {
        std::printf("WINCLIP_FAIL: parse header\n");
        return 1;
    }
    if (rw != w || (rh != h && rh != -h) || bpp != 24) {
        std::printf("WINCLIP_FAIL: dims w=%d h=%d bpp=%d\n", rw, rh, bpp);
        return 1;
    }
    if (off + 3 > back.size() || back[off] != 30 || back[off + 1] != 20 ||
        back[off + 2] != 10) {
        std::printf("WINCLIP_FAIL: pixel BGR mismatch\n");
        return 1;
    }

    std::printf("WINCLIP_TEST_OK\n");
    return 0;
}
