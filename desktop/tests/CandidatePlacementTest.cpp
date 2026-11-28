// 候选窗定位验收：必须跟随光标，且在屏幕边缘正确翻转，不越界。
//
// 背景：最初用 GetCaretPos + foreground 窗口换算，在 TSF 场景下取不到真实
// 光标位置，候选窗固定出现在错误位置（用户反馈"没有跟随光标"）。
// 正确来源是 ITfContextView::GetTextExt。这里验证定位算法本身。
#include "../src/ui/CandidateWindow.h"

#include <windows.h>

#include <cstdio>

namespace {

int g_fail = 0;

void Expect(bool cond, const char* what, int x, int y) {
    if (cond) {
        std::printf("PLACE_OK   %s at (%d,%d)\n", what, x, y);
    } else {
        std::printf("PLACE_FAIL %s at (%d,%d)\n", what, x, y);
        ++g_fail;
    }
}

// 与 CandidateWindow.cpp 内保持一致的定位实现（通过真实窗口验证）。
struct Case {
    const char* name;
    RECT caret;
    RECT work;
    int winW;
    int winH;
};

} // namespace

int main() {
    // 用真实候选窗对象验证：SetCaretScreenPos + Show + CurrentRect。
    xime::ui::CandidateWindow win;

    const RECT work = {0, 0, 1920, 1040};

    // 情况 1：屏幕中部，候选窗应在光标下方。
    {
        POINT pt = {800, 500};
        win.SetCaretScreenPos(pt, true);
        win.Show();
        RECT r = win.CurrentRect();
        Expect(r.top >= 500, "middle: below caret", r.left, r.top);
        Expect(r.left <= 800 && r.left >= 0, "middle: x near caret", r.left, r.top);
        win.Hide();
    }

    // 情况 2：贴近屏幕底部，应翻到光标上方（关键翻转逻辑）。
    {
        POINT pt = {800, 1020};
        win.SetCaretScreenPos(pt, true);
        win.Show();
        RECT r = win.CurrentRect();
        // 上方翻转成功意味着窗口底边在光标之上。
        Expect(r.bottom <= 1020, "bottom: flipped above caret", r.left, r.top);
        win.Hide();
    }

    // 情况 3：贴近右边界，应水平左移，不越界。
    {
        POINT pt = {1910, 400};
        win.SetCaretScreenPos(pt, true);
        win.Show();
        RECT r = win.CurrentRect();
        Expect(r.right <= 1920, "right: no horizontal overflow", r.left, r.top);
        win.Hide();
    }

    // 情况 4：无效光标 → 兜底不崩，位置仍在屏幕内。
    {
        win.SetCaretScreenPos(POINT{0, 0}, false);
        win.Show();
        RECT r = win.CurrentRect();
        Expect(r.left >= 0 && r.top >= 0, "invalid caret: on-screen fallback",
               r.left, r.top);
        win.Hide();
    }

    if (g_fail != 0) {
        std::printf("PLACE_FAIL %d\n", g_fail);
        return 1;
    }
    std::printf("PLACE_OK_ALL\n");
    return 0;
}
