// 方案循环切换端到端：真实 librime 枚举已部署方案 → CycleSchema 依次切换。
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
    const std::string user = argc > 2 ? argv[2] : "desktop/build/cycle-user";
    auto& e = RimeEngine::Instance();
    if (!e.Initialize(user, data)) {
        std::printf("CYCLE_FAIL: init\n");
        return 1;
    }
    if (!e.IsDeployed()) {
        e.StartMaintenance(true);
        for (int i = 0; i < 600 && e.IsMaintaining(); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
        }
    }
    if (!e.CreateSession()) {
        std::printf("CYCLE_FAIL: session\n");
        return 1;
    }
    const auto list = e.SchemaList();
    std::printf("CYCLE_SCHEMAS=%zu\n", list.size());
    Check(list.size() >= 2, "at least two schemas deployed");

    const std::string first = e.CurrentSchema();
    std::printf("CYCLE_FIRST=%s\n", first.c_str());
    const std::string second = e.CycleSchema();
    std::printf("CYCLE_SECOND=%s\n", second.c_str());
    Check(!second.empty() && second != first, "cycled to a different schema");
    Check(e.CurrentSchema() == second, "current schema updated");

    // 从 second 继续切换 list.size()-1 次，应回到首个（共 N 个方案一圈）。
    std::string cur = second;
    for (size_t i = 0; i + 1 < list.size(); ++i) {
        cur = e.CycleSchema();
    }
    Check(cur == first, "full cycle returns to first");

    if (fails == 0) {
        std::printf("CYCLE_OK\n");
        return 0;
    }
    std::printf("CYCLE_FAILS=%d\n", fails);
    return 1;
}
