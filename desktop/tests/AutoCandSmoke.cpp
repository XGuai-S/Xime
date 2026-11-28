// 自动造词候选冒烟：验证「注入造词 → 合成编码 → 输入该码 → 候选出现造词词」。
#include "../src/core/RimeEngine.h"

#include <cstdio>
#include <filesystem>
#include <string>
#include <thread>

namespace fs = std::filesystem;
using xime::core::RimeEngine;

int main(int argc, char** argv) {
    std::string dataDir = argc > 1 ? argv[1] : "desktop/build/data";
    std::string userDir = argc > 2 ? argv[2] : "desktop/build/autocand-user";
    fs::create_directories(userDir);

    auto& engine = RimeEngine::Instance();
    if (!engine.Initialize(userDir, dataDir)) {
        std::printf("AUTOCAND_FAIL: init\n");
        return 1;
    }
    engine.StartMaintenance(true);
    for (int i = 0; i < 600 && engine.IsMaintaining(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    if (!engine.CreateSession()) {
        std::printf("AUTOCAND_FAIL: session\n");
        return 1;
    }
    if (!engine.SelectSchema("wubi86")) {
        std::printf("AUTOCAND_FAIL: select wubi86\n");
        return 1;
    }
    std::printf("AUTOCAND_SCHEMA=%s\n", engine.CurrentSchema().c_str());

    const std::string word = "小霞";
    engine.SetAutoTempWords({word});
    const auto codes = engine.ComposeWordCodes(word);
    if (codes.empty()) {
        std::printf("AUTOCAND_FAIL: 无合成编码（缺单字满四码）\n");
        return 1;
    }
    std::printf("AUTOCAND_CODE=%s\n", codes.front().c_str());

    engine.SetInput(codes.front());
    const auto r = engine.ReadResult(true);
    std::printf("AUTOCAND_TYPED cands=%zu\n", r.candidates.size());
    bool found = false;
    int idx = -1;
    for (size_t i = 0; i < r.candidates.size(); ++i) {
        std::printf("  cand[%zu]=%s engine=%d\n", i, r.candidates[i].text.c_str(),
                    r.candidates[i].engineIndex);
        if (r.candidates[i].text == word) {
            found = true;
            idx = static_cast<int>(i);
        }
    }
    if (!found) {
        std::printf("AUTOCAND_FAIL: 候选未出现造词词\n");
        return 1;
    }
    std::printf("AUTOCAND_FOUND idx=%d engine=%d\n", idx,
                r.candidates[idx].engineIndex);
    std::printf("AUTOCAND_OK\n");
    return 0;
}
