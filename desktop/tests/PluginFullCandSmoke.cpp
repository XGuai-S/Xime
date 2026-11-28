// 插件完整契约端到端：quick-phrase 风格插件（保留引擎候选 + 新增文本候选），
// 经引擎 transform 后候选栏出现新增项，且选引擎候选仍正确上屏。
#include "../src/core/RimeEngine.h"
#include "../src/plugins/PluginHost.h"

#include <cstdio>
#include <filesystem>
#include <string>
#include <thread>

namespace fs = std::filesystem;
using namespace xime;

// 插件：保留全部引擎候选，并在其后新增一个 engineIndex=-1 的文本候选。
static const char* kQuickJs =
    "var plugin = definePlugin({\n"
    "  manifest: { name: 'quick-phrase' },\n"
    "  transform: { candidates: function(req){\n"
    "    var out = [];\n"
    "    var cs = req.candidates || [];\n"
    "    for (var i = 0; i < cs.length; i++) out.push({ engineIndex: i });\n"
    "    out.push({ text: '快捷句子', comment: '快捷' });\n"
    "    return { candidates: out };\n"
    "  } }\n"
    "});\n";

int main(int argc, char** argv) {
    std::string dataDir = argc > 1 ? argv[1] : "desktop/build/data";
    std::string userDir = argc > 2 ? argv[2] : "desktop/build/pluginfull-user";
    if (!fs::exists(dataDir + "/default.yaml")) {
        std::printf("PLUGFULL_FAIL: no data\n");
        return 1;
    }
    std::error_code ec;
    fs::create_directories(userDir, ec);

    auto& engine = core::RimeEngine::Instance();
    if (!engine.Initialize(userDir, dataDir)) {
        std::printf("PLUGFULL_FAIL: init\n");
        return 1;
    }
    engine.StartMaintenance(true);
    for (int i = 0; i < 600 && engine.IsMaintaining(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    if (!engine.CreateSession() || !engine.SelectSchema("pinyin_simp")) {
        std::printf("PLUGFULL_FAIL: session/schema\n");
        return 1;
    }

    auto host = std::make_shared<plugins::PluginHost>();
    std::string err;
    if (!host->LoadFromSource(kQuickJs, &err)) {
        std::printf("PLUGFULL_FAIL: plugin load: %s\n", err.c_str());
        return 1;
    }
    engine.SetPluginTransformFull(
        [host](core::RimeEngine::PluginTransformContext& ctx) {
            plugins::TransformRequest req;
            req.inputText = ctx.inputText;
            req.preedit = ctx.preedit;
            req.asciiMode = ctx.asciiMode;
            for (const auto& c : ctx.candidates) {
                plugins::TransformCandidate pc;
                pc.text = c.text;
                pc.comment = c.comment;
                pc.engineIndex = c.engineIndex;
                req.candidates.push_back(pc);
            }
            auto resp = host->Transform(req);
            if (!resp.changed) {
                return;
            }
            ctx.candidates.clear();
            for (const auto& pc : resp.candidates) {
                core::Candidate c;
                c.text = pc.text;
                c.comment = pc.comment;
                c.engineIndex = pc.engineIndex;
                ctx.candidates.push_back(c);
            }
        });

    core::ProcessResult last;
    for (const char* p = "nihao"; *p; ++p) {
        last = engine.ProcessKeyAndGetResult(static_cast<unsigned int>(*p), 0);
    }
    if (last.candidates.empty()) {
        std::printf("PLUGFULL_FAIL: no candidates\n");
        return 1;
    }
    bool hasAdded = false;
    int addedIdx = -1;
    for (size_t i = 0; i < last.candidates.size(); ++i) {
        if (last.candidates[i].text == "快捷句子" &&
            last.candidates[i].engineIndex == -1) {
            hasAdded = true;
            addedIdx = static_cast<int>(i);
        }
    }
    std::printf("PLUGFULL_CANDS=%zu ADDED=%d IDX=%d\n", last.candidates.size(),
                hasAdded ? 1 : 0, addedIdx);
    if (!hasAdded) {
        std::printf("PLUGFULL_FAIL: added candidate missing\n");
        return 1;
    }

    // 选第 0 个引擎候选（应仍映射到引擎词，不受新增项影响）。
    const auto sel = engine.SelectCandidateByEngineIndex(0);
    std::printf("PLUGFULL_COMMIT=%s\n", sel.committedText.c_str());
    if (sel.committedText.empty()) {
        std::printf("PLUGFULL_FAIL: engine select commit empty\n");
        return 1;
    }
    std::printf("PLUGFULL_OK\n");
    return 0;
}
