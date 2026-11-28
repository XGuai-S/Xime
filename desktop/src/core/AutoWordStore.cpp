#include "AutoWordStore.h"

#include <algorithm>
#include <cstdint>
#include <set>

namespace xime {
namespace core {

namespace {

// 解码 UTF-8 为「单码点字符串」序列，便于按码点切片。
std::vector<std::string> SplitCodepoints(const std::string& s) {
    std::vector<std::string> out;
    size_t i = 0;
    while (i < s.size()) {
        const unsigned char c0 = static_cast<unsigned char>(s[i]);
        size_t len = 1;
        if (c0 < 0x80) {
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
        if (i + len > s.size()) {
            len = s.size() - i;
        }
        out.push_back(s.substr(i, len));
        i += len;
    }
    return out;
}

uint32_t DecodeOne(const std::string& ch) {
    if (ch.empty()) {
        return 0xFFFFFFFFu;
    }
    const unsigned char c0 = static_cast<unsigned char>(ch[0]);
    if (c0 < 0x80) {
        return c0;
    }
    uint32_t cp = 0;
    int extra = 0;
    if ((c0 & 0xE0) == 0xC0) {
        extra = 1;
        cp = c0 & 0x1F;
    } else if ((c0 & 0xF0) == 0xE0) {
        extra = 2;
        cp = c0 & 0x0F;
    } else if ((c0 & 0xF8) == 0xF0) {
        extra = 3;
        cp = c0 & 0x07;
    } else {
        return 0xFFFFFFFFu;
    }
    for (int k = 1; k <= extra; ++k) {
        if (k >= static_cast<int>(ch.size())) {
            return 0xFFFFFFFFu;
        }
        cp = (cp << 6) | (static_cast<unsigned char>(ch[k]) & 0x3F);
    }
    return cp;
}

bool IsHanCodepoint(uint32_t cp) {
    return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) ||
           (cp >= 0x20000 && cp <= 0x2FA1F) || (cp >= 0xF900 && cp <= 0xFAFF);
}

std::string JoinRange(const std::vector<std::string>& cps, size_t begin, size_t end) {
    std::string s;
    for (size_t i = begin; i < end && i < cps.size(); ++i) {
        s += cps[i];
    }
    return s;
}

int64_t WindowMillis(int windowDays) {
    const int d = windowDays < 1 ? 1 : windowDays;
    return static_cast<int64_t>(d) * 24 * 60 * 60 * 1000;
}

int FindEntryIndex(const std::vector<AutoWordEntry>& vec, const std::string& word) {
    for (size_t i = 0; i < vec.size(); ++i) {
        if (vec[i].word == word) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

} // namespace

size_t AutoWordStore::CodePointCount(const std::string& utf8) {
    return SplitCodepoints(utf8).size();
}

bool AutoWordStore::IsComboWord(const std::string& utf8) {
    if (utf8.empty()) {
        return false;
    }
    for (const auto& ch : SplitCodepoints(utf8)) {
        if (!IsHanCodepoint(DecodeOne(ch))) {
            return false;
        }
    }
    return true;
}

bool AutoWordStore::IsLearnedWord(const std::string& utf8) {
    return CodePointCount(utf8) >= 2 && IsComboWord(utf8);
}

std::string AutoWordStore::ComboWord(const std::string& prev, const std::string& word,
                                     int minChars, int maxChars) {
    if (prev.empty() || word.empty()) {
        return "";
    }
    if (!IsComboWord(prev) || !IsComboWord(word)) {
        return "";
    }
    const std::string combined = prev + word;
    const size_t len = CodePointCount(combined);
    if (static_cast<int>(len) < minChars || static_cast<int>(len) > maxChars) {
        return "";
    }
    return combined;
}

std::vector<std::string> AutoWordStore::ComboWithHistory(
    const std::vector<std::string>& history, const std::string& word, int minChars,
    int maxChars, const std::function<bool(const std::string&)>& isDictWord,
    bool dictWordFilterEnabled) {
    std::vector<std::string> out;
    if (history.empty() || word.empty()) {
        return out;
    }
    const auto* dictFilter = dictWordFilterEnabled ? &isDictWord : nullptr;

    auto blocked = [&](const std::string& seg) -> bool {
        return CodePointCount(seg) > 1 && dictFilter && *dictFilter &&
               (*dictFilter)(seg);
    };
    if (blocked(word)) {
        return out;
    }

    auto containsDictMultiWord = [&](const std::string& candidate) -> bool {
        if (!dictFilter || !*dictFilter) {
            return false;
        }
        const auto cps = SplitCodepoints(candidate);
        if (cps.size() <= 2) {
            return false;
        }
        const size_t probeEnd = std::min<size_t>(cps.size(), 4);
        for (size_t len = 2; len <= probeEnd; ++len) {
            for (size_t start = 0; start + len <= cps.size(); ++start) {
                const std::string piece = JoinRange(cps, start, start + len);
                if (piece != candidate && (*dictFilter)(piece)) {
                    return true;
                }
            }
        }
        return false;
    };

    std::set<std::string> seen;
    for (size_t take = 1; take <= history.size(); ++take) {
        const std::string prev = JoinRange(history, history.size() - take, history.size());
        if (blocked(prev)) {
            continue;
        }
        const std::string combined = ComboWord(prev, word, minChars, maxChars);
        if (combined.empty()) {
            continue;
        }
        if (containsDictMultiWord(combined)) {
            continue;
        }
        seen.insert(combined);
    }
    out.assign(seen.begin(), seen.end());
    std::stable_sort(out.begin(), out.end(), [](const std::string& a, const std::string& b) {
        return CodePointCount(a) < CodePointCount(b);
    });
    return out;
}

bool AutoWordStore::Record(AutoWordTemp& temp, const std::string& code,
                           const std::string& word, int64_t now, int initialCount) {
    if (code.empty() || !IsLearnedWord(word)) {
        return false;
    }
    auto& vec = temp[code];
    const int idx = FindEntryIndex(vec, word);
    if (idx < 0) {
        AutoWordEntry e;
        e.word = word;
        e.code = code;
        e.firstSeen = now;
        e.count = initialCount < 0 ? 0 : initialCount;
        e.lastSeen = now;
        vec.push_back(e);
        return true;
    }
    if (initialCount > 0) {
        vec[idx].count += 1;
        vec[idx].lastSeen = now;
        return true;
    }
    return false; // 组合生词已存在：不计数不刷新
}

int AutoWordStore::PromoteWeight(int count) {
    const int c = count < kPromoteMinCount ? kPromoteMinCount : count;
    return c * 10;
}

bool AutoWordStore::Promote(AutoWordTemp& temp, AutoWordFixed& fixed, int64_t now,
                            const std::string& preferCode,
                            std::vector<std::string>* promoted, int windowDays,
                            int minCount) {
    if (temp.empty()) {
        return false;
    }
    const int64_t limit = WindowMillis(windowDays);
    const int needCount = minCount < 1 ? 1 : minCount;

    std::vector<AutoWordEntry> promotedEntries;
    AutoWordTemp nextTemp;
    bool changed = false;
    for (const auto& codeEntry : temp) {
        std::vector<AutoWordEntry> kept;
        for (const auto& e : codeEntry.second) {
            const bool stale = (now - e.lastSeen) > limit;
            if (stale) {
                changed = true;
                continue;
            }
            if (e.count >= needCount) {
                promotedEntries.push_back(e);
                changed = true;
            } else {
                kept.push_back(e);
            }
        }
        if (!kept.empty()) {
            nextTemp[codeEntry.first] = kept;
        }
    }
    if (!changed && promotedEntries.empty()) {
        return false;
    }

    // 一词一码：preferCode 优先 → 计数最多 → 先出现。
    std::map<std::string, AutoWordEntry> bestByWord;
    for (const auto& e : promotedEntries) {
        auto it = bestByWord.find(e.word);
        if (it == bestByWord.end()) {
            bestByWord[e.word] = e;
            continue;
        }
        const bool prevPreferred = it->second.code == preferCode;
        const bool currPreferred = e.code == preferCode;
        if (currPreferred && !prevPreferred) {
            it->second = e;
        } else if (!currPreferred && prevPreferred) {
            // keep prev
        } else if (e.count > it->second.count) {
            it->second = e;
        }
    }
    for (const auto& kv : bestByWord) {
        const AutoWordEntry& e = kv.second;
        // 清掉该词历史挂在别的编码下的同名条目
        for (auto& fe : fixed) {
            if (fe.first != e.code) {
                fe.second.erase(e.word);
            }
        }
        fixed[e.code][e.word] = PromoteWeight(e.count);
        if (promoted) {
            promoted->push_back(e.word);
        }
    }
    temp.swap(nextTemp);
    return true;
}

bool AutoWordStore::Prune(AutoWordTemp& temp, int64_t now, int windowDays) {
    if (temp.empty()) {
        return false;
    }
    const int64_t limit = WindowMillis(windowDays);
    AutoWordTemp next;
    bool changed = false;
    for (const auto& codeEntry : temp) {
        std::vector<AutoWordEntry> kept;
        for (const auto& e : codeEntry.second) {
            if ((now - e.lastSeen) > limit) {
                changed = true;
                continue;
            }
            kept.push_back(e);
        }
        if (!kept.empty()) {
            next[codeEntry.first] = kept;
        }
    }
    if (changed) {
        temp.swap(next);
    }
    return changed;
}

std::vector<std::string> AutoWordStore::CandidatesFor(
    const AutoWordTemp& temp, const AutoWordFixed& fixed, const std::string& code,
    const std::vector<std::string>& known) {
    std::vector<std::string> out;
    if (code.empty()) {
        return out;
    }
    std::set<std::string> seen(known.begin(), known.end());
    // 固定库按权重降序
    auto fit = fixed.find(code);
    if (fit != fixed.end()) {
        std::vector<std::pair<std::string, int>> words(fit->second.begin(),
                                                       fit->second.end());
        std::stable_sort(words.begin(), words.end(),
                         [](const auto& a, const auto& b) { return a.second > b.second; });
        for (const auto& w : words) {
            if (seen.insert(w.first).second) {
                out.push_back(w.first);
            }
        }
    }
    // 临时库垫底（保持插入顺序）
    auto tit = temp.find(code);
    if (tit != temp.end()) {
        for (const auto& e : tit->second) {
            if (seen.insert(e.word).second) {
                out.push_back(e.word);
            }
        }
    }
    return out;
}

} // namespace core
} // namespace xime
