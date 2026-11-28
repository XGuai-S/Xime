// Xime TSF 文本服务：系统输入法前端。
#pragma once

#include "Guids.h"

#include "../core/RimeEngine.h"
#include "../core/CandidatePicker.h"

#include <windows.h>
#include <msctf.h>

#include "LangBarCompat.h"

#include <memory>
#include <string>
#include <vector>

#ifndef TF_CLIENTID_NULL
#define TF_CLIENTID_NULL 0
#endif

namespace xime {
namespace plugins {
class PluginManager;
}
namespace ui {
class CandidateWindow;
}
namespace tsf {

class LangBarButton;

// 文本服务：实现 TSF 必需的输入处理、按键接收与组合管理接口。
// 说明：为兼容 MinGW 与 MSVC 两套头文件，只实现 ITfTextInputProcessor
// （Activate/Deactivate 是 TSF 装载 TIP 的硬性接口）；ITfTextInputProcessorEx
// 为可选增强，最小实现不依赖它。
class XimeTextService :
    public ITfTextInputProcessor,
    public ITfKeyEventSink,
    public ITfThreadMgrEventSink,
    public ITfTextEditSink,
    public ITfCompositionSink,
    public ITfLangBarItemSink {
public:
    XimeTextService();
    ~XimeTextService();

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppvObj) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // ITfLangBarItemSink：语言栏通知（如项目状态变化）。
    STDMETHODIMP OnUpdate(DWORD dwFlags) override;

    // ITfTextInputProcessor
    STDMETHODIMP Activate(ITfThreadMgr* ptim, TfClientId tid) override;
    STDMETHODIMP Deactivate() override;

    // ITfKeyEventSink
    STDMETHODIMP OnSetFocus(BOOL fForeground) override;
    STDMETHODIMP OnTestKeyDown(ITfContext* pic, WPARAM wParam, LPARAM lParam, BOOL* pfEaten) override;
    STDMETHODIMP OnKeyDown(ITfContext* pic, WPARAM wParam, LPARAM lParam, BOOL* pfEaten) override;
    STDMETHODIMP OnTestKeyUp(ITfContext* pic, WPARAM wParam, LPARAM lParam, BOOL* pfEaten) override;
    STDMETHODIMP OnKeyUp(ITfContext* pic, WPARAM wParam, LPARAM lParam, BOOL* pfEaten) override;
    STDMETHODIMP OnPreservedKey(ITfContext* pic, REFGUID rguid, BOOL* pfEaten) override;

    // ITfThreadMgrEventSink
    STDMETHODIMP OnInitDocumentMgr(ITfDocumentMgr* pdim) override;
    STDMETHODIMP OnUninitDocumentMgr(ITfDocumentMgr* pdim) override;
    STDMETHODIMP OnSetFocus(ITfDocumentMgr* pdimFocus, ITfDocumentMgr* pdimPrevFocus) override;
    STDMETHODIMP OnPushContext(ITfContext* pic) override;
    STDMETHODIMP OnPopContext(ITfContext* pic) override;

    // ITfTextEditSink
    STDMETHODIMP OnEndEdit(ITfContext* pic, TfEditCookie ecReadOnly,
                           ITfEditRecord* pEditRecord) override;

    // ITfCompositionSink
    STDMETHODIMP OnCompositionTerminated(TfEditCookie ecWrite,
                                         ITfComposition* pComposition) override;

private:
    bool EnsureSession();
    bool HandleCandidateSelection(ITfContext* context, unsigned int vk);
    void ApplyPickAction(ITfContext* context, const core::PickAction& action,
                         unsigned int fallbackVk);
    core::ProcessResult ProcessInputKey(ITfContext* context, unsigned int vk,
                                        bool shift, bool ctrl, bool alt,
                                        bool capslock);
    void ApplyEngineResult(ITfContext* context,
                           const core::ProcessResult& result);
    void CommitText(ITfContext* context, const std::string& utf8);
    void UpdateComposition(ITfContext* context, const std::string& preedit,
                           int caretBytes,
                           const std::vector<core::Candidate>& candidates);
    // 取组合文本的屏幕矩形（候选窗跟随光标的定位依据）。
    bool GetCompositionScreenRect(ITfContext* context, TfEditCookie ec,
                                  RECT* out);
    void EndComposition(ITfContext* context);
    void EndCompositionInSession(ITfContext* context, TfEditCookie ec);
    ITfContext* CurrentContext() const;

    ULONG refCount_ = 1;
    ITfThreadMgr* threadMgr_ = nullptr;
    TfClientId clientId_ = TF_CLIENTID_NULL;
    ITfDocumentMgr* docMgr_ = nullptr;
    ITfContext* context_ = nullptr;
    ITfComposition* composition_ = nullptr;
    ITfSource* threadMgrSource_ = nullptr;
    ITfSource* contextSource_ = nullptr;
    ITfKeystrokeMgr* keystrokeMgr_ = nullptr;
    DWORD threadMgrSinkCookie_ = TF_INVALID_COOKIE;
    DWORD keyEventSinkCookie_ = TF_INVALID_COOKIE;
    DWORD textEditSinkCookie_ = TF_INVALID_COOKIE;
    bool activated_ = false;
    bool hasSession_ = false;
    ui::CandidateWindow* candidateWindow_ = nullptr;
    ITfLangBarItemSink* langBarSink_ = nullptr; // 语言栏项的回调出口
    LangBarButton* langBarButton_ = nullptr;    // 输入指示器里的图标/中英状态
    ITfLangBarItemMgr* langBarMgr_ = nullptr;   // 语言栏管理器
    DWORD langBarSinkCookie_ = TF_INVALID_COOKIE;
    // 打开设置（右键语言栏项 / 菜单）。
    void OpenSettings();
    // 按引擎当前 ascii 状态刷新语言栏图标与文字。
    void SyncLangBarState();
    std::unique_ptr<plugins::PluginManager> pluginManager_; // 插件宿主（启动时从 plugins/ 加载）
    std::vector<core::Candidate> lastCandidates_; // 最近一次候选（用于按显示位映射引擎下标选词）
};

} // namespace tsf
} // namespace xime
