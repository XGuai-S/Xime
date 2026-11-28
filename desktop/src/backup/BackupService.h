// 桌面版备份/恢复服务：把用户数据（词频/造词/配置）打包为 ZIP，或从 ZIP 恢复。
#pragma once

#include <string>

namespace xime {
namespace backup {

// 导出 userDir 下受管文件到 zipPath。成功返回 true（无可导出文件也返回 true）。
bool ExportBackup(const std::string& userDir, const std::string& zipPath);

// 从 zipPath 恢复受管文件到 userDir（仅白名单文件名，拒绝路径穿越）。
bool ImportBackup(const std::string& userDir, const std::string& zipPath);

} // namespace backup
} // namespace xime
