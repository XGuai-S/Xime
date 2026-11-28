// 中/英文切换端到端：真实 librime 会话 toggle ascii_mode 并回读状态。
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
    const std::string user = argc > 2 ? argv[2] : "desktop/build/ascii-user";
    auto& e = RimeEngine::Instance();
    if (!e.Initialize(user, data)) {
        std::printf("ASCII_FAIL: init\n");
        return 1;
    }
    e.StartMaintenance(true);
    for (int i = 0; i < 600 && e.IsMaintaining(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    if (!e.CreateSession()) {
        std::printf("ASCII_FAIL: session\n");
        return 1;
    }
    e.SelectSchema("pinyin_simp");

    const bool initial = e.IsAsciiMode();
    Check(!initial, "initial should be Chinese (ascii off)");
    std::printf("ASCII_INITIAL=%d\n", initial ? 1 : 0);

    const bool afterOn = e.ToggleAsciiMode();
    Check(afterOn, "toggle -> ascii on");
    Check(e.IsAsciiMode(), "read back ascii on");
    std::printf("ASCII_AFTER_ON=%d\n", e.IsAsciiMode() ? 1 : 0);

    const bool afterOff = e.ToggleAsciiMode();
    Check(!afterOff, "toggle -> ascii off");
    Check(!e.IsAsciiMode(), "read back ascii off");
    std::printf("ASCII_AFTER_OFF=%d\n", e.IsAsciiMode() ? 1 : 0);

    if (fails == 0) {
        std::printf("ASCII_TOGGLE_OK\n");
        return 0;
    }
    std::printf("ASCII_FAILS=%d\n", fails);
    return 1;
}
