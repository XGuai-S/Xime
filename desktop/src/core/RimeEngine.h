// Xime 桌面版核心引擎：平台无关的 librime 封装，移植自 Android JNI Rime 类。
#pragma once

#include <rime_api.h>

#include <functional>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace xime {
namespace plugins {
class PluginHost;
}
namespace core {

struct Candidate {
    std::string text;
    std::string comment;
    int engineIndex = -1; // 该候选在引擎原始候选列表中的下标（重排后仍指回引擎）
};

struct ProcessResult {
    bool processed = false;
    std::string committedText;
    std::string inputText;
    std::string preeditText;
    int caretPos = 0;         // raw input 内的光标位置（字符）
    int preeditCursorPos = 0; // preedit 中的光标位置（UTF-8 字节偏移）
    std::vector<Candidate> candidates;
    bool isAsciiMode = false;
    bool hasNextPage = false;
    bool hasPrevPage = false;
    // T9 快照（仅 T9 会话活跃时填充）
    std::string t9PanelState;
    std::string t9SyllableOptions;
};

class RimeEngine {
public:
    static RimeEngine& Instance();

    RimeEngine(const RimeEngine&) = delete;
    RimeEngine& operator=(const RimeEngine&) = delete;

    bool Initialize(const std::string& userDataDir,
                    const std::string& sharedDataDir);
    bool StartMaintenance(bool full);
    bool IsMaintaining() const;

    bool CreateSession();
    void DestroySession();
    bool HasSession() const;

    bool ProcessKey(int keycode, int mask);
    ProcessResult ProcessKeyAndGetResult(int keycode, int mask);
    ProcessResult ReadResult(bool processed);

    // 按引擎原始下标选词（用于候选被词频重排后，点选回到正确引擎词）。
    ProcessResult SelectCandidateByEngineIndex(int engineIndex);

    bool SetInput(const std::string& input);
    void ClearComposition();

    // 候选翻页：backward=false 下一页。返回 false 表示无此页/无会话。
    ProcessResult ChangePage(bool backward);

    std::string CurrentSchema() const;
    bool SelectSchema(const std::string& schemaId);
    bool IsAsciiMode() const;

    // 每页候选数（对齐安卓设置 5..50）。写入当前方案 menu/page_size 并重建 Schema 生效。
    bool SetPageSize(int pageSize);
    int PageSize() const { return pageSize_; }

    // ── 自动造词接入 ──
    // 为指定方案加载单字码表（用于合成组合词编码）；方案切换/建会话后自动调用。
    void BuildAutoIndex(const std::string& schemaId);
    // 注入自动造词词表（来自 AutoWordStore 临时库；测试可直接注入）。
    void SetAutoTempWords(const std::vector<std::string>& words);
    // 用当前方案单字码表给 word 合成可打出的编码（用于验证/记录）。
    std::vector<std::string> ComposeWordCodes(const std::string& word) const;

    // 注入插件候选变换回调（如插件宿主的 transform.candidates）。
    // 用回调解耦，避免引擎对 QuickJS/插件宿主的链接依赖。
    using PluginTransform =
        std::function<std::vector<std::string>(const std::vector<std::string>&)>;
    void SetPluginTransform(PluginTransform fn);

    // 完整契约回调：可新增候选（engineIndex<0）或保留引擎候选（engineIndex>=0）。
    // 引擎在回调后按 engineIndex 重建候选列表；回调可读写 candidates。
    struct PluginTransformContext {
        std::string inputText;
        std::string preedit;
        bool asciiMode = false;
        std::vector<Candidate> candidates;
    };
    using PluginTransformFull = std::function<void(PluginTransformContext&)>;
    void SetPluginTransformFull(PluginTransformFull fn);

private:
    RimeEngine() = default;

    void ReadCurrentState(ProcessResult& result) const;
    void ApplyPluginTransform(ProcessResult& result);
    void FlushT9IfActive();
    void ReapplyPageSizeIfNeeded();
    void ApplyPageSizeOverrideLocked(const std::string& schemaId);
    void BuildAutoIndexLocked(const std::string& schemaId);

    RimeApi* rime_ = nullptr;
    RimeSessionId session_ = 0;
    bool initialized_ = false;
    int pageSize_ = 0;
    std::string userDataDir_;
    std::string sharedDataDir_;
    // 当前方案的单字码表（自动造词合成编码用）与临时造词词表。
    std::map<std::string, std::vector<std::string>> charCodes_;
    std::vector<std::string> autoTempWords_;
    // 插件候选变换回调（可为空）。
    PluginTransform pluginTransform_;
    PluginTransformFull pluginTransformFull_;
};

} // namespace core
} // namespace xime
