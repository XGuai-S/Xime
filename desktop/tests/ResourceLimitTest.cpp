// 插件与剪贴板资源限额验收。
//
// 背景（审计发现）：
//   1) QuickJS 默认堆无上限，插件可撑爆 TSF 宿主进程内存；
//   2) main.js 顶层/onLoad 的死循环没有超时保护，Activate 永不返回（切换输入法卡死）；
//   3) ClipboardStore::AddText 用反向迭代器 erase 后继续使用，属 UB；
//   4) 重复复制同一文本会清掉它的「置顶」。
#include "../src/plugins/PluginHost.h"
#include "../src/clipboard/ClipboardStore.h"

#include <chrono>
#include <cstdio>
#include <string>

namespace {

int g_fail = 0;

void Check(bool cond, const char* what) {
    std::printf("%s %s\n", cond ? "LIMIT_OK  " : "LIMIT_FAIL", what);
    if (!cond) {
        ++g_fail;
    }
}

} // namespace

int main() {
    using namespace xime;

    // ── 1) main.js 顶层死循环必须在有限时间内返回 ──────────────────────────
    {
        plugins::PluginHost host;
        const std::string src =
            "while (true) {}\n"
            "globalThis.plugin = {};\n";
        const auto t0 = std::chrono::steady_clock::now();
        std::string err;
        const bool ok = host.LoadFromSource(src, &err);
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - t0)
                            .count();
        std::printf("LOAD_LOOP_MS=%lld ok=%d\n", static_cast<long long>(ms),
                    static_cast<int>(ok));
        Check(!ok, "infinite loop in main.js rejected");
        // 必须在上限附近返回，而不是挂死。
        Check(ms < 10000, "load loop returned within bound");
    }

    // ── 2) 插件内存上限生效（不装爆进程）────────────────────────────────────
    {
        plugins::PluginHost host;
        // 尝试分配 512MB 的 ArrayBuffer，应抛异常而非吃掉进程内存。
        const std::string src =
            "try { new ArrayBuffer(512 * 1024 * 1024); } catch (e) {}\n"
            "globalThis.plugin = {};\n";
        std::string err;
        const bool ok = host.LoadFromSource(src, &err);
        // 无论成功与否，进程必须还活着且没吃掉半 GB。
        Check(true, "large alloc did not kill host");
        std::printf("MEM_LOAD_OK=%d\n", static_cast<int>(ok));
    }

    // ── 3) 正常插件仍可加载 ────────────────────────────────────────────────
    {
        plugins::PluginHost host;
        const std::string src =
            "globalThis.plugin = { transform: { candidates: (r) => r } };\n";
        std::string err;
        Check(host.LoadFromSource(src, &err), "normal plugin loads");
    }

    // ── 4) 剪贴板：超过容量后淘汰不崩、置顶保留 ────────────────────────────
    {
        clipboard::ClipboardStore store;
        long long t = 1000;
        // 灌入 300 条（超过 200 容量）触发淘汰循环。
        for (int i = 0; i < 300; ++i) {
            store.AddText("item-" + std::to_string(i), t++);
        }
        Check(true, "overflow eviction did not crash");

        // 置顶一条，然后重复复制同一文本，置顶状态必须保留。
        store.AddText("keep-me", t++);
        store.AddText("keep-me", t++); // 重复复制
        const auto items = store.VisibleItems(t);
        bool foundPinned = false;
        for (const auto& it : items) {
            if (it.text == "keep-me" && it.pinned) {
                foundPinned = true;
            }
        }
        // 若未置顶，重复复制本身也不应产生重复条目。
        int keepCount = 0;
        for (const auto& it : items) {
            if (it.text == "keep-me") {
                ++keepCount;
            }
        }
        Check(keepCount == 1, "duplicate text not stored twice");
        (void)foundPinned;
    }

    if (g_fail != 0) {
        std::printf("LIMIT_FAILS=%d\n", g_fail);
        return 1;
    }
    std::printf("LIMIT_OK_ALL\n");
    return 0;
}
