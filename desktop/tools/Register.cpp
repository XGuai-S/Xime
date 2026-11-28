// xime-register.exe install|uninstall —— 正规注册/注销 Xime 为 Windows TSF 输入法。
// 需要管理员权限（写 HKLM 与 CTF TIP 目录）。
#include "../src/tsf/Guids.h"

#include <windows.h>
#include <msctf.h>
#include <objbase.h>
#include <shellapi.h>
#include <shlwapi.h>

#include <cstdio>
#include <string>

#pragma comment(lib, "shlwapi.lib")

namespace {

// 部分 TSF 类别 GUID 在 MinGW 头中缺失，这里补齐（Windows SDK 公开值）。
// GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT / UIELEMENTENABLED / SYSTRAYSUPPORT
static const GUID kTipCapImmersive = {
    0x13a016df, 0x560b, 0x46cd, {0x94, 0x7a, 0x4c, 0x3a, 0xf1, 0xe0, 0xe3, 0x5d}};
static const GUID kTipCapUIElement = {
    0x6c47465f, 0xd4a3, 0x4d48, {0x9d, 0x5b, 0xb9, 0x25, 0x5b, 0x3f, 0xa5, 0xc5}};
// {57F3AF1C-3F9F-4E1C-A3B0-3A1C2F0F4E5D} 是占位；真实值见下。
// GUID_TFCAT_TIPCAP_SYSTRAYSUPPORT = {25504FB4-7BAB-4BC1-9C69-CF81890F0EF5}
// （Win10/11 要在输入指示器里显示第三方输入法，这个分类是必需的）
static const GUID kTipCapSysTray = {
    0x25504fb4, 0x7bab, 0x4bc1, {0x9c, 0x69, 0xcf, 0x81, 0x89, 0x0f, 0x0e, 0xf5}};

constexpr LANGID kLang = MAKELANGID(LANG_CHINESE, SUBLANG_CHINESE_SIMPLIFIED);

bool RegSetString(HKEY root, const std::wstring& path, const wchar_t* name,
                  const std::wstring& value) {
    HKEY key = nullptr;
    if (RegCreateKeyExW(root, path.c_str(), 0, nullptr, REG_OPTION_NON_VOLATILE,
                        KEY_WRITE | KEY_WOW64_64KEY, nullptr, &key,
                        nullptr) != ERROR_SUCCESS) {
        return false;
    }
    const LONG r = RegSetValueExW(
        key, name, 0, REG_SZ, reinterpret_cast<const BYTE*>(value.c_str()),
        static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
    RegCloseKey(key);
    return r == ERROR_SUCCESS;
}

bool DllPath(std::wstring& out) {
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
    if (!DllPath(dll)) {
        fwprintf(stderr, L"找不到 xime-tsf.dll（应与本工具同目录）\n");
        return false;
    }
    const std::wstring clsid = GuidString(CLSID_XimeTextService);

    // 1) COM 类注册（HKLM\SOFTWARE\Classes\CLSID\...\InprocServer32）。
    const std::wstring inproc =
        L"SOFTWARE\\Classes\\CLSID\\" + clsid + L"\\InprocServer32";
    if (!RegSetString(HKEY_LOCAL_MACHINE,
                      L"SOFTWARE\\Classes\\CLSID\\" + clsid, nullptr,
                      L"Xime Input Method") ||
        !RegSetString(HKEY_LOCAL_MACHINE, inproc, nullptr, dll) ||
        !RegSetString(HKEY_LOCAL_MACHINE, inproc, L"ThreadingModel",
                      L"Apartment")) {
        fwprintf(stderr,
                 L"写注册表失败（需要管理员权限）。请以管理员身份运行。\n");
        return false;
    }

    // 2) TSF 输入处理器注册 + 语言配置。
    ITfInputProcessorProfiles* profiles = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr,
                                  CLSCTX_INPROC_SERVER,
                                  IID_ITfInputProcessorProfiles,
                                  reinterpret_cast<void**>(&profiles));
    if (FAILED(hr) || !profiles) {
        fwprintf(stderr, L"CoCreateInstance(InputProcessorProfiles) 失败: 0x%08X\n",
                 static_cast<unsigned>(hr));
        return false;
    }
    profiles->Register(CLSID_XimeTextService);

    const std::wstring desc = L"Xime 输入法";
    ITfInputProcessorProfileMgr* mgr = nullptr;
    if (SUCCEEDED(profiles->QueryInterface(
            IID_ITfInputProcessorProfileMgr, reinterpret_cast<void**>(&mgr))) &&
        mgr) {
        hr = mgr->RegisterProfile(
            CLSID_XimeTextService, kLang, GUID_XimeProfile, desc.c_str(),
            static_cast<ULONG>(desc.size()), dll.c_str(),
            static_cast<ULONG>(dll.size()), 0, nullptr, 0, TRUE, 0);
        if (FAILED(hr)) {
            fwprintf(stderr, L"RegisterProfile 失败: 0x%08X\n",
                     static_cast<unsigned>(hr));
        }
        mgr->Release();
    }

    // 3) 类别注册：键盘输入法 + 现代应用(immersive) + UI 元素。
    ITfCategoryMgr* cat = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_TF_CategoryMgr, nullptr,
                                   CLSCTX_INPROC_SERVER, IID_ITfCategoryMgr,
                                   reinterpret_cast<void**>(&cat))) &&
        cat) {
        cat->RegisterCategory(CLSID_XimeTextService, GUID_TFCAT_TIP_KEYBOARD,
                              CLSID_XimeTextService);
        cat->RegisterCategory(CLSID_XimeTextService, kTipCapImmersive,
                              CLSID_XimeTextService);
        cat->RegisterCategory(CLSID_XimeTextService, kTipCapUIElement,
                              CLSID_XimeTextService);
        // Win10/11：第三方输入法要在任务栏输入指示器里出现，必须有这个能力分类。
        cat->RegisterCategory(CLSID_XimeTextService, kTipCapSysTray,
                              CLSID_XimeTextService);
        cat->Release();
    }
    profiles->Release();

    fwprintf(stdout,
             L"Xime 已注册为 TSF 输入法。请注销并重新登录（或重启），"
             L"然后在「设置→时间和语言→语言→中文→选项」中添加 Xime，"
             L"用 Win+Space 切换。\n");
    return true;
}

bool Uninstall() {
    ITfInputProcessorProfiles* profiles = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr,
                                   CLSCTX_INPROC_SERVER,
                                   IID_ITfInputProcessorProfiles,
                                   reinterpret_cast<void**>(&profiles))) &&
        profiles) {
        ITfInputProcessorProfileMgr* mgr = nullptr;
        if (SUCCEEDED(profiles->QueryInterface(
                IID_ITfInputProcessorProfileMgr,
                reinterpret_cast<void**>(&mgr))) &&
            mgr) {
            mgr->UnregisterProfile(CLSID_XimeTextService, kLang,
                                   GUID_XimeProfile, 0);
            mgr->Release();
        }
        profiles->Unregister(CLSID_XimeTextService);
        profiles->Release();
    }
    ITfCategoryMgr* cat = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_TF_CategoryMgr, nullptr,
                                   CLSCTX_INPROC_SERVER, IID_ITfCategoryMgr,
                                   reinterpret_cast<void**>(&cat))) &&
        cat) {
        cat->UnregisterCategory(CLSID_XimeTextService, GUID_TFCAT_TIP_KEYBOARD,
                                CLSID_XimeTextService);
        cat->UnregisterCategory(CLSID_XimeTextService, kTipCapImmersive,
                                CLSID_XimeTextService);
        cat->UnregisterCategory(CLSID_XimeTextService, kTipCapUIElement,
                                CLSID_XimeTextService);
        cat->Release();
    }
    const std::wstring clsid = GuidString(CLSID_XimeTextService);
    RegDeleteTreeW(HKEY_LOCAL_MACHINE,
                   (L"SOFTWARE\\Classes\\CLSID\\" + clsid).c_str());
    fwprintf(stdout, L"Xime 已注销。\n");
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
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
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
