// 极简 JSON 解析/序列化（够用于 AI 协议层，无第三方依赖）。
#pragma once

#include <map>
#include <memory>
#include <string>
#include <vector>

namespace xime {
namespace ai {

class JsonValue {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Type type = Type::Null;
    bool boolValue = false;
    double numberValue = 0.0;
    std::string stringValue;
    std::vector<JsonValue> arrayValue;
    std::map<std::string, JsonValue> objectValue;

    bool IsNull() const { return type == Type::Null; }
    bool IsObject() const { return type == Type::Object; }
    bool IsArray() const { return type == Type::Array; }
    bool IsString() const { return type == Type::String; }
    bool IsNumber() const { return type == Type::Number; }

    // 对象取键；不存在返回 nullptr。
    const JsonValue* Get(const std::string& key) const;
    // 数组下标；越界返回 nullptr。
    const JsonValue* At(size_t index) const;
    // 安全取字符串（非字符串或缺失返回 fallback）。
    std::string GetString(const std::string& key,
                          const std::string& fallback = "") const;
};

// 解析 JSON；失败返回 type=Null 的值（不抛异常）。
JsonValue JsonParse(const std::string& text);

// 把字符串转义为 JSON 字符串字面量（含两端引号）。
std::string JsonEscape(const std::string& raw);

} // namespace ai
} // namespace xime
