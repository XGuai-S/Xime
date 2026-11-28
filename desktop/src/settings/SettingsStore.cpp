#include "SettingsStore.h"

#include "../ai/Json.h"

#include <cstdio>
#include <fstream>
#include <sstream>

namespace xime {
namespace settings {

using ai::JsonEscape;
using ai::JsonParse;
using ai::JsonValue;

namespace {

std::string BuildJson(const DesktopSettings& s) {
    std::string out = "{";
    out += "\"page_size\":" + std::to_string(s.pageSize);
    out += ",\"polish_enabled\":";
    out += s.polishEnabled ? "true" : "false";
    out += ",\"llm_model\":" + JsonEscape(s.llmModel);
    out += ",\"llm_base_url\":" + JsonEscape(s.llmBaseUrl);
    out += ",\"stt_model\":" + JsonEscape(s.sttModel);
    out += ",\"stt_base_url\":" + JsonEscape(s.sttBaseUrl);
    out += ",\"schema_list\":[";
    for (size_t i = 0; i < s.schemaList.size(); ++i) {
        if (i) out += ",";
        out += JsonEscape(s.schemaList[i]);
    }
    out += "]}";
    return out;
}

int IntFrom(const JsonValue& root, const char* key, int fallback) {
    const JsonValue* v = root.Get(key);
    if (v && v->IsNumber()) {
        return static_cast<int>(v->numberValue);
    }
    return fallback;
}

bool BoolFrom(const JsonValue& root, const char* key, bool fallback) {
    const JsonValue* v = root.Get(key);
    if (v && v->type == JsonValue::Type::Bool) {
        return v->boolValue;
    }
    return fallback;
}

} // namespace

std::string SettingsStore::Serialize(const DesktopSettings& s) {
    return BuildJson(s);
}

DesktopSettings SettingsStore::ParseOrDefault(const std::string& json) {
    DesktopSettings s;
    const JsonValue root = JsonParse(json);
    if (!root.IsObject()) {
        return s;
    }
    s.pageSize = IntFrom(root, "page_size", 5);
    if (s.pageSize < 5) s.pageSize = 5;
    if (s.pageSize > 50) s.pageSize = 50;
    s.polishEnabled = BoolFrom(root, "polish_enabled", true);
    if (const JsonValue* v = root.Get("llm_model")) {
        if (v->IsString()) s.llmModel = v->stringValue;
    }
    if (const JsonValue* v = root.Get("llm_base_url")) {
        if (v->IsString()) s.llmBaseUrl = v->stringValue;
    }
    if (const JsonValue* v = root.Get("stt_model")) {
        if (v->IsString()) s.sttModel = v->stringValue;
    }
    if (const JsonValue* v = root.Get("stt_base_url")) {
        if (v->IsString()) s.sttBaseUrl = v->stringValue;
    }
    if (const JsonValue* arr = root.Get("schema_list")) {
        if (arr->IsArray()) {
            for (const auto& e : arr->arrayValue) {
                if (e.IsString()) s.schemaList.push_back(e.stringValue);
            }
        }
    }
    return s;
}

bool SettingsStore::Load(const std::string& dir, DesktopSettings* out) {
    const std::string path = dir + "/settings.json";
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        if (out) *out = DesktopSettings{};
        return false;
    }
    std::ostringstream ss;
    ss << in.rdbuf();
    if (out) *out = ParseOrDefault(ss.str());
    return true;
}

bool SettingsStore::Save(const std::string& dir, const DesktopSettings& settings) {
    const std::string path = dir + "/settings.json";
    const std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out) return false;
        out << Serialize(settings) << "\n";
        out.flush();
        if (!out) return false;
    }
    std::remove(path.c_str());
    return std::rename(tmp.c_str(), path.c_str()) == 0;
}

} // namespace settings
} // namespace xime
