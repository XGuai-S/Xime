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

// 发送 POST 请求。http:// 走 Winsock；https:// 走 WinHTTP（含代理/证书默认策略）。
// headers 自动补 Content-Length / Host / Connection: close（Winsock 路径）。
HttpPostResult HttpPost(const std::string& url,
                        const std::map<std::string, std::string>& headers,
                        const std::string& body,
                        int timeoutMs = 30000);

// 强制使用 WinHTTP（http/https 均可），主要用于测试与 https 场景。
HttpPostResult HttpPostWinHttp(const std::string& url,
                               const std::map<std::string, std::string>& headers,
                               const std::string& body,
                               int timeoutMs = 30000);

} // namespace ai
} // namespace xime
