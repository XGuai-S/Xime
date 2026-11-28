// 备份/恢复往返测试：写示例用户数据 → 导出 ZIP → 恢复到新目录 → 逐字节比对；
// 并验证非法条目（路径穿越）被拒绝。纯文件 IO，不依赖 librime。
#include "../src/backup/BackupArchive.h"
#include "../src/backup/BackupService.h"

#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>

namespace fs = std::filesystem;

namespace {
int fails = 0;
void Check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++fails;
    }
}
void WriteText(const std::string& p, const std::string& s) {
    std::ofstream o(p, std::ios::binary | std::ios::trunc);
    o.write(s.data(), static_cast<std::streamsize>(s.size()));
}
std::string ReadText(const std::string& p) {
    std::ifstream i(p, std::ios::binary);
    return std::string((std::istreambuf_iterator<char>(i)),
                       std::istreambuf_iterator<char>());
}
} // namespace

int main() {
    const std::string base = (fs::temp_directory_path() / "xime-bak-test").string();
    std::error_code ec;
    fs::remove_all(base, ec);
    const std::string src = base + "/src";
    const std::string dst = base + "/dst";
    fs::create_directories(src, ec);
    fs::create_directories(dst, ec);

    const std::string freq = "# header\n你好\tnihao\t3\n拟好\tnihao\t1\n";
    const std::string auto1 = "# auto\n小霞\tihfn\t0\n";
    const std::string cfg = "auto_word:\n  enabled: true\n";
    const std::string settings =
        "{\"page_size\":9,\"polish_enabled\":true}";
    WriteText(src + "/word_freq.txt", freq);
    WriteText(src + "/auto_word.txt", auto1);
    WriteText(src + "/xime.yaml", cfg);
    WriteText(src + "/settings.json", settings);
    // 非白名单文件不应进备份。
    WriteText(src + "/secret.bin", "zzz");

    const std::string zip = base + "/backup.zip";
    Check(xime::backup::ExportBackup(src, zip), "export ok");
    Check(fs::exists(zip), "zip exists");

    // 校验它确实是 ZIP（PK 头）。
    const std::string head = ReadText(zip).substr(0, 2);
    Check(head == "PK", "zip magic PK");

    Check(xime::backup::ImportBackup(dst, zip), "import ok");
    Check(ReadText(dst + "/word_freq.txt") == freq, "word_freq roundtrip");
    Check(ReadText(dst + "/auto_word.txt") == auto1, "auto_word roundtrip");
    Check(ReadText(dst + "/xime.yaml") == cfg, "xime.yaml roundtrip");
    Check(ReadText(dst + "/settings.json") == settings, "settings.json roundtrip");
    Check(!fs::exists(dst + "/secret.bin"), "non-whitelist excluded");

    // 非法条目：手工构造含 "../evil" 与子目录的 ZIP，恢复应忽略。
    std::vector<xime::backup::ZipEntry> evil = {
        {"../evil.txt", "x"}, {"sub/evil.txt", "y"},
        {"word_freq.txt", "# ok\n"}};
    const std::string evilZip = base + "/evil.zip";
    Check(xime::backup::WriteZip(evilZip, evil), "evil zip write");
    xime::backup::ImportBackup(dst, evilZip);
    Check(!fs::exists(base + "/evil.txt"), "traversal rejected");
    Check(!fs::exists(dst + "/sub/evil.txt"), "subdir rejected");
    Check(ReadText(dst + "/word_freq.txt") == "# ok\n", "whitelist restored from evil zip");

    // 合并恢复：本地已有词频，导入应保留本地独有词、同键取较大频数。
    const std::string localFreq =
        "# header\n甲\tab\t3\n乙\tcd\t10\n";
    WriteText(dst + "/word_freq.txt", localFreq);
    const std::string incomingFreq =
        "# header\n甲\tab\t5\n丙\tef\t7\n";
    std::vector<xime::backup::ZipEntry> mergeEntries = {
        {"word_freq.txt", incomingFreq}};
    const std::string mergeZip = base + "/merge.zip";
    Check(xime::backup::WriteZip(mergeZip, mergeEntries), "merge zip write");
    Check(xime::backup::ImportBackup(dst, mergeZip, true), "merge import ok");
    const std::string merged = ReadText(dst + "/word_freq.txt");
    Check(merged.find("甲\tab\t5") != std::string::npos, "same key takes max");
    Check(merged.find("乙\tcd\t10") != std::string::npos, "local-only kept");
    Check(merged.find("丙\tef\t7") != std::string::npos, "incoming-only kept");

    // 直接测 MergeFreqTable 的确定性。
    const std::string m2 = xime::backup::MergeFreqTable(localFreq, incomingFreq);
    Check(m2.find("乙\tcd\t10") != std::string::npos, "merge fn local kept");

    fs::remove_all(base, ec);
    if (fails == 0) {
        std::printf("BACKUP_TEST_OK\n");
        return 0;
    }
    std::printf("BACKUP_TEST_FAILS=%d\n", fails);
    return 1;
}
