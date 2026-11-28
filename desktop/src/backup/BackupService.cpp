#include "BackupService.h"

#include "BackupArchive.h"

#include <fstream>
#include <set>
#include <string>
#include <vector>

namespace xime {
namespace backup {

namespace {

// 允许备份/恢复的用户文件名（与 xime.yaml 同级）。
const std::set<std::string> kWhitelist = {
    "word_freq.txt",
    "auto_word.txt",
    "xime.yaml",
    "default.custom.yaml",
    "clipboard.json",
    "settings.json",  // 桌面版统一设置（润色开关/LLM·STT/方案列表/每页候选数）
};

bool ReadFile(const std::string& path, std::string& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return false;
    }
    out.assign((std::istreambuf_iterator<char>(in)),
               std::istreambuf_iterator<char>());
    return true;
}

bool WriteFile(const std::string& path, const std::string& content) {
    const std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out) {
            return false;
        }
        out.write(content.data(), static_cast<std::streamsize>(content.size()));
    }
    std::remove(path.c_str());
    return std::rename(tmp.c_str(), path.c_str()) == 0;
}

} // namespace

bool ExportBackup(const std::string& userDir, const std::string& zipPath) {
    std::vector<ZipEntry> entries;
    for (const auto& name : kWhitelist) {
        std::string content;
        if (ReadFile(userDir + "/" + name, content)) {
            entries.push_back(ZipEntry{name, content});
        }
    }
    return WriteZip(zipPath, entries);
}

bool ImportBackup(const std::string& userDir, const std::string& zipPath) {
    std::vector<ZipEntry> entries;
    if (!ReadZip(zipPath, entries)) {
        return false;
    }
    bool any = false;
    for (const auto& e : entries) {
        // 只恢复白名单内的纯文件名，拒绝任何路径分隔或穿越。
        if (kWhitelist.find(e.name) == kWhitelist.end()) {
            continue;
        }
        if (e.name.find('/') != std::string::npos ||
            e.name.find('\\') != std::string::npos ||
            e.name.find("..") != std::string::npos) {
            continue;
        }
        if (WriteFile(userDir + "/" + e.name, e.content)) {
            any = true;
        }
    }
    return any;
}

} // namespace backup
} // namespace xime
