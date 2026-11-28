// T9 定制冒烟：验证 librime-t9 处理器在 Windows 下真实可用。
// 选 t9_pinyin 方案 → 输入九键数字（nihao=64426）→ 校验候选与 T9 面板状态。
#include "../src/core/RimeEngine.h"

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>

namespace fs = std::filesystem;

int main(int argc, char** argv) {
    std::string dataDir = "desktop/build/data";
    std::string userDir = "desktop/build/t9-user";
    if (argc > 1) dataDir = argv[1];
    if (argc > 2) userDir = argv[2];
    if (!fs::exists(dataDir + "/default.yaml")) {
        std::fprintf(stderr, "SMOKE_FAIL: 缺 default.yaml\n");
        return 1;
    }
    std::error_code ec;
    fs::create_directories(userDir, ec);

    // 让部署把 t9_pinyin 纳入 schema_list（原始 assets 的 default 未含九键方案）。
    {
        std::ofstream patch(userDir + "/default.custom.yaml");
        patch << "patch:\n"
              << "  schema_list:\n"
              << "    - schema: pinyin_simp\n"
              << "    - schema: t9_pinyin\n";
    }

    auto& engine = xime::core::RimeEngine::Instance();
    if (!engine.Initialize(userDir, dataDir)) {
        std::fprintf(stderr, "SMOKE_FAIL: Initialize\n");
        return 1;
    }
    engine.StartMaintenance(true);
    for (int i = 0; i < 600 && engine.IsMaintaining(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    if (engine.IsMaintaining()) {
        std::fprintf(stderr, "SMOKE_FAIL: 部署超时\n");
        return 1;
    }
    if (!engine.CreateSession()) {
        std::fprintf(stderr, "SMOKE_FAIL: CreateSession\n");
        return 1;
    }
    if (!engine.SelectSchema("t9_pinyin")) {
        std::fprintf(stderr, "SMOKE_FAIL: 无法选择 t9_pinyin 方案\n");
        return 1;
    }
    std::printf("T9_SCHEMA=t9_pinyin\n");

    // nihao 的九键数字：n6 i4 h4 a2 o6 → 64426
    const char* digits = "64426";
    xime::core::ProcessResult last;
    for (const char* p = digits; *p; ++p) {
        last = engine.ProcessKeyAndGetResult(static_cast<unsigned int>(*p), 0);
    }
    if (last.candidates.empty()) {
        std::fprintf(stderr, "T9_SMOKE_FAIL: 数字输入后无候选\n");
        return 1;
    }
    std::printf("T9_CANDIDATES=%zu FIRST=%s\n", last.candidates.size(),
                last.candidates.front().text.c_str());
    std::printf("T9_PANEL_STATE=%s\n", last.t9PanelState.c_str());
    if (last.t9PanelState.empty()) {
        std::fprintf(stderr, "T9_SMOKE_FAIL: T9 面板状态为空（处理器未生效）\n");
        return 1;
    }

    // 选首候选（数字键 1）。
    auto sel = engine.ProcessKeyAndGetResult('1', 0);
    if (sel.committedText.empty() && sel.candidates.empty()) {
        std::fprintf(stderr, "T9_SMOKE_FAIL: 选候选无响应\n");
        return 1;
    }
    std::printf("T9_COMMIT=%s\n", sel.committedText.c_str());
    std::printf("T9_SMOKE_OK\n");
    return 0;
}
