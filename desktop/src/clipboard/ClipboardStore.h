// 剪贴板存储（移植安卓 Clipboard 语义）：文本历史 + 置顶 + 去重 + 验证码(OTP)隔离 + 快捷发送。
#pragma once

#include <map>
#include <string>
#include <vector>

namespace xime {
namespace clipboard {

struct ClipItem {
    std::string text;
    bool pinned = false;
    long long tsMs = 0;
};

struct QuickSendItem {
    std::string text;
    std::string code;
    long long tsMs = 0;
};

class ClipboardStore {
public:
    static constexpr size_t kHistoryCapacity = 200;
    static constexpr size_t kOtpCapacity = 5;
    static constexpr long long kOtpTtlMs = 2 * 60 * 1000; // 2 分钟

    // 普通文本入历史：去重（同文移到最前并刷新时间），超出容量淘汰最旧的未置顶项。
    void AddText(const std::string& text, long long nowMs);

    // 验证码入独立的 OTP 列表（最多 5 条），不进普通历史、不进备份。
    void AddOtp(const std::string& text, long long nowMs);

    // 置顶/取消置顶（按文本）。
    bool SetPinned(const std::string& text, bool pinned);
    // 删除普通历史/置顶项（按文本）。
    bool Delete(const std::string& text);

    // 快捷发送：按 (文字, 快捷码) 联合去重。
    void AddQuickSend(const std::string& text, const std::string& code,
                      long long nowMs);

    // 可见普通项：置顶在前、其余按时间倒序；顺带清理过期 OTP。
    std::vector<ClipItem> VisibleItems(long long nowMs);

    std::vector<QuickSendItem> QuickSends() const;

    // 序列化（不含 OTP——隐私隔离）；往返一致。
    std::string Serialize() const;
    bool Deserialize(const std::string& json);

    size_t HistorySize() const { return history_.size(); }
    size_t OtpSize() const { return otp_.size(); }

private:
    std::vector<ClipItem> history_;   // 新在前
    std::vector<ClipItem> otp_;       // 验证码（新在前，含过期时间）
    std::vector<long long> otpExpiry_;
    std::vector<QuickSendItem> quick_; // 新在前
};

} // namespace clipboard
} // namespace xime
