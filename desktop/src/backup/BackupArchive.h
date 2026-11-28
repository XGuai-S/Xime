// 极简 ZIP 归档（仅 store，无压缩）+ CRC32，用于桌面版备份/恢复。
// 与安卓备份同为 ZIP，可被常规工具解开；离线可测。
#pragma once

#include <string>
#include <utility>
#include <vector>

namespace xime {
namespace backup {

struct ZipEntry {
    std::string name;    // 归档内路径（相对）
    std::string content; // 文件字节
};

// 写出 store 方式的 ZIP；成功返回 true。
bool WriteZip(const std::string& path, const std::vector<ZipEntry>& entries);

// 读取 store 方式的 ZIP，解析为 name->content；非 store 或损坏返回 false。
bool ReadZip(const std::string& path, std::vector<ZipEntry>& out);

} // namespace backup
} // namespace xime
