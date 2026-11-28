// 组合生词编码生成（移植自安卓 SchemeCodeIndex）：用方案单字「满四码」按通用规则
// 拼出新词可打出的编码。纯逻辑，无 IO。
#pragma once

#include <map>
#include <string>
#include <vector>

namespace xime {
namespace core {

class SchemeCodeIndex {
public:
    // 从词条（词, 码）建「单字 → 全部满四码」索引（去重、字典序、上限 8）。
    static std::map<std::string, std::vector<std::string>> BuildCharCodes(
        const std::vector<std::pair<std::string, std::string>>& entries);

    // 按 charCodes 给 word 拼出全部可用编码（2 字 2+2；3 字 1+1+1 与 1+1+2；
    // 4+ 字 1+1+1+1），笛卡尔积、去重、短码优先、每形态配额、整词上限 16。
    // 任一字无满四码条目返回空。
    static std::vector<std::string> ComposeCodes(
        const std::string& word,
        const std::map<std::string, std::vector<std::string>>& charCodes);

    // 按 UTF-8 码点切分为单个字符（增补平面字占多字节）。
    static std::vector<std::string> CodePointStrings(const std::string& text);
};

} // namespace core
} // namespace xime
