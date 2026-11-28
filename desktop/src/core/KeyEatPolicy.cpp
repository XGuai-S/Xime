#include "KeyEatPolicy.h"

#include "RimeEngine.h"

namespace xime {
namespace core {

bool KeyEatPolicy::ShouldEat(const ProcessResult& result) {
    if (result.processed) {
        return true;
    }
    if (!result.committedText.empty()) {
        return true; // 自动上屏
    }
    if (!result.candidates.empty() || !result.preeditText.empty()) {
        return true; // 有候选栏/组合态，需由输入法接管
    }
    return false; // 引擎未处理：放行给应用
}

bool KeyEatPolicy::PassThroughInAscii(bool asciiMode, bool isRimeKey,
                                      bool isBackspace, bool isReturn,
                                      bool isEscape) {
    if (!asciiMode || !isRimeKey) {
        return false;
    }
    // 退格/回车/ESC 仍交引擎处理残留组合；其余字符/符号直通应用。
    return !(isBackspace || isReturn || isEscape);
}

} // namespace core
} // namespace xime
