#include "XimeTextService.h"

#include "../core/KeyMap.h"
#include "../core/RimeEngine.h"
#include "../plugins/PluginManager.h"
#include "../ui/CandidateWindow.h"

#include <functional>
#include <memory>
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

// TSF 的所有文本改动（提交、组合、清空）都必须在 edit session 内、凭有效
// edit cookie 执行。这里用一个同步 edit session 封装，回调里拿 cookie 干活。
class FuncEditSession : public ITfEditSession {
public:
    explicit FuncEditSession(std::function<HRESULT(TfEditCookie)> fn)
        : fn_(std::move(fn)) {}

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) {
            return E_POINTER;
        }
        *ppv = nullptr;
        if (riid == IID_IUnknown || riid == IID_ITfEditSession) {
            *ppv = static_cast<ITfEditSession*>(this);
            return S_OK;
        }
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return 2; }
    STDMETHODIMP_(ULONG) Release() override { return 1; }

    STDMETHODIMP DoEditSession(TfEditCookie ec) override {
        result_ = fn_(ec);
        return S_OK;
    }

    HRESULT Result() const { return result_; }

private:
    std::function<HRESULT(TfEditCookie)> fn_;
    HRESULT result_ = E_FAIL;
};

HRESULT SyncEdit(ITfContext* context, TfClientId clientId,
                 std::function<HRESULT(TfEditCookie)> fn) {
    if (!context) {
        return E_INVALIDARG;
    }
    FuncEditSession session(std::move(fn));
    HRESULT sessionHr = S_OK;
    const HRESULT hr = context->RequestEditSession(
        clientId, &session, TF_ES_SYNC | TF_ES_READWRITE, &sessionHr);
    if (FAILED(hr)) {
        return hr;
    }
    if (FAILED(sessionHr)) {
        return sessionHr;
    }
    return session.Result();
}

} // namespace

XimeTextService::XimeTextService() = default;

XimeTextService::~XimeTextService() {
    delete candidateWindow_;
    candidateWindow_ = nullptr;
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
    if (!ptim) {
        return E_INVALIDARG;
    }
    if (activated_) {
        return S_FALSE;
    }

    threadMgr_ = ptim;
    threadMgr_->AddRef();
    clientId_ = tid;

    if (SUCCEEDED(threadMgr_->QueryInterface(
            IID_ITfSource, reinterpret_cast<void**>(&threadMgrSource_)))) {
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

    // 初始化 librime：用户目录 %LOCALAPPDATA%\Xime，共享数据目录取 DLL 同级 data。
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
        candidateWindow_ = new (std::nothrow) ui::CandidateWindow();
    }

    // 启动加载插件：DLL 同级 plugins/ 目录下的所有 <name>/main.js。
    // 插件宿主失败（目录不存在/插件语法错）不影响输入法主体。
    pluginManager_.reset(new (std::nothrow) plugins::PluginManager());
    if (pluginManager_) {
        const std::string pluginDir = wideToUtf8(dir + L"\\plugins");
        std::string pluginErr;
        pluginManager_->LoadDirectory(pluginDir, &pluginErr);
        // 把完整契约变换注册进引擎（core 类型回调，不链接 QuickJS）。
        auto* mgr = pluginManager_.get();
        core::RimeEngine::Instance().SetPluginTransformFull(
            [mgr](core::RimeEngine::PluginTransformContext& ctx) {
                plugins::TransformRequest pr;
                pr.inputText = ctx.inputText;
                pr.preedit = ctx.preedit;
                pr.asciiMode = ctx.asciiMode;
                for (const auto& c : ctx.candidates) {
                    pr.candidates.push_back({c.text, c.comment, c.engineIndex});
                }
                plugins::TransformResponse pres = mgr->Transform(pr);
                if (!pres.changed) {
                    return; // 插件不干预
                }
                ctx.candidates.clear();
                for (const auto& c : pres.candidates) {
                    core::Candidate cc;
                    cc.text = c.text;
                    cc.comment = c.comment;
                    cc.engineIndex = c.engineIndex;
                    ctx.candidates.push_back(std::move(cc));
                }
            });
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
    (void)pic;
    (void)wParam;
    (void)lParam;
    *pfEaten = FALSE;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnTestKeyUp(ITfContext* pic, WPARAM wParam,
                                          LPARAM lParam, BOOL* pfEaten) {
    (void)pic;
    (void)wParam;
    (void)lParam;
    *pfEaten = FALSE;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnKeyUp(ITfContext* pic, WPARAM wParam,
                                      LPARAM lParam, BOOL* pfEaten) {
    (void)pic;
    (void)wParam;
    (void)lParam;
    *pfEaten = FALSE;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnPreservedKey(ITfContext* pic, REFGUID rguid,
                                             BOOL* pfEaten) {
    (void)pic;
    (void)rguid;
    *pfEaten = FALSE;
    return S_OK;
}

STDMETHODIMP XimeTextService::OnKeyDown(ITfContext* pic, WPARAM wParam,
                                        LPARAM lParam, BOOL* pfEaten) {
    (void)lParam;
    *pfEaten = FALSE;
    if (!pic || !activated_) {
        return S_OK;
    }

    const unsigned int vk = static_cast<unsigned int>(wParam & 0xff);
    const bool shift = (GetKeyState(VK_SHIFT) & 0x8000) != 0;
    const bool ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
    const bool alt = (GetKeyState(VK_MENU) & 0x8000) != 0;
    const bool capslock = (GetKeyState(VK_CAPITAL) & 0x0001) != 0;

    if (vk == VK_SHIFT || vk == VK_CONTROL || vk == VK_MENU || vk == VK_CAPITAL) {
        return S_OK;
    }
    if (ctrl || alt) {
        return S_OK;
    }

    if (!EnsureSession()) {
        return S_OK;
    }

    // 候选翻页：仅在候选窗可见时拦截，PageUp/PageDown 与 -/= 翻页。
    if (candidateWindow_ && candidateWindow_->IsVisible()) {
        bool backward = false;
        bool paging = true;
        if (vk == VK_PRIOR || vk == VK_OEM_MINUS) {
            backward = true;
        } else if (vk != VK_NEXT && vk != VK_OEM_PLUS) {
            paging = false;
        }
        if (paging) {
            const auto result = core::RimeEngine::Instance().ChangePage(backward);
            if (result.processed) {
                ApplyEngineResult(pic, result);
            }
            *pfEaten = TRUE;
            return S_OK;
        }
    }

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
    int displayIndex = -1;
    if (vk >= '1' && vk <= '9') {
        displayIndex = static_cast<int>(vk - '1');
    } else if (vk == '0') {
        displayIndex = 9;
    }
    if (displayIndex < 0) {
        return false;
    }
    // 候选可能被词频重排：数字键对应「显示位」，选词要回到它原本的引擎下标。
    if (displayIndex < static_cast<int>(lastCandidates_.size())) {
        const auto& cand = lastCandidates_[displayIndex];
        if (cand.engineIndex >= 0) {
            const auto result =
                core::RimeEngine::Instance().SelectCandidateByEngineIndex(
                    cand.engineIndex);
            ApplyEngineResult(context, result);
            return true;
        }
        // engineIndex < 0：自动造词候选，非引擎词，直接上屏其文本并清空引擎组合。
        core::RimeEngine::Instance().ClearComposition();
        CommitText(context, cand.text);
        lastCandidates_.clear();
        return true;
    }
    // 无候选映射时退回引擎页内选词。
    const auto result =
        core::RimeEngine::Instance().ProcessKeyAndGetResult(vk, 0);
    ApplyEngineResult(context, result);
    return true;
}

void XimeTextService::ProcessInputKey(ITfContext* context, unsigned int vk,
                                      bool shift, bool ctrl, bool alt,
                                      bool capslock) {
    const unsigned int rimeKey = core::ToRimeKey(vk, shift, capslock);
    const unsigned int mask = core::ToRimeMask(shift, ctrl, alt);
    if (rimeKey == 0) {
        return;
    }
    const auto result =
        core::RimeEngine::Instance().ProcessKeyAndGetResult(rimeKey, mask);
    ApplyEngineResult(context, result);
}

void XimeTextService::ApplyEngineResult(ITfContext* context,
                                        const core::ProcessResult& result) {
    lastCandidates_ = result.candidates;
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
    if (!context || utf8.empty()) {
        return;
    }
    const std::wstring text = Utf8ToWide(utf8);
    SyncEdit(context, clientId_, [this, context, &text](TfEditCookie ec) -> HRESULT {
        EndCompositionInSession(context, ec);
        ITfInsertAtSelection* insert = nullptr;
        if (FAILED(context->QueryInterface(
                IID_ITfInsertAtSelection, reinterpret_cast<void**>(&insert)))) {
            return E_FAIL;
        }
        ITfRange* range = nullptr;
        const HRESULT hr = insert->InsertTextAtSelection(
            ec, 0, text.c_str(), static_cast<LONG>(text.size()), &range);
        SafeRelease(range);
        SafeRelease(insert);
        return hr;
    });
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
    LONG caretUtf16 = 0;
    if (caretBytes > 0) {
        caretUtf16 = static_cast<LONG>(
            Utf8ToWide(preedit.substr(0, static_cast<size_t>(caretBytes))).size());
    }

    SyncEdit(context, clientId_,
             [this, context, &text, caretUtf16](TfEditCookie ec) -> HRESULT {
                 if (!composition_) {
                     ITfContextComposition* ctxComp = nullptr;
                     if (FAILED(context->QueryInterface(
                             IID_ITfContextComposition,
                             reinterpret_cast<void**>(&ctxComp)))) {
                         return E_FAIL;
                     }
                     TF_SELECTION sel = {0};
                     ULONG fetched = 0;
                     ITfRange* caret = nullptr;
                     if (SUCCEEDED(context->GetSelection(
                             ec, TF_DEFAULT_SELECTION, 1, &sel, &fetched)) &&
                         fetched > 0) {
                         caret = sel.range;
                     }
                     if (caret) {
                         ctxComp->StartComposition(
                             ec, caret,
                             static_cast<ITfCompositionSink*>(this),
                             &composition_);
                     }
                     SafeRelease(caret);
                     SafeRelease(ctxComp);
                 }
                 if (!composition_) {
                     return E_FAIL;
                 }
                 ITfRange* range = nullptr;
                 if (FAILED(composition_->GetRange(&range))) {
                     return E_FAIL;
                 }
                 range->SetText(ec, 0, text.c_str(),
                                static_cast<LONG>(text.size()));
                 range->Collapse(ec, caretUtf16 > 0 ? TF_ANCHOR_END
                                                    : TF_ANCHOR_START);
                 SafeRelease(range);
                 return S_OK;
             });

    if (candidateWindow_) {
        candidateWindow_->Update(candidates, caretBytes);
        candidateWindow_->Show();
    }
}

void XimeTextService::EndComposition(ITfContext* context) {
    if (!composition_) {
        if (candidateWindow_) {
            candidateWindow_->Hide();
        }
        return;
    }
    if (context) {
        SyncEdit(context, clientId_,
                 [this, context](TfEditCookie ec) -> HRESULT {
                     EndCompositionInSession(context, ec);
                     return S_OK;
                 });
    }
    if (composition_) {
        SafeRelease(composition_);
        composition_ = nullptr;
    }
    if (candidateWindow_) {
        candidateWindow_->Hide();
    }
}

void XimeTextService::EndCompositionInSession(ITfContext* context,
                                              TfEditCookie ec) {
    if (composition_) {
        composition_->EndComposition(ec);
        SafeRelease(composition_);
        composition_ = nullptr;
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
