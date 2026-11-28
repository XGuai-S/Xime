// Win32 剪贴板文本读写（CF_UNICODETEXT）。
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace xime {
namespace clipboard {

// 读取当前剪贴板纯文本（UTF-8）；无文本或失败返回空串。
std::string ReadClipboardText();

// 写入纯文本到剪贴板（UTF-8）；成功返回 true。
bool WriteClipboardText(const std::string& utf8);

// 由图像素（每像素 RGB，top-down，长度 w*h*3）构造 24bpp bottom-up DIB。
std::vector<uint8_t> BuildDib24(int width, int height,
                                const std::vector<uint8_t>& rgbTopDown);

// 解析 DIB 头：宽/高（正=bottom-up）/位深/像素数据偏移。失败返回 false。
bool ParseDibHeader(const std::vector<uint8_t>& dib, int& width, int& height,
                    int& bitCount, size_t& dataOffset);

// 写入/读取 CF_DIB 位图（原始 DIB 字节）。
bool WriteClipboardImage(const std::vector<uint8_t>& dib);
std::vector<uint8_t> ReadClipboardImage();

} // namespace clipboard
} // namespace xime
