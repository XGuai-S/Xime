// 语音链路测试：WAV 编码 + AI STT 回环上传（合成音频，无需真实麦克风）。
#include "../src/voice/VoiceService.h"
#include "../src/voice/Wav.h"

#include <winsock2.h>
#include <ws2tcpip.h>

#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>

#pragma comment(lib, "ws2_32.lib")

namespace {
int fails = 0;
void Check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++fails;
    }
}

// 极简 HTTP 服务器：接一个请求，读全（含 Content-Length 正文），记录，回固定 JSON。
class OneShotServer {
public:
    bool Start() {
        WSADATA wsa;
        WSAStartup(MAKEWORD(2, 2), &wsa);
        listen_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (listen_ == INVALID_SOCKET) return false;
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        addr.sin_port = 0;
        if (bind(listen_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0)
            return false;
        int len = sizeof(addr);
        getsockname(listen_, reinterpret_cast<sockaddr*>(&addr), &len);
        port_ = ntohs(addr.sin_port);
        listen(listen_, 1);
        thread_ = std::thread([this] { Run(); });
        return true;
    }
    int port() const { return port_; }
    std::string request() const { return request_; }
    void Join() { if (thread_.joinable()) thread_.join(); }
    ~OneShotServer() {
        if (listen_ != INVALID_SOCKET) closesocket(listen_);
        WSACleanup();
    }

private:
    void Run() {
        sockaddr_in cli{};
        int cl = sizeof(cli);
        SOCKET c = accept(listen_, reinterpret_cast<sockaddr*>(&cli), &cl);
        if (c == INVALID_SOCKET) return;
        std::string buf;
        char tmp[4096];
        size_t headerEnd = std::string::npos;
        long contentLength = 0;
        while (true) {
            int n = recv(c, tmp, sizeof(tmp), 0);
            if (n <= 0) break;
            buf.append(tmp, n);
            if (headerEnd == std::string::npos) {
                headerEnd = buf.find("\r\n\r\n");
                if (headerEnd != std::string::npos) {
                    const std::string head = buf.substr(0, headerEnd);
                    const std::string key = "Content-Length:";
                    size_t p = head.find(key);
                    if (p != std::string::npos) {
                        contentLength = std::atol(head.c_str() + p + key.size());
                    }
                }
            }
            if (headerEnd != std::string::npos &&
                buf.size() >= headerEnd + 4 + static_cast<size_t>(contentLength)) {
                break;
            }
        }
        request_ = buf;
        const std::string body = "{\"text\":\"语音识别结果\"}";
        std::string resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n";
        resp += "Content-Length: " + std::to_string(body.size()) +
                "\r\nConnection: close\r\n\r\n" + body;
        send(c, resp.c_str(), static_cast<int>(resp.size()), 0);
        closesocket(c);
    }

    SOCKET listen_ = INVALID_SOCKET;
    int port_ = 0;
    std::string request_;
    std::thread thread_;
};
} // namespace

int main() {
    // 1. WAV 编码
    std::vector<int16_t> samples(1600); // 0.1s @16k
    for (size_t i = 0; i < samples.size(); ++i) {
        samples[i] = static_cast<int16_t>(3000 * std::sin(i * 0.05));
    }
    const std::string wav = xime::voice::EncodeWav16Mono(samples, 16000);
    Check(wav.size() == 44 + samples.size() * 2, "wav size");
    Check(wav.compare(0, 4, "RIFF") == 0 && wav.compare(8, 4, "WAVE") == 0, "wav magic");
    int sr = 0, db = 0, ch = 0, bits = 0;
    Check(xime::voice::ParseWavInfo(wav, &sr, &db, &ch, &bits), "parse ok");
    Check(sr == 16000 && db == static_cast<int>(samples.size() * 2) && ch == 1 && bits == 16,
          "wav params");

    // 2. STT 回环上传
    OneShotServer server;
    if (!server.Start()) {
        std::printf("SERVER_START_FAIL\n");
        return 1;
    }
    xime::voice::SttConfig cfg;
    cfg.baseUrl = "http://127.0.0.1:" + std::to_string(server.port());
    cfg.apiKey = "test-key";
    cfg.model = "whisper-1";
    const auto r = xime::voice::Transcribe(wav, cfg, 10000);
    server.Join();
    Check(r.ok, "transcribe ok");
    Check(r.text == "语音识别结果", "stt text parsed");
    const std::string req = server.request();
    Check(req.find("POST /v1/audio/transcriptions") != std::string::npos, "path");
    Check(req.find("multipart/form-data") != std::string::npos, "content-type");
    Check(req.find("whisper-1") != std::string::npos, "model field");
    Check(req.find("audio.wav") != std::string::npos, "filename");
    Check(req.find("RIFF") != std::string::npos, "wav bytes in body");

    if (fails == 0) {
        std::printf("VOICE_TEST_OK\n");
        return 0;
    }
    std::printf("VOICE_TEST_FAILS=%d\n", fails);
    return 1;
}
