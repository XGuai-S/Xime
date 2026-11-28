// Windows 虚拟键码 → librime（X11 keysym）键码映射。
#pragma once

namespace xime {
namespace core {

// 返回键码是否应交给 librime 处理。
bool IsRimeKey(unsigned int vk);

// 将 Windows VK 映射为 X11 keysym；未知键返回 0。
unsigned int ToRimeKey(unsigned int vk, bool shift, bool capslock);

// librime 修饰位（与 Android JNI 保持一致的 mask 约定）。
unsigned int ToRimeMask(bool shift, bool ctrl, bool alt);

} // namespace core
} // namespace xime
