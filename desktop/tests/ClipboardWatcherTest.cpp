// 剪贴板自动捕获：写系统剪贴板 → PollOnce → 入历史；重复不重复入库。
#include "../src/clipboard/ClipboardWatcher.h"
#include "../src/clipboard/ClipboardStore.h"
#include "../src/clipboard/WinClipboard.h"

#include <cstdio>
#include <string>

using namespace xime::clipboard;

namespace {
int fails = 0;
void Check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++fails;
    }
}
} // namespace

int main() {
    ClipboardStore store;
    ClipboardWatcher watcher;
    watcher.SetCaptureImages(false); // 文本路径即可，图片系统剪贴板易受环境影响

    // 1. 写文本 → 捕获
    WriteClipboardText("捕获测试-1");
    Check(watcher.PollOnce(store, 1), "first capture");
    Check(store.HistorySize() == 1, "one item after first");

    // 2. 同内容再 poll → 不重复
    Check(!watcher.PollOnce(store, 2), "same content not re-captured");
    Check(store.HistorySize() == 1, "still one item");

    // 3. 换内容 → 捕获
    WriteClipboardText("捕获测试-2");
    Check(watcher.PollOnce(store, 3), "second capture");
    Check(store.HistorySize() == 2, "two items");
    auto items = store.VisibleItems(10);
    Check(!items.empty() && items[0].text == "捕获测试-2", "latest first");

    if (fails == 0) {
        std::printf("CLIPWATCH_TEST_OK\n");
        return 0;
    }
    std::printf("CLIPWATCH_TEST_FAILS=%d\n", fails);
    return 1;
}
