// 按键吞掉策略：只有引擎处理/产生结果才拦截，否则放行给应用。
#include "../src/core/KeyEatPolicy.h"
#include "../src/core/RimeEngine.h"

#include <cstdio>

using namespace xime::core;

namespace {
int fails = 0;
void Check(bool cond, const char* msg) {
    if (!cond) { std::printf("FAIL: %s\n", msg); ++fails; }
}
} // namespace

int main() {
    // 引擎未处理且无任何结果 → 放行
    { ProcessResult r; Check(!KeyEatPolicy::ShouldEat(r), "unprocessed empty -> pass"); }
    // 引擎处理 → 吃掉
    { ProcessResult r; r.processed = true; Check(KeyEatPolicy::ShouldEat(r), "processed -> eat"); }
    // 自动上屏 → 吃掉
    { ProcessResult r; r.committedText = "你好"; Check(KeyEatPolicy::ShouldEat(r), "commit -> eat"); }
    // 有候选 → 吃掉
    { ProcessResult r; r.candidates.push_back({"你", "", 0}); Check(KeyEatPolicy::ShouldEat(r), "candidates -> eat"); }
    // 有组合 → 吃掉
    { ProcessResult r; r.preeditText = "ni"; Check(KeyEatPolicy::ShouldEat(r), "preedit -> eat"); }

    // 英文直通
    Check(KeyEatPolicy::PassThroughInAscii(true, true, false, false, false), "ascii letter pass");
    Check(!KeyEatPolicy::PassThroughInAscii(true, true, true, false, false), "ascii backspace to engine");
    Check(!KeyEatPolicy::PassThroughInAscii(true, true, false, true, false), "ascii return to engine");
    Check(!KeyEatPolicy::PassThroughInAscii(false, true, false, false, false), "chinese not pass");
    Check(!KeyEatPolicy::PassThroughInAscii(true, false, false, false, false), "non-rime not pass");

    if (fails == 0) { std::printf("KEYEAT_TEST_OK\n"); return 0; }
    std::printf("KEYEAT_FAILS=%d\n", fails);
    return 1;
}
