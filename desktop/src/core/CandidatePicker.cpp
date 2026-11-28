#include "CandidatePicker.h"

#include "RimeEngine.h"

namespace xime {
namespace core {

PickAction CandidatePicker::Resolve(const std::vector<Candidate>& candidates,
                                    int displayIndex) {
    PickAction action;
    if (displayIndex < 0 ||
        displayIndex >= static_cast<int>(candidates.size())) {
        return action;
    }
    const Candidate& c = candidates[displayIndex];
    if (c.engineIndex >= 0) {
        action.type = PickAction::Type::kEngine;
        action.engineIndex = c.engineIndex;
    } else {
        // 非引擎候选（自动造词/插件新增）：直接上屏其文本。
        action.type = PickAction::Type::kCommitText;
        action.text = c.text;
    }
    return action;
}

int CandidatePicker::DisplayIndexFromDigit(unsigned int vk) {
    if (vk >= '1' && vk <= '9') {
        return static_cast<int>(vk - '1');
    }
    if (vk == '0') {
        return 9;
    }
    return -1;
}

} // namespace core
} // namespace xime
