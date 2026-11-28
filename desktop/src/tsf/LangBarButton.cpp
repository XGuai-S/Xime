// 语言栏按钮：在任务栏输入指示器里显示 Xime 图标与「中/英」状态。
//
// TSF 的语言栏项通过 ITfLangBarItemButton 提供：系统（ctfmon）会来问图标、
// 文字、状态；用户点击时回调 OnClick。没有这个实现，输入指示器里就只有
// 一个空白条目 —— 也就是"没有图标、没有中、切不了英、双击无反应"。
//
// 注意：本文件是 COM 对象，所有方法都必须自带 try/catch。C++ 异常跨 COM 边界
// 会直接终止宿主进程（TextInputFramework.dll + 0xC000041D），而这里的回调
// 会走到引擎（EnsureSession → 大量分配），是真实可能抛异常的路径。
#include "LangBarButton.h"

#include "../core/RimeEngine.h"
#include "../core/Log.h"

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
    try {
        info->clsidService = CLSID_XimeTextService;
        // 用本输入法自己的项 GUID。GUID_LBI_INPUTMODE 是系统为"输入模式"保留的
        // 项，第三方 TIP 直接占用会被语言栏以 E_FAIL 拒绝（AddItem 不成功）。
        info->guidItem = GUID_XimeLangBarItem;
        info->dwStyle = TF_LBI_STYLE_BTN_BUTTON | TF_LBI_STYLE_SHOWTOOLTIP;
        info->ulSort = 0;
        wcscpy_s(info->szDescription, TF_LBI_DESC_MAXLEN, L"Xime 输入法");
        xime::log::WriteInfo(
            "语言栏 GetInfo guid=%08X-%04X-%04X style=0x%X desc=%ls",
            static_cast<unsigned>(info->guidItem.Data1),
            static_cast<unsigned>(info->guidItem.Data2),
            static_cast<unsigned>(info->guidItem.Data3),
            static_cast<unsigned>(info->dwStyle), info->szDescription);
        return S_OK;
    } catch (...) {
        return E_FAIL;
    }
}

STDMETHODIMP LangBarButton::GetStatus(DWORD* status) {
    if (!status) {
        return E_INVALIDARG;
    }
    try {
        *status = visible_ ? 0 : TF_LBI_STATUS_HIDDEN;
        return S_OK;
    } catch (...) {
        return E_FAIL;
    }
}

STDMETHODIMP LangBarButton::Show(BOOL show) {
    try {
        visible_ = show != FALSE;
        NotifyUpdate(TF_LBI_STATUS);
        return S_OK;
    } catch (...) {
        return E_FAIL;
    }
}

STDMETHODIMP LangBarButton::GetTooltipString(BSTR* tooltip) {
    if (!tooltip) {
        return E_INVALIDARG;
    }
    try {
        const wchar_t* text = ascii_ ? L"Xime - English（单击切中文）"
                                     : L"Xime - 中文（单击切英文）";
        *tooltip = SysAllocString(text);
        return *tooltip ? S_OK : E_OUTOFMEMORY;
    } catch (...) {
        return E_FAIL;
    }
}

// ── ITfLangBarItemButton ────────────────────────────────────────────────────

STDMETHODIMP LangBarButton::OnClick(TfLBIClick click, POINT pt,
                                    const RECT* area) {
    (void)pt;
    (void)area;
    try {
        if (click == TF_LBI_CLK_RIGHT) {
            // 右键等同"打开设置"（与主流输入法一致）。
            if (onConfig_) {
                onConfig_(ctx_);
            }
            return S_OK;
        }
        if (click == TF_LBI_CLK_LEFT) {
            // 左键：中英切换。配置入口放右键与菜单（双击在 TSF 里会转成两次
            // LEFT，无法可靠区分）。
            if (onToggle_) {
                onToggle_(ctx_);
            }
            // 用引擎真实状态回读，绝不本地取反：切换可能失败（无会话/部署中），
            // 本地取反会造成图标显示与实际输入模式不一致。
            ascii_ = core::RimeEngine::Instance().IsAsciiMode();
            NotifyUpdate(TF_LBI_ICON | TF_LBI_TEXT | TF_LBI_TOOLTIP);
            return S_OK;
        }
        return S_OK;
    } catch (...) {
        return E_FAIL;
    }
}

STDMETHODIMP LangBarButton::InitMenu(ITfMenu* menu) {
    if (!menu) {
        return E_INVALIDARG;
    }
    try {
        // 菜单项：切换中/英、打开设置。TSF 菜单 id 从 1 起。
        const wchar_t* toggleText = ascii_ ? L"切换到中文" : L"切换到英文";
        menu->AddMenuItem(1, 0, nullptr, nullptr, toggleText,
                          static_cast<ULONG>(wcslen(toggleText)), nullptr);
        const wchar_t* cfgText = L"Xime 设置…";
        menu->AddMenuItem(2, 0, nullptr, nullptr, cfgText,
                          static_cast<ULONG>(wcslen(cfgText)), nullptr);
        return S_OK;
    } catch (...) {
        return E_FAIL;
    }
}

STDMETHODIMP LangBarButton::OnMenuSelect(UINT id) {
    try {
        if (id == 1) {
            if (onToggle_) {
                onToggle_(ctx_);
            }
            ascii_ = core::RimeEngine::Instance().IsAsciiMode();
            NotifyUpdate(TF_LBI_ICON | TF_LBI_TEXT | TF_LBI_TOOLTIP);
        } else if (id == 2) {
            if (onConfig_) {
                onConfig_(ctx_);
            }
        }
        return S_OK;
    } catch (...) {
        return E_FAIL;
    }
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
    try {
        // 调用方（语言栏）负责 DestroyIcon，这里每次给新句柄即可。
        *icon = LoadStateIcon();
        xime::log::WriteDebug("语言栏 GetIcon ascii=%d icon=%p",
                              static_cast<int>(ascii_),
                              reinterpret_cast<void*>(*icon));
        return S_OK;
    } catch (...) {
        *icon = nullptr;
        return E_FAIL;
    }
}

STDMETHODIMP LangBarButton::GetText(BSTR* text) {
    if (!text) {
        return E_INVALIDARG;
    }
    try {
        // 语言栏文字：中文态显示「中」，英文态显示「英」。
        *text = SysAllocString(ascii_ ? L"英" : L"中");
        return *text ? S_OK : E_OUTOFMEMORY;
    } catch (...) {
        return E_FAIL;
    }
}

} // namespace tsf
} // namespace xime
