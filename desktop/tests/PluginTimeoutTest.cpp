// 插件 transform 硬超时：死循环插件不得卡死输入热路径（对齐安卓 15ms）。
#include "../src/plugins/PluginHost.h"

#include <chrono>
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
    const std::string kDeadLoop =
        "var plugin = definePlugin({"
        "  manifest: { name: 'deadloop' },"
        "  transform: { candidates: function(req) { while (true) {} } }"
        "});";

    PluginHost host;
    host.SetTransformTimeoutMs(15);
    std::string err;
    if (!host.LoadFromSource(kDeadLoop, &err)) {
        std::printf("FAIL: load: %s\n", err.c_str());
        return 1;
    }
    Check(host.Name() == "deadloop", "name");

    TransformRequest req;
    req.inputText = "nihao";
    req.candidates.push_back({"你好", "", 0});

    const auto t0 = std::chrono::steady_clock::now();
    TransformResponse resp = host.Transform(req);
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0);

    Check(host.lastTimedOut(), "deadloop timed out");
    Check(!resp.changed, "timeout means no intervention");
    Check(elapsed.count() < 1000, "returned promptly (not hung)");
    std::printf("PLUGTIMEOUT_ELAPSED_MS=%lld\n",
                static_cast<long long>(elapsed.count()));

    // 超时后宿主仍可继续服务：换一个正常插件逻辑不应残留异常状态。
    // （同 runtime 无法替换插件函数，这里只验证再次调用同样快速返回。）
    const auto t1 = std::chrono::steady_clock::now();
    (void)host.Transform(req);
    const auto elapsed2 = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t1);
    Check(elapsed2.count() < 1000, "subsequent call still responsive");

    // 关闭超时（0=不限时）时 deadline 不生效，加载仍正常。
    PluginHost host2;
    host2.SetTransformTimeoutMs(0);
    Check(host2.LoadFromSource(kDeadLoop, &err), "no-timeout host loads");

    if (fails == 0) {
        std::printf("PLUGTIMEOUT_TEST_OK\n");
        return 0;
    }
    std::printf("PLUGTIMEOUT_TEST_FAILS=%d\n", fails);
    return 1;
}
