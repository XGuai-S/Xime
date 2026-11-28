// 桌面版备份/恢复服务：把用户数据（词频/造词/配置）打包为 ZIP，或从 ZIP 恢复。
#pragma once

#include <string>

namespace xime {
namespace backup {

// 导出 userDir 下受管文件到 zipPath。成功返回 true（无可导出文件也返回 true）。
bool ExportBackup(const std::string& userDir, const std::string& zipPath);

// 从 zipPath 恢复受管文件到 userDir（仅白名单文件名，拒绝路径穿越）。
// mergeTabular=true 时，词频/造词这类「词\t编码\t频数」表按 (编码,词) 合并取最大值，
// 保留本地已有数据（对齐安卓「恢复按内容合并保留本地」）；配置文件仍整体覆盖。
bool ImportBackup(const std::string& userDir, const std::string& zipPath,
                  bool mergeTabular = false);

// 合并两张「word\tcode\tcount」表：同键取较大值，保留双方独有条目。
// 供导入与单测使用。
std::string MergeFreqTable(const std::string& local, const std::string& incoming);

} // namespace backup
} // namespace xime
