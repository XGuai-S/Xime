// 共享数据目录解析验收：TSF DLL 被加载进宿主进程时，必须用本 DLL 的路径定位
// 同级 data/，而不是宿主 EXE 的路径。
//
// 背景：最初用 GetModuleFileNameW(nullptr, ...) 取路径，拿到的是宿主 EXE
// （explorer.exe 等），共享数据目录被算成 C:\Windows\data，引擎加载不到任何
// 方案，按键全部直通 —— 用户看到的现象是"只能打英文"。
#include <windows.h>

#include <cstdio>
#include <string>

namespace {

int g_failures = 0;

void Expect(bool cond, const char* what) {
    if (cond) {
        std::printf("RESOLVE_OK   %s\n", what);
    } else {
        std::printf("RESOLVE_FAIL %s\n", what);
        ++g_failures;
    }
}

// 取"本模块"路径的锚点：定义在本翻译单元内。
void Anchor() {}

std::wstring SelfDir() {
    HMODULE mod = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCWSTR>(&Anchor), &mod)) {
        return L"";
    }
    wchar_t path[MAX_PATH] = {0};
    GetModuleFileNameW(mod, path, MAX_PATH);
    std::wstring p(path);
    const size_t s = p.find_last_of(L"\\/");
    return s == std::wstring::npos ? L"" : p.substr(0, s);
}

std::wstring ExeDir() {
    wchar_t path[MAX_PATH] = {0};
    GetModuleFileNameW(nullptr, path, MAX_PATH);
    std::wstring p(path);
    const size_t s = p.find_last_of(L"\\/");
    return s == std::wstring::npos ? L"" : p.substr(0, s);
}

} // namespace

int main() {
    const std::wstring self = SelfDir();
    const std::wstring exe = ExeDir();

    std::printf("SELF_DIR=%ls\n", self.c_str());
    std::printf("EXE_DIR=%ls\n", exe.c_str());

    // 1) 取模块路径必须成功。
    Expect(!self.empty(), "self module dir resolved");

    // 2) 两者可能相同（独立 exe）或不同（DLL 被载入别的宿主），
    //    但关键是：用模块句柄一定能拿到"本模块"所在目录。
    //    在本测试里 exe 与模块同目录，故比较是否为同一目录。
    Expect(self == exe, "anchor resolves to this binary's dir");

    // 3) 该目录下应能找到测试自身（证明路径有效、不是空串或根目录）。
    const std::wstring selfExe = self + L"\\xime-pathresolve-test.exe";
    Expect(GetFileAttributesW(selfExe.c_str()) != INVALID_FILE_ATTRIBUTES,
           "resolved dir contains this test binary");

    // 4) 反向验证：宿主 EXE 目录下通常没有 data/（除非巧合装在 C:\Windows），
    //    因此"用 nullptr 取路径"是不可靠的 —— 记录此行以便诊断。
    const std::wstring exeData = exe + L"\\data";
    const std::wstring selfData = self + L"\\data";
    std::printf("EXE_DATA_EXISTS=%d SELF_DATA_EXISTS=%d\n",
                GetFileAttributesW(exeData.c_str()) != INVALID_FILE_ATTRIBUTES,
                GetFileAttributesW(selfData.c_str()) != INVALID_FILE_ATTRIBUTES);

    if (g_failures != 0) {
        std::printf("PATHRESOLVE_FAIL %d\n", g_failures);
        return 1;
    }
    std::printf("PATHRESOLVE_OK\n");
    return 0;
}
