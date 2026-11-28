// 语言栏按钮：在任务栏输入指示器里显示 Xime 图标与「中/英」状态。
//
// TSF 的语言栏项通过 ITfLangBarItemButton 提供：系统（ctfmon）会来问图标、
// 文字、状态；用户点击时回调 OnClick。没有这个实现，输入指示器里就只有
// 一个空白条目 —— 也就是"没有图标、没有中、切不了英、双击无反应"。
#include "LangBarButton.h"

#include "../core/RimeEngine.h"

#include <objbase.h>

namespace xime {
namespace tsf {

namespace {
// 与 xime.rc 中的资源 ID 对应。
constexpr int kIconZh = 101;
constexpr int kIconEn = 102;

// 取本模块 HMODULE（语言栏按钮可能被载入不同线程，不能用 nullptr）。
HMODULE SelfModule() {
    HMODULE mod = nullptr;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                           GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       reinterpret_cast<LPCWSTR>(&SelfModule), &mod);
    return mod;
}
} // namespace

STDMETHODIMP LangBarButton::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) {
        return E_POINTER;
    }
    *ppv = nullptr;
    if (riid == IID_IUnknown || riid == IID_ITfLangBarItem) {
        *ppv = static_cast<ITfLangBarItem*>(this);
    } else if (riid == IID_ITfLangBarItemButton) {
        *ppv = static_cast<ITfLangBarItemButton*>(this);
    } else {
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

STDMETHODIMP_(ULONG) LangBarButton::AddRef() {
    return static_cast<ULONG>(InterlockedIncrement(&refCount_));
}

STDMETHODIMP_(ULONG) LangBarButton::Release() {
    const ULONG n = static_cast<ULONG>(InterlockedDecrement(&refCount_));
    if (n == 0) {
        if (sink_) {
            sink_->Release();
            sink_ = nullptr;
        }
        delete this;
    }
    return n;
}

STDMETHODIMP LangBarButton::GetInfo(TF_LANGBARITEMINFO* info) {
    if (!info) {
        return E_INVALIDARG;
    }
    info->clsidService = CLSID_XimeTextService;
    info->guidItem = GUID_XimeLangBarItem;
    info->dwStyle = TF_LBI_STYLE_BTN_BUTTON | TF_LBI_STYLE_SHOWTOOLTIP;
    info->ulSort = 0;
    // 语言栏显示名（悬停/列表里看到的文字）。
    wcscpy_s(info->szDescription, TF_LBI_DESC_MAXLEN, L"Xime 输入法");
    return S_OK;
}

STDMETHODIMP LangBarButton::GetStatus(DWORD* status) {
    if (!status) {
        return E_INVALIDARG;
    }
    *status = visible_ ? 0 : TF_LBI_STATUS_HIDDEN;
    return S_OK;
}

STDMETHODIMP LangBarButton::Show(BOOL show) {
    visible_ = show != FALSE;
    if (sink_) {
        sink_->OnUpdate(TF_LBI_STATUS);
    }
    return S_OK;
}

STDMETHODIMP LangBarButton::GetTooltipString(BSTR* tooltip) {
    if (!tooltip) {
        return E_INVALIDARG;
    }
    const wchar_t* text = ascii_ ? L"Xime - English（单击切中文）"
                                 : L"Xime - 中文（单击切英文）";
    *tooltip = SysAllocString(text);
    return *tooltip ? S_OK : E_OUTOFMEMORY;
}

// ── ITfLangBarItemButton ────────────────────────────────────────────────────

STDMETHODIMP LangBarButton::OnClick(TfLBIClick click, POINT pt,
                                    const RECT* area) {
    (void)pt;
    (void)area;
    if (click == TF_LBI_CLK_RIGHT) {
        // 右键等同"打开设置"（与主流输入法一致）。
        if (onConfig_) {
            onConfig_(ctx_);
        }
        return S_OK;
    }
    if (click == TF_LBI_CLK_LEFT) {
        // 双击由 TSF 的 UM 层转成两次 LEFT；这里用 GetDoubleClickTime 无法区分，
        // 故左键单击=中英切换，配置入口放右键与菜单。
        if (onToggle_) {
            onToggle_(ctx_);
        }
        // 立即刷新图标，让用户看到状态变化。
        ascii_ = !ascii_;
        if (sink_) {
            sink_->OnUpdate(TF_LBI_ICON | TF_LBI_TEXT | TF_LBI_TOOLTIP);
        }
        return S_OK;
    }
    return S_OK;
}

STDMETHODIMP LangBarButton::InitMenu(ITfMenu* menu) {
    if (!menu) {
        return E_INVALIDARG;
    }
    // 菜单项：切换中/英、打开设置。TSF 菜单 id 从 1 起。
    const wchar_t* toggleText = ascii_ ? L"切换到中文" : L"切换到英文";
    menu->AddMenuItem(1, 0, nullptr, nullptr, toggleText,
                      static_cast<ULONG>(wcslen(toggleText)), nullptr);
    const wchar_t* cfgText = L"Xime 设置…";
    menu->AddMenuItem(2, 0, nullptr, nullptr, cfgText,
                      static_cast<ULONG>(wcslen(cfgText)), nullptr);
    return S_OK;
}

STDMETHODIMP LangBarButton::OnMenuSelect(UINT id) {
    if (id == 1) {
        if (onToggle_) {
            onToggle_(ctx_);
        }
        ascii_ = !ascii_;
        if (sink_) {
            sink_->OnUpdate(TF_LBI_ICON | TF_LBI_TEXT | TF_LBI_TOOLTIP);
        }
    } else if (id == 2) {
        if (onConfig_) {
            onConfig_(ctx_);
        }
    }
    return S_OK;
}

HICON LangBarButton::LoadStateIcon() const {
    HMODULE mod = SelfModule();
    if (!mod) {
        return nullptr;
    }
    // 语言栏标准小图标尺寸；系统会按 DPI 选最接近的那张。
    const int cx = GetSystemMetrics(SM_CXSMICON);
    const int cy = GetSystemMetrics(SM_CYSMICON);
    const int id = ascii_ ? kIconEn : kIconZh;
    HICON icon = static_cast<HICON>(
        LoadImageW(mod, MAKEINTRESOURCEW(id), IMAGE_ICON, cx, cy,
                   LR_DEFAULTCOLOR));
    if (!icon) {
        // 退化为 DLL 主图标，至少不是空白。
        icon = static_cast<HICON>(LoadImageW(
            mod, MAKEINTRESOURCEW(1), IMAGE_ICON, cx, cy, LR_DEFAULTCOLOR));
    }
    return icon;
}

STDMETHODIMP LangBarButton::GetIcon(HICON* icon) {
    if (!icon) {
        return E_INVALIDARG;
    }
    *icon = LoadStateIcon();
    return S_OK;
}

STDMETHODIMP LangBarButton::GetText(BSTR* text) {
    if (!text) {
        return E_INVALIDARG;
    }
    // 语言栏文字：中文态显示「中」，英文态显示「英」。
    *text = SysAllocString(ascii_ ? L"英" : L"中");
    return *text ? S_OK : E_OUTOFMEMORY;
}

} // namespace tsf
} // namespace xime
