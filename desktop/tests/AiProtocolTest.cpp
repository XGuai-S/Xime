// AI 协议层单元测试（纯逻辑，无网络）。
#include "../src/ai/AiProtocol.h"
#include "../src/ai/Json.h"

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

int main() {
    // 1. JSON 解析：嵌套/数组/转义/unicode
    const std::string sample =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好\\n世界\"}}],"
        "\"usage\":{\"total_tokens\":42}}";
    JsonValue root = JsonParse(sample);
    Check(root.IsObject(), "root object");
    const JsonValue* choices = root.Get("choices");
    Check(choices && choices->IsArray() && choices->arrayValue.size() == 1, "choices");
    Check(ExtractChatContent(sample) == "你好\n世界", "extract content with escape");
    Check(root.Get("usage")->Get("total_tokens") == nullptr ||
              root.Get("usage")->Get("total_tokens")->IsNumber(), "usage number");

    // 2. 请求体构造并回解
    ChatRequestOptions opts;
    opts.model = "glm-4";
    opts.systemPrompt = "你是润色助手，保持原意。";
    opts.userText = "把\"这句话\"整理通顺\n第二行";
    opts.temperature = 0.2;
    const std::string body = BuildChatRequestBody(opts);
    JsonValue req = JsonParse(body);
    Check(req.IsObject(), "req object");
    Check(req.GetString("model") == "glm-4", "model");
    const JsonValue* msgs = req.Get("messages");
    Check(msgs && msgs->IsArray() && msgs->arrayValue.size() == 2, "two messages");
    Check(msgs->At(0)->GetString("role") == "system", "sys role");
    Check(msgs->At(0)->GetString("content") == "你是润色助手，保持原意。", "sys content");
    Check(msgs->At(1)->GetString("role") == "user", "user role");
    Check(msgs->At(1)->GetString("content") == "把\"这句话\"整理通顺\n第二行", "user content escaped roundtrip");
    Check(req.Get("temperature") != nullptr, "temp present");

    // 3. 无 system、关闭 temperature、maxTokens
    ChatRequestOptions o2;
    o2.model = "m";
    o2.userText = "hi";
    o2.includeTemperature = false;
    o2.maxTokens = 128;
    JsonValue r2 = JsonParse(BuildChatRequestBody(o2));
    Check(r2.Get("messages")->arrayValue.size() == 1, "one message when no system");
    Check(r2.Get("temperature") == nullptr, "no temperature when disabled");
    Check(r2.Get("max_tokens") != nullptr, "max_tokens present");

    // 4. 空/异常响应
    Check(ExtractChatContent("") == "", "empty resp");
    Check(ExtractChatContent("{\"choices\":[]}") == "", "empty choices");
    Check(ExtractChatContent("not json") == "", "garbage resp");

    // 5. 端点解析
    Check(ResolveChatEndpoint("") == "https://api.openai.com/v1/chat/completions", "chat default");
    Check(ResolveChatEndpoint("https://api.x.com") == "https://api.x.com/v1/chat/completions", "chat append v1");
    Check(ResolveChatEndpoint("https://api.x.com/v1") == "https://api.x.com/v1/chat/completions", "chat base v1");
    Check(ResolveChatEndpoint("https://api.x.com/v1/chat/completions") == "https://api.x.com/v1/chat/completions", "chat full");
    Check(ResolveSttEndpoint("https://api.x.com/v1") == "https://api.x.com/v1/audio/transcriptions", "stt base v1");

    // 6. 转义
    Check(JsonEscape("a\"b\\c\n") == "\"a\\\"b\\\\c\\n\"", "escape");

    // 7. 代理对（emoji）
    Check(JsonParse("{\"e\":\"\\ud83d\\ude00\"}").GetString("e") == "😀", "surrogate pair");

    if (fails == 0) {
        std::printf("AIPROTO_TEST_OK\n");
        return 0;
    }
    std::printf("AIPROTO_TEST_FAILS=%d\n", fails);
    return 1;
}
