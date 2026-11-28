// 空格键选词验收。
//
// 背景（审计发现 P1）：VK_SPACE 既不在 IsRimeKey 也不在 ToRimeKey，导致
//   1) 空格无法选中高亮候选（中文输入最主要的选词键）；
//   2) 组合态下按空格会把字面空格插进文档，而组合态仍留在引擎里，候选窗与
//      文档内容错位。
#include "../src/core/KeyMap.h"
#include "../src/core/RimeEngine.h"

#include <windows.h>
#include <cstdio>
#include <filesystem>
#include <string>

namespace fs = std::filesystem;

namespace {
int g_fail = 0;
void Check(bool cond, const char* what) {
    std::printf("%s %s\n", cond ? "SPACE_OK  " : "SPACE_FAIL", what);
    if (!cond) {
        ++g_fail;
    }
}
} // namespace

int main(int argc, char** argv) {
    using namespace xime::core;

    // ── 1) 纯逻辑：空格必须被识别为 rime 键，并映射到 XK_space ──────────────
    Check(IsRimeKey(VK_SPACE), "VK_SPACE is a rime key");
    const unsigned int ks = ToRimeKey(VK_SPACE, false, false);
    Check(ks == 0x20, "VK_SPACE maps to XK_space(0x20)");

    // ── 2) 修饰键掩码必须与 librime 一致（Shift=1, Lock=2, Ctrl=4, Alt=8）──
    // 用带修饰键的映射间接验证：shift 不应被当成 ctrl/alt。
    Check(ToRimeKey('A', true, false) != ToRimeKey('A', false, false),
          "shift changes keycode (not mask)");

    // ── 3) 端到端：真实引擎里输入拼音后按空格应上屏首候选 ──────────────────
    const std::string data = argc > 1 ? argv[1] : "desktop/build/data";
    const std::string user = argc > 2 ? argv[2] : "desktop/build/space-user";
    fs::remove_all(user);

    auto& e = RimeEngine::Instance();
    if (!e.Initialize(user, data)) {
        std::printf("SPACE_FAIL init\n");
        return 1;
    }
    if (!e.IsDeployed()) {
        e.StartMaintenance(true);
        for (int i = 0; i < 1200 && e.IsMaintaining(); ++i) {
            ::Sleep(100);
        }
    }
    if (!e.CreateSession() || !e.SelectSchema("pinyin_simp")) {
        std::printf("SPACE_FAIL session/schema\n");
        return 1;
    }

    // 输入 nihao
    ProcessResult r;
    for (const char* p = "nihao"; *p; ++p) {
        r = e.ProcessKeyAndGetResult(static_cast<unsigned int>(*p), 0);
    }
    if (r.candidates.empty()) {
        std::printf("SPACE_FAIL no candidates\n");
        return 1;
    }
    const std::string first = r.candidates[0].text;
    std::printf("SPACE_CAND0=%s\n", first.c_str());

    // 按空格：应当提交首候选（而不是插入空格）。
    r = e.ProcessKeyAndGetResult(ToRimeKey(VK_SPACE, false, false), 0);
    std::printf("SPACE_COMMIT=[%s]\n", r.committedText.c_str());
    Check(r.committedText == first, "space commits first candidate");
    Check(r.committedText != " ", "space did not insert literal space");

    if (g_fail != 0) {
        std::printf("SPACE_FAILS=%d\n", g_fail);
        return 1;
    }
    std::printf("SPACE_OK_ALL\n");
    return 0;
}
