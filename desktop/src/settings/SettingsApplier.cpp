#include "SettingsApplier.h"

#include "../core/SchemaListConfig.h"

namespace xime {
namespace settings {

ApplyResult Apply(const DesktopSettings& settings, const std::string& userDir) {
    ApplyResult result;
    if (settings.schemaList.empty()) {
        return result;
    }
    // 仅当与现有文件内容不同才写，避免每次都触发重新部署。
    const auto existing = core::SchemaListConfig::Read(userDir);
    if (existing == settings.schemaList) {
        return result;
    }
    result.schemaListWritten =
        core::SchemaListConfig::Write(userDir, settings.schemaList);
    result.ok = result.schemaListWritten;
    return result;
}

} // namespace settings
} // namespace xime
