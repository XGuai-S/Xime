// SchemaListConfig 单测：往返、排序、手写/注释解析、空列表、异常行容错。
#include "../src/core/SchemaListConfig.h"

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

namespace fs = std::filesystem;
using xime::core::SchemaListConfig;

namespace {
int fails = 0;
void check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++fails;
    }
}
bool eq(const std::vector<std::string>& a, const std::vector<std::string>& b) {
    return a == b;
}
} // namespace

int main() {
    const std::string dir = "desktop/build/schema-test-user";
    fs::remove_all(dir);
    fs::create_directories(dir);

    // 往返：写入 + 读回
    std::vector<std::string> list = {"pinyin_simp", "wubi86", "t9_pinyin"};
    check(SchemaListConfig::Write(dir, list), "write ok");
    check(eq(SchemaListConfig::Read(dir), list), "roundtrip equal");

    // 排序变化
    std::vector<std::string> reordered = {"wubi86", "pinyin_simp"};
    check(SchemaListConfig::Write(dir, reordered), "write reordered");
    check(eq(SchemaListConfig::Read(dir), reordered), "reorder roundtrip");

    // 空列表
    check(SchemaListConfig::Write(dir, {}), "write empty");
    check(SchemaListConfig::Read(dir).empty(), "empty read");

    // 手写/注释/引号/无 schema: 前缀 解析
    {
        std::ofstream out(SchemaListConfig::FilePath(dir), std::ios::trunc);
        out << "# comment\npatch:\n  schema_list:\n"
            << "    - schema: pinyin_simp  # inline\n"
            << "    - wubi86\n"
            << "    - schema: \"t9_pinyin\"\n"
            << "  other_key: 1\n";
    }
    std::vector<std::string> parsed = SchemaListConfig::Read(dir);
    check(eq(parsed, {"pinyin_simp", "wubi86", "t9_pinyin"}), "parse handwritten");

    // 不存在文件 → 空
    {
        const std::string dir2 = "desktop/build/schema-test-none";
        fs::remove_all(dir2);
        check(SchemaListConfig::Read(dir2).empty(), "missing file empty");
    }

    if (fails == 0) {
        std::printf("SCHEMALIST_TEST_OK\n");
        return 0;
    }
    std::printf("SCHEMALIST_TEST_FAILS=%d\n", fails);
    return 1;
}
