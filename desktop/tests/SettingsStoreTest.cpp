// 设置存储往返测试。
#include "../src/settings/SettingsStore.h"

#include <cstdio>
#include <filesystem>
#include <string>

using namespace xime::settings;

namespace {
int fails = 0;
void Check(bool c, const char* m) { if (!c) { std::printf("FAIL: %s\n", m); ++fails; } }
} // namespace

int main(int argc, char** argv) {
    const std::string dir = (argc > 1) ? argv[1] : "desktop/build/settings-test";
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);

    // 1. 缺文件 → 默认
    DesktopSettings d;
    Check(!SettingsStore::Load(dir + "/nope", &d), "missing returns false");
    Check(d.pageSize == 5 && d.polishEnabled && d.llmModel == "glm-4", "defaults");

    // 2. 往返
    DesktopSettings s;
    s.pageSize = 9;
    s.polishEnabled = false;
    s.llmModel = "glm-4-flash";
    s.llmBaseUrl = "https://api.example.com";
    s.sttModel = "sensevoice";
    s.sttBaseUrl = "http://127.0.0.1:8080";
    s.schemaList = {"pinyin_simp", "wubi86"};
    Check(SettingsStore::Save(dir, s), "save");
    DesktopSettings back;
    Check(SettingsStore::Load(dir, &back), "load");
    Check(back.pageSize == 9 && !back.polishEnabled, "scalar roundtrip");
    Check(back.llmModel == "glm-4-flash" && back.llmBaseUrl == "https://api.example.com", "strings roundtrip");
    Check(back.sttModel == "sensevoice" && back.sttBaseUrl == "http://127.0.0.1:8080", "stt roundtrip");
    Check(back.schemaList.size() == 2 && back.schemaList[1] == "wubi86", "schema list roundtrip");

    // 3. 越界钳制
    DesktopSettings clamped = SettingsStore::ParseOrDefault(
        "{\"page_size\":999,\"polish_enabled\":true}");
    Check(clamped.pageSize == 50, "page_size clamp high");
    DesktopSettings low = SettingsStore::ParseOrDefault("{\"page_size\":1}");
    Check(low.pageSize == 5, "page_size clamp low");

    // 4. 损坏 json → 默认
    DesktopSettings bad = SettingsStore::ParseOrDefault("not json {{{");
    Check(bad.pageSize == 5 && bad.polishEnabled, "malformed defaults");

    if (fails == 0) {
        std::printf("SETTINGS_TEST_OK\n");
        return 0;
    }
    std::printf("SETTINGS_TEST_FAILS=%d\n", fails);
    return 1;
}
