// 极简 HTTP POST 传输层（Winsock，明文 http）。https 后续接 WinHTTP。
#pragma once

#include <map>
#include <string>

namespace xime {
namespace ai {

struct HttpPostResult {
    bool ok = false;
    int status = 0;        // 0 = 未拿到响应
    std::string body;      // 响应正文
    std::string error;     // 失败原因
};

// 发送 POST 请求。url 形如 http://host[:port]/path。
// headers 自动补 Content-Length / Host / Connection: close。
HttpPostResult HttpPost(const std::string& url,
                        const std::map<std::string, std::string>& headers,
                        const std::string& body,
                        int timeoutMs = 30000);

} // namespace ai
} // namespace xime
