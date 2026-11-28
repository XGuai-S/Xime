// 插件管理器测试：扫描目录、链式变换候选。
#include "../src/plugins/PluginManager.h"

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

namespace fs = std::filesystem;
using namespace xime::plugins;

namespace {
int fails = 0;
void Check(bool c, const char* m) { if (!c) { std::printf("FAIL: %s\n", m); ++fails; } }

void WriteFile(const fs::path& p, const std::string& content) {
    std::ofstream out(p, std::ios::binary);
    out << content;
}
} // namespace

int main(int argc, char** argv) {
    const fs::path root = (argc > 1) ? argv[1] : "desktop/build/pluginmgr-test";
    std::error_code ec;
    fs::remove_all(root, ec);
    fs::create_directories(root / "a", ec);
    fs::create_directories(root / "b", ec);
    WriteFile(root / "a" / "main.js",
              "var plugin=(function(){return definePlugin({manifest:{name:'a'},"
              "transform:{candidates:function(l){return l.map(function(x){return 'A'+x;});}}});})();");
    WriteFile(root / "b" / "main.js",
              "var plugin=(function(){return definePlugin({manifest:{name:'b'},"
              "transform:{candidates:function(l){return l.map(function(x){return 'B'+x;});}}});})();");
    // 非插件子目录（无 main.js）应被跳过
    fs::create_directories(root / "c", ec);

    PluginManager mgr;
    std::string err;
    const size_t loaded = mgr.LoadDirectory(root.string(), &err);
    Check(loaded == 2, "loaded two plugins");
    Check(mgr.Count() == 2, "count two");

    auto names = mgr.Names();
    Check(names.size() == 2 && names[0] == "a" && names[1] == "b", "names sorted");

    const auto out = mgr.TransformCandidates({"你", "好"});
    Check(out.size() == 2 && out[0] == "BA你" && out[1] == "BA好", "chained transform");

    if (fails == 0) {
        std::printf("PLUGINMGR_TEST_OK\n");
        return 0;
    }
    std::printf("PLUGINMGR_TEST_FAILS=%d\n", fails);
    return 1;
}
