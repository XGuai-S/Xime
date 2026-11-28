// 自动造词库（移植自安卓 AutoWordStore）：临时词库→固定词库分级管理 + 组合生词。
// 本轮实现纯逻辑（组合生成/收录/晋升/候选命中），供上层合并进候选栏与单测验证。
#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <vector>

namespace xime {
namespace core {

// 临时词库条目。
struct AutoWordEntry {
    std::string word;
    std::string code;
    int64_t firstSeen = 0; // 首次上屏时间（ms）
    int count = 0;         // 被用户选中次数
    int64_t lastSeen = 0;  // 最后上屏时间（ms）
};

using AutoWordTemp = std::map<std::string, std::vector<AutoWordEntry>>; // code -> entries
using AutoWordFixed = std::map<std::string, std::map<std::string, int>>; // code -> word -> weight

class AutoWordStore {
public:
    // 组合生词参数默认值（对齐安卓）。
    static constexpr int kComboMinChars = 2;
    static constexpr int kComboMaxChars = 4;
    static constexpr int kPromoteWindowDays = 3;
    static constexpr int kPromoteMinCount = 1;

    // 是否适合参与组合：非空且全为汉字。
    static bool IsComboWord(const std::string& utf8);
    // 造词收录门槛：≥2 个汉字。
    static bool IsLearnedWord(const std::string& utf8);
    // UTF-8 码点计数。
    static size_t CodePointCount(const std::string& utf8);

    // 组合生词：prev+word（都非空、都是汉字、码点数在 [min,max]）。不满足返回空串。
    static std::string ComboWord(const std::string& prev, const std::string& word,
                                 int minChars = kComboMinChars,
                                 int maxChars = kComboMaxChars);

    // 累积窗口组合：history 的每个后缀 + word，返回全部满足的新词（去重、短到长）。
    // isDictWord：判断某段是否方案词典已有词；dictWordFilterEnabled=false 时不启用该过滤。
    static std::vector<std::string> ComboWithHistory(
        const std::vector<std::string>& history, const std::string& word,
        int minChars = kComboMinChars, int maxChars = kComboMaxChars,
        const std::function<bool(const std::string&)>& isDictWord = nullptr,
        bool dictWordFilterEnabled = true);

    // 收录一条：initialCount=1 普通上屏（计数+1、刷新时间）；0 组合生词（已存在则不计数）。
    // 返回是否有变化。
    static bool Record(AutoWordTemp& temp, const std::string& code,
                       const std::string& word, int64_t now, int initialCount = 1);

    // 晋升权重：count*10（下限 1）。
    static int PromoteWeight(int count);

    // 晋升/淘汰（全表）。返回是否有变化；promoted 输出本次晋升的词。
    static bool Promote(AutoWordTemp& temp, AutoWordFixed& fixed, int64_t now,
                        const std::string& preferCode,
                        std::vector<std::string>* promoted,
                        int windowDays = kPromoteWindowDays,
                        int minCount = kPromoteMinCount);

    // 淘汰超期临时词（只淘汰不晋升）。返回是否有变化。
    static bool Prune(AutoWordTemp& temp, int64_t now,
                      int windowDays = kPromoteWindowDays);

    // 完整编码命中：fixed 按权重降序 + temp 垫底，排除 known（引擎已有候选）。
    static std::vector<std::string> CandidatesFor(const AutoWordTemp& temp,
                                                  const AutoWordFixed& fixed,
                                                  const std::string& code,
                                                  const std::vector<std::string>& known);
};

} // namespace core
} // namespace xime
