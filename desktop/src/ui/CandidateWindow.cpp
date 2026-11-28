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

// 候选窗与光标之间的固定间距（CSS 意义上的 gap）。
constexpr int kGap = 6;

// 计算候选窗应出现的位置：默认在光标正下方，下方放不下则翻到上方，
// 再整体钳制进工作区，保证不越界。
// caret: 光标/组合文本的屏幕矩形；winW/winH: 候选窗尺寸；work: 屏幕工作区。
POINT PlaceNearCaret(const RECT& caret, int winW, int winH, const RECT& work) {
    int x = caret.left;
    int y = caret.bottom + kGap;

    // 下方空间不足且上方更宽敞 → 翻到光标上方。
    if (y + winH > work.bottom) {
        const int above = caret.top - kGap - winH;
        if (above >= work.top) {
            y = above;
        } else {
            y = (std::max)(static_cast<int>(work.top),
                           static_cast<int>(work.bottom) - winH);
        }
    }

    // 水平钳制：先左对齐光标，超出右边界则右对齐光标，再兜底钳进工作区。
    if (x + winW > work.right) {
        x = caret.right - winW;
    }
    const int maxX = (std::max)(static_cast<int>(work.left),
                                static_cast<int>(work.right) - winW);
    const int maxY = (std::max)(static_cast<int>(work.top),
                                static_cast<int>(work.bottom) - winH);
    x = (std::max)(static_cast<int>(work.left), (std::min)(x, maxX));
    y = (std::max)(static_cast<int>(work.top), (std::min)(y, maxY));

    POINT out = {x, y};
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

    // 候选窗尺寸随候选数量变化，先按实际内容算出目标尺寸。
    RECT cur = {0, 0, 0, 0};
    if (GetWindowRect(hwnd_, &cur)) {
        // 用当前窗口尺寸，避免每帧重建布局。
    }
    const int winW = (cur.right - cur.left) > 0 ? (cur.right - cur.left) : 360;
    const int winH = (cur.bottom - cur.top) > 0 ? (cur.bottom - cur.top) : 220;

    RECT work = {0, 0, 0, 0};
    if (!SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0)) {
        work.left = 0;
        work.top = 0;
        work.right = GetSystemMetrics(SM_CXSCREEN);
        work.bottom = GetSystemMetrics(SM_CYSCREEN);
    }

    // 优先用 TSF 层给出的组合文本屏幕矩形（唯一可靠的光标位置来源）。
    RECT caret = {0, 0, 0, 0};
    bool haveCaret = false;
    if (caretValid_) {
        caret.left = caretPt_.x;
        caret.top = caretPt_.y;
        caret.right = caretPt_.x;
        caret.bottom = caretPt_.y;
        haveCaret = true;
    } else {
        // 兜底：当前线程的 GUI 线程信息里取光标位置（同进程有效）。
        GUITHREADINFO gti = {0};
        gti.cbSize = sizeof(gti);
        if (GetGUIThreadInfo(0, &gti) && gti.hwndCaret) {
            POINT p = {gti.rcCaret.left, gti.rcCaret.bottom};
            if (ClientToScreen(gti.hwndCaret, &p)) {
                caret.left = caret.top = p.x;
                caret.right = caret.bottom = p.y;
                haveCaret = true;
            }
        }
    }
    if (!haveCaret) {
        // 最后兜底：鼠标位置，至少不会跑到屏幕左上角。
        POINT p = {0, 0};
        GetCursorPos(&p);
        caret.left = caret.top = p.x;
        caret.right = caret.bottom = p.y;
    }

    const POINT pos = PlaceNearCaret(caret, winW, winH, work);
    SetWindowPos(hwnd_, HWND_TOPMOST, pos.x, pos.y, 0, 0,
                 SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
    visible_ = true;
}

RECT CandidateWindow::CurrentRect() const {
    RECT r = {0, 0, 0, 0};
    if (hwnd_) {
        GetWindowRect(hwnd_, &r);
    }
    return r;
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
