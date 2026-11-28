// Xime 桌面版核心引擎：平台无关的 librime 封装，移植自 Android JNI Rime 类。
#pragma once

#include <rime_api.h>

#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace xime {
namespace core {

struct Candidate {
    std::string text;
    std::string comment;
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

    bool SetInput(const std::string& input);
    void ClearComposition();

    std::string CurrentSchema() const;
    bool SelectSchema(const std::string& schemaId);
    bool IsAsciiMode() const;

private:
    RimeEngine() = default;

    void ReadCurrentState(ProcessResult& result) const;
    void ReapplyPageSizeIfNeeded();

    RimeApi* rime_ = nullptr;
    RimeSessionId session_ = 0;
    bool initialized_ = false;
    std::string userDataDir_;
    std::string sharedDataDir_;
};

} // namespace core
} // namespace xime
