#include "AiHttp.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <winhttp.h>

#include <cstdlib>
#include <cstring>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "winhttp.lib")

namespace xime {
namespace ai {

namespace {

bool ParseUrl(const std::string& url, std::string& host, int& port,
              std::string& path, std::string& error) {
    static const std::string kPrefix = "http://";
    if (url.compare(0, kPrefix.size(), kPrefix) != 0) {
        error = "仅支持 http:// (https 需 WinHTTP)";
        return false;
    }
    std::string rest = url.substr(kPrefix.size());
    const size_t slash = rest.find('/');
    std::string hostPort = slash == std::string::npos ? rest : rest.substr(0, slash);
    path = slash == std::string::npos ? "/" : rest.substr(slash);
    port = 80;
    const size_t colon = hostPort.find(':');
    if (colon != std::string::npos) {
        host = hostPort.substr(0, colon);
        port = std::atoi(hostPort.substr(colon + 1).c_str());
        if (port <= 0) {
            port = 80;
        }
    } else {
        host = hostPort;
    }
    return !host.empty();
}

struct WinsockGuard {
    WinsockGuard() {
        WSADATA wsa;
        WSAStartup(MAKEWORD(2, 2), &wsa);
    }
    ~WinsockGuard() { WSACleanup(); }
};

} // namespace

HttpPostResult HttpPost(const std::string& url,
                        const std::map<std::string, std::string>& headers,
                        const std::string& body, int timeoutMs) {
    if (url.rfind("https://", 0) == 0) {
        return HttpPostWinHttp(url, headers, body, timeoutMs);
    }
    HttpPostResult result;
    std::string host;
    int port = 80;
    std::string path;
    if (!ParseUrl(url, host, port, path, result.error)) {
        return result;
    }

    WinsockGuard guard;

    addrinfo hints;
    std::memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* info = nullptr;
    const std::string portStr = std::to_string(port);
    if (getaddrinfo(host.c_str(), portStr.c_str(), &hints, &info) != 0 || !info) {
        result.error = "DNS/地址解析失败";
        return result;
    }
    SOCKET sock = socket(info->ai_family, info->ai_socktype, info->ai_protocol);
    if (sock == INVALID_SOCKET) {
        freeaddrinfo(info);
        result.error = "socket 创建失败";
        return result;
    }
    DWORD tv = static_cast<DWORD>(timeoutMs);
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&tv),
               sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&tv),
               sizeof(tv));

    if (connect(sock, info->ai_addr, static_cast<int>(info->ai_addrlen)) ==
        SOCKET_ERROR) {
        freeaddrinfo(info);
        closesocket(sock);
        result.error = "连接失败";
        return result;
    }
    freeaddrinfo(info);

    // 构造请求。
    std::string req = "POST " + path + " HTTP/1.1\r\n";
    bool hasHost = false;
    for (const auto& kv : headers) {
        if (kv.first == "Host") {
            hasHost = true;
        }
        req += kv.first + ": " + kv.second + "\r\n";
    }
    if (!hasHost) {
        req += "Host: " + host + "\r\n";
    }
    req += "Content-Length: " + std::to_string(body.size()) + "\r\n";
    req += "Connection: close\r\n\r\n";
    req += body;

    size_t sent = 0;
    while (sent < req.size()) {
        const int n = send(sock, req.data() + sent,
                           static_cast<int>(req.size() - sent), 0);
        if (n <= 0) {
            closesocket(sock);
            result.error = "发送失败";
            return result;
        }
        sent += static_cast<size_t>(n);
    }

    std::string raw;
    char buf[4096];
    for (;;) {
        const int n = recv(sock, buf, sizeof(buf), 0);
        if (n <= 0) {
            break;
        }
        raw.append(buf, static_cast<size_t>(n));
    }
    closesocket(sock);

    // 解析状态行与正文。
    const size_t headerEnd = raw.find("\r\n\r\n");
    if (headerEnd == std::string::npos) {
        result.error = "响应格式错误";
        return result;
    }
    const std::string header = raw.substr(0, headerEnd);
    const size_t sp1 = header.find(' ');
    if (sp1 != std::string::npos) {
        result.status = std::atoi(header.c_str() + sp1 + 1);
    }
    result.body = raw.substr(headerEnd + 4);

    // 去掉可能的 chunked（简单场景不处理，回环夹具用 Content-Length 或 close）。
    result.ok = result.status >= 200 && result.status < 300;
    return result;
}

namespace {

std::wstring AiUtf8ToWide(const std::string& s) {
    if (s.empty()) {
        return L"";
    }
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(),
                                      static_cast<int>(s.size()), nullptr, 0);
    std::wstring out(static_cast<size_t>(n > 0 ? n : 0), L'\0');
    if (n > 0) {
        MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()),
                            &out[0], n);
    }
    return out;
}

bool ParseUrlSecure(const std::string& url, std::wstring& host, int& port,
                    bool& secure, std::wstring& path, std::string& error) {
    secure = false;
    std::string rest;
    if (url.rfind("https://", 0) == 0) {
        secure = true;
        rest = url.substr(8);
        port = 443;
    } else if (url.rfind("http://", 0) == 0) {
        rest = url.substr(7);
        port = 80;
    } else {
        error = "不支持的协议";
        return false;
    }
    const size_t slash = rest.find('/');
    const std::string hostPort =
        slash == std::string::npos ? rest : rest.substr(0, slash);
    const std::string pathStr = slash == std::string::npos ? "/" : rest.substr(slash);
    std::string hostStr = hostPort;
    const size_t colon = hostPort.find(':');
    if (colon != std::string::npos) {
        hostStr = hostPort.substr(0, colon);
        port = std::atoi(hostPort.substr(colon + 1).c_str());
        if (port <= 0) {
            port = secure ? 443 : 80;
        }
    }
    if (hostStr.empty()) {
        error = "空主机";
        return false;
    }
    host = AiUtf8ToWide(hostStr);
    path = AiUtf8ToWide(pathStr);
    return true;
}

} // namespace

HttpPostResult HttpPostWinHttp(const std::string& url,
                               const std::map<std::string, std::string>& headers,
                               const std::string& body, int timeoutMs) {
    HttpPostResult result;
    std::wstring host;
    std::wstring path;
    int port = 0;
    bool secure = false;
    if (!ParseUrlSecure(url, host, port, secure, path, result.error)) {
        return result;
    }

    HINTERNET session = WinHttpOpen(
        L"XimeAI/1.0", WINHTTP_ACCESS_TYPE_NO_PROXY, WINHTTP_NO_PROXY_NAME,
        WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) {
        result.error = "WinHttpOpen 失败";
        return result;
    }
    WinHttpSetTimeouts(session, timeoutMs, timeoutMs, timeoutMs, timeoutMs);

    HINTERNET conn = WinHttpConnect(session, host.c_str(),
                                    static_cast<INTERNET_PORT>(port), 0);
    if (!conn) {
        result.error = "WinHttpConnect 失败";
        WinHttpCloseHandle(session);
        return result;
    }
    const DWORD flags = secure ? WINHTTP_FLAG_SECURE : 0;
    HINTERNET req = WinHttpOpenRequest(conn, L"POST", path.c_str(), nullptr,
                                       WINHTTP_NO_REFERER,
                                       WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
    if (!req) {
        result.error = "WinHttpOpenRequest 失败";
        WinHttpCloseHandle(conn);
        WinHttpCloseHandle(session);
        return result;
    }

    std::wstring hdr;
    for (const auto& kv : headers) {
        hdr += AiUtf8ToWide(kv.first + ": " + kv.second + "\r\n");
    }

    BOOL ok = WinHttpSendRequest(
        req, hdr.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : hdr.c_str(),
        static_cast<DWORD>(hdr.empty() ? 0 : hdr.size()),
        body.empty() ? WINHTTP_NO_REQUEST_DATA : const_cast<char*>(body.data()),
        static_cast<DWORD>(body.size()), static_cast<DWORD>(body.size()), 0);
    if (!ok || !WinHttpReceiveResponse(req, nullptr)) {
        result.error = "发送/接收失败";
        WinHttpCloseHandle(req);
        WinHttpCloseHandle(conn);
        WinHttpCloseHandle(session);
        return result;
    }

    DWORD statusCode = 0;
    DWORD len = sizeof(statusCode);
    WinHttpQueryHeaders(req,
                        WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &statusCode, &len,
                        WINHTTP_NO_HEADER_INDEX);
    result.status = static_cast<int>(statusCode);

    DWORD avail = 0;
    do {
        if (!WinHttpQueryDataAvailable(req, &avail) || avail == 0) {
            break;
        }
        std::string chunk(avail, '\0');
        DWORD read = 0;
        if (!WinHttpReadData(req, &chunk[0], avail, &read) || read == 0) {
            break;
        }
        result.body.append(chunk.data(), read);
    } while (avail > 0);

    WinHttpCloseHandle(req);
    WinHttpCloseHandle(conn);
    WinHttpCloseHandle(session);
    result.ok = result.status >= 200 && result.status < 300;
    return result;
}

} // namespace ai
} // namespace xime