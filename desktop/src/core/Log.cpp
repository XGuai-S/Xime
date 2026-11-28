#include "Log.h"

#include <windows.h>

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <io.h>
#include <mutex>
#include <string>

namespace xime {
namespace log {

namespace {

std::mutex g_mu;
bool g_enabled = true;
bool g_verbose = false;   // XIME_LOG=debug 时为真
bool g_inited = false;
FILE* g_file = nullptr;
std::string g_path;

constexpr long kMaxBytes = 4 * 1024 * 1024; // 单文件上限，超出轮转
long g_written = 0;

const char* LevelName(Level lv) {
    switch (lv) {
    case Level::Debug: return "DEBUG";
    case Level::Info:  return "INFO ";
    case Level::Warn:  return "WARN ";
    case Level::Error: return "ERROR";
    }
    return "?????";
}

std::string LogDir() {
    char buf[MAX_PATH] = {0};
    if (GetEnvironmentVariableA("LOCALAPPDATA", buf, MAX_PATH) == 0) {
        return ".";
    }
    return std::string(buf) + "\\Xime\\logs";
}

void OpenLocked() {
    if (g_file) {
        return;
    }
    const std::string dir = LogDir();
    // 逐级建目录（%LOCALAPPDATA%\Xime\logs，Xime 可能还不存在）。
    std::string acc;
    for (size_t i = 0; i < dir.size(); ++i) {
        acc.push_back(dir[i]);
        if (dir[i] == '\\' || dir[i] == '/') {
            CreateDirectoryA(acc.c_str(), nullptr);
        }
    }
    CreateDirectoryA(dir.c_str(), nullptr);

    g_path = dir + "\\xime-tsf.log";
    // 已存在且超限则轮转（保留上一份为 .1）
    WIN32_FILE_ATTRIBUTE_DATA fad;
    if (GetFileAttributesExA(g_path.c_str(), GetFileExInfoStandard, &fad)) {
        const long size = static_cast<long>(fad.nFileSizeLow);
        if (size > kMaxBytes) {
            const std::string bak = g_path + ".1";
            DeleteFileA(bak.c_str());
            MoveFileA(g_path.c_str(), bak.c_str());
        } else {
            g_written = size;
        }
    }
    // 用 fopen 的 "a" 模式时 Windows 默认不共享，其它进程/工具（含调试时用
    // 记事本或 Get-Content 查看）会被拒绝访问。这里改用 CreateFile 显式指定
    // FILE_SHARE_*，再用 _open_osfhandle + _fdopen 得到 FILE*。
    // 这样日志可以边写边看，排查问题时不用先杀掉输入法。
    const HANDLE h = CreateFileA(
        g_path.c_str(), FILE_APPEND_DATA,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
        OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h != INVALID_HANDLE_VALUE) {
        const int fd = _open_osfhandle(reinterpret_cast<intptr_t>(h),
                                       _O_APPEND | _O_TEXT);
        if (fd != -1) {
            g_file = _fdopen(fd, "a");
            if (!g_file) {
                _close(fd);
            }
        } else {
            CloseHandle(h);
        }
    }
    if (!g_file) {
        // 兜底：普通 fopen（可能无法共享读取，但至少能记录）。
        fopen_s(&g_file, g_path.c_str(), "ab");
    }
}

} // namespace

void Init() {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_inited) {
        return;
    }
    g_inited = true;
    // 默认开启；XIME_LOG=0/off 关闭，XIME_LOG=debug 打开 Debug 级。
    char buf[32] = {0};
    if (GetEnvironmentVariableA("XIME_LOG", buf, sizeof(buf)) > 0) {
        if (_stricmp(buf, "0") == 0 || _stricmp(buf, "off") == 0) {
            g_enabled = false;
        } else if (_stricmp(buf, "debug") == 0) {
            g_verbose = true;
        }
    }
    if (g_enabled) {
        OpenLocked();
    }
}

void SetEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(g_mu);
    g_enabled = enabled;
}

void Write(Level level, const char* fmt, ...) {
    if (!g_enabled) {
        return;
    }
    // Debug 级仅在 XIME_LOG=debug 时输出，避免热路径刷屏。
    if (level == Level::Debug && !g_verbose) {
        char buf[2048];
        (void)buf;
        // 仍需初始化（其它级别会用），但本条不写。
        std::lock_guard<std::mutex> lock(g_mu);
        if (!g_inited) {
            // 不能在持锁时调 Init()（会重复加锁），内联处理
            g_inited = true;
            OpenLocked();
        }
        return;
    }

    SYSTEMTIME st;
    GetLocalTime(&st);
    const DWORD tid = GetCurrentThreadId();

    char msg[2048];
    va_list ap;
    va_start(ap, fmt);
    _vsnprintf_s(msg, sizeof(msg), _TRUNCATE, fmt, ap);
    va_end(ap);

    char line[2400];
    _snprintf_s(line, sizeof(line), _TRUNCATE,
                "[%02d:%02d:%02d.%03d][tid:%lu][%s] %s\r\n", st.wHour,
                st.wMinute, st.wSecond, st.wMilliseconds, tid,
                LevelName(level), msg);

    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_inited) {
        g_inited = true;
        OpenLocked();
    }
    if (g_file) {
        const size_t n = strlen(line);
        fwrite(line, 1, n, g_file);
        // 立即 flush：崩溃前的最后一条必须落盘，这是调试的关键。
        fflush(g_file);
        g_written += static_cast<long>(n);
        if (g_written > kMaxBytes) {
            fclose(g_file);
            g_file = nullptr;
            g_written = 0;
            const std::string bak = g_path + ".1";
            DeleteFileA(bak.c_str());
            MoveFileA(g_path.c_str(), bak.c_str());
            g_file = nullptr;
            OpenLocked();
        }
    }
    // 同时输出到调试器（DebugView / VS 输出窗口可见）。
    OutputDebugStringA(line);
}

void WriteDebug(const char* fmt, ...) {
    if (!g_verbose) {
        return;
    }
    va_list ap;
    va_start(ap, fmt);
    char msg[2048];
    _vsnprintf_s(msg, sizeof(msg), _TRUNCATE, fmt, ap);
    va_end(ap);
    Write(Level::Debug, "%s", msg);
}

void WriteInfo(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char msg[2048];
    _vsnprintf_s(msg, sizeof(msg), _TRUNCATE, fmt, ap);
    va_end(ap);
    Write(Level::Info, "%s", msg);
}

void WriteWarn(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char msg[2048];
    _vsnprintf_s(msg, sizeof(msg), _TRUNCATE, fmt, ap);
    va_end(ap);
    Write(Level::Warn, "%s", msg);
}

void WriteError(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char msg[2048];
    _vsnprintf_s(msg, sizeof(msg), _TRUNCATE, fmt, ap);
    va_end(ap);
    Write(Level::Error, "%s", msg);
}

} // namespace log
} // namespace xime
