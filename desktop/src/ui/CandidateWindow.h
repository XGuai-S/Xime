// Win32 候选窗口：显示 librime 候选列表。
#pragma once

#include <windows.h>

#include <functional>
#include <string>
#include <vector>

namespace xime {
namespace core {
struct Candidate;
}

namespace ui {

class CandidateWindow {
public:
    CandidateWindow();
    ~CandidateWindow();

    CandidateWindow(const CandidateWindow&) = delete;
    CandidateWindow& operator=(const CandidateWindow&) = delete;

    void Update(const std::vector<core::Candidate>& candidates, int caretBytes);
    void Show();
    void Hide();
    bool IsVisible() const;

    // 鼠标单击候选行回调（displayIndex 为显示位）。
    using SelectCallback = std::function<void(int displayIndex)>;
    void SetSelectCallback(SelectCallback cb) { selectCallback_ = std::move(cb); }

private:
    static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam,
                                    LPARAM lParam);
    static constexpr const wchar_t* kClassName = L"XimeCandidateWindow";

    HWND hwnd_ = nullptr;
    HWND list_ = nullptr;
    bool visible_ = false;
    bool registered_ = false;
    SelectCallback selectCallback_;
};

} // namespace ui
} // namespace xime
