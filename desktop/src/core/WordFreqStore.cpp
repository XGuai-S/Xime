#include "WordFreqStore.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <sstream>

namespace xime {
namespace core {

namespace {

constexpr const char* kHeader =
    "# 上屏词频：词\t编码\t频数（每次上屏自动 +1）";

// 解码一个 UTF-8 码点；成功返回码点并推进 i，失败返回 0xFFFFFFFF。
uint32_t DecodeUtf8(const std::string& s, size_t& i) {
    if (i >= s.size()) {
        return 0xFFFFFFFFu;
    }
    const unsigned char c0 = static_cast<unsigned char>(s[i]);
    if (c0 < 0x80) {
        ++i;
        return c0;
    }
    int extra = 0;
    uint32_t cp = 0;
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
        ++i;
        return 0xFFFFFFFFu;
    }
    if (i + extra >= s.size()) {
        i = s.size();
        return 0xFFFFFFFFu;
    }
    for (int k = 1; k <= extra; ++k) {
        const unsigned char c = static_cast<unsigned char>(s[i + k]);
        if ((c & 0xC0) != 0x80) {
            i += k;
            return 0xFFFFFFFFu;
        }
        cp = (cp << 6) | (c & 0x3F);
    }
    i += extra + 1;
    return cp;
}

bool IsHanCodepoint(uint32_t cp) {
    return (cp >= 0x4E00 && cp <= 0x9FFF) ||   // CJK Unified
           (cp >= 0x3400 && cp <= 0x4DBF) ||   // Ext A
           (cp >= 0x20000 && cp <= 0x2FA1F) || // Ext B..F
           (cp >= 0xF900 && cp <= 0xFAFF);     // Compat
}

std::vector<std::string> SplitTab(const std::string& line) {
    std::vector<std::string> out;
    std::string cur;
    for (char c : line) {
        if (c == '\t') {
            out.push_back(cur);
            cur.clear();
        } else {
            cur.push_back(c);
        }
    }
    out.push_back(cur);
    return out;
}

std::string Trim(const std::string& s) {
    size_t a = 0;
    size_t b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\r' || s[a] == '\n')) {
        ++a;
    }
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\r' || s[b - 1] == '\n')) {
        --b;
    }
    return s.substr(a, b - a);
}

} // namespace

WordFreqStore& WordFreqStore::Instance() {
    static WordFreqStore instance;
    return instance;
}

void WordFreqStore::SetUserDir(const std::string& dir) {
    std::lock_guard<std::mutex> lock(mu_);
    userDir_ = dir;
}

std::string WordFreqStore::FilePath(const std::string& dir) {
    return dir + "/word_freq.txt";
}

bool WordFreqStore::IsCountableWord(const std::string& utf8) {
    if (utf8.empty()) {
        return false;
    }
    size_t i = 0;
    bool any = false;
    while (i < utf8.size()) {
        const uint32_t cp = DecodeUtf8(utf8, i);
        if (cp == 0xFFFFFFFFu) {
            return false;
        }
        if (!IsHanCodepoint(cp)) {
            return false;
        }
        any = true;
    }
    return any;
}

void WordFreqStore::RecordCommit(const std::string& code, const std::string& word) {
    if (code.empty() || !IsCountableWord(word)) {
        return;
    }
    std::lock_guard<std::mutex> lock(mu_);
    table_[code][word] += 1;
}

std::vector<int> WordFreqStore::ComputeDisplayOrder(
    const std::string& code,
    const std::vector<std::string>& candidates) const {
    if (code.empty() || candidates.empty()) {
        return {};
    }
    std::lock_guard<std::mutex> lock(mu_);
    const auto it = table_.find(code);
    if (it == table_.end() || it->second.empty()) {
        return {};
    }
    const auto& freq = it->second;

    auto weightOf = [&freq](const std::string& w) -> int {
        const auto f = freq.find(w);
        return f == freq.end() ? INT32_MIN : f->second;
    };

    // 已按权重降序（等权保持原序）则无需重排。
    bool needs = false;
    int prev = INT32_MAX;
    for (const auto& c : candidates) {
        const int w = weightOf(c);
        if (w > prev) {
            needs = true;
            break;
        }
        prev = w;
    }
    if (!needs) {
        return {};
    }

    std::vector<int> order(candidates.size());
    for (size_t i = 0; i < order.size(); ++i) {
        order[i] = static_cast<int>(i);
    }
    std::stable_sort(order.begin(), order.end(), [&](int a, int b) {
        const int wa = weightOf(candidates[a]);
        const int wb = weightOf(candidates[b]);
        if (wa != wb) {
            return wa > wb;
        }
        return a < b;
    });
    bool identity = true;
    for (size_t i = 0; i < order.size(); ++i) {
        if (order[i] != static_cast<int>(i)) {
            identity = false;
            break;
        }
    }
    return identity ? std::vector<int>{} : order;
}

bool WordFreqStore::Load() {
    std::lock_guard<std::mutex> lock(mu_);
    table_.clear();
    if (userDir_.empty()) {
        return false;
    }
    std::ifstream in(FilePath(userDir_));
    if (!in) {
        return false;
    }
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') {
            continue;
        }
        const auto parts = SplitTab(line);
        if (parts.size() < 3) {
            continue;
        }
        const std::string word = Trim(parts[0]);
        const std::string code = Trim(parts[1]);
        int count = 0;
        try {
            count = std::stoi(Trim(parts[2]));
        } catch (...) {
            continue;
        }
        if (code.empty() || word.empty() || count <= 0) {
            continue;
        }
        table_[code][word] = count;
    }
    return true;
}

bool WordFreqStore::Save() {
    std::lock_guard<std::mutex> lock(mu_);
    if (userDir_.empty()) {
        return false;
    }
    const std::string path = FilePath(userDir_);
    const std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out) {
            return false;
        }
        out << kHeader << "\n";
        for (const auto& codeEntry : table_) {
            const std::string& code = codeEntry.first;
            std::vector<std::pair<std::string, int>> words(codeEntry.second.begin(),
                                                           codeEntry.second.end());
            std::sort(words.begin(), words.end(), [](const auto& a, const auto& b) {
                if (a.second != b.second) {
                    return a.second > b.second;
                }
                return a.first < b.first;
            });
            for (const auto& w : words) {
                out << w.first << '\t' << code << '\t' << w.second << '\n';
            }
        }
        out.flush();
    }
    std::remove(path.c_str());
    if (std::rename(tmp.c_str(), path.c_str()) != 0) {
        return false;
    }
    return true;
}

} // namespace core
} // namespace xime
