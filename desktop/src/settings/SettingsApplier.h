// 设置应用：把 DesktopSettings 落到引擎/配置文件。
// 目前负责：方案列表写入 default.custom.yaml（启用/排序），用于下次部署生效。
#pragma once

#include <string>

#include "SettingsStore.h"

namespace xime {
namespace settings {

struct ApplyResult {
    bool schemaListWritten = false; // schema_list 有变化并写入（需重新部署生效）
    bool ok = true;
};

// 应用设置到 userDir。schemaList 非空时写入 default.custom.yaml。
// 不在此触发部署（由调用方/首启流程按需 StartMaintenance）。
ApplyResult Apply(const DesktopSettings& settings, const std::string& userDir);

} // namespace settings
} // namespace xime
