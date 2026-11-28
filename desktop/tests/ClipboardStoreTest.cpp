// 剪贴板存储纯逻辑测试（无 OS 依赖）。
#include "../src/clipboard/ClipboardStore.h"

#include <cstdio>
#include <string>
#include <vector>

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
    ClipboardStore s;
    s.AddText("A", 1000);
    s.AddText("B", 2000);
    s.AddText("A", 3000); // 去重，A 移到最前
    auto v = s.VisibleItems(4000);
    Check(s.HistorySize() == 2, "dedup size 2");
    Check(v.size() == 2 && v[0].text == "A" && v[1].text == "B", "dedup order A,B");

    // 置顶 B
    Check(s.SetPinned("B", true), "pin B");
    v = s.VisibleItems(5000);
    Check(v[0].text == "B" && v[0].pinned, "pinned first");

    // 删除 A
    Check(s.Delete("A"), "delete A");
    Check(s.HistorySize() == 1, "after delete size 1");

    // OTP 隔离 + 过期
    s.AddOtp("123456", 10000);
    Check(s.OtpSize() == 1, "otp added");
    Check(s.VisibleItems(10001).size() == 1, "otp not in normal view");
    s.VisibleItems(10000 + ClipboardStore::kOtpTtlMs + 1); // 过期清理
    Check(s.OtpSize() == 0, "otp expired removed");

    // 快捷发送联合去重
    ClipboardStore q;
    q.AddQuickSend("地址", "dz", 1);
    q.AddQuickSend("地址", "addr", 2);
    q.AddQuickSend("地址", "dz", 3); // 覆盖 (text,dz)
    Check(q.QuickSends().size() == 2, "quicksend dedup");
    Check(q.QuickSends()[0].code == "dz", "latest first");

    // 序列化往返（OTP 不入序列化）
    ClipboardStore a;
    a.AddText("你好", 10);
    a.AddText("世界", 20);
    a.SetPinned("世界", true);
    a.AddQuickSend("邮箱", "yx", 30);
    a.AddOtp("999999", 40);
    const std::string json = a.Serialize();
    ClipboardStore b;
    Check(b.Deserialize(json), "deserialize ok");
    Check(b.HistorySize() == 2, "roundtrip history");
    Check(b.OtpSize() == 0, "otp excluded from serialize");
    auto vb = b.VisibleItems(100);
    Check(vb.size() == 2 && vb[0].text == "世界" && vb[0].pinned, "roundtrip pinned first");
    Check(b.QuickSends().size() == 1 && b.QuickSends()[0].code == "yx", "roundtrip quick");

    // 容量淘汰（非置顶）
    ClipboardStore c;
    c.AddText("keep", 1);
    c.SetPinned("keep", true);
    for (size_t i = 0; i < ClipboardStore::kHistoryCapacity + 5; ++i) {
        c.AddText("n" + std::to_string(i), static_cast<long long>(100 + i));
    }
    size_t nonPinned = 0;
    for (const auto& it : c.VisibleItems(100000)) {
        if (!it.pinned) ++nonPinned;
    }
    Check(nonPinned == ClipboardStore::kHistoryCapacity, "capacity trims non-pinned");
    bool keepSurvived = false;
    for (const auto& it : c.VisibleItems(100000)) {
        if (it.text == "keep" && it.pinned) keepSurvived = true;
    }
    Check(keepSurvived, "pinned survives capacity");

    // 图片：去重 + 容量淘汰
    ClipboardStore img;
    std::vector<uint8_t> imgBytes = {1, 2, 3, 4};
    img.AddImage(imgBytes, 2, 2, 1);
    img.AddImage(imgBytes, 2, 2, 2); // 同内容去重
    Check(img.Images().size() == 1, "image dedup");
    for (size_t i = 0; i < ClipboardStore::kImageCapacity + 5; ++i) {
        std::vector<uint8_t> b = {static_cast<uint8_t>(i), 9, 9, 9, 9};
        img.AddImage(b, 1, 1, static_cast<long long>(100 + i));
    }
    Check(img.Images().size() == ClipboardStore::kImageCapacity, "image capacity");

    if (fails == 0) {
        std::printf("CLIPBOARD_TEST_OK\n");
        return 0;
    }
    std::printf("CLIPBOARD_TEST_FAILS=%d\n", fails);
    return 1;
}
