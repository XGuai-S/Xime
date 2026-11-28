#include "PluginManager.h"

#include "PluginHost.h"

#include <algorithm>
#include <filesystem>

namespace fs = std::filesystem;

namespace xime {
namespace plugins {

PluginManager::~PluginManager() = default;

size_t PluginManager::LoadDirectory(const std::string& dir, std::string* err) {
    hosts_.clear();
    std::error_code ec;
    if (!fs::is_directory(dir, ec)) {
        if (err) *err = "not a directory: " + dir;
        return 0;
    }
    std::vector<fs::path> mains;
    for (const auto& entry : fs::directory_iterator(dir, ec)) {
        if (!entry.is_directory()) continue;
        const fs::path mainJs = entry.path() / "main.js";
        if (fs::is_regular_file(mainJs, ec)) {
            mains.push_back(mainJs);
        }
    }
    std::sort(mains.begin(), mains.end());
    size_t loaded = 0;
    for (const auto& mainJs : mains) {
        auto host = std::make_unique<PluginHost>();
        std::string perr;
        if (host->LoadFromFile(mainJs.string(), &perr)) {
            hosts_.push_back(std::move(host));
            ++loaded;
        } else if (err) {
            *err += "[" + mainJs.string() + "] " + perr + "; ";
        }
    }
    return loaded;
}

std::vector<std::string> PluginManager::TransformCandidates(
    const std::vector<std::string>& input) {
    std::vector<std::string> cur = input;
    for (auto& host : hosts_) {
        cur = host->TransformCandidates(cur);
    }
    return cur;
}

TransformResponse PluginManager::Transform(const TransformRequest& request) {
    TransformResponse result;
    result.changed = false;
    TransformRequest cur = request;
    for (auto& host : hosts_) {
        TransformResponse one = host->Transform(cur);
        if (one.changed) {
            result.changed = true;
            cur.candidates = one.candidates;
        }
    }
    if (result.changed) {
        result.candidates = std::move(cur.candidates);
    }
    return result;
}

std::vector<std::string> PluginManager::Names() const {
    std::vector<std::string> out;
    out.reserve(hosts_.size());
    for (const auto& h : hosts_) {
        out.push_back(h->Name());
    }
    return out;
}

} // namespace plugins
} // namespace xime
