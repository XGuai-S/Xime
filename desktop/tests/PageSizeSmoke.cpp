// 每页候选数（page_size）端到端验证：默认值 vs 设置为 9 后的候选数对比。
#include "../src/core/RimeEngine.h"

#include <cstdio>
#include <filesystem>
#include <string>
#include <thread>

namespace fs = std::filesystem;
using xime::core::RimeEngine;

static xime::core::ProcessResult Type(RimeEngine& e, const std::string& keys) {
    xime::core::ProcessResult last;
    for (char c : keys) {
        last = e.ProcessKeyAndGetResult(static_cast<unsigned int>(
                                            static_cast<unsigned char>(c)), 0);
    }
    return last;
}

int main(int argc, char** argv) {
    std::string dataDir = argc > 1 ? argv[1] : "desktop/build/data";
    std::string userDir = argc > 2 ? argv[2] : "desktop/build/page-user";
    std::error_code ec;
    fs::create_directories(userDir, ec);

    auto& e = RimeEngine::Instance();
    if (!e.Initialize(userDir, dataDir)) { std::printf("PAGE_FAIL init\n"); return 1; }
    e.StartMaintenance(true);
    for (int i = 0; i < 600 && e.IsMaintaining(); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    if (!e.CreateSession()) { std::printf("PAGE_FAIL session\n"); return 1; }
    if (!e.SelectSchema("pinyin_simp")) { std::printf("PAGE_FAIL schema\n"); return 1; }

    e.ClearComposition();
    auto def = Type(e, "nihao");
    const int defCount = static_cast<int>(def.candidates.size());
    std::printf("PAGE_DEFAULT=%d\n", defCount);

    e.ClearComposition();
    if (!e.SetPageSize(9)) { std::printf("PAGE_FAIL setpage\n"); return 1; }
    auto nine = Type(e, "nihao");
    const int nineCount = static_cast<int>(nine.candidates.size());
    std::printf("PAGE_SET9=%d\n", nineCount);
    e.ClearComposition();

    // 方案切换验证：切到 wubi86 再切回 pinyin_simp，CurrentSchema 应随之改变。
    std::string before = e.CurrentSchema();
    const bool sw1 = e.SelectSchema("wubi86");
    std::string afterWubi = e.CurrentSchema();
    const bool sw2 = e.SelectSchema("pinyin_simp");
    std::string afterBack = e.CurrentSchema();
    std::printf("SCHEMA_BEFORE=%s AFTER_WUBI=%s AFTER_BACK=%s\n", before.c_str(),
                afterWubi.c_str(), afterBack.c_str());
    const bool switchOk = sw1 && sw2 && afterWubi == "wubi86" && before == afterBack;

    if (nineCount == 9 && nineCount > defCount && switchOk) {
        std::printf("PAGE_SMOKE_OK\n");
        return 0;
    }
    std::printf("PAGE_SMOKE_FAIL def=%d nine=%d switch=%d\n", defCount, nineCount,
                switchOk ? 1 : 0);
    return 1;
}
