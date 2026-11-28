// 方案列表持久化：读写 default.custom.yaml 的 patch.schema_list。
// 支持启用/排序；写出固定规范结构（由本程序管理该文件）。
#pragma once

#include <string>
#include <vector>

namespace xime {
namespace core {

class SchemaListConfig {
public:
    // 从 <userDir>/default.custom.yaml 解析 schema_list（按出现顺序）。
    // 文件不存在或解析失败返回空。
    static std::vector<std::string> Read(const std::string& userDir);

    // 以固定规范结构写入 schema_list（覆盖该文件）。空列表也写出空列表。
    static bool Write(const std::string& userDir,
                      const std::vector<std::string>& schemaIds);

    static std::string FilePath(const std::string& userDir);
};

} // namespace core
} // namespace xime
