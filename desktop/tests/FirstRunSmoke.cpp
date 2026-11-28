// 首启部署端到端：全新用户目录 → 未部署 → 触发部署 → 已部署 → 有候选。
#include "../src/core/RimeEngine.h"

#include <cstdio>
#include <filesystem>
#include <string>
#include <thread>

using namespace xime::core;
namespace fs = std::filesystem;

int main(int argc, char** argv) {
    const std::string data = argc > 1 ? argv[1] : "desktop/build/data";
    const std::string user = argc > 2 ? argv[2] : "desktop/build/firstrun-user";
    fs::remove_all(user);

    auto& e = RimeEngine::Instance();
    if (!e.Initialize(user, data)) {
        std::printf("FIRSTRUN_FAIL: init\n");
        return 1;
    }
    if (e.IsDeployed()) {
        std::printf("FIRSTRUN_FAIL: should not be deployed initially\n");
        return 1;
    }
    std::printf("FIRSTRUN_NOT_DEPLOYED_OK\n");

    e.StartMaintenance(true);
    for (int i = 0; i < 1200 && e.IsMaintaining(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    if (e.IsMaintaining()) {
        std::printf("FIRSTRUN_FAIL: deploy timeout\n");
        return 1;
    }
    if (!e.IsDeployed()) {
        std::printf("FIRSTRUN_FAIL: still not deployed\n");
        return 1;
    }
    std::printf("FIRSTRUN_DEPLOYED_OK\n");

    if (!e.CreateSession() || !e.SelectSchema("pinyin_simp")) {
        std::printf("FIRSTRUN_FAIL: session/schema\n");
        return 1;
    }
    ProcessResult r;
    for (const char* p = "nihao"; *p; ++p) {
        r = e.ProcessKeyAndGetResult(static_cast<unsigned int>(*p), 0);
    }
    if (r.candidates.empty()) {
        std::printf("FIRSTRUN_FAIL: no candidates after deploy\n");
        return 1;
    }
    std::printf("FIRSTRUN_CANDS=%zu FIRST=%s\n", r.candidates.size(),
                r.candidates[0].text.c_str());
    std::printf("FIRSTRUN_OK\n");
    return 0;
}
