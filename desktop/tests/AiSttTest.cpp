// STT multipart 上传测试：构造 multipart 体 + 本机回环真实发送 + 解析响应 text。
#include "../src/ai/AiHttp.h"
#include "../src/ai/AiProtocol.h"

#include <winsock2.h>
#include <ws2tcpip.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>

#pragma comment(lib, "ws2_32.lib")

using namespace xime::ai;

namespace {
int g_fails = 0;
void Check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++g_fails;
    }
}
} // namespace

int main() {
    // 1. 纯逻辑：multipart 体结构 + 响应解析
    const std::string boundary = "XIMEBOUNDARY123";
    const std::string audio = std::string("RIFF\x01\x02\x00\xFF", 7); // 含二进制字节
    std::string ct;
    const std::string body =
        BuildSttMultipartBody(boundary, "whisper-1", "a.wav", audio, ct);
    Check(ct == "multipart/form-data; boundary=XIMEBOUNDARY123", "content-type");
    Check(body.find("--XIMEBOUNDARY123\r\n") != std::string::npos, "first boundary");
    Check(body.find("name=\"model\"") != std::string::npos, "model part");
    Check(body.find("whisper-1") != std::string::npos, "model value");
    Check(body.find("name=\"file\"; filename=\"a.wav\"") != std::string::npos, "file part");
    Check(body.find(audio) != std::string::npos, "audio bytes embedded");
    Check(body.find("--XIMEBOUNDARY123--\r\n") != std::string::npos, "closing boundary");
    Check(ExtractSttText("{\"text\":\"你好世界\"}") == "你好世界", "extract stt text");
    Check(ExtractSttText("bad") == "", "extract bad");

    // 2. 回环：真实发送 multipart，服务器校验并返回 STT JSON
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    SOCKET listener = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    addr.sin_port = 0;
    if (bind(listener, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        std::printf("FAIL: bind\n");
        return 1;
    }
    listen(listener, 1);
    int addrLen = sizeof(addr);
    getsockname(listener, reinterpret_cast<sockaddr*>(&addr), &addrLen);
    const int port = ntohs(addr.sin_port);

    std::string capturedBody;
    std::string capturedType;
    std::atomic<bool> serverDone{false};
    std::thread server([&]() {
        SOCKET client = accept(listener, nullptr, nullptr);
        if (client == INVALID_SOCKET) { serverDone = true; return; }
        std::string raw;
        char buf[4096];
        size_t headerEnd = std::string::npos;
        while (headerEnd == std::string::npos) {
            const int n = recv(client, buf, sizeof(buf), 0);
            if (n <= 0) break;
            raw.append(buf, static_cast<size_t>(n));
            headerEnd = raw.find("\r\n\r\n");
        }
        size_t contentLength = 0;
        if (headerEnd != std::string::npos) {
            const std::string header = raw.substr(0, headerEnd);
            // 提取 Content-Length（大小写不敏感，简单定位）
            std::string lower = header;
            for (auto& c : lower) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
            const size_t p = lower.find("content-length:");
            if (p != std::string::npos) {
                contentLength = static_cast<size_t>(std::atoi(header.c_str() + p + 15));
            }
            const size_t cp = lower.find("content-type:");
            if (cp != std::string::npos) {
                const size_t eol = header.find("\r\n", cp);
                capturedType = header.substr(cp + 13, eol - cp - 13);
            }
        }
        while (headerEnd != std::string::npos && raw.size() < headerEnd + 4 + contentLength) {
            const int n = recv(client, buf, sizeof(buf), 0);
            if (n <= 0) break;
            raw.append(buf, static_cast<size_t>(n));
        }
        if (headerEnd != std::string::npos) {
            capturedBody = raw.substr(headerEnd + 4, contentLength);
        }
        const std::string respBody = "{\"text\":\"你好世界\"}";
        std::string resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n";
        resp += "Content-Length: " + std::to_string(respBody.size()) + "\r\n";
        resp += "Connection: close\r\n\r\n" + respBody;
        send(client, resp.data(), static_cast<int>(resp.size()), 0);
        closesocket(client);
        serverDone = true;
    });

    const std::string url = "http://127.0.0.1:" + std::to_string(port) + "/v1/audio/transcriptions";
    std::map<std::string, std::string> headers;
    headers["Content-Type"] = ct;
    headers["Authorization"] = "Bearer " + std::string("TESTKEY");
    const HttpPostResult r = HttpPost(url, headers, body, 5000);

    server.join();
    closesocket(listener);
    WSACleanup();

    Check(r.ok, "http ok");
    Check(r.status == 200, "status 200");
    Check(ExtractSttText(r.body) == "你好世界", "stt text parsed from loopback");
    Check(capturedBody == body, "server received exact multipart body");
    Check(capturedType.find("multipart/form-data") != std::string::npos, "server saw multipart ct");
    Check(capturedBody.find(audio) != std::string::npos, "server received audio bytes");

    if (g_fails == 0) {
        std::printf("AI_STT_OK\n");
        return 0;
    }
    std::printf("AI_STT_FAILS=%d\n", g_fails);
    return 1;
}
