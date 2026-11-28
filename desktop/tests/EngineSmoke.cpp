// 引擎冒烟测试：无 GUI，验证 librime 核心链路在 Windows 上可用。
// 流程：初始化 → 部署 → 建会话 → 选方案 → 输入 "nihao" → 取候选 → 选首候选 → 校验上屏。
// 成功返回 0，失败返回非 0 并打印原因。
#include "../src/core/RimeEngine.h"

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <string>
#include <thread>

namespace fs = std::filesystem;

namespace {

int Fail(const char* msg) {
    std::fprintf(stderr, "SMOKE_FAIL: %s\n", msg);
    return 1;
}

} // namespace

int main(int argc, char** argv) {
    std::string dataDir = "desktop/build/data";
    std::string userDir = "desktop/build/smoke-user";
    if (argc > 1) {
        dataDir = argv[1];
    }
    if (argc > 2) {
        userDir = argv[2];
    }
    if (!fs::exists(dataDir + "/default.yaml")) {
        return Fail("data 目录缺少 default.yaml");
    }
    std::error_code ec;
    fs::create_directories(userDir, ec);

    auto& engine = xime::core::RimeEngine::Instance();
    if (!engine.Initialize(userDir, dataDir)) {
        return Fail("Initialize 失败");
    }

    // 首次部署（构建词典）。已部署时也安全。
    engine.StartMaintenance(true);
    for (int i = 0; i < 600 && engine.IsMaintaining(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    if (engine.IsMaintaining()) {
        return Fail("部署超时（10 分钟）");
    }

    if (!engine.CreateSession()) {
        return Fail("CreateSession 失败");
    }

    const char* schemas[] = {"pinyin_simp", "wubi86", "t9_pinyin"};
    std::string chosen;
    for (const char* s : schemas) {
        if (engine.SelectSchema(s)) {
            chosen = s;
            break;
        }
    }
    if (chosen.empty()) {
        return Fail("没有可用方案（pinyin_simp/wubi86/t9_pinyin）");
    }
    std::printf("SMOKE_SCHEMA=%s\n", chosen.c_str());

    // 输入 nihao（pinyin_simp）。T9 方案需要数字，这里仅对拼音方案按键。
    if (chosen == "pinyin_simp") {
        const char* keys = "nihao";
        xime::core::ProcessResult last;
        for (const char* p = keys; *p; ++p) {
            last = engine.ProcessKeyAndGetResult(static_cast<unsigned int>(*p), 0);
        }
        if (last.candidates.empty()) {
            return Fail("输入 nihao 后无候选");
        }
        std::printf("SMOKE_CANDIDATES=%zu FIRST=%s\n", last.candidates.size(),
                    last.candidates.front().text.c_str());

        // 选第一个候选（数字键 1）。
        auto sel = engine.ProcessKeyAndGetResult('1', 0);
        if (sel.committedText.empty()) {
            return Fail("选择首候选后无上屏文本");
        }
        std::printf("SMOKE_COMMIT=%s\n", sel.committedText.c_str());
    }

    std::printf("SMOKE_OK\n");
    return 0;
}
