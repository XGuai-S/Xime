// 插件生命周期 + host 服务：onLoad/onUnload 调用、host.quickSend.list() 注入，
// 对齐安卓 quick-phrase 插件契约（onLoad 拉缓存 → transform 用缓存）。纯逻辑。
#include "../src/plugins/PluginHost.h"

#include <cstdio>
#include <string>

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
    // quick-phrase 风格：onLoad 从 host.quickSend.list() 拉缓存，transform 用缓存。
    const std::string src = R"JS(
var plugin = definePlugin({
  manifest: { name: "quick-phrase-test" },
  onLoad: function () {
    var items = host.quickSend.list();
    cache = [];
    for (var i = 0; i < items.length; i++) {
      cache.push(items[i].text);
    }
  },
  onUnload: function () { cache = []; },
  transform: {
    candidates: function (req) {
      if (cache.length === 0) return null;
      var out = [];
      for (var i = 0; i < cache.length; i++) {
        out.push({ text: cache[i], comment: "快捷" });
      }
      return { candidates: out };
    }
  }
});
var cache = [];
)JS";

    PluginHost host;
    std::string err;
    Check(host.LoadFromSource(src, &err), "load");
    Check(host.Name() == "quick-phrase-test", "name");

    // 注入快捷发送数据源。
    host.SetQuickSendProvider([]() {
        return std::vector<QuickSendEntry>{
            {1, "电话号码：1350000", "dh", 100, true},
            {2, "地址：北京", "dz", 200, false},
        };
    });

    // onLoad 拉缓存 → transform 应看到 2 条快捷候选。
    Check(host.InvokeLoad(&err), "onLoad ok");
    TransformRequest req;
    req.inputText = "dh";
    req.candidates.push_back({"电话", "", 0});
    auto resp = host.Transform(req);
    Check(resp.changed, "changed after load");
    Check(resp.candidates.size() == 2, "two quick candidates");
    Check(resp.candidates[0].text == "电话号码：1350000", "first quick text");

    // onUnload 清缓存 → transform 返回 null（不干预）。
    Check(host.InvokeUnload(&err), "onUnload ok");
    auto resp2 = host.Transform(req);
    Check(!resp2.changed, "no intervention after unload");

    // 缺少生命周期方法的插件：调用视为成功（不报错）。
    PluginHost host2;
    Check(host2.LoadFromSource(
              "var plugin = definePlugin({manifest:{name:'noop'}});", &err),
          "load noop");
    Check(host2.InvokeLoad(&err), "noop onLoad ok");
    Check(host2.InvokeUnload(&err), "noop onUnload ok");

    // 生命周期抛错的插件：InvokeLoad 返回 false 并带错误。
    PluginHost host3;
    Check(host3.LoadFromSource(
              "var plugin = definePlugin({onLoad:function(){throw new Error('boom');}});",
              &err),
          "load throw plugin");
    std::string err3;
    Check(!host3.InvokeLoad(&err3), "throwing onLoad fails");
    Check(!err3.empty(), "error message present");

    if (fails == 0) {
        std::printf("PLUGLIFECYCLE_TEST_OK\n");
        return 0;
    }
    std::printf("PLUGLIFECYCLE_FAILS=%d\n", fails);
    return 1;
}
