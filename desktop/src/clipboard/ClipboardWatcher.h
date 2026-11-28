// 剪贴板自动捕获：轮询系统剪贴板，把新文本/图片并入 ClipStore（历史）。
// 与 UI/定时器解耦——PollOnce 做一次快照，便于 headless 测试；
// 生产端可用一个后台线程周期性调用 PollOnce。
#pragma once

#include <cstdint>
#include <string>

namespace xime {
namespace clipboard {

class ClipboardStore;

class ClipboardWatcher {
public:
    // 一次快照：读取系统剪贴板，若与上次已捕获内容不同则并入 store。
    // 文本非空且变化 → AddText；位图存在且变化 → AddImage。返回是否有新内容入历史。
    bool PollOnce(ClipboardStore& store, long long nowMs);

    // 是否把图片也纳入历史（默认 true）。
    void SetCaptureImages(bool enabled) { captureImages_ = enabled; }

    // 重置"上次已见"状态（例如用户手动清空历史后）。
    void Reset();

private:
    std::string lastText_;
    std::string lastImageHash_; // 以内容哈希判断图片是否变化
    bool haveText_ = false;
    bool haveImage_ = false;
    bool captureImages_ = true;
};

} // namespace clipboard
} // namespace xime
