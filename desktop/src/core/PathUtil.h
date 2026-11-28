// Windows 路径工具：UTF-8 与宽字符互转。
//
// 为什么必须有它：Windows 的 std::fstream(const std::string&) 与
// std::filesystem::path(std::string) 按 **ANSI 代码页** 解释窄字符，而本项目
// 上层一律传 UTF-8。中文用户名（C:\Users\张三\AppData\Local\Xime）下：
//   - is_directory / ofstream 一律失败
//   - 于是 IsDeployed 永远为 false，每次激活都重新部署并冻结按键线程
//   - 词频、方案列表读写静默失败
// 所以所有路径一律先转 std::wstring 再交给系统 API / filesystem。
#pragma once

#include <string>

#include <windows.h>

namespace xime {
namespace core {

// UTF-8 → 宽字符；失败返回空串。
inline std::wstring Utf8ToWidePath(const std::string& s) {
    if (s.empty()) {
        return L"";
    }
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(),
                                      static_cast<int>(s.size()), nullptr, 0);
    if (n <= 0) {
        return L"";
    }
    std::wstring out(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()),
                        &out[0], n);
    return out;
}

// 宽字符 → UTF-8。
inline std::string WideToUtf8Path(const std::wstring& w) {
    if (w.empty()) {
        return "";
    }
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(),
                                      static_cast<int>(w.size()), nullptr, 0,
                                      nullptr, nullptr);
    if (n <= 0) {
        return "";
    }
    std::string out(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()),
                        &out[0], n, nullptr, nullptr);
    return out;
}

} // namespace core
} // namespace xime
