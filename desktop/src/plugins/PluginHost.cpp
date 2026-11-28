#include "PluginHost.h"

#include "quickjs.h"

#include <cstdio>
#include <fstream>
#include <sstream>

namespace xime {
namespace plugins {

namespace {

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
    ctx_ = JS_NewContext(rt_);
    InstallHost(ctx_);
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

    JSValue ret = JS_Call(ctx_, fn, plugin, 1, &arr);
    JS_FreeValue(ctx_, arr);
    JS_FreeValue(ctx_, fn);
    JS_FreeValue(ctx_, plugin);

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
