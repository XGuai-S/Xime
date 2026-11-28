// 插件管理器：扫描插件目录（<dir>/<name>/main.js）加载全部插件，链式变换候选。
#pragma once

#include <memory>
#include <string>
#include <vector>

#include "PluginHost.h"

namespace xime {
namespace plugins {

class PluginManager {
public:
    PluginManager() = default;
    ~PluginManager();

    // 扫描 dir 下的子目录（每个含 main.js），加载全部插件。返回成功加载数量。
    size_t LoadDirectory(const std::string& dir, std::string* err);

    // 兼容接口：依次对每个插件执行简单文本变换。
    std::vector<std::string> TransformCandidates(
        const std::vector<std::string>& input);

    // 完整契约：链式执行每个插件的 transform.candidates（request/response 对象）。
    TransformResponse Transform(const TransformRequest& request);

    size_t Count() const { return hosts_.size(); }
    std::vector<std::string> Names() const;

private:
    std::vector<std::unique_ptr<PluginHost>> hosts_;
};

} // namespace plugins
} // namespace xime
