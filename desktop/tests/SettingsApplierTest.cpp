// 设置应用测试：schemaList 写入 default.custom.yaml，幂等，空列表不写。
#include "../src/settings/SettingsApplier.h"
#include "../src/core/SchemaListConfig.h"

#include <cstdio>
#include <filesystem>
#include <string>

namespace fs = std::filesystem;
using namespace xime::settings;
using namespace xime::core;

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
    const std::string dir = "desktop/build/settingsapply-test-user";
    fs::remove_all(dir);
    fs::create_directories(dir);

    DesktopSettings s;
    s.schemaList = {"pinyin_simp", "wubi86"};
    auto r = Apply(s, dir);
    Check(r.ok && r.schemaListWritten, "schema list written");
    const auto read = SchemaListConfig::Read(dir);
    Check(read.size() == 2 && read[0] == "pinyin_simp" && read[1] == "wubi86",
          "schema list roundtrip");

    // 幂等：内容相同不再写。
    auto r2 = Apply(s, dir);
    Check(!r2.schemaListWritten, "idempotent no rewrite");

    // 空列表：不写。
    DesktopSettings empty;
    auto r3 = Apply(empty, dir);
    Check(!r3.schemaListWritten, "empty list no write");

    if (fails == 0) {
        std::printf("SETTINGSAPPLIER_TEST_OK\n");
        return 0;
    }
    std::printf("SETTINGSAPPLIER_TEST_FAILS=%d\n", fails);
    return 1;
}
