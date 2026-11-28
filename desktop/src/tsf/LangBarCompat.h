// 补齐 MinGW ctfutb.h 缺失的语言栏定义。
//
// MinGW 的 ctfutb.h 已提供：ITfLangBarItem、ITfLangBarItemSink、
// ITfLangBarItemMgr、TF_LANGBARITEMINFO 及其 IID。
// 缺失的是：ITfMenu、ITfLangBarItemButton、TfLBIClick 与 TF_LBI_* 常量。
// 这里只补缺失部分，避免与系统头重复定义。
//
// IID 与 vtable 顺序取自 Windows SDK (ctfutb.h) / microsoft win32metadata，
// 不可凭记忆改动，否则运行期调用错位会直接崩溃。
#pragma once

#include <windows.h>
#include <msctf.h>
#include <ctfutb.h>
#include <objbase.h>

// ── 缺失的 IID ──────────────────────────────────────────────────────────────
#ifndef IID_ITfLangBarItemButton
// {28C7F1D0-DE25-11D2-AFDD-00105A2799B5}
DEFINE_GUID(IID_ITfLangBarItemButton,
            0x28c7f1d0, 0xde25, 0x11d2, 0xaf, 0xdd, 0x00, 0x10, 0x5a, 0x27,
            0x99, 0xb5);
#endif

// ── TF_LBI_* 常量（SDK 真值）───────────────────────────────────────────────
#ifndef TF_LBI_STYLE_BTN_BUTTON
#define TF_LBI_STYLE_BTN_BUTTON 0x00010000
#endif
#ifndef TF_LBI_STYLE_BTN_MENU
#define TF_LBI_STYLE_BTN_MENU 0x00020000
#endif
#ifndef TF_LBI_STYLE_SHOWTOOLTIP
#define TF_LBI_STYLE_SHOWTOOLTIP 0x00080000
#endif
#ifndef TF_LBI_STATUS_HIDDEN
#define TF_LBI_STATUS_HIDDEN 0x00000020
#endif
#ifndef TF_LBI_STATUS_BTN_TOGGLED
#define TF_LBI_STATUS_BTN_TOGGLED 0x00010000
#endif
#ifndef TF_LBI_STATUS
#define TF_LBI_STATUS 0x00000001
#endif
#ifndef TF_LBI_ICON
#define TF_LBI_ICON 0x00000002
#endif
#ifndef TF_LBI_TEXT
#define TF_LBI_TEXT 0x00000004
#endif
#ifndef TF_LBI_TOOLTIP
#define TF_LBI_TOOLTIP 0x00000008
#endif

// ── TfLBIClick ─────────────────────────────────────────────────────────────
#ifndef __TfLBIClick_DEFINED
#define __TfLBIClick_DEFINED
typedef enum __TfLBIClick {
    TF_LBI_CLK_RIGHT = 0,
    TF_LBI_CLK_LEFT = 1
} TfLBIClick;
#endif

// ── ITfMenu（只用 AddMenuItem；vtbl 3 起，共 4 个 Add* + 结束）─────────────
// SDK 顺序：AddMenuItem, AddMenuSeparator, AddMenu, EndMenu
#ifndef __ITfMenu_INTERFACE_DEFINED__
#define __ITfMenu_INTERFACE_DEFINED__
struct ITfMenu : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE AddMenuItem(
        UINT uId, DWORD dwFlags, HBITMAP hbmp, HBITMAP hbmpMask,
        const WCHAR* pchText, ULONG cch, ITfMenu** ppMenu) = 0;
    virtual HRESULT STDMETHODCALLTYPE AddMenuSeparator(UINT uId) = 0;
    virtual HRESULT STDMETHODCALLTYPE AddMenu(
        UINT uId, DWORD dwFlags, HBITMAP hbmp, HBITMAP hbmpMask,
        const WCHAR* pchText, ULONG cch, ITfMenu** ppMenu) = 0;
    virtual HRESULT STDMETHODCALLTYPE EndMenu() = 0;
};
#endif

// ── ITfLangBarItemButton（vtbl 7..11，继承 ITfLangBarItem）──────────────────
#ifndef __ITfLangBarItemButton_INTERFACE_DEFINED__
#define __ITfLangBarItemButton_INTERFACE_DEFINED__
struct ITfLangBarItemButton : public ITfLangBarItem {
    virtual HRESULT STDMETHODCALLTYPE OnClick(TfLBIClick click, POINT pt,
                                              const RECT* prcArea) = 0;
    virtual HRESULT STDMETHODCALLTYPE InitMenu(ITfMenu* pMenu) = 0;
    virtual HRESULT STDMETHODCALLTYPE OnMenuSelect(UINT wID) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetIcon(HICON* phIcon) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetText(BSTR* pbstrText) = 0;
};
#endif
