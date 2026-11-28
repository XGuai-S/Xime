// 桌面版设置存储（JSON 文件，位于用户目录 settings.json）。
#pragma once

#include <string>
#include <vector>

namespace xime {
namespace settings {

struct DesktopSettings {
    int pageSize = 5;              // 每页候选数 5..50
    bool polishEnabled = true;     // 文本润色开关
    std::string llmModel = "glm-4";
    std::string llmBaseUrl;        // 空=官方默认
    std::string sttModel = "whisper-1";
    std::string sttBaseUrl;
    std::vector<std::string> schemaList; // 方案列表（空=引擎默认）
};

class SettingsStore {
public:
    // 从 <dir>/settings.json 读取；文件缺失/损坏时返回默认值（并返回 false）。
    static bool Load(const std::string& dir, DesktopSettings* out);
    // 写入 <dir>/settings.json（原子写）。成功返回 true。
    static bool Save(const std::string& dir, const DesktopSettings& settings);

    static std::string Serialize(const DesktopSettings& s);
    static DesktopSettings ParseOrDefault(const std::string& json);
};

} // namespace settings
} // namespace xime
