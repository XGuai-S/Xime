// 插件宿主测试：QuickJS 加载 main.js，验证 manifest.name 与 transform.candidates。
#include "../src/plugins/PluginHost.h"

#include <cstdio>
#include <string>
#include <vector>

using namespace xime::plugins;

namespace {
int fails = 0;
void Check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++fails;
    }
}
} // namespace

int main() {
    // 1. 标准插件：definePlugin 返回对象，transform.candidates 前置标记。
    const std::string src = R"JS(
var plugin = (function () {
  return definePlugin({
    manifest: { name: "test-plugin", version: "1.0" },
    transform: {
      candidates: function (list) {
        var out = [];
        if (list.length > 0) { out.push("★" + list[0]); }
        for (var i = 0; i < list.length; i++) { out.push(list[i]); }
        return out;
      }
    }
  });
})();
)JS";
    PluginHost host;
    std::string err;
    Check(host.LoadFromSource(src, &err), "load ok");
    Check(host.transformAvailable(), "transform available");
    Check(host.Name() == "test-plugin", "manifest name");
    const auto in = std::vector<std::string>{"你", "好"};
    const auto out = host.TransformCandidates(in);
    Check(out.size() == 3, "out size");
    Check(out.size() == 3 && out[0] == "★你", "prepended marker");
    Check(out.size() == 3 && out[1] == "你" && out[2] == "好", "originals kept");

    // 2. 无 transform 的插件：不改变候选。
    PluginHost host2;
    Check(host2.LoadFromSource("var plugin = definePlugin({manifest:{name:'noop'}});", &err),
          "load noop ok");
    Check(!host2.transformAvailable(), "noop has no transform");
    const auto same = host2.TransformCandidates(in);
    Check(same == in, "noop unchanged");

    // 3. 语法错误：加载失败并带错误信息。
    PluginHost host3;
    std::string err3;
    Check(!host3.LoadFromSource("var plugin = (((;", &err3), "syntax error load fails");
    Check(!err3.empty(), "error message present");

    if (fails == 0) {
        std::printf("PLUGIN_TEST_OK\n");
        return 0;
    }
    std::printf("PLUGIN_TEST_FAILS=%d\n", fails);
    return 1;
}
