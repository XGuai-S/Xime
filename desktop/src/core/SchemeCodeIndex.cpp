#include "SchemeCodeIndex.h"

#include <algorithm>
#include <cstdint>
#include <set>

namespace xime {
namespace core {

namespace {

constexpr size_t kFullCodeLength = 4;      // 单字满四码才做组合基底
constexpr size_t kPerCharCodeLength = 2;   // 二字词每字取前 2 码
constexpr size_t kMaxFullCodesPerChar = 8; // 单字满四码上限
constexpr size_t kMaxCodesPerWord = 16;    // 单词编码上限

bool IsCodeLike(const std::string& code) {
    if (code.empty()) {
        return false;
    }
    for (char c : code) {
        if (!((c >= 'a' && c <= 'z') || c == ';' || c == '\'')) {
            return false;
        }
    }
    return true;
}

std::string Take(const std::string& s, size_t n) {
    return s.size() <= n ? s : s.substr(0, n);
}

// 多组做笛卡尔积拼接；任一空组返回空。
std::vector<std::string> Combine(const std::vector<std::vector<std::string>>& groups) {
    std::vector<std::string> acc{""};
    for (const auto& group : groups) {
        if (group.empty()) {
            return {};
        }
        std::vector<std::string> next;
        next.reserve(acc.size() * group.size());
        for (const auto& prefix : acc) {
            for (const auto& suffix : group) {
                next.push_back(prefix + suffix);
            }
        }
        acc.swap(next);
    }
    return acc;
}

void TakeFirst(std::vector<std::string>& v, size_t n) {
    if (v.size() > n) {
        v.resize(n);
    }
}

} // namespace

std::vector<std::string> SchemeCodeIndex::CodePointStrings(const std::string& text) {
    std::vector<std::string> out;
    size_t i = 0;
    const size_t n = text.size();
    while (i < n) {
        const unsigned char c0 = static_cast<unsigned char>(text[i]);
        size_t len = 1;
        if ((c0 & 0x80) == 0) {
            len = 1;
        } else if ((c0 & 0xE0) == 0xC0) {
            len = 2;
        } else if ((c0 & 0xF0) == 0xE0) {
            len = 3;
        } else if ((c0 & 0xF8) == 0xF0) {
            len = 4;
        } else {
            len = 1;
        }
        if (i + len > n) {
            len = n - i;
        }
        out.push_back(text.substr(i, len));
        i += len;
    }
    return out;
}

std::map<std::string, std::vector<std::string>> SchemeCodeIndex::BuildCharCodes(
    const std::vector<std::pair<std::string, std::string>>& entries) {
    std::map<std::string, std::set<std::string>> singles;
    for (const auto& e : entries) {
        const auto chars = CodePointStrings(e.first);
        if (chars.size() != 1) {
            continue; // 只认单字条目
        }
        if (!IsCodeLike(e.second) || e.second.size() != kFullCodeLength) {
            continue;
        }
        singles[chars[0]].insert(e.second);
    }
    std::map<std::string, std::vector<std::string>> result;
    for (auto& kv : singles) {
        std::vector<std::string> codes(kv.second.begin(), kv.second.end()); // set 已字典序
        TakeFirst(codes, kMaxFullCodesPerChar);
        result[kv.first] = std::move(codes);
    }
    return result;
}

std::vector<std::string> SchemeCodeIndex::ComposeCodes(
    const std::string& word,
    const std::map<std::string, std::vector<std::string>>& charCodes) {
    const auto chars = CodePointStrings(word);
    if (chars.size() < 2) {
        return {};
    }
    std::vector<std::vector<std::string>> perChar;
    perChar.reserve(chars.size());
    for (const auto& ch : chars) {
        auto it = charCodes.find(ch);
        if (it == charCodes.end() || it->second.empty()) {
            return {};
        }
        perChar.push_back(it->second);
    }

    auto mapTake = [](const std::vector<std::string>& codes, size_t n) {
        std::vector<std::string> out;
        out.reserve(codes.size());
        for (const auto& c : codes) {
            out.push_back(Take(c, n));
        }
        return out;
    };

    std::vector<std::vector<std::string>> formGroups;
    if (chars.size() == 2) {
        formGroups.push_back(Combine(
            {mapTake(perChar[0], kPerCharCodeLength),
             mapTake(perChar[1], kPerCharCodeLength)}));
    } else if (chars.size() == 3) {
        formGroups.push_back(Combine({mapTake(perChar[0], 1), mapTake(perChar[1], 1),
                                      mapTake(perChar[2], 1)}));
        formGroups.push_back(Combine({mapTake(perChar[0], 1), mapTake(perChar[1], 1),
                                      mapTake(perChar[2], kPerCharCodeLength)}));
    } else {
        formGroups.push_back(Combine({mapTake(perChar[0], 1), mapTake(perChar[1], 1),
                                      mapTake(perChar[2], 1),
                                      mapTake(perChar.back(), 1)}));
    }

    const size_t perFormQuota =
        (std::max)(static_cast<size_t>(1), kMaxCodesPerWord / formGroups.size());
    std::vector<std::string> merged;
    for (auto& group : formGroups) {
        std::vector<std::string> forms;
        for (auto& g : group) {
            if (!g.empty()) {
                forms.push_back(g);
            }
        }
        std::sort(forms.begin(), forms.end());
        forms.erase(std::unique(forms.begin(), forms.end()), forms.end());
        std::sort(forms.begin(), forms.end(), [](const std::string& a, const std::string& b) {
            if (a.size() != b.size()) {
                return a.size() < b.size();
            }
            return a < b;
        });
        TakeFirst(forms, perFormQuota);
        for (auto& f : forms) {
            merged.push_back(f);
        }
    }
    std::sort(merged.begin(), merged.end());
    merged.erase(std::unique(merged.begin(), merged.end()), merged.end());
    std::sort(merged.begin(), merged.end(), [](const std::string& a, const std::string& b) {
        if (a.size() != b.size()) {
            return a.size() < b.size();
        }
        return a < b;
    });
    TakeFirst(merged, kMaxCodesPerWord);
    return merged;
}

} // namespace core
} // namespace xime
