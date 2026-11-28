// 候选选取解析单测（纯逻辑）：词频重排/造词/插件新增候选在数字键与鼠标点击
// 两条路径必须一致。
#include "../src/core/CandidatePicker.h"
#include "../src/core/RimeEngine.h"

#include <cstdio>

using namespace xime::core;

namespace {
int fails = 0;
void Check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++fails;
    }
}
} // namespace

int main() {
    std::vector<Candidate> cands = {
        {"你好", "", 1},       // 引擎原候选 #1（被词频重排到首位）
        {"小霞", "", -1},      // 自动造词候选
        {"电话", "", 0},       // 引擎原候选 #0
        {"★好", "", -1},       // 插件新增候选
    };

    // 显示位 0 → 引擎词 #1
    auto a = CandidatePicker::Resolve(cands, 0);
    Check(a.type == PickAction::Type::kEngine && a.engineIndex == 1,
          "display 0 -> engine idx 1");

    // 显示位 1 → 造词，直接上屏文本
    a = CandidatePicker::Resolve(cands, 1);
    Check(a.type == PickAction::Type::kCommitText && a.text == "小霞",
          "display 1 -> commit text 小霞");

    // 显示位 2 → 引擎词 #0
    a = CandidatePicker::Resolve(cands, 2);
    Check(a.type == PickAction::Type::kEngine && a.engineIndex == 0,
          "display 2 -> engine idx 0");

    // 显示位 3 → 插件新增，直接上屏
    a = CandidatePicker::Resolve(cands, 3);
    Check(a.type == PickAction::Type::kCommitText && a.text == "★好",
          "display 3 -> plugin added text");

    // 越界 → 无动作
    Check(CandidatePicker::Resolve(cands, -1).IsNone(), "negative -> none");
    Check(CandidatePicker::Resolve(cands, 4).IsNone(), "out of range -> none");
    Check(CandidatePicker::Resolve({}, 0).IsNone(), "empty -> none");

    // 数字键映射
    Check(CandidatePicker::DisplayIndexFromDigit('1') == 0, "digit 1 -> 0");
    Check(CandidatePicker::DisplayIndexFromDigit('9') == 8, "digit 9 -> 8");
    Check(CandidatePicker::DisplayIndexFromDigit('0') == 9, "digit 0 -> 9");
    Check(CandidatePicker::DisplayIndexFromDigit('a') == -1, "non-digit -> -1");

    if (fails == 0) {
        std::printf("CANDIDATEPICKER_OK\n");
        return 0;
    }
    std::printf("CANDIDATEPICKER_FAILS=%d\n", fails);
    return 1;
}
