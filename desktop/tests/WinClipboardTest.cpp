// Win32 剪贴板读写往返测试（真实 OS 剪贴板）。
#include "../src/clipboard/WinClipboard.h"

#include <cstdio>
#include <string>

using namespace xime::clipboard;

int main() {
    const std::string payload = "你好 Xime 剪贴板 123 😀";
    if (!WriteClipboardText(payload)) {
        std::printf("WINCLIP_FAIL: write failed\n");
        return 1;
    }
    const std::string back = ReadClipboardText();
    if (back != payload) {
        std::printf("WINCLIP_FAIL: got '%s'\n", back.c_str());
        return 1;
    }
    std::printf("WINCLIP_TEST_OK\n");
    return 0;
}
