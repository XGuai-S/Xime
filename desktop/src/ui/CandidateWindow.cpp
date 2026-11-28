#include "CandidateWindow.h"

#include "../core/RimeEngine.h"

#include <algorithm>

namespace xime {
namespace ui {

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

} // namespace

CandidateWindow::CandidateWindow() {
    WNDCLASSEXW wc = {0};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = WndProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = kClassName;
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    if (RegisterClassExW(&wc)) {
        registered_ = true;
    }
    hwnd_ = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, kClassName,
                            L"Xime Candidates", WS_POPUP | WS_BORDER,
                            0, 0, 360, 220, nullptr, nullptr,
                            GetModuleHandleW(nullptr), this);
    if (hwnd_) {
        list_ = CreateWindowExW(0, L"ListBox", nullptr,
                                WS_CHILD | WS_VISIBLE | WS_VSCROLL |
                                    LBS_NOINTEGRALHEIGHT,
                                0, 0, 360, 220, hwnd_, nullptr,
                                GetModuleHandleW(nullptr), nullptr);
        SendMessageW(list_, WM_SETFONT,
                     reinterpret_cast<WPARAM>(GetStockObject(DEFAULT_GUI_FONT)),
                     TRUE);
    }
}

CandidateWindow::~CandidateWindow() {
    if (hwnd_) {
        DestroyWindow(hwnd_);
        hwnd_ = nullptr;
    }
    if (registered_) {
        UnregisterClassW(kClassName, GetModuleHandleW(nullptr));
    }
}

LRESULT CALLBACK CandidateWindow::WndProc(HWND hwnd, UINT msg, WPARAM wParam,
                                          LPARAM lParam) {
    if (msg == WM_CREATE) {
        SetWindowLongPtrW(hwnd, GWLP_USERDATA,
                          reinterpret_cast<LONG_PTR>(
                              reinterpret_cast<CREATESTRUCTW*>(lParam)->lpCreateParams));
        return DefWindowProcW(hwnd, msg, wParam, lParam);
    }
    if (msg == WM_COMMAND) {
        auto* self = reinterpret_cast<CandidateWindow*>(
            GetWindowLongPtrW(hwnd, GWLP_USERDATA));
        const WORD code = HIWORD(wParam);
        // ListBox 单击选中(LBN_SELCHANGE)或双击(LBN_DBLCLK)都触发选词。
        if (self && (code == LBN_SELCHANGE || code == LBN_DBLCLK) &&
            self->selectCallback_) {
            const LRESULT sel = SendMessageW(self->list_, LB_GETCURSEL, 0, 0);
            if (sel != LB_ERR && sel >= 0) {
                self->selectCallback_(static_cast<int>(sel));
            }
        }
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

void CandidateWindow::Update(const std::vector<core::Candidate>& candidates,
                             int caretBytes) {
    (void)caretBytes;
    if (!list_) {
        return;
    }
    SendMessageW(list_, LB_RESETCONTENT, 0, 0);
    int index = 0;
    for (const auto& c : candidates) {
        std::wstring text = Utf8ToWide(c.text);
        if (!c.comment.empty()) {
            text += L"  [";
            text += Utf8ToWide(c.comment);
            text += L"]";
        }
        SendMessageW(list_, LB_ADDSTRING, 0,
                     reinterpret_cast<LPARAM>(text.c_str()));
        if (++index >= 50) {
            break;
        }
    }
}

void CandidateWindow::Show() {
    if (!hwnd_) {
        return;
    }
    POINT pt = {0, 0};
    HWND foreground = GetForegroundWindow();
    if (foreground && GetCaretPos(&pt)) {
        ClientToScreen(foreground, &pt);
    } else {
        GetCursorPos(&pt);
    }
    int x = pt.x;
    int y = pt.y + 24;
    RECT work = {0};
    if (SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0)) {
        x = (std::max)(x, static_cast<int>(work.left));
        y = (std::max)(y, static_cast<int>(work.top));
        x = (std::min)(x, static_cast<int>(work.right - 360));
        y = (std::min)(y, static_cast<int>(work.bottom - 220));
    }
    SetWindowPos(hwnd_, HWND_TOPMOST, x, y, 0, 0,
                 SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
    visible_ = true;
}

void CandidateWindow::Hide() {
    if (hwnd_) {
        ShowWindow(hwnd_, SW_HIDE);
    }
    visible_ = false;
}

bool CandidateWindow::IsVisible() const {
    return visible_;
}

} // namespace ui
} // namespace xime
