// AI 传输层回环测试：本地监听 127.0.0.1，真实收发一次 HTTP POST，
// 校验请求体、响应解析为 content。不访问外网。
#include "../src/ai/AiHttp.h"
#include "../src/ai/AiProtocol.h"

#include <winsock2.h>
#include <ws2tcpip.h>

#include <atomic>
#include <cstdio>
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
    std::atomic<bool> serverDone{false};

    std::thread server([&]() {
        SOCKET client = accept(listener, nullptr, nullptr);
        if (client == INVALID_SOCKET) {
            serverDone = true;
            return;
        }
        std::string raw;
        char buf[4096];
        // 先读到 header 结束
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
            const std::string key = "Content-Length:";
            const size_t p = header.find(key);
            if (p != std::string::npos) {
                contentLength = static_cast<size_t>(std::atoi(header.c_str() + p + key.size()));
            }
        }
        // 补齐 body
        while (headerEnd != std::string::npos &&
               raw.size() < headerEnd + 4 + contentLength) {
            const int n = recv(client, buf, sizeof(buf), 0);
            if (n <= 0) break;
            raw.append(buf, static_cast<size_t>(n));
        }
        if (headerEnd != std::string::npos) {
            capturedBody = raw.substr(headerEnd + 4, contentLength);
        }
        const std::string respBody =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"润色后的文本\"}}]}";
        std::string resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n";
        resp += "Content-Length: " + std::to_string(respBody.size()) + "\r\n";
        resp += "Connection: close\r\n\r\n" + respBody;
        send(client, resp.data(), static_cast<int>(resp.size()), 0);
        closesocket(client);
        serverDone = true;
    });

    ChatRequestOptions opts;
    opts.model = "glm-4";
    opts.systemPrompt = "润色";
    opts.userText = "这段话帮我改通顺";
    const std::string reqBody = BuildChatRequestBody(opts);

    const std::string url = "http://127.0.0.1:" + std::to_string(port) + "/v1/chat/completions";
    std::map<std::string, std::string> headers;
    headers["Content-Type"] = "application/json";
    const HttpPostResult r = HttpPost(url, headers, reqBody, 5000);

    server.join();
    closesocket(listener);
    WSACleanup();

    Check(r.ok, "http ok");
    Check(r.status == 200, "status 200");
    Check(ExtractChatContent(r.body) == "润色后的文本", "response content parsed");
    Check(capturedBody == reqBody, "server received exact request body");
    Check(capturedBody.find("\"model\":\"glm-4\"") != std::string::npos, "model in request");
    Check(capturedBody.find("这段话帮我改通顺") != std::string::npos, "user text (utf8) in request");

    if (g_fails == 0) {
        std::printf("AIHTTP_LOOPBACK_OK\n");
        return 0;
    }
    std::printf("AIHTTP_LOOPBACK_FAILS=%d\n", g_fails);
    return 1;
}
