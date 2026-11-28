#include "ClipboardStore.h"

#include "../ai/Json.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>

namespace xime {
namespace clipboard {

namespace {
using xime::ai::JsonEscape;
using xime::ai::JsonParse;
using xime::ai::JsonValue;
} // namespace

void ClipboardStore::AddText(const std::string& text, long long nowMs) {
    if (text.empty()) {
        return;
    }
    // 去重时保留原「置顶」状态：用户置顶的常用语再次复制不应掉出置顶区。
    bool wasPinned = false;
    for (const auto& it : history_) {
        if (it.text == text) {
            wasPinned = wasPinned || it.pinned;
        }
    }
    history_.erase(std::remove_if(history_.begin(), history_.end(),
                                  [&](const ClipItem& it) { return it.text == text; }),
                   history_.end());
    ClipItem item;
    item.text = text;
    item.pinned = wasPinned;
    item.tsMs = nowMs;
    history_.insert(history_.begin(), item);

    // 淘汰最旧的非置顶项，直到非置顶数量 <= 容量。
    // 注意：这里必须用下标正序遍历。原先用反向迭代器 + erase，erase 后 rit
    // 立即失效，下一轮再比较/解引用就是未定义行为（Debug 下直接断言崩溃）。
    size_t nonPinned = 0;
    for (const auto& it : history_) {
        if (!it.pinned) {
            ++nonPinned;
        }
    }
    // 从尾部（最旧）向前收集要删的下标，再从后往前一次性删除。
    std::vector<size_t> doomed;
    for (size_t i = history_.size(); i-- > 0 && nonPinned > kHistoryCapacity;) {
        if (!history_[i].pinned) {
            doomed.push_back(i);
            --nonPinned;
        }
    }
    for (size_t idx : doomed) {
        history_.erase(history_.begin() + static_cast<long>(idx));
    }
}

void ClipboardStore::AddOtp(const std::string& text, long long nowMs) {
    if (text.empty()) {
        return;
    }
    // 同码去重
    for (size_t i = 0; i < otp_.size();) {
        if (otp_[i].text == text) {
            otp_.erase(otp_.begin() + static_cast<long>(i));
            otpExpiry_.erase(otpExpiry_.begin() + static_cast<long>(i));
        } else {
            ++i;
        }
    }
    ClipItem item;
    item.text = text;
    item.tsMs = nowMs;
    otp_.insert(otp_.begin(), item);
    otpExpiry_.insert(otpExpiry_.begin(), nowMs + kOtpTtlMs);
    while (otp_.size() > kOtpCapacity) {
        otp_.pop_back();
        otpExpiry_.pop_back();
    }
}

bool ClipboardStore::SetPinned(const std::string& text, bool pinned) {
    for (auto& it : history_) {
        if (it.text == text) {
            it.pinned = pinned;
            return true;
        }
    }
    return false;
}

bool ClipboardStore::Delete(const std::string& text) {
    const auto before = history_.size();
    history_.erase(std::remove_if(history_.begin(), history_.end(),
                                  [&](const ClipItem& it) { return it.text == text; }),
                   history_.end());
    return history_.size() != before;
}

void ClipboardStore::AddQuickSend(const std::string& text, const std::string& code,
                                  long long nowMs) {
    if (text.empty()) {
        return;
    }
    quick_.erase(std::remove_if(quick_.begin(), quick_.end(),
                                [&](const QuickSendItem& it) {
                                    return it.text == text && it.code == code;
                                }),
                 quick_.end());
    QuickSendItem item;
    item.text = text;
    item.code = code;
    item.tsMs = nowMs;
    quick_.insert(quick_.begin(), item);
}

namespace {
std::string FnvHash(const std::vector<uint8_t>& bytes) {
    uint64_t h = 1469598103934665603ULL;
    for (uint8_t b : bytes) {
        h ^= b;
        h *= 1099511628211ULL;
    }
    char buf[24];
    std::snprintf(buf, sizeof(buf), "%016llx", static_cast<unsigned long long>(h));
    return buf;
}
} // namespace

void ClipboardStore::AddImage(const std::vector<uint8_t>& bytes, int width,
                              int height, long long nowMs) {
    if (bytes.empty()) {
        return;
    }
    const std::string hash = FnvHash(bytes);
    images_.erase(std::remove_if(images_.begin(), images_.end(),
                                 [&](const ClipImage& im) {
                                     return im.hash == hash;
                                 }),
                  images_.end());
    ClipImage img;
    img.hash = hash;
    img.size = bytes.size();
    img.width = width;
    img.height = height;
    img.tsMs = nowMs;
    images_.insert(images_.begin(), img);
    // 淘汰最旧的未置顶项，保留置顶。
    while (images_.size() > kImageCapacity) {
        size_t idx = images_.size();
        for (size_t i = images_.size(); i-- > 0;) {
            if (!images_[i].pinned) {
                idx = i;
                break;
            }
        }
        if (idx == images_.size()) {
            break;
        }
        images_.erase(images_.begin() + static_cast<long>(idx));
    }
}

std::vector<ClipItem> ClipboardStore::VisibleItems(long long nowMs) {
    // 清理过期 OTP。
    for (size_t i = 0; i < otp_.size();) {
        if (otpExpiry_[i] <= nowMs) {
            otp_.erase(otp_.begin() + static_cast<long>(i));
            otpExpiry_.erase(otpExpiry_.begin() + static_cast<long>(i));
        } else {
            ++i;
        }
    }
    // 置顶在前，其余保持原顺序（新在前）。
    std::vector<ClipItem> out;
    out.reserve(history_.size());
    for (const auto& it : history_) {
        if (it.pinned) {
            out.push_back(it);
        }
    }
    for (const auto& it : history_) {
        if (!it.pinned) {
            out.push_back(it);
        }
    }
    return out;
}

std::vector<QuickSendItem> ClipboardStore::QuickSends() const {
    return quick_;
}

std::string ClipboardStore::Serialize() const {
    std::string out = "{\"history\":[";
    bool first = true;
    for (const auto& it : history_) {
        if (!first) out += ",";
        first = false;
        out += "{\"text\":" + JsonEscape(it.text) +
               ",\"pinned\":" + (it.pinned ? "true" : "false") +
               ",\"ts\":" + std::to_string(it.tsMs) + "}";
    }
    out += "],\"quick\":[";
    first = true;
    for (const auto& it : quick_) {
        if (!first) out += ",";
        first = false;
        out += "{\"text\":" + JsonEscape(it.text) +
               ",\"code\":" + JsonEscape(it.code) +
               ",\"ts\":" + std::to_string(it.tsMs) + "}";
    }
    out += "]}";
    return out;
}

bool ClipboardStore::Deserialize(const std::string& json) {
    const JsonValue root = JsonParse(json);
    if (!root.IsObject()) {
        return false;
    }
    history_.clear();
    quick_.clear();
    const JsonValue* h = root.Get("history");
    if (h && h->IsArray()) {
        for (const auto& v : h->arrayValue) {
            ClipItem item;
            item.text = v.GetString("text");
            const JsonValue* p = v.Get("pinned");
            item.pinned = p && p->type == JsonValue::Type::Bool && p->boolValue;
            const JsonValue* ts = v.Get("ts");
            item.tsMs = (ts && ts->IsNumber()) ? static_cast<long long>(ts->numberValue) : 0;
            if (!item.text.empty()) {
                history_.push_back(item);
            }
        }
    }
    const JsonValue* q = root.Get("quick");
    if (q && q->IsArray()) {
        for (const auto& v : q->arrayValue) {
            QuickSendItem item;
            item.text = v.GetString("text");
            item.code = v.GetString("code");
            const JsonValue* ts = v.Get("ts");
            item.tsMs = (ts && ts->IsNumber()) ? static_cast<long long>(ts->numberValue) : 0;
            if (!item.text.empty()) {
                quick_.push_back(item);
            }
        }
    }
    return true;
}

} // namespace clipboard
} // namespace xime
