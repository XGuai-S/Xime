// 候选翻页端到端：真实 librime 输入产生多页候选 → ChangePage 翻页 → 回退。
#include "../src/core/RimeEngine.h"

#include <cstdio>
#include <string>
#include <thread>

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

int main(int argc, char** argv) {
    const std::string data = argc > 1 ? argv[1] : "desktop/build/data";
    const std::string user = argc > 2 ? argv[2] : "desktop/build/pageflip-user";
    auto& e = RimeEngine::Instance();
    if (!e.Initialize(user, data)) {
        std::printf("PAGEFLIP_FAIL: init\n");
        return 1;
    }
    e.StartMaintenance(true);
    for (int i = 0; i < 600 && e.IsMaintaining(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    if (!e.CreateSession()) {
        std::printf("PAGEFLIP_FAIL: session\n");
        return 1;
    }
    if (!e.SelectSchema("pinyin_simp")) {
        std::printf("PAGEFLIP_FAIL: schema\n");
        return 1;
    }
    // 设每页 1 个候选，确保单键输入产生多页。
    e.SetPageSize(1);
    ProcessResult r;
    for (const char* p = "nihao"; *p; ++p) {
        r = e.ProcessKeyAndGetResult(static_cast<unsigned int>(*p), 0);
    }
    if (r.candidates.empty()) {
        std::printf("PAGEFLIP_FAIL: no candidates\n");
        return 1;
    }
    Check(r.hasNextPage, "has next page with page size 1");
    const std::string first = r.candidates[0].text;
    std::printf("PAGEFLIP_PAGE0=%s next=%d\n", first.c_str(), r.hasNextPage ? 1 : 0);

    // 下一页
    ProcessResult nxt = e.ChangePage(false);
    Check(nxt.processed, "change page processed");
    Check(nxt.hasPrevPage, "prev available after next");
    std::printf("PAGEFLIP_NEXT=%s\n",
                nxt.candidates.empty() ? "" : nxt.candidates[0].text.c_str());

    // 回退
    ProcessResult prev = e.ChangePage(true);
    Check(prev.processed, "change page back processed");
    Check(!prev.candidates.empty() && prev.candidates[0].text == first,
          "back to first page");

    if (fails == 0) {
        std::printf("PAGEFLIP_OK\n");
        return 0;
    }
    std::printf("PAGEFLIP_FAILS=%d\n", fails);
    return 1;
}
