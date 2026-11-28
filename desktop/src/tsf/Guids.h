// Xime 桌面版 TSF COM 标识。
#pragma once

#include <windows.h>
#include <initguid.h>

// {8A7C1E20-3F5B-4A2D-9E11-6F3D5C8A2B47}
DEFINE_GUID(CLSID_XimeTextService,
            0x8a7c1e20, 0x3f5b, 0x4a2d, 0x9e, 0x11, 0x6f, 0x3d, 0x5c, 0x8a, 0x2b, 0x47);

// {B4E92F10-7A6C-4D85-A3F2-1C8D9E4B7A60}
DEFINE_GUID(GUID_XimeProfile,
            0xb4e92f10, 0x7a6c, 0x4d85, 0xa3, 0xf2, 0x1c, 0x8d, 0x9e, 0x4b, 0x7a, 0x60);

// {2D5A8C30-91E4-4F76-B0C8-3A7D1E9F4B52}
DEFINE_GUID(GUID_XimeLangBarItem,
            0x2d5a8c30, 0x91e4, 0x4f76, 0xb0, 0xc8, 0x3a, 0x7d, 0x1e, 0x9f, 0x4b, 0x52);

namespace xime {
namespace tsf {

constexpr wchar_t kTextServiceDescription[] = L"Xime Input Method";
constexpr wchar_t kProfileDescription[] = L"Xime Chinese";

} // namespace tsf
} // namespace xime
