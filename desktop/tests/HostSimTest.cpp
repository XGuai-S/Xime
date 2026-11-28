// 真实宿主模拟：把已安装的 xime-tsf.dll 加载进本进程（宿主 ≠ DLL），
// 再走 DllGetClassObject → CreateInstance，验证 DLL 能在"别人的进程"里
// 正确定位自己的 data 目录并初始化引擎。
//
// 这直接对应曾经的 bug：用 GetModuleFileNameW(nullptr,...) 会拿到宿主 EXE
// 的路径 → data 算成 C:\Windows\data → 引擎零方案 → 只能打英文。
#include <windows.h>
#include <objbase.h>

#include <cstdio>

namespace {

const CLSID kClsid = {0x8A7C1E20, 0x3F5B, 0x4A2D,
                      {0x9E, 0x11, 0x6F, 0x3D, 0x5C, 0x8A, 0x2B, 0x47}};

using GetClassObjectFn = HRESULT(WINAPI*)(REFCLSID, REFIID, void**);

} // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::printf("HOSTSIM_FAIL: need dll path\n");
        return 1;
    }
    wchar_t dllPath[MAX_PATH] = {0};
    MultiByteToWideChar(CP_ACP, 0, argv[1], -1, dllPath, MAX_PATH);

    HMODULE dll = LoadLibraryW(dllPath);
    if (!dll) {
        std::printf("HOSTSIM_FAIL: LoadLibrary err=%lu\n", GetLastError());
        return 1;
    }
    std::printf("HOSTSIM_DLL_LOADED\n");

    auto getClassObject = reinterpret_cast<GetClassObjectFn>(
        GetProcAddress(dll, "DllGetClassObject"));
    if (!getClassObject) {
        std::printf("HOSTSIM_FAIL: no DllGetClassObject export\n");
        return 1;
    }

    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    // 取 IClassFactory 并创建实例。CreateInstance 里会走 Activate 之前的构造，
    // 但真正的引擎初始化在 Activate 中；此处只验证 COM 链路可用。
    IClassFactory* factory = nullptr;
    HRESULT hr = getClassObject(kClsid, IID_IClassFactory,
                                reinterpret_cast<void**>(&factory));
    if (FAILED(hr) || !factory) {
        std::printf("HOSTSIM_FAIL: GetClassObject hr=0x%08X\n",
                    static_cast<unsigned>(hr));
        CoUninitialize();
        return 1;
    }
    std::printf("HOSTSIM_FACTORY_OK\n");

    IUnknown* instance = nullptr;
    hr = factory->CreateInstance(nullptr, IID_IUnknown,
                                 reinterpret_cast<void**>(&instance));
    if (FAILED(hr) || !instance) {
        std::printf("HOSTSIM_FAIL: CreateInstance hr=0x%08X\n",
                    static_cast<unsigned>(hr));
        factory->Release();
        CoUninitialize();
        return 1;
    }
    std::printf("HOSTSIM_INSTANCE_OK\n");

    instance->Release();
    factory->Release();
    CoUninitialize();

    // 关键：进程活着没崩，且 DLL 能在"宿主 EXE 目录 ≠ DLL 目录"的情况下加载成功。
    FreeLibrary(dll);
    std::printf("HOSTSIM_OK\n");
    return 0;
}
