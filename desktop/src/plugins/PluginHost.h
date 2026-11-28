// 桌面版插件宿主（QuickJS）：加载插件 main.js，调用 transform.candidates。
// 对齐安卓 JsPlugin 的核心契约：main.js 形如 `var plugin = (function(){...})();`，
// 执行后 globalThis.plugin 即插件对象；transform.candidates 同步变换候选。
#pragma once

#include <string>
#include <vector>

struct JSRuntime;
struct JSContext;

namespace xime {
namespace plugins {

// 与安卓 XimeTransformRequest/Response 对齐的候选项。
struct TransformCandidate {
    std::string text;
    std::string comment;
    int engineIndex = -1; // >=0 指回引擎原下标；<0 表示新增文本候选
};

struct TransformRequest {
    std::string inputText;
    std::string preedit;
    bool asciiMode = false;
    std::vector<TransformCandidate> candidates;
};

struct TransformResponse {
    bool changed = false; // 插件是否返回了非 null 结果
    std::vector<TransformCandidate> candidates;
};

class PluginHost {
public:
    PluginHost() = default;
    ~PluginHost();

    PluginHost(const PluginHost&) = delete;
    PluginHost& operator=(const PluginHost&) = delete;

    // 从 JS 源码加载（会自动提供 definePlugin 与最小 host）。失败返回 false 并写 err。
    bool LoadFromSource(const std::string& jsSource, std::string* err);
    bool LoadFromFile(const std::string& path, std::string* err);

    // 插件 manifest.name（无则空）。
    std::string Name() const;

    // 【兼容接口】同步变换候选文本（无 transform 或出错时返回原列表）。
    std::vector<std::string> TransformCandidates(
        const std::vector<std::string>& input);

    // 【完整契约】对齐安卓 XimeTransformRequest/Response，支持 engineIndex
    // 指向引擎原候选、或 text/comment 新增候选。返回 changed=false 表示未干预。
    TransformResponse Transform(const TransformRequest& request);

    bool transformAvailable() const { return transformAvailable_; }

private:
    void EnsureReady();

    JSRuntime* rt_ = nullptr;
    JSContext* ctx_ = nullptr;
    bool loaded_ = false;
    bool transformAvailable_ = false;
    std::string name_;
};

} // namespace plugins
} // namespace xime
