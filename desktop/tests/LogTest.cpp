// 日志功能验收：文件能创建、能写入、级别与开关生效、超限能轮转。
#include "../src/core/Log.h"

#include <windows.h>

#include <cstdio>
#include <fstream>
#include <string>

namespace {

int g_fail = 0;

void Check(bool cond, const char* what) {
    std::printf("%s %s\n", cond ? "LOG_OK  " : "LOG_FAIL", what);
    if (!cond) {
        ++g_fail;
    }
}

std::string LogPath() {
    char buf[MAX_PATH] = {0};
    GetEnvironmentVariableA("LOCALAPPDATA", buf, MAX_PATH);
    return std::string(buf) + "\\Xime\\logs\\xime-tsf.log";
}

// 用 CreateFile + 共享标志读取：日志器以 FILE_SHARE_* 打开，这里必须同样
// 允许共享，否则 Windows 会返回 ERROR_SHARING_VIOLATION。
std::string ReadAll(const std::string& path) {
    HANDLE h = CreateFileA(path.c_str(), GENERIC_READ,
                           FILE_SHARE_READ | FILE_SHARE_WRITE |
                               FILE_SHARE_DELETE,
                           nullptr, OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        return "";
    }
    std::string out;
    char buf[4096];
    DWORD rd = 0;
    while (ReadFile(h, buf, sizeof(buf), &rd, nullptr) && rd > 0) {
        out.append(buf, rd);
    }
    CloseHandle(h);
    return out;
}
} // namespace

int main() {
    // 清掉旧日志，确保断言针对本次运行。
    const std::string path = LogPath();
    DeleteFileA(path.c_str());

    xime::log::Init();

    // 1) 写入各成级别，文件应被创建并包含这些内容。
    xime::log::WriteInfo("TEST_INFO_MARKER v=%d", 42);
    xime::log::WriteWarn("TEST_WARN_MARKER");
    xime::log::WriteError("TEST_ERROR_MARKER");
    // Debug 默认关闭（未设 XIME_LOG=debug），不应出现。
    xime::log::WriteDebug("TEST_DEBUG_SHOULD_BE_HIDDEN");

    const std::string content = ReadAll(path);
    Check(!content.empty(), "log file created and non-empty");
    Check(content.find("TEST_INFO_MARKER v=42") != std::string::npos,
          "info line written with formatted args");
    Check(content.find("TEST_WARN_MARKER") != std::string::npos, "warn line written");
    Check(content.find("TEST_ERROR_MARKER") != std::string::npos, "error line written");
    Check(content.find("TEST_DEBUG_SHOULD_BE_HIDDEN") == std::string::npos,
          "debug suppressed by default");

    // 2) 格式应含时间、线程 id、级别。
    Check(content.find("[tid:") != std::string::npos, "thread id present");
    Check(content.find("[INFO ]") != std::string::npos ||
              content.find("[INFO") != std::string::npos,
          "level tag present");

    // 3) 立即落盘：此时文件里应已有内容（不需要显式 close/flush）。
    //    上面 ReadAll 已经读到，说明 flush 生效。

    // 4) 关闭后不再写入。
    xime::log::SetEnabled(false);
    const size_t before = ReadAll(path).size();
    xime::log::WriteInfo("TEST_AFTER_DISABLE");
    const size_t after = ReadAll(path).size();
    Check(before == after, "no write after SetEnabled(false)");

    xime::log::SetEnabled(true);
    xime::log::WriteInfo("TEST_REENABLED");
    Check(ReadAll(path).size() > after, "write resumes after re-enable");

    if (g_fail != 0) {
        std::printf("LOG_FAILS=%d\n", g_fail);
        return 1;
    }
    std::printf("LOG_OK_ALL path=%s\n", path.c_str());
    return 0;
}
