// 按键“是否吞掉”策略（纯逻辑）：只有引擎真正处理、或产生了提交/组合/候选时才
// 拦截按键；否则放行给应用（如英文模式下的退格/标点，应用应照常处理）。
#pragma once

#include <string>

namespace xime {
namespace core {

struct ProcessResult;

class KeyEatPolicy {
public:
    // 依据引擎处理结果判断是否应拦截（吃掉）该按键。
    static bool ShouldEat(const ProcessResult& result);

    // 英文模式直通：ascii 模式下的字符/符号键不送引擎，直接放行。
    static bool PassThroughInAscii(bool asciiMode, bool isRimeKey,
                                   bool isBackspace, bool isReturn,
                                   bool isEscape);
};

} // namespace core
} // namespace xime
