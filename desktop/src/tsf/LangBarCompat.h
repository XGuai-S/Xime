// 补齐 MinGW ctfutb.h 缺失的语言栏定义。
//
// 重要：MinGW 的 ctfutb.h **已经提供** ITfLangBarItem / ITfLangBarItemSink /
// ITfLangBarItemMgr 接口及其 IID（真实值，与 Windows SDK 一致）：
//   IID_ITfLangBarItem      {73540D69-EDEB-4EE9-96C9-23AA30B25916}
//   IID_ITfLangBarItemSink  {57DBE1A0-DE25-11D2-AFDD-00105A2799B5}
//   IID_ITfLangBarItemMgr   {BA468C55-9956-4FB1-A59D-52A7DD7CC6AA}
// 因此这里**绝不能**再 DEFINE_GUID 一遍 —— 重复定义会生成第二个同名 GUID 对象，
// 导致 QueryInterface 用的 IID 与系统对象实际支持的 IID 不是同一个符号，
// 表现为"查询语言栏管理器总是失败"（日志里 语言栏=不可用）。
//
// 本文件只补 MinGW 真正缺的部分：ITfMenu、ITfLangBarItemButton、TfLBIClick
// 与 TF_LBI_* 常量。
#pragma once

#include <windows.h>
#include <msctf.h>
#include <ctfutb.h>
#include <objbase.h>

// ── 缺失的 IID（仅 ITfLangBarItemButton）────────────────────────────────────
// {28C7F1D0-DE25-11D2-AFDD-00105A2799B5}
#ifndef IID_ITfLangBarItemButton
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

// ── ITfMenu（SDK 顺序：AddMenuItem, AddMenuSeparator, AddMenu, EndMenu）────
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

// ── ITfLangBarItemButton（继承已有的 ITfLangBarItem，vtbl 7..11）────────────
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
