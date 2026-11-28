// 模拟 TSF 服务启动接线：plugins/ 目录加载 → 注册进引擎 → 输入时插件改候选生效。
#include "../src/core/RimeEngine.h"
#include "../src/plugins/PluginManager.h"

#include <filesystem>
#include <cstdio>
#include <fstream>
#include <string>
#include <thread>

namespace fs = std::filesystem;
using namespace xime;

namespace {
int fails = 0;
void Check(bool c, const char* m) {
    if (!c) { std::printf("FAIL: %s\n", m); ++fails; }
}
} // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::printf("usage: xime-tsfplugin-smoke <data> <user>\n");
        return 2;
    }
    const std::string dataDir = argv[1];
    const std::string userDir = argv[2];
    const std::string pluginRoot = userDir + "/plugins";
    const std::string pluginDir = pluginRoot + "/marker";
    std::error_code ec;
    fs::create_directories(pluginDir, ec);

    // 写一个 quick-phrase 风格插件：保留引擎候选并新增一个文本候选。
    const std::string mainJs = R"JS(
var plugin = definePlugin({
  manifest: { name: "marker" },
  transform: {
    candidates: function (req) {
      var out = [];
      var cands = req.candidates || [];
      for (var i = 0; i < cands.length; i++) {
        out.push({ engineIndex: i, text: cands[i].text });
      }
      if (cands.length > 0) {
        out.push({ text: "插件新增", comment: "plug" });
      }
      return { candidates: out };
    }
  }
});
)JS";
    {
        std::ofstream out(pluginDir + "/main.js", std::ios::binary);
        out << mainJs;
    }

    // 模拟 TSF Activate 的接线。
    auto& engine = core::RimeEngine::Instance();
    if (!engine.Initialize(userDir, dataDir)) {
        std::printf("FAIL: init\n");
        return 1;
    }
    engine.StartMaintenance(true);
    for (int i = 0; i < 600 && engine.IsMaintaining(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    plugins::PluginManager mgr;
    std::string err;
    const size_t loaded = mgr.LoadDirectory(pluginRoot, &err);
    Check(loaded == 1, "one plugin loaded");
    if (loaded != 1) {
        std::printf("PLUGIN_LOAD_ERR=%s\n", err.c_str());
        return 1;
    }

    auto* pMgr = &mgr;
    engine.SetPluginTransformFull(
        [pMgr](core::RimeEngine::PluginTransformContext& ctx) {
            plugins::TransformRequest pr;
            pr.inputText = ctx.inputText;
            pr.preedit = ctx.preedit;
            pr.asciiMode = ctx.asciiMode;
            for (const auto& c : ctx.candidates) {
                pr.candidates.push_back({c.text, c.comment, c.engineIndex});
            }
            plugins::TransformResponse pres = pMgr->Transform(pr);
            if (!pres.changed) return;
            ctx.candidates.clear();
            for (const auto& c : pres.candidates) {
                core::Candidate cc;
                cc.text = c.text;
                cc.comment = c.comment;
                cc.engineIndex = c.engineIndex;
                ctx.candidates.push_back(std::move(cc));
            }
        });

    if (!engine.CreateSession()) {
        std::printf("FAIL: session\n");
        return 1;
    }
    engine.SelectSchema("pinyin_simp");

    core::ProcessResult last;
    for (const char* p = "nihao"; *p; ++p) {
        last = engine.ProcessKeyAndGetResult(
            static_cast<unsigned int>(*p), 0);
    }
    bool foundAdded = false;
    for (const auto& c : last.candidates) {
        if (c.text == "插件新增" && c.engineIndex < 0) {
            foundAdded = true;
        }
    }
    Check(foundAdded, "plugin-added candidate present after wiring");
    std::printf("TSFPLUG_CANDS=%zu ADDED=%d\n", last.candidates.size(),
                foundAdded ? 1 : 0);

    // 模拟 TSF Deactivate→Reactivate：清除回调后插件不得再干预候选。
    engine.ClearPluginTransforms();
    {
        core::ProcessResult again;
        for (const char* p = "nihao"; *p; ++p) {
            again = engine.ProcessKeyAndGetResult(
                static_cast<unsigned int>(*p), 0);
        }
        bool stale = false;
        for (const auto& c : again.candidates) {
            if (c.text == "插件新增") {
                stale = true;
            }
        }
        Check(!stale, "no stale transform after deactivate");
    }

    if (fails == 0) {
        std::printf("TSFPLUG_SMOKE_OK\n");
        return 0;
    }
    return 1;
}
