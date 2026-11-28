// Xime TSF 文本服务：系统输入法前端。
#pragma once

#include "Guids.h"

#include <windows.h>
#include <msctf.h>
#include <string>
#include <vector>

namespace xime {
namespace core {
struct Candidate;
}
namespace tsf {

class CandidateWindow;

// 文本服务：实现 TSF 必需的输入处理、按键接收与组合管理接口。
class XimeTextService :
    public ITfTextInputProcessorEx,
    public ITfKeyEventSink,
    public ITfThreadMgrEventSink,
    public ITfTextEditSink,
    public ITfCompositionSink {
public:
    XimeTextService();
    ~XimeTextService() override;

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppvObj) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // ITfTextInputProcessor
    STDMETHODIMP Activate(ITfThreadMgr* ptim, TfClientId tid) override;
    STDMETHODIMP Deactivate() override;

    // ITfTextInputProcessorEx
    STDMETHODIMP ActivateEx(ITfThreadMgr* ptim, TfClientId tid, DWORD dwFlags) override;

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
    void ProcessInputKey(ITfContext* context, unsigned int vk,
                         bool shift, bool ctrl, bool alt, bool capslock);
    void CommitText(ITfContext* context, const std::string& utf8);
    void UpdateComposition(ITfContext* context, const std::string& preedit,
                           int caretBytes,
                           const std::vector<core::Candidate>& candidates);
    void EndComposition(ITfContext* context);
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
    CandidateWindow* candidateWindow_ = nullptr;
};

} // namespace tsf
} // namespace xime
