// AI 服务门面测试：润色开关决策 + LLM 请求/回退链路（http 桩，离线）。
#include "../src/ai/AiService.h"

#include <cstdio>
#include <string>

using namespace xime::ai;

namespace {
int fails = 0;
void Check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++fails;
    }
}
} // namespace

static const char* kTestKey = "TESTKEY";

int main() {
    ProviderVault vault;
    vault.SetKey("siliconflow", kTestKey);
    vault.SetBaseUrl("siliconflow", "https://api.siliconflow.cn");

    AppSettings settings;
    settings.llm.enabled = true;
    settings.llm.providerId = "siliconflow";
    settings.llm.model = "glm-4";

    const std::string raw = "今天天气不错我们出去玩吧";

    // 1. 润色关闭 → 直出原始，不发请求
    {
        AppSettings off = settings;
        off.llm.enabled = false;
        int calls = 0;
        auto http = [&](const AiHttpRequest&, AiHttpResponse&) {
            ++calls;
            return true;
        };
        auto r = FinalizeDictation(off, vault, raw, http);
        Check(r.ok, "off ok");
        Check(!r.polished, "off not polished");
        Check(r.text == raw, "off returns raw verbatim");
        Check(calls == 0, "off makes no http call");
    }

    // 2. 润色开启、成功 → 返回润色文本，请求构造正确
    {
        AiHttpRequest captured;
        std::string httpBody =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"今天天气不错，我们出去玩吧。\"}}]}";
        auto http = [&](const AiHttpRequest& req, AiHttpResponse& resp) {
            captured = req;
            resp.status = 200;
            resp.body = httpBody;
            return true;
        };
        auto r = FinalizeDictation(settings, vault, raw, http);
        Check(r.ok && r.polished, "on polished");
        Check(r.text == "今天天气不错，我们出去玩吧。", "on returns polished");
        Check(captured.url == "https://api.siliconflow.cn/v1/chat/completions",
              "chat url");
        Check(captured.headers["Authorization"] == "Bearer TESTKEY", "auth header");
        Check(captured.body.find("\"model\":\"glm-4\"") != std::string::npos,
              "model in body");
        Check(captured.body.find(raw) != std::string::npos, "raw in body");
        Check(captured.body.find("\"stream\":false") != std::string::npos,
              "stream false");
    }

    // 3. 润色开启、响应 content 为空 → 回退原始
    {
        auto http = [&](const AiHttpRequest&, AiHttpResponse& resp) {
            resp.status = 200;
            resp.body = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}";
            return true;
        };
        auto r = FinalizeDictation(settings, vault, raw, http);
        Check(r.text == raw && !r.polished, "empty content falls back to raw");
    }

    // 4. HTTP 非 2xx → 回退原始
    {
        auto http = [&](const AiHttpRequest&, AiHttpResponse& resp) {
            resp.status = 500;
            resp.body = "err";
            return true;
        };
        auto r = FinalizeDictation(settings, vault, raw, http);
        Check(r.text == raw && !r.polished, "500 falls back to raw");
        Check(r.error == "http_status_500", "500 error code");
    }

    // 5. 无 LLM 凭据 → 回退原始，不发请求
    {
        ProviderVault emptyVault;
        int calls = 0;
        auto http = [&](const AiHttpRequest&, AiHttpResponse&) {
            ++calls;
            return true;
        };
        auto r = FinalizeDictation(settings, emptyVault, raw, http);
        Check(r.text == raw && !r.polished, "no key falls back to raw");
        Check(r.error == "no_llm_credential", "no key error");
        Check(calls == 0, "no key makes no http call");
    }

    // 6. 传输失败 → 回退原始
    {
        auto http = [&](const AiHttpRequest&, AiHttpResponse&) { return false; };
        auto r = FinalizeDictation(settings, vault, raw, http);
        Check(r.text == raw && !r.polished, "transport fail falls back to raw");
        Check(r.error == "http_failed", "transport fail error");
    }

    if (fails == 0) {
        std::printf("AISERVICE_TEST_OK\n");
        return 0;
    }
    std::printf("AISERVICE_TEST_FAILS=%d\n", fails);
    return 1;
}
