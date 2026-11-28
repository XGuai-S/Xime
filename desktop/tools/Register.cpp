// xime-register.exe install|uninstall —— 注册/注销 TSF 文本服务。
#include "../src/tsf/Guids.h"

#include <windows.h>
#include <objbase.h>
#include <shellapi.h>
#include <shlwapi.h>

#include <string>

#pragma comment(lib, "shlwapi.lib")

namespace {

bool RegSetString(HKEY root, const std::wstring& path, const wchar_t* name,
                  const std::wstring& value) {
    HKEY key = nullptr;
    if (RegCreateKeyExW(root, path.c_str(), 0, nullptr, REG_OPTION_NON_VOLATILE,
                        KEY_WRITE, nullptr, &key, nullptr) != ERROR_SUCCESS) {
        return false;
    }
    const LONG r = RegSetValueExW(key, name, 0, REG_SZ,
                                  reinterpret_cast<const BYTE*>(value.c_str()),
                                  static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
    RegCloseKey(key);
    return r == ERROR_SUCCESS;
}

bool RegDeleteTreeSafe(HKEY root, const std::wstring& path) {
    return RegDeleteTreeW(root, path.c_str()) == ERROR_SUCCESS;
}

bool CurrentDllPath(std::wstring& out) {
    wchar_t path[MAX_PATH] = {0};
    GetModuleFileNameW(nullptr, path, MAX_PATH);
    std::wstring exe(path);
    const size_t slash = exe.find_last_of(L"\\/");
    out = (slash != std::wstring::npos ? exe.substr(0, slash + 1) : L"") +
          L"xime-tsf.dll";
    return PathFileExistsW(out.c_str()) != FALSE;
}

std::wstring GuidString(REFGUID guid) {
    wchar_t* s = nullptr;
    if (FAILED(StringFromCLSID(guid, &s)) || !s) {
        return L"";
    }
    std::wstring out(s);
    CoTaskMemFree(s);
    return out;
}

bool Install() {
    std::wstring dll;
    if (!CurrentDllPath(dll)) {
        fwprintf(stderr, L"找不到 xime-tsf.dll\n");
        return false;
    }

    const std::wstring clsid = GuidString(CLSID_XimeTextService);
    const std::wstring profile = GuidString(GUID_XimeProfile);
    const std::wstring clsidPath = L"CLSID\\" + clsid;
    const std::wstring inproc = clsidPath + L"\\InprocServer32";
    RegSetString(HKEY_CURRENT_USER, clsidPath, nullptr,
                 xime::tsf::kTextServiceDescription);
    RegSetString(HKEY_CURRENT_USER, inproc, nullptr, dll);
    RegSetString(HKEY_CURRENT_USER, inproc, L"ThreadingModel", L"Apartment");

    // TSF TIP 注册（HKCU 便于免管理员安装；系统范围部署可改 HKEY_LOCAL_MACHINE）。
    const std::wstring tipBase = L"SOFTWARE\\Microsoft\\CTF\\TIP\\" + clsid;
    RegSetString(HKEY_CURRENT_USER, tipBase, nullptr,
                 xime::tsf::kTextServiceDescription);
    const std::wstring langProfile = tipBase + L"\\LanguageProfile\\0x0804\\" + profile;
    RegSetString(HKEY_CURRENT_USER, langProfile, nullptr,
                 xime::tsf::kProfileDescription);
    RegSetString(HKEY_CURRENT_USER, langProfile, L"IconFile", dll);
    RegSetString(HKEY_CURRENT_USER, langProfile, L"LayoutFile", L"");
    RegSetString(HKEY_CURRENT_USER, tipBase + L"\\LanguageProfile\\0x0804",
                 nullptr, L"");

    fwprintf(stdout, L"Xime TSF 已注册（HKCU，当前用户）。请重新登录或重启 CTfFMON。\n");
    return true;
}

bool Uninstall() {
    const std::wstring clsid = GuidString(CLSID_XimeTextService);
    RegDeleteTreeSafe(HKEY_CURRENT_USER, L"CLSID\\" + clsid);
    RegDeleteTreeSafe(HKEY_CURRENT_USER,
                      L"SOFTWARE\\Microsoft\\CTF\\TIP\\" + clsid);
    fwprintf(stdout, L"Xime TSF 已注销。\n");
    return true;
}

} // namespace

int main() {
    int argc = 0;
    wchar_t** argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (!argv || argc < 2) {
        fwprintf(stderr, L"用法: xime-register.exe install|uninstall\n");
        if (argv) {
            LocalFree(argv);
        }
        return 1;
    }
    const std::wstring cmd(argv[1]);
    CoInitialize(nullptr);
    bool ok = false;
    if (cmd == L"install") {
        ok = Install();
    } else if (cmd == L"uninstall") {
        ok = Uninstall();
    } else {
        fwprintf(stderr, L"未知命令: %s\n", cmd.c_str());
    }
    CoUninitialize();
    LocalFree(argv);
    return ok ? 0 : 1;
}
