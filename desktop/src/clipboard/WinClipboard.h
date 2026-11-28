// Win32 剪贴板文本读写（CF_UNICODETEXT）。
#pragma once

#include <string>

namespace xime {
namespace clipboard {

// 读取当前剪贴板纯文本（UTF-8）；无文本或失败返回空串。
std::string ReadClipboardText();

// 写入纯文本到剪贴板（UTF-8）；成功返回 true。
bool WriteClipboardText(const std::string& utf8);

} // namespace clipboard
} // namespace xime
