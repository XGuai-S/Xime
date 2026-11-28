// 语言栏按钮：在任务栏输入指示器里显示 Xime 图标与「中/英」状态。
//
// TSF 的语言栏项通过 ITfLangBarItemButton 提供：系统（ctfmon）会来问图标、
// 文字、状态；用户点击时回调 OnClick。没有这个接口，输入指示器就只有
// 一个空白条目 —— 表现为"没有图标 logo、没有中、切不了英、双击无反应"。
#pragma once

#include <windows.h>
#include <msctf.h>

#include "LangBarCompat.h"
#include "Guids.h"

#include <string>

namespace xime {
namespace tsf {

class LangBarButton : public ITfLangBarItemButton {
public:
    explicit LangBarButton(ITfLangBarItemSink* sink) : sink_(sink) {
        if (sink_) {
            sink_->AddRef();
        }
    }

    // 由文本服务在 ASCII 模式切换后调用，刷新图标与文字。
    void SetAsciiMode(bool ascii) { ascii_ = ascii; }
    bool AsciiMode() const { return ascii_; }

    // 主动通知语言栏重取图标/文字/提示（sink 在构造时已持有）。
    void NotifyUpdate(DWORD flags) {
        if (sink_) {
            sink_->OnUpdate(flags);
        }
    }

    // 设置单击切换（中/英）与双击（打开设置）的回调。
    using Action = void (*)(void* ctx);
    void SetHandlers(void* ctx, Action onToggle, Action onConfig) {
        ctx_ = ctx;
        onToggle_ = onToggle;
        onConfig_ = onConfig;
    }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // ITfLangBarItem
    STDMETHODIMP GetInfo(TF_LANGBARITEMINFO* info) override;
    STDMETHODIMP GetStatus(DWORD* status) override;
    STDMETHODIMP Show(BOOL show) override;
    STDMETHODIMP GetTooltipString(BSTR* tooltip) override;

    // ITfLangBarItemButton
    STDMETHODIMP OnClick(TfLBIClick click, POINT pt,
                         const RECT* area) override;
    STDMETHODIMP InitMenu(ITfMenu* menu) override;
    STDMETHODIMP OnMenuSelect(UINT id) override;
    STDMETHODIMP GetIcon(HICON* icon) override;
    STDMETHODIMP GetText(BSTR* text) override;

private:
    ~LangBarButton() = default;

    // 从本 DLL 资源加载图标（16px，语言栏标准尺寸）。
    HICON LoadStateIcon() const;

    LONG refCount_ = 1;
    ITfLangBarItemSink* sink_ = nullptr;
    bool ascii_ = false;
    bool visible_ = true;
    void* ctx_ = nullptr;
    Action onToggle_ = nullptr;
    Action onConfig_ = nullptr;
};

} // namespace tsf
} // namespace xime
