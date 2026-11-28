#include "Json.h"

#include <cctype>
#include <cstdint>
#include <cstdlib>

namespace xime {
namespace ai {

const JsonValue* JsonValue::Get(const std::string& key) const {
    if (type != Type::Object) {
        return nullptr;
    }
    const auto it = objectValue.find(key);
    return it == objectValue.end() ? nullptr : &it->second;
}

const JsonValue* JsonValue::At(size_t index) const {
    if (type != Type::Array || index >= arrayValue.size()) {
        return nullptr;
    }
    return &arrayValue[index];
}

std::string JsonValue::GetString(const std::string& key,
                                 const std::string& fallback) const {
    const JsonValue* v = Get(key);
    if (v && v->type == Type::String) {
        return v->stringValue;
    }
    return fallback;
}

namespace {

struct Parser {
    const std::string& s;
    size_t i = 0;
    bool ok = true;

    explicit Parser(const std::string& text) : s(text) {}

    void SkipWs() {
        while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) {
            ++i;
        }
    }

    bool Consume(char c) {
        SkipWs();
        if (i < s.size() && s[i] == c) {
            ++i;
            return true;
        }
        return false;
    }

    JsonValue ParseValue() {
        SkipWs();
        if (i >= s.size()) {
            ok = false;
            return {};
        }
        const char c = s[i];
        if (c == '{') {
            return ParseObject();
        }
        if (c == '[') {
            return ParseArray();
        }
        if (c == '"') {
            JsonValue v;
            v.type = JsonValue::Type::String;
            v.stringValue = ParseString();
            return v;
        }
        if (c == 't' || c == 'f') {
            return ParseBool();
        }
        if (c == 'n') {
            return ParseNull();
        }
        return ParseNumber();
    }

    JsonValue ParseObject() {
        JsonValue v;
        v.type = JsonValue::Type::Object;
        if (!Consume('{')) {
            ok = false;
            return v;
        }
        SkipWs();
        if (Consume('}')) {
            return v;
        }
        while (ok) {
            SkipWs();
            if (i >= s.size() || s[i] != '"') {
                ok = false;
                return v;
            }
            std::string key = ParseString();
            if (!Consume(':')) {
                ok = false;
                return v;
            }
            JsonValue val = ParseValue();
            if (!ok) {
                return v;
            }
            v.objectValue[key] = std::move(val);
            SkipWs();
            if (Consume(',')) {
                continue;
            }
            if (Consume('}')) {
                return v;
            }
            ok = false;
            return v;
        }
        return v;
    }

    JsonValue ParseArray() {
        JsonValue v;
        v.type = JsonValue::Type::Array;
        if (!Consume('[')) {
            ok = false;
            return v;
        }
        SkipWs();
        if (Consume(']')) {
            return v;
        }
        while (ok) {
            JsonValue val = ParseValue();
            if (!ok) {
                return v;
            }
            v.arrayValue.push_back(std::move(val));
            SkipWs();
            if (Consume(',')) {
                continue;
            }
            if (Consume(']')) {
                return v;
            }
            ok = false;
            return v;
        }
        return v;
    }

    std::string ParseString() {
        std::string out;
        if (i >= s.size() || s[i] != '"') {
            ok = false;
            return out;
        }
        ++i;
        while (i < s.size()) {
            const char c = s[i++];
            if (c == '"') {
                return out;
            }
            if (c == '\\') {
                if (i >= s.size()) {
                    ok = false;
                    return out;
                }
                const char e = s[i++];
                switch (e) {
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                case '/': out.push_back('/'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case 'u': {
                    if (i + 4 > s.size()) {
                        ok = false;
                        return out;
                    }
                    uint32_t cp = 0;
                    for (int k = 0; k < 4; ++k) {
                        cp <<= 4;
                        const char h = s[i++];
                        if (h >= '0' && h <= '9') cp |= (h - '0');
                        else if (h >= 'a' && h <= 'f') cp |= (h - 'a' + 10);
                        else if (h >= 'A' && h <= 'F') cp |= (h - 'A' + 10);
                        else { ok = false; return out; }
                    }
                    // 代理对
                    if (cp >= 0xD800 && cp <= 0xDBFF && i + 6 <= s.size() &&
                        s[i] == '\\' && s[i + 1] == 'u') {
                        i += 2;
                        uint32_t lo = 0;
                        for (int k = 0; k < 4; ++k) {
                            lo <<= 4;
                            const char h = s[i++];
                            if (h >= '0' && h <= '9') lo |= (h - '0');
                            else if (h >= 'a' && h <= 'f') lo |= (h - 'a' + 10);
                            else if (h >= 'A' && h <= 'F') lo |= (h - 'A' + 10);
                            else { ok = false; return out; }
                        }
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    }
                    AppendUtf8(out, cp);
                    break;
                }
                default:
                    ok = false;
                    return out;
                }
            } else {
                out.push_back(c);
            }
        }
        ok = false;
        return out;
    }

    static void AppendUtf8(std::string& out, uint32_t cp) {
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    JsonValue ParseBool() {
        JsonValue v;
        v.type = JsonValue::Type::Bool;
        if (s.compare(i, 4, "true") == 0) {
            v.boolValue = true;
            i += 4;
        } else if (s.compare(i, 5, "false") == 0) {
            v.boolValue = false;
            i += 5;
        } else {
            ok = false;
        }
        return v;
    }

    JsonValue ParseNull() {
        JsonValue v;
        v.type = JsonValue::Type::Null;
        if (s.compare(i, 4, "null") == 0) {
            i += 4;
        } else {
            ok = false;
        }
        return v;
    }

    JsonValue ParseNumber() {
        JsonValue v;
        v.type = JsonValue::Type::Number;
        const size_t start = i;
        while (i < s.size() &&
               (std::isdigit(static_cast<unsigned char>(s[i])) || s[i] == '-' ||
                s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) {
            ++i;
        }
        if (i == start) {
            ok = false;
            return v;
        }
        v.numberValue = std::strtod(s.substr(start, i - start).c_str(), nullptr);
        return v;
    }
};

} // namespace

JsonValue JsonParse(const std::string& text) {
    Parser p(text);
    JsonValue v = p.ParseValue();
    if (!p.ok) {
        return {};
    }
    return v;
}

std::string JsonEscape(const std::string& raw) {
    std::string out;
    out.reserve(raw.size() + 2);
    out.push_back('"');
    for (unsigned char c : raw) {
        switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\b': out += "\\b"; break;
        case '\f': out += "\\f"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (c < 0x20) {
                char buf[8];
                std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                out += buf;
            } else {
                out.push_back(static_cast<char>(c));
            }
        }
    }
    out.push_back('"');
    return out;
}

} // namespace ai
} // namespace xime
