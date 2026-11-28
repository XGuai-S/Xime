#include "SchemaListConfig.h"

#include <cstdio>
#include <fstream>
#include <sstream>

namespace xime {
namespace core {

namespace {

std::string Trim(const std::string& s) {
    size_t a = 0;
    size_t b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\t' || s[a] == '\r' || s[a] == '\n')) {
        ++a;
    }
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\r' ||
                     s[b - 1] == '\n')) {
        --b;
    }
    return s.substr(a, b - a);
}

// 去掉行尾注释（# 之后），保留值本体。
std::string StripComment(const std::string& line) {
    const size_t p = line.find('#');
    return p == std::string::npos ? line : line.substr(0, p);
}

} // namespace

std::string SchemaListConfig::FilePath(const std::string& userDir) {
    return userDir + "/default.custom.yaml";
}

std::vector<std::string> SchemaListConfig::Read(const std::string& userDir) {
    std::vector<std::string> out;
    if (userDir.empty()) {
        return out;
    }
    std::ifstream in(FilePath(userDir));
    if (!in) {
        return out;
    }
    bool inList = false;
    std::string line;
    while (std::getline(in, line)) {
        const std::string body = StripComment(line);
        const std::string trimmed = Trim(body);
        if (trimmed.empty()) {
            continue;
        }
        if (trimmed == "schema_list:") {
            inList = true;
            continue;
        }
        if (inList) {
            // 列表项形如 "- schema: pinyin_simp" 或 "- pinyin_simp"
            if (trimmed[0] != '-') {
                // 遇到非列表项（缩进更浅的键）即结束 schema_list 段
                inList = false;
                continue;
            }
            std::string item = Trim(trimmed.substr(1));
            const std::string key = "schema:";
            if (item.rfind(key, 0) == 0) {
                item = Trim(item.substr(key.size()));
            }
            // 去掉可能的两端引号
            if (item.size() >= 2 &&
                ((item.front() == '"' && item.back() == '"') ||
                 (item.front() == '\'' && item.back() == '\''))) {
                item = item.substr(1, item.size() - 2);
            }
            if (!item.empty()) {
                out.push_back(item);
            }
        }
    }
    return out;
}

bool SchemaListConfig::Write(const std::string& userDir,
                             const std::vector<std::string>& schemaIds) {
    if (userDir.empty()) {
        return false;
    }
    std::ostringstream sb;
    sb << "# Rime 自定义配置（由 Xime 桌面版生成）\n";
    sb << "patch:\n";
    sb << "  schema_list:\n";
    for (const auto& id : schemaIds) {
        if (id.empty()) {
            continue;
        }
        sb << "    - schema: " << id << "\n";
    }
    const std::string text = sb.str();
    const std::string path = FilePath(userDir);
    const std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out) {
            return false;
        }
        out << text;
        out.flush();
        if (!out) {
            return false;
        }
    }
    std::remove(path.c_str());
    return std::rename(tmp.c_str(), path.c_str()) == 0;
}

} // namespace core
} // namespace xime
