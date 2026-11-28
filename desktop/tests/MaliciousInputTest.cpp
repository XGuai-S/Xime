// 恶意输入健壮性验收：不可信数据（AI 响应 / 备份包）不得让宿主崩溃。
//
// 背景（审计发现的两个 P0）：
//   1) JSON 解析器无递归深度上限，"[[[[…" 数万层即栈溢出；
//   2) ZIP 中央目录偏移用 uint32 相加回绕，绕过边界检查后按约 4GB 偏移读内存。
// 两者都发生在 TSF 宿主进程（Word/浏览器）内，崩的是用户的应用。
#include "../src/ai/Json.h"
#include "../src/backup/BackupArchive.h"

#include <cstdio>
#include <filesystem>
#include <string>
#include <vector>

namespace fs = std::filesystem;

namespace {

int g_fail = 0;

void Check(bool cond, const char* what) {
    std::printf("%s %s\n", cond ? "FUZZ_OK  " : "FUZZ_FAIL", what);
    if (!cond) {
        ++g_fail;
    }
}

void PutU16(std::string& s, uint16_t v) {
    s.push_back(static_cast<char>(v & 0xFF));
    s.push_back(static_cast<char>((v >> 8) & 0xFF));
}
void PutU32(std::string& s, uint32_t v) {
    s.push_back(static_cast<char>(v & 0xFF));
    s.push_back(static_cast<char>((v >> 8) & 0xFF));
    s.push_back(static_cast<char>((v >> 16) & 0xFF));
    s.push_back(static_cast<char>((v >> 24) & 0xFF));
}

} // namespace

int main() {
    using namespace xime;
using xime::ai::JsonValue;
using xime::ai::JsonParse;

    // ── 1) 深嵌套 JSON 不得栈溢出 ──────────────────────────────────────────
    {
        // 10 万层 '[' —— 修复前必然栈溢出。
        std::string deep(100000, '[');
        JsonValue v = JsonParse(deep);
        // 只要没崩就算通过；解析结果应为失败。
        Check(v.type == JsonValue::Type::Null, "deep array rejected without crash");
    }
    {
        // 深嵌套对象同样。
        std::string deep;
        for (int i = 0; i < 50000; ++i) {
            deep += "{\"a\":";
        }
        JsonValue v = JsonParse(deep);
        Check(v.type == JsonValue::Type::Null, "deep object rejected without crash");
    }
    {
        // 正常浅层 JSON 仍要能解析（不能把功能一起堵死）。
        JsonValue v = JsonParse("{\"a\":[1,2,{\"b\":\"c\"}]}");
        Check(v.type == JsonValue::Type::Object, "normal json still parses");
    }

    // ── 2) 畸形 ZIP 不得越界读 ─────────────────────────────────────────────
    const fs::path dir = fs::temp_directory_path() / "xime_fuzz";
    fs::remove_all(dir);
    fs::create_directories(dir);

    // 构造：EOCD 里中央目录偏移写 0xFFFFFFF0（修复前会回绕通过检查）。
    {
        const fs::path bad = dir / "bad_offset.zip";
        std::string z;
        PutU32(z, 0x06054b50); // EOCD
        PutU16(z, 0);
        PutU16(z, 0);
        PutU16(z, 1);          // 1 entry
        PutU16(z, 1);
        PutU32(z, 0xFFFFFFF0); // central dir offset（恶意）
        PutU16(z, 0);
        std::FILE* f = nullptr;
        fopen_s(&f, bad.string().c_str(), "wb");
        if (f) {
            std::fwrite(z.data(), 1, z.size(), f);
            std::fclose(f);
            std::vector<backup::ZipEntry> out;
            const bool ok = backup::ReadZip(bad.string(), out);
            Check(!ok, "malicious central offset rejected");
        }
    }

    // 构造：条目名含 ../ （zip-slip），应被跳过但包整体仍可读。
    {
        const fs::path slip = dir / "slip.zip";
        std::vector<backup::ZipEntry> entries = {
            {"../escape.txt", "evil"},
            {"good.txt", "ok"},
        };
        Check(backup::WriteZip(slip.string(), entries), "slip zip written");
        std::vector<backup::ZipEntry> out;
        if (backup::ReadZip(slip.string(), out)) {
            bool hasEscape = false;
            bool hasGood = false;
            for (const auto& e : out) {
                if (e.name == "../escape.txt") hasEscape = true;
                if (e.name == "good.txt") hasGood = true;
            }
            Check(!hasEscape, "traversal entry skipped");
            Check(hasGood, "legit entry still readable");
        } else {
            Check(false, "slip zip should still be readable");
        }
    }

    // 构造：CRC 被篡改的条目应被跳过。
    {
        const fs::path crc = dir / "badcrc.zip";
        std::vector<backup::ZipEntry> entries = {{"a.txt", "hello"}};
        backup::WriteZip(crc.string(), entries);
        // 直接改内容字节（store 方式，内容在 local header 之后）。
        std::string raw;
        {
            std::FILE* f = nullptr;
            fopen_s(&f, crc.string().c_str(), "rb");
            if (f) {
                char buf[4096];
                size_t n = 0;
                while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) {
                    raw.append(buf, n);
                }
                std::fclose(f);
            }
        }
        const size_t pos = raw.find("hello");
        if (pos != std::string::npos) {
            raw[pos] = 'X';
            std::FILE* f = nullptr;
            fopen_s(&f, crc.string().c_str(), "wb");
            if (f) {
                std::fwrite(raw.data(), 1, raw.size(), f);
                std::fclose(f);
            }
            std::vector<backup::ZipEntry> out;
            const bool ok = backup::ReadZip(crc.string(), out);
            Check(!ok || out.empty(), "crc-mismatch entry skipped");
        } else {
            Check(false, "could not locate content for crc test");
        }
    }

    fs::remove_all(dir);

    if (g_fail != 0) {
        std::printf("FUZZ_FAILS=%d\n", g_fail);
        return 1;
    }
    std::printf("FUZZ_OK_ALL\n");
    return 0;
}
