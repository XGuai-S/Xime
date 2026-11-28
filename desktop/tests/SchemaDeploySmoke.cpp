// 方案管理端到端：用 SchemaListConfig 写 schema_list → 引擎部署 → 该方案可选中。
#include "../src/core/RimeEngine.h"
#include "../src/core/SchemaListConfig.h"

#include <chrono>
#include <cstdio>
#include <filesystem>
#include <thread>

namespace fs = std::filesystem;
using xime::core::RimeEngine;
using xime::core::SchemaListConfig;

int main(int argc, char** argv) {
    std::string dataDir = argc > 1 ? argv[1] : "desktop/build/data";
    std::string userDir = argc > 2 ? argv[2] : "desktop/build/schemadeploy-user";
    fs::remove_all(userDir);
    fs::create_directories(userDir);

    // 通过配置层写入方案列表（而非手工编辑 YAML）。
    std::vector<std::string> list = {"pinyin_simp", "wubi86", "t9_pinyin"};
    if (!SchemaListConfig::Write(userDir, list)) {
        std::printf("SCHEMADEPLOY_FAIL: write\n");
        return 1;
    }
    const auto readBack = SchemaListConfig::Read(userDir);
    std::printf("SCHEMADEPLOY_LIST=%zu\n", readBack.size());

    auto& engine = RimeEngine::Instance();
    if (!engine.Initialize(userDir, dataDir)) {
        std::printf("SCHEMADEPLOY_FAIL: init\n");
        return 1;
    }
    engine.StartMaintenance(true);
    for (int i = 0; i < 900 && engine.IsMaintaining(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    if (engine.IsMaintaining()) {
        std::printf("SCHEMADEPLOY_FAIL: deploy timeout\n");
        return 1;
    }
    if (!engine.CreateSession()) {
        std::printf("SCHEMADEPLOY_FAIL: session\n");
        return 1;
    }

    // schema_list 里的 t9_pinyin 部署后应可直接选中。
    if (!engine.SelectSchema("t9_pinyin")) {
        std::printf("SCHEMADEPLOY_FAIL: select t9_pinyin\n");
        return 1;
    }
    const std::string cur = engine.CurrentSchema();
    std::printf("SCHEMADEPLOY_CUR=%s\n", cur.c_str());
    if (cur != "t9_pinyin") {
        std::printf("SCHEMADEPLOY_FAIL: current != t9_pinyin\n");
        return 1;
    }
    std::printf("SCHEMADEPLOY_OK\n");
    return 0;
}
