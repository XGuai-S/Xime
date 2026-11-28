#include "BackupService.h"

#include "BackupArchive.h"

#include <fstream>
#include <map>
#include <set>
#include <sstream>
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

std::string MergeFreqTable(const std::string& local,
                           const std::string& incoming) {
    // 解析 key=word\tcode -> count；同键取较大值。
    struct Entry {
        std::string word;
        std::string code;
        int count = 0;
    };
    auto parse = [](const std::string& text, std::map<std::string, Entry>& out) {
        std::istringstream in(text);
        std::string line;
        while (std::getline(in, line)) {
            if (line.empty() || line[0] == '#') {
                continue;
            }
            // 兼容 dict.yaml 头。
            if (line[0] == '-' || line.compare(0, 3, "...") == 0 ||
                line.find(':') != std::string::npos) {
                continue;
            }
            std::vector<std::string> parts;
            size_t a = 0;
            for (size_t i = 0; i <= line.size(); ++i) {
                if (i == line.size() || line[i] == '\t') {
                    parts.push_back(line.substr(a, i - a));
                    a = i + 1;
                }
            }
            if (parts.size() < 3) {
                continue;
            }
            int count = 0;
            try {
                count = std::stoi(parts[2]);
            } catch (...) {
                continue;
            }
            if (count <= 0) {
                continue;
            }
            std::string key = parts[0] + "\t" + parts[1];
            auto& e = out[key];
            e.word = parts[0];
            e.code = parts[1];
            if (count > e.count) {
                e.count = count;
            }
        }
    };
    std::map<std::string, Entry> merged;
    parse(local, merged);
    parse(incoming, merged);

    std::ostringstream out;
    out << "# 上屏词频：词\t编码\t频数（合并恢复后取较大值）\n";
    for (const auto& kv : merged) {
        const Entry& e = kv.second;
        out << e.word << '\t' << e.code << '\t' << e.count << '\n';
    }
    return out.str();
}

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

bool ImportBackup(const std::string& userDir, const std::string& zipPath,
                  bool mergeTabular) {
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
        std::string content = e.content;
        const std::string path = userDir + "/" + e.name;
        if (mergeTabular &&
            (e.name == "word_freq.txt" || e.name == "auto_word.txt")) {
            std::string local;
            if (ReadFile(path, local) && !local.empty()) {
                content = MergeFreqTable(local, e.content);
            }
        }
        if (WriteFile(path, content)) {
            any = true;
        }
    }
    return any;
}

} // namespace backup
} // namespace xime
