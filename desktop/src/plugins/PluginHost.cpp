#include "PluginHost.h"

#include "quickjs.h"

#include <cstdio>
#include <fstream>
#include <sstream>

namespace xime {
namespace plugins {

// 当前正在执行 JS 的宿主（供 host.* C 回调读取，QuickJS 同步单线程）。
thread_local PluginHost* t_activeHost = nullptr;

namespace {

// 调用插件的无参生命周期方法（onLoad/onUnload），返回成功与否。
bool CallLifecycle(PluginHost* self, JSContext* ctx, const char* name,
                   std::string* err) {
    // onLoad 可能调用 host.quickSend.list()，设置活动宿主。
    PluginHost* prev = t_activeHost;
    t_activeHost = self;
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue plugin = JS_GetPropertyStr(ctx, global, "plugin");
    JS_FreeValue(ctx, global);
    if (!JS_IsObject(plugin)) {
        JS_FreeValue(ctx, plugin);
        return true; // 无插件对象视为无需生命周期
    }
    JSValue fn = JS_GetPropertyStr(ctx, plugin, name);
    if (!JS_IsFunction(ctx, fn)) {
        JS_FreeValue(ctx, fn);
        JS_FreeValue(ctx, plugin);
        return true; // 未定义该生命周期 = 成功
    }
    JSValue ret = JS_Call(ctx, fn, plugin, 0, nullptr);
    bool ok = !JS_IsException(ret);
    if (!ok && err) {
        JSValue exc = JS_GetException(ctx);
        const char* s = JS_ToCString(ctx, exc);
        *err = s ? s : "lifecycle error";
        if (s) JS_FreeCString(ctx, s);
        JS_FreeValue(ctx, exc);
    }
    JS_FreeValue(ctx, ret);
    JS_FreeValue(ctx, fn);
    JS_FreeValue(ctx, plugin);
    t_activeHost = prev;
    return ok;
}

// 输入热路径的 JS 中断回调
JSValue QuickSendList(JSContext* ctx, JSValueConst /*this_val*/, int /*argc*/,
                      JSValueConst* /*argv*/) {
    JSValue arr = JS_NewArray(ctx);
    if (!t_activeHost || !t_activeHost->HasQuickSendProvider()) {
        return arr;
    }
    const auto items = t_activeHost->QuickSendList();
    for (uint32_t i = 0; i < items.size(); ++i) {
        JSValue o = JS_NewObject(ctx);
        JS_SetPropertyStr(ctx, o, "id", JS_NewInt32(ctx, items[i].id));
        JS_SetPropertyStr(ctx, o, "text",
                          JS_NewString(ctx, items[i].text.c_str()));
        JS_SetPropertyStr(ctx, o, "code",
                          JS_NewString(ctx, items[i].code.c_str()));
        JS_SetPropertyStr(ctx, o, "timestamp",
                          JS_NewFloat64(ctx,
                                        static_cast<double>(items[i].timestamp)));
        JS_SetPropertyStr(ctx, o, "isPinned",
                          JS_NewBool(ctx, items[i].pinned));
        JS_SetPropertyUint32(ctx, arr, i, o);
    }
    return arr;
}

// 输入热路径的 JS 中断回调：超时后返回非 0，QuickJS 在循环回边/函数调用处
// 抛出内部异常，避免死循环插件卡死输入法。
int InterruptHandler(JSRuntime* rt, void* opaque) {
    auto* deadline = static_cast<std::chrono::steady_clock::time_point*>(opaque);
    if (deadline->time_since_epoch().count() == 0) {
        return 0;
    }
    return std::chrono::steady_clock::now() >= *deadline ? 1 : 0;
}

// host.console.log：把插件日志转 stderr（最小 host 服务）。
JSValue HostLog(JSContext* ctx, JSValueConst /*this_val*/, int argc,
                JSValueConst* argv) {
    for (int i = 0; i < argc; ++i) {
        const char* s = JS_ToCString(ctx, argv[i]);
        if (s) {
            std::fprintf(stderr, "[plugin] %s\n", s);
            JS_FreeCString(ctx, s);
        }
    }
    return JS_UNDEFINED;
}

// definePlugin(o) => o：让 `var plugin = (...)()` 直接得到插件对象。
JSValue DefinePlugin(JSContext* ctx, JSValueConst /*this_val*/, int argc,
                     JSValueConst* argv) {
    if (argc >= 1) {
        return JS_DupValue(ctx, argv[0]);
    }
    return JS_UNDEFINED;
}

void InstallHost(JSContext* ctx) {
    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "definePlugin",
                      JS_NewCFunction(ctx, DefinePlugin, "definePlugin", 1));

    JSValue host = JS_NewObject(ctx);
    JSValue console = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, console, "log",
                      JS_NewCFunction(ctx, HostLog, "log", 1));
    JS_SetPropertyStr(ctx, console, "info",
                      JS_NewCFunction(ctx, HostLog, "info", 1));
    JS_SetPropertyStr(ctx, console, "warn",
                      JS_NewCFunction(ctx, HostLog, "warn", 1));
    JS_SetPropertyStr(ctx, console, "error",
                      JS_NewCFunction(ctx, HostLog, "error", 1));
    JS_SetPropertyStr(ctx, host, "console", console);

    // host.quickSend.list()：同步返回快捷发送条目数组。
    // 用线程局部指针把 provider 传给 C 回调（QuickJS 调用同步、单线程）。
    JSValue quickSend = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, quickSend, "list",
                      JS_NewCFunction(ctx, QuickSendList, "list", 0));
    JS_SetPropertyStr(ctx, host, "quickSend", quickSend);

    JS_SetPropertyStr(ctx, global, "host", host);
    JS_FreeValue(ctx, global);
}

// 读 globalThis.plugin，判断 transform.candidates 是否为函数。
bool ProbeTransform(JSContext* ctx, std::string* nameOut) {
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue plugin = JS_GetPropertyStr(ctx, global, "plugin");
    JS_FreeValue(ctx, global);
    if (!JS_IsObject(plugin)) {
        JS_FreeValue(ctx, plugin);
        return false;
    }
    // manifest.name
    if (nameOut) {
        JSValue manifest = JS_GetPropertyStr(ctx, plugin, "manifest");
        if (JS_IsObject(manifest)) {
            JSValue nm = JS_GetPropertyStr(ctx, manifest, "name");
            if (JS_IsString(nm)) {
                const char* s = JS_ToCString(ctx, nm);
                if (s) {
                    *nameOut = s;
                    JS_FreeCString(ctx, s);
                }
            }
            JS_FreeValue(ctx, nm);
        }
        JS_FreeValue(ctx, manifest);
    }
    JSValue transform = JS_GetPropertyStr(ctx, plugin, "transform");
    bool ok = false;
    if (JS_IsObject(transform)) {
        JSValue fn = JS_GetPropertyStr(ctx, transform, "candidates");
        ok = JS_IsFunction(ctx, fn);
        JS_FreeValue(ctx, fn);
    }
    JS_FreeValue(ctx, transform);
    JS_FreeValue(ctx, plugin);
    return ok;
}

} // namespace

PluginHost::~PluginHost() {
    if (ctx_) {
        JS_FreeContext(ctx_);
        ctx_ = nullptr;
    }
    if (rt_) {
        JS_FreeRuntime(rt_);
        rt_ = nullptr;
    }
}

void PluginHost::EnsureReady() {
    if (rt_) {
        return;
    }
    rt_ = JS_NewRuntime();
    // opaque 指向成员 deadline_（对象生命周期内稳定）；未调用时其 time_point 为默认
    // （count==0），回调直接放行，不影响 LoadFromSource 等初始化执行。
    JS_SetInterruptHandler(rt_, InterruptHandler, &deadline_);
    ctx_ = JS_NewContext(rt_);
    InstallHost(ctx_);
}

void PluginHost::BeginCall() {
    lastTimedOut_ = false;
    if (timeoutMs_ > 0) {
        deadline_ = std::chrono::steady_clock::now() +
                    std::chrono::milliseconds(timeoutMs_);
    } else {
        deadline_ = std::chrono::steady_clock::time_point{};
    }
}

void PluginHost::EndCall(bool wasException) {
    // 仅当本次调用真的抛异常（超时中断或插件错误）时取回并清除，
    // 避免污染后续调用；正常调用不触碰异常状态。
    if (wasException) {
        JSValue exc = JS_GetException(ctx_);
        JS_FreeValue(ctx_, exc);
        lastTimedOut_ = true;
    }
    deadline_ = std::chrono::steady_clock::time_point{};
}

bool PluginHost::LoadFromSource(const std::string& jsSource, std::string* err) {
    EnsureReady();
    loaded_ = false;
    transformAvailable_ = false;
    JSValue val = JS_Eval(ctx_, jsSource.c_str(), jsSource.size(), "main.js",
                          JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(val)) {
        if (err) {
            JSValue exc = JS_GetException(ctx_);
            const char* s = JS_ToCString(ctx_, exc);
            *err = s ? s : "eval error";
            if (s) {
                JS_FreeCString(ctx_, s);
            }
            JS_FreeValue(ctx_, exc);
        }
        JS_FreeValue(ctx_, val);
        return false;
    }
    JS_FreeValue(ctx_, val);
    transformAvailable_ = ProbeTransform(ctx_, &name_);
    loaded_ = true;
    return true;
}

bool PluginHost::LoadFromFile(const std::string& path, std::string* err) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        if (err) {
            *err = "cannot open " + path;
        }
        return false;
    }
    std::ostringstream ss;
    ss << in.rdbuf();
    return LoadFromSource(ss.str(), err);
}

std::string PluginHost::Name() const {
    return name_;
}

bool PluginHost::InvokeLoad(std::string* err) {
    if (!loaded_ || !ctx_) {
        return true;
    }
    return CallLifecycle(this, ctx_, "onLoad", err);
}

bool PluginHost::InvokeUnload(std::string* err) {
    if (!loaded_ || !ctx_) {
        return true;
    }
    return CallLifecycle(this, ctx_, "onUnload", err);
}

TransformResponse PluginHost::Transform(const TransformRequest& request) {
    TransformResponse response;
    if (!loaded_ || !transformAvailable_) {
        return response;
    }
    JSValue global = JS_GetGlobalObject(ctx_);
    JSValue plugin = JS_GetPropertyStr(ctx_, global, "plugin");
    JS_FreeValue(ctx_, global);
    JSValue transform = JS_GetPropertyStr(ctx_, plugin, "transform");
    JSValue fn = JS_GetPropertyStr(ctx_, transform, "candidates");
    JS_FreeValue(ctx_, transform);

    // 构造 request 对象：{ inputText, preedit, asciiMode, candidates:[{text,comment}] }
    JSValue req = JS_NewObject(ctx_);
    JS_SetPropertyStr(ctx_, req, "inputText",
                      JS_NewString(ctx_, request.inputText.c_str()));
    JS_SetPropertyStr(ctx_, req, "preedit",
                      JS_NewString(ctx_, request.preedit.c_str()));
    JS_SetPropertyStr(ctx_, req, "asciiMode", JS_NewBool(ctx_, request.asciiMode));
    JSValue cands = JS_NewArray(ctx_);
    for (uint32_t i = 0; i < request.candidates.size(); ++i) {
        JSValue c = JS_NewObject(ctx_);
        JS_SetPropertyStr(ctx_, c, "text",
                          JS_NewString(ctx_, request.candidates[i].text.c_str()));
        JS_SetPropertyStr(ctx_, c, "comment",
                          JS_NewString(ctx_, request.candidates[i].comment.c_str()));
        JS_SetPropertyUint32(ctx_, cands, i, c);
    }
    JS_SetPropertyStr(ctx_, req, "candidates", cands);

    BeginCall();
    PluginHost* prev = t_activeHost;
    t_activeHost = this;
    JSValue ret = JS_Call(ctx_, fn, plugin, 1, &req);
    t_activeHost = prev;
    EndCall(JS_IsException(ret));
    if (lastTimedOut_) {
        JS_FreeValue(ctx_, ret);
        JS_FreeValue(ctx_, req);
        JS_FreeValue(ctx_, fn);
        JS_FreeValue(ctx_, plugin);
        return response; // 超时 = 不干预，保护输入热路径
    }
    JS_FreeValue(ctx_, req);
    JS_FreeValue(ctx_, fn);
    JS_FreeValue(ctx_, plugin);

    if (JS_IsException(ret) || JS_IsNull(ret) || JS_IsUndefined(ret)) {
        JS_FreeValue(ctx_, ret);
        return response; // null = 不干预
    }
    JSValue outCands = JS_GetPropertyStr(ctx_, ret, "candidates");
    if (!JS_IsArray(ctx_, outCands)) {
        JS_FreeValue(ctx_, outCands);
        JS_FreeValue(ctx_, ret);
        return response;
    }
    response.changed = true;
    JSValue lenv = JS_GetPropertyStr(ctx_, outCands, "length");
    uint32_t len = 0;
    JS_ToUint32(ctx_, &len, lenv);
    JS_FreeValue(ctx_, lenv);
    for (uint32_t i = 0; i < len; ++i) {
        JSValue item = JS_GetPropertyUint32(ctx_, outCands, i);
        TransformCandidate out;
        JSValue ei = JS_GetPropertyStr(ctx_, item, "engineIndex");
        if (JS_IsNumber(ei)) {
            double d = 0;
            JS_ToFloat64(ctx_, &d, ei);
            out.engineIndex = static_cast<int>(d);
        }
        JS_FreeValue(ctx_, ei);
        JSValue tx = JS_GetPropertyStr(ctx_, item, "text");
        if (JS_IsString(tx)) {
            const char* s = JS_ToCString(ctx_, tx);
            if (s) {
                out.text = s;
                JS_FreeCString(ctx_, s);
            }
        }
        JS_FreeValue(ctx_, tx);
        JSValue cm = JS_GetPropertyStr(ctx_, item, "comment");
        if (JS_IsString(cm)) {
            const char* s = JS_ToCString(ctx_, cm);
            if (s) {
                out.comment = s;
                JS_FreeCString(ctx_, s);
            }
        }
        JS_FreeValue(ctx_, cm);
        response.candidates.push_back(std::move(out));
        JS_FreeValue(ctx_, item);
    }
    JS_FreeValue(ctx_, outCands);
    JS_FreeValue(ctx_, ret);
    return response;
}

std::vector<std::string> PluginHost::TransformCandidates(
    const std::vector<std::string>& input) {
    if (!loaded_ || !transformAvailable_) {
        return input;
    }
    JSValue global = JS_GetGlobalObject(ctx_);
    JSValue plugin = JS_GetPropertyStr(ctx_, global, "plugin");
    JS_FreeValue(ctx_, global);
    JSValue transform = JS_GetPropertyStr(ctx_, plugin, "transform");
    JSValue fn = JS_GetPropertyStr(ctx_, transform, "candidates");
    JS_FreeValue(ctx_, transform);

    JSValue arr = JS_NewArray(ctx_);
    for (uint32_t i = 0; i < input.size(); ++i) {
        JS_SetPropertyUint32(ctx_, arr, i,
                             JS_NewString(ctx_, input[i].c_str()));
    }

    BeginCall();
    PluginHost* prev = t_activeHost;
    t_activeHost = this;
    JSValue ret = JS_Call(ctx_, fn, plugin, 1, &arr);
    t_activeHost = prev;
    EndCall(JS_IsException(ret));
    JS_FreeValue(ctx_, arr);
    JS_FreeValue(ctx_, fn);
    JS_FreeValue(ctx_, plugin);
    if (lastTimedOut_) {
        JS_FreeValue(ctx_, ret);
        return input; // 超时 = 原样返回
    }

    std::vector<std::string> out = input;
    if (!JS_IsException(ret) && JS_IsArray(ctx_, ret)) {
        JSValue lenv = JS_GetPropertyStr(ctx_, ret, "length");
        uint32_t len = 0;
        JS_ToUint32(ctx_, &len, lenv);
        JS_FreeValue(ctx_, lenv);
        std::vector<std::string> transformed;
        for (uint32_t i = 0; i < len; ++i) {
            JSValue item = JS_GetPropertyUint32(ctx_, ret, i);
            const char* s = JS_ToCString(ctx_, item);
            transformed.push_back(s ? s : "");
            if (s) {
                JS_FreeCString(ctx_, s);
            }
            JS_FreeValue(ctx_, item);
        }
        out = std::move(transformed);
    }
    JS_FreeValue(ctx_, ret);
    return out;
}

} // namespace plugins
} // namespace xime
