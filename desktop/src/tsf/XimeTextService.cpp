#include "XimeTextService.h"

#include "../core/KeyMap.h"
#include "../core/RimeEngine.h"
#include "../ui/CandidateWindow.h"

#include <algorithm>
#include <new>
#include <string>

namespace xime {
namespace tsf {

namespace {

std::wstring Utf8ToWide(const std::string& s) {
    if (s.empty()) {
        return L"";
    }
    const int n = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                                      s.c_str(), static_cast<int>(s.size()),
                                      nullptr, 0);
    if (n <= 0) {
        return L"";
    }
    std::wstring out(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, s.c_str(),
                        static_cast<int>(s.size()), &out[0], n);
    return out;
}

void SafeRelease(IUnknown* p) {
    if (p) {
        p->Release();
    }
}

} // namespace

XimeTextService::XimeTextService() = default;

XimeTextService::~XimeTextService() {
    SafeRelease(candidateWindow_);
    SafeRelease(composition_);
    SafeRelease(context_);
    SafeRelease(docMgr_);
    SafeRelease(threadMgr_);
}

// ── IUnknown ────────────────────────────────────────────────────────────────

STDMETHODIMP XimeTextService::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) {
        return E_POINTER;
    }
    *ppv = nullptr;
    if (riid == IID_IUnknown) {
        *ppv = static_cast<IUnknown*>(static_cast<ITfTextInputProcessor*>(this));
    } else if (riid == IID_ITfTextInputProcessor) {
        *ppv = static_cast<ITfTextInputProcessor*>(this);
    } else if (riid == IID_ITfTextInputProcessorEx) {
        *ppv = static_cast<ITfTextInputProcessorEx*>(this);
    } else if (riid == IID_ITfKeyEventSink) {
        *ppv = static_cast<ITfKeyEventSink*>(this);
    } else if (riid == IID_ITfThreadMgrEventSink) {
        *ppv = static_cast<ITfThreadMgrEventSink*>(this);
    } else if (riid == IID_ITfTextEditSink) {
        *ppv = static_cast<ITfTextEditSink*>(this);
    } else if (riid == IID_ITfCompositionSink) {
        *ppv = static_cast<ITfCompositionSink*>(this);
    } else {
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

STDMETHODIMP_(ULONG) XimeTextService::AddRef() {
    return static_cast<ULONG>(InterlockedIncrement(&refCount_));
}

STDMETHODIMP_(ULONG) XimeTextService::Release() {
    const ULONG n = static_cast<ULONG>(InterlockedDecrement(&refCount_));
    if (n == 0) {
        delete this;
    }
    return n;
}

// ── ITfTextInputProcessor ───────────────────────────────────────────────────

STDMETHODIMP XimeTextService::Activate(ITfThreadMgr* ptim, TfClientId tid) {
    return ActivateEx(ptim, tid, 0);
}

STDMETHODIMP XimeTextService::ActivateEx(ITfThreadMgr* ptim, TfClientId tid,
                                         DWORD dwFlags) {
    (void)dwFlags;
    if (!ptim) {
        return E_INVALIDARG;
    }
    if (activated_) {
        return S_FALSE;
    }

    threadMgr_ = ptim;
    threadMgr_->AddRef();
    clientId_ = tid;

    if (SUCCEEDED(threadMgr_->QueryInterface(IID_ITfSource,
                                             reinterpret_cast<void**>(&threadMgrSource_)))) {
        threadMgrSource_->AdviseSink(IID_ITfThreadMgrEventSink,
                                     static_cast<ITfThreadMgrEventSink*>(this),
                                     &threadMgrSinkCookie_);
    }
    if (SUCCEEDED(threadMgr_->QueryInterface(
            IID_ITfKeystrokeMgr, reinterpret_cast<void**>(&keystrokeMgr_)))) {
        keystrokeMgr_->AdviseKeyEventSink(clientId_,
                                          static_cast<ITfKeyEventSink*>(this),
                                          TRUE);
    }

    // 初始化 librime：用户目录放在 %LOCALAPPDATA%\Xime，共享数据目录取模块目录。
    wchar_t modulePath[MAX_PATH] = {0};
    GetModuleFileNameW(nullptr, modulePath, MAX_PATH);
    std::wstring dir(modulePath);
    const size_t slash = dir.find_last_of(L"\\/");
    if (slash != std::wstring::npos) {
        dir.resize(slash);
    }
    wchar_t localAppData[MAX_PATH] = {0};
    if (GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData, MAX_PATH) == 0) {
        wcscpy_s(localAppData, MAX_PATH, L".");
    }
    const std::wstring userDir = std::wstring(localAppData) + L"\\Xime";
    CreateDirectoryW(userDir.c_str(), nullptr);
    const std::wstring dataDir = dir + L"\\data";

    auto wideToUtf8 = [](const std::wstring& w) {
        const int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(),
                                          static_cast<int>(w.size()),
                                          nullptr, 0, nullptr, nullptr);
        std::string out(static_cast<size_t>(n > 0 ? n : 0), '\0');
        if (n > 0) {
            WideCharToMultiByte(CP_UTF8, 0, w.c_str(),
                                static_cast<int>(w.size()), &out[0], n,
                                nullptr, nullptr);
        }
        return out;
    };

    core::RimeEngine::Instance().Initialize(wideToUtf8(userDir),
                                            wideToUtf8(dataDir));
    hasSession_ = false;
    if (!candidateWindow_) {
        candidateWindow_ = new (std::nothrow) CandidateWindow();
    }
    activated_ = true;
    return S_OK;
}

STDMETHODIMP XimeTextService::Deactivate() {
    if (!activated_) {
        return S_FALSE;
    }

    if (candidateWindow_) {
        candidateWindow_->Hide();
    }

    if (contextSource_ && textEditSinkCookie_ != TF_INVALID_COOKIE) {
        contextSource_->UnadviseSink(textEditSinkCookie_);
        textEditSinkCookie_ = TF_INVALID_COOKIE;
    }
    if (keystrokeMgr_) {
        keystrokeMgr_->UnadviseKeyEventSink(clientId_);
    }
    if (threadMgrSource_ && threadMgrSinkCookie_ != TF_INVALID_COOKIE) {
        threadMgrSource_->UnadviseSink(threadMgrSinkCookie_);
        threadMgrSinkCookie_ = TF_INVALID_COOKIE;
    }

    core::RimeEngine::Instance().DestroySession();
    hasSession_ = false;

    SafeRelease(contextSource_);
    SafeRelease(keystrokeMgr_);
    SafeRelease(threadMgrSource_);
    SafeRelease(composition_);
    SafeRelease(context_);
    SafeRelease(docMgr_);
    SafeRelease(threadMgr_);
    threadMgr_ = nullptr;
    clientId_ = TF_CLIENTID_NULL;
    activated_ = false;
    return S_OK;
}

// ── ITfKeyEventSink ─────────────────────────────────────────────────────────

STDMETHODIMP XimeTextService::OnSetFocus(BOOL fForeground) {
    (void)fForeground;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnTestKeyDown(ITfContext* pic, WPARAM wParam,
                                            LPARAM lParam, BOOL* pfEaten) {
    *pfEaten = FALSE;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnTestKeyUp(ITfContext* pic, WPARAM wParam,
                                          LPARAM lParam, BOOL* pfEaten) {
    *pfEaten = FALSE;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnKeyUp(ITfContext* pic, WPARAM wParam,
                                      LPARAM lParam, BOOL* pfEaten) {
    *pfEaten = FALSE;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnPreservedKey(ITfContext* pic, REFGUID rguid,
                                             BOOL* pfEaten) {
    *pfEaten = FALSE;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnKeyDown(ITfContext* pic, WPARAM wParam,
                                        LPARAM lParam, BOOL* pfEaten) {
    *pfEaten = FALSE;
    if (!pic || !activated_) {
        return S_OK;
    }

    const unsigned int vk = static_cast<unsigned int>(wParam & 0xff);
    const bool shift = (GetKeyState(VK_SHIFT) & 0x8000) != 0;
    const bool ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
    const bool alt = (GetKeyState(VK_MENU) & 0x8000) != 0;
    const bool capslock = (GetKeyState(VK_CAPITAL) & 0x0001) != 0;

    // 修饰键与系统快捷键不拦截。
    if (vk == VK_SHIFT || vk == VK_CONTROL || vk == VK_MENU || vk == VK_CAPITAL) {
        return S_OK;
    }
    if (ctrl || alt) {
        return S_OK;
    }

    if (!EnsureSession()) {
        return S_OK;
    }

    // 候选窗口存在时，数字键用于直接选词。
    if (candidateWindow_ && candidateWindow_->IsVisible() &&
        HandleCandidateSelection(pic, vk)) {
        *pfEaten = TRUE;
        return S_OK;
    }

    if (!core::IsRimeKey(vk)) {
        return S_OK;
    }

    ProcessInputKey(pic, vk, shift, ctrl, alt, capslock);
    *pfEaten = TRUE;
    return S_OK;
}

bool XimeTextService::EnsureSession() {
    if (hasSession_ && core::RimeEngine::Instance().HasSession()) {
        return true;
    }
    hasSession_ = core::RimeEngine::Instance().CreateSession();
    return hasSession_;
}

bool XimeTextService::HandleCandidateSelection(ITfContext* context,
                                               unsigned int vk) {
    int index = -1;
    if (vk >= '1' && vk <= '9') {
        index = static_cast<int>(vk - '1');
    } else if (vk == '0') {
        index = 9;
    }
    if (index < 0) {
        return false;
    }
    // 候选选择走 Rime 的数字键；由 Rime 决定消费还是上屏。
    const unsigned int rimeKey = vk;
    const auto result = core::RimeEngine::Instance().ProcessKeyAndGetResult(
        rimeKey, 0);
    if (!result.committedText.empty()) {
        CommitText(context, result.committedText);
    }
    if (result.candidates.empty()) {
        EndComposition(context);
    } else {
        UpdateComposition(context, result.preeditText, result.preeditCursorPos,
                          result.candidates);
    }
    return true;
}

void XimeTextService::ProcessInputKey(ITfContext* context, unsigned int vk,
                                      bool shift, bool ctrl, bool alt,
                                      bool capslock) {
    (void)ctrl;
    (void)alt;
    const unsigned int rimeKey = core::ToRimeKey(vk, shift, capslock);
    const unsigned int mask = core::ToRimeMask(shift, ctrl, alt);
    if (rimeKey == 0) {
        return;
    }

    const auto result =
        core::RimeEngine::Instance().ProcessKeyAndGetResult(rimeKey, mask);
    if (!result.committedText.empty()) {
        CommitText(context, result.committedText);
    }
    if (result.candidates.empty() && result.preeditText.empty()) {
        EndComposition(context);
    } else {
        UpdateComposition(context, result.preeditText, result.preeditCursorPos,
                          result.candidates);
    }
}

// ── 文本提交与组合 ──────────────────────────────────────────────────────────

void XimeTextService::CommitText(ITfContext* context,
                                 const std::string& utf8) {
    if (context && !utf8.empty()) {
        EndComposition(context);
        const std::wstring text = Utf8ToWide(utf8);
        ITfInsertAtSelection* insert = nullptr;
        if (SUCCEEDED(context->QueryInterface(
                IID_ITfInsertAtSelection, reinterpret_cast<void**>(&insert)))) {
            ITfRange* range = nullptr;
            insert->InsertTextAtSelection(TF_DEFAULT_SELECTION, text.c_str(),
                                          static_cast<LONG>(text.size()),
                                          &range, nullptr);
            SafeRelease(range);
            SafeRelease(insert);
        }
    }
    if (candidateWindow_) {
        candidateWindow_->Hide();
    }
}

void XimeTextService::UpdateComposition(
    ITfContext* context, const std::string& preedit, int caretBytes,
    const std::vector<core::Candidate>& candidates) {
    if (!context) {
        return;
    }
    const std::wstring text = Utf8ToWide(preedit);

    if (!composition_) {
        ITfContextComposition* ctxComp = nullptr;
        if (FAILED(context->QueryInterface(
                IID_ITfContextComposition,
                reinterpret_cast<void**>(&ctxComp)))) {
            return;
        }
        ITfRange* caret = nullptr;
        TF_SELECTION sel = {0};
        ULONG fetched = 0;
        if (SUCCEEDED(context->GetSelection(TF_DEFAULT_SELECTION, 1, &sel,
                                            &fetched)) &&
            fetched > 0) {
            caret = sel.range;
        }
        if (caret) {
            ctxComp->StartComposition(context, caret,
                                      static_cast<ITfCompositionSink*>(this),
                                      &composition_);
        }
        SafeRelease(ctxComp);
    }

    if (composition_) {
        ITfRange* range = nullptr;
        if (SUCCEEDED(composition_->GetRange(&range))) {
            range->SetText(TF_STRING_INSERTION, text.c_str(),
                           static_cast<LONG>(text.size()));
            // caretBytes 是 UTF-8 字节偏移，近似换算为 UTF-16 单元偏移。
            LONG offset = 0;
            if (caretBytes > 0) {
                offset = static_cast<LONG>(Utf8ToWide(preedit.substr(
                    0, static_cast<size_t>(caretBytes))).size());
            }
            range->Collapse(offset > 0 ? TF_ANCHOR_END : TF_ANCHOR_START);
            SafeRelease(range);
        }
    }

    if (candidateWindow_) {
        candidateWindow_->Update(candidates, caretBytes);
        candidateWindow_->Show();
    }
}

void XimeTextService::EndComposition(ITfContext* context) {
    if (composition_) {
        composition_->EndComposition(context);
        SafeRelease(composition_);
        composition_ = nullptr;
    }
    if (candidateWindow_) {
        candidateWindow_->Hide();
    }
}

ITfContext* XimeTextService::CurrentContext() const {
    return context_;
}

// ── ITfThreadMgrEventSink ───────────────────────────────────────────────────

STDMETHODIMP XimeTextService::OnInitDocumentMgr(ITfDocumentMgr* pdim) {
    (void)pdim;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnUninitDocumentMgr(ITfDocumentMgr* pdim) {
    if (docMgr_ == pdim) {
        if (contextSource_ && textEditSinkCookie_ != TF_INVALID_COOKIE) {
            contextSource_->UnadviseSink(textEditSinkCookie_);
            textEditSinkCookie_ = TF_INVALID_COOKIE;
        }
        SafeRelease(contextSource_);
        SafeRelease(context_);
        context_ = nullptr;
        SafeRelease(docMgr_);
        docMgr_ = nullptr;
    }
    return S_OK;
}

STDMETHODIMP XimeTextService::OnSetFocus(ITfDocumentMgr* pdimFocus,
                                         ITfDocumentMgr* pdimPrevFocus) {
    (void)pdimPrevFocus;
    if (contextSource_ && textEditSinkCookie_ != TF_INVALID_COOKIE) {
        contextSource_->UnadviseSink(textEditSinkCookie_);
        textEditSinkCookie_ = TF_INVALID_COOKIE;
    }
    SafeRelease(contextSource_);
    SafeRelease(context_);
    context_ = nullptr;
    SafeRelease(docMgr_);
    docMgr_ = nullptr;

    if (pdimFocus && SUCCEEDED(pdimFocus->GetTop(&context_)) && context_) {
        docMgr_ = pdimFocus;
        docMgr_->AddRef();
        if (SUCCEEDED(context_->QueryInterface(
                IID_ITfSource, reinterpret_cast<void**>(&contextSource_)))) {
            contextSource_->AdviseSink(IID_ITfTextEditSink,
                                       static_cast<ITfTextEditSink*>(this),
                                       &textEditSinkCookie_);
        }
    }
    return S_OK;
}

STDMETHODIMP XimeTextService::OnPushContext(ITfContext* pic) {
    (void)pic;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnPopContext(ITfContext* pic) {
    (void)pic;
    return S_OK;
}

// ── ITfTextEditSink / ITfCompositionSink ────────────────────────────────────

STDMETHODIMP XimeTextService::OnEndEdit(ITfContext* pic, TfEditCookie ecReadOnly,
                                        ITfEditRecord* pEditRecord) {
    (void)pic;
    (void)ecReadOnly;
    (void)pEditRecord;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnCompositionTerminated(TfEditCookie ecWrite,
                                                      ITfComposition* pComposition) {
    (void)ecWrite;
    if (composition_ == pComposition) {
        SafeRelease(composition_);
        composition_ = nullptr;
    }
    if (candidateWindow_) {
        candidateWindow_->Hide();
    }
    return S_OK;
}

} // namespace tsf
} // namespace xime
