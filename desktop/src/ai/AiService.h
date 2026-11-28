// AI 服务门面：把「配置/密钥 → 是否润色 → LLM 请求 → 直出或润色」串成一条链路。
// 传输以函数注入，便于离线用回环/桩验证，不绑定具体网络。
#pragma once

#include <functional>
#include <map>
#include <string>

#include "AiProviderConfig.h"

namespace xime {
namespace ai {

struct AiHttpRequest {
    std::string method = "POST";
    std::string url;
    std::string body;
    std::map<std::string, std::string> headers;
};

struct AiHttpResponse {
    int status = 0;
    std::string body;
};

// 返回 true 表示网络层成功拿到响应（status 可为非 2xx）。
using HttpPostFn =
    std::function<bool(const AiHttpRequest& request, AiHttpResponse& response)>;

struct DictationResult {
    bool ok = true;
    bool polished = false;
    std::string text;
    std::string error;
};

// 对一个「已完成 STT」的原始识别文本做最终定型：
//   润色关闭 → 直接返回原始文本（不发起任何 LLM 请求）；
//   润色开启 → 发起 chat 请求润色，成功且非空则返回润色结果，否则回退原始文本。
DictationResult FinalizeDictation(const AppSettings& settings,
                                  const ProviderVault& vault,
                                  const std::string& rawStt,
                                  const HttpPostFn& http);

} // namespace ai
} // namespace xime
