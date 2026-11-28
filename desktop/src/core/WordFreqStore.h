// 上屏词频表（移植自安卓 WordFreqStore）：按编码统计已上屏的词与次数，
// 用频数对候选重排（高频在前，未统计的保持引擎原序）。
#pragma once

#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace xime {
namespace core {

class WordFreqStore {
public:
    static WordFreqStore& Instance();

    void SetUserDir(const std::string& dir);
    bool Load();
    bool Save();

    // 是否计入词频的词：非空且全部为汉字。
    static bool IsCountableWord(const std::string& utf8);

    // 记一次上屏：code 非空且 word 可计入时 (code, word) 计数 +1。
    void RecordCommit(const std::string& code, const std::string& word);

    // 按频数计算显示顺序（元素为引擎候选下标）。无需重排返回空。
    std::vector<int> ComputeDisplayOrder(
        const std::string& code,
        const std::vector<std::string>& candidates) const;

private:
    WordFreqStore() = default;
    static std::string FilePath(const std::string& dir);

    mutable std::mutex mu_;
    std::string userDir_;
    std::map<std::string, std::map<std::string, int>> table_; // code -> word -> count
};

} // namespace core
} // namespace xime
