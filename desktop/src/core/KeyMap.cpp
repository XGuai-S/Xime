#include "KeyMap.h"

#include <windows.h>

namespace xime {
namespace core {

namespace {

// X11 keysym 常量（只列 librime 输入需要的部分，避免依赖 X11 头文件）。
constexpr unsigned int kXK_BackSpace = 0xff08;
constexpr unsigned int kXK_Tab = 0xff09;
constexpr unsigned int kXK_Return = 0xff0d;
constexpr unsigned int kXK_Escape = 0xff1b;
constexpr unsigned int kXK_Delete = 0xffff;
constexpr unsigned int kXK_Home = 0xff50;
constexpr unsigned int kXK_Left = 0xff51;
constexpr unsigned int kXK_Up = 0xff52;
constexpr unsigned int kXK_Right = 0xff53;
constexpr unsigned int kXK_Down = 0xff54;
constexpr unsigned int kXK_Prior = 0xff55; // PageUp
constexpr unsigned int kXK_Next = 0xff56;  // PageDown
constexpr unsigned int kXK_End = 0xff57;

// librime mask 约定（与 Android JNI 一致）。
constexpr unsigned int kRimeModShift = 1;
constexpr unsigned int kRimeModCtrl = 2;
constexpr unsigned int kRimeModAlt = 4;

} // namespace

bool IsRimeKey(unsigned int vk) {
    switch (vk) {
    case VK_BACK:
    case VK_TAB:
    case VK_RETURN:
    case VK_ESCAPE:
    case VK_DELETE:
    case VK_HOME:
    case VK_LEFT:
    case VK_UP:
    case VK_RIGHT:
    case VK_DOWN:
    case VK_PRIOR:
    case VK_NEXT:
    case VK_END:
        return true;
    default:
        break;
    }
    if (vk >= '0' && vk <= '9') {
        return true;
    }
    if (vk >= 'A' && vk <= 'Z') {
        return true;
    }
    // 标点键（OEM），交给 librime 处理以保留 speller 行为。
    switch (vk) {
    case VK_OEM_1:       // ; :
    case VK_OEM_2:       // / ?
    case VK_OEM_3:       // ` ~
    case VK_OEM_4:       // [ {
    case VK_OEM_5:       // \ |
    case VK_OEM_6:       // ] }
    case VK_OEM_7:       // ' "
    case VK_OEM_COMMA:   // , <
    case VK_OEM_PERIOD:  // . >
    case VK_OEM_MINUS:   // - _
    case VK_OEM_PLUS:    // = +
        return true;
    default:
        return false;
    }
}

unsigned int ToRimeKey(unsigned int vk, bool shift, bool capslock) {
    switch (vk) {
    case VK_BACK:
        return kXK_BackSpace;
    case VK_TAB:
        return kXK_Tab;
    case VK_RETURN:
        return kXK_Return;
    case VK_ESCAPE:
        return kXK_Escape;
    case VK_DELETE:
        return kXK_Delete;
    case VK_HOME:
        return kXK_Home;
    case VK_LEFT:
        return kXK_Left;
    case VK_UP:
        return kXK_Up;
    case VK_RIGHT:
        return kXK_Right;
    case VK_DOWN:
        return kXK_Down;
    case VK_PRIOR:
        return kXK_Prior;
    case VK_NEXT:
        return kXK_Next;
    case VK_END:
        return kXK_End;
    default:
        break;
    }

    const bool upper = shift != capslock; // 异或：Shift 与 CapsLock 同时按下为小写
    if (vk >= '0' && vk <= '9') {
        return vk;
    }
    if (vk >= 'A' && vk <= 'Z') {
        return upper ? vk : vk + 0x20;
    }

    struct OemEntry {
        unsigned int vk;
        unsigned int normal;
        unsigned int shifted;
    };
    static const OemEntry kOemMap[] = {
        {VK_OEM_1, ';', ':'},
        {VK_OEM_2, '/', '?'},
        {VK_OEM_3, '`', '~'},
        {VK_OEM_4, '[', '{'},
        {VK_OEM_5, '\\', '|'},
        {VK_OEM_6, ']', '}'},
        {VK_OEM_7, '\'', '"'},
        {VK_OEM_COMMA, ',', '<'},
        {VK_OEM_PERIOD, '.', '>'},
        {VK_OEM_MINUS, '-', '_'},
        {VK_OEM_PLUS, '=', '+'},
    };
    for (const auto& e : kOemMap) {
        if (vk == e.vk) {
            return upper ? e.shifted : e.normal;
        }
    }
    return 0;
}

unsigned int ToRimeMask(bool shift, bool ctrl, bool alt) {
    unsigned int mask = 0;
    if (shift) {
        mask |= kRimeModShift;
    }
    if (ctrl) {
        mask |= kRimeModCtrl;
    }
    if (alt) {
        mask |= kRimeModAlt;
    }
    return mask;
}

} // namespace core
} // namespace xime
