// 候选选取解析（纯逻辑）：给定「显示位」→ 选引擎词 / 直接上屏文本 / 无动作。
// 词频重排、自动造词、插件新增候选都会改变显示顺序，数字键与鼠标点击必须
// 走同一套映射，避免两种交互路径行为不一致。
#pragma once

#include <string>
#include <vector>

namespace xime {
namespace core {

struct Candidate;

// 选词动作：
//   kNone       无动作（越界/无候选）
//   kEngine     按 engineIndex 选引擎候选（数字键路径可调引擎页内选词兜底）
//   kCommitText 非引擎候选（自动造词/插件新增），直接上屏 text
struct PickAction {
    enum class Type { kNone, kEngine, kCommitText };

    Type type = Type::kNone;
    int engineIndex = -1;   // kEngine 时有效，>=0
    std::string text;       // kCommitText 时有效

    bool IsNone() const { return type == Type::kNone; }
};

class CandidatePicker {
public:
    // displayIndex 为候选栏中的显示下标（0 起，数字 1..9/0 或鼠标点击行）。
    static PickAction Resolve(const std::vector<Candidate>& candidates,
                              int displayIndex);

    // 数字键 VK('1'..'9','0') → 显示下标；非数字返回 -1。
    static int DisplayIndexFromDigit(unsigned int vk);
};

} // namespace core
} // namespace xime
