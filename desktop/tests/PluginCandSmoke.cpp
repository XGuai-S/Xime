// 插件候选变换端到端冒烟：加载插件（首候选加★前缀）→ 输入 nihao → 候选被改且选词仍正确。
#include "../src/core/RimeEngine.h"
#include "../src/plugins/PluginHost.h"

#include <cstdio>
#include <filesystem>
#include <string>
#include <thread>

namespace fs = std::filesystem;
using namespace xime;

static const char* kPluginJs =
    "var plugin = (function(){\n"
    "  return definePlugin({\n"
    "    manifest: { name: 'star-plugin' },\n"
    "    transform: { candidates: function(list){\n"
    "      var out = list.slice(); if (out.length) out[0] = '\\u2605' + out[0]; return out; } }\n"
    "  });\n"
    "})();\n";

int main(int argc, char** argv) {
    std::string dataDir = argc > 1 ? argv[1] : "desktop/build/data";
    std::string userDir = argc > 2 ? argv[2] : "desktop/build/plugincand-user";
    if (!fs::exists(dataDir + "/default.yaml")) {
        std::printf("PLUGCAND_FAIL: no data\n");
        return 1;
    }
    std::error_code ec;
    fs::create_directories(userDir, ec);

    auto& engine = core::RimeEngine::Instance();
    if (!engine.Initialize(userDir, dataDir)) {
        std::printf("PLUGCAND_FAIL: init\n");
        return 1;
    }
    engine.StartMaintenance(true);
    for (int i = 0; i < 600 && engine.IsMaintaining(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    if (!engine.CreateSession() || !engine.SelectSchema("pinyin_simp")) {
        std::printf("PLUGCAND_FAIL: session/schema\n");
        return 1;
    }

    auto host = std::make_shared<plugins::PluginHost>();
    std::string err;
    if (!host->LoadFromSource(kPluginJs, &err)) {
        std::printf("PLUGCAND_FAIL: plugin load: %s\n", err.c_str());
        return 1;
    }
    std::printf("PLUGCAND_PLUGIN=%s transform=%d\n", host->Name().c_str(),
                host->transformAvailable() ? 1 : 0);
    engine.SetPluginHost(host);

    core::ProcessResult last;
    for (const char* p = "nihao"; *p; ++p) {
        last = engine.ProcessKeyAndGetResult(static_cast<unsigned int>(*p), 0);
    }
    if (last.candidates.empty()) {
        std::printf("PLUGCAND_FAIL: no candidates\n");
        return 1;
    }
    const std::string first = last.candidates.front().text;
    std::printf("PLUGCAND_FIRST=%s engineIdx=%d\n", first.c_str(),
                last.candidates.front().engineIndex);
    // 首候选应带 ★ 前缀（UTF-8 E2 98 85）。
    const bool starred = first.rfind("\xE2\x98\x85", 0) == 0;
    if (!starred) {
        std::printf("PLUGCAND_FAIL: first not starred\n");
        return 1;
    }
    // 去掉 ★ 后应为引擎原词；选首候选应上屏该词（引擎下标保留）。
    const std::string expected = first.substr(3);
    const auto sel = engine.SelectCandidateByEngineIndex(0);
    std::printf("PLUGCAND_COMMIT=%s expected=%s\n", sel.committedText.c_str(),
                expected.c_str());
    if (sel.committedText != expected) {
        std::printf("PLUGCAND_FAIL: commit mismatch\n");
        return 1;
    }
    std::printf("PLUGCAND_OK\n");
    return 0;
}
