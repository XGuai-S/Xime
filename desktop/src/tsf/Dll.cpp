// TSF COM DLL entry and class factory. Exports DllGetClassObject etc. so that
// TSF can CoCreateInstance the text service.
#include "Guids.h"
#include "XimeTextService.h"

#include <windows.h>
#include <objbase.h>

#include <new>

namespace {

volatile LONG g_lockCount = 0;

class XimeClassFactory : public IClassFactory {
public:
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) {
            return E_POINTER;
        }
        *ppv = nullptr;
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        return E_NOINTERFACE;
    }

    STDMETHODIMP_(ULONG) AddRef() override {
        InterlockedIncrement(&g_lockCount);
        return 2;
    }

    STDMETHODIMP_(ULONG) Release() override {
        InterlockedDecrement(&g_lockCount);
        return 1;
    }

    STDMETHODIMP CreateInstance(IUnknown* pUnkOuter, REFIID riid,
                                void** ppv) override {
        if (!ppv) {
            return E_POINTER;
        }
        *ppv = nullptr;
        if (pUnkOuter) {
            return CLASS_E_NOAGGREGATION;
        }
        auto* service = new (std::nothrow) xime::tsf::XimeTextService();
        if (!service) {
            return E_OUTOFMEMORY;
        }
        const HRESULT hr = service->QueryInterface(riid, ppv);
        service->Release();
        return hr;
    }

    STDMETHODIMP LockServer(BOOL fLock) override {
        if (fLock) {
            InterlockedIncrement(&g_lockCount);
        } else {
            InterlockedDecrement(&g_lockCount);
        }
        return S_OK;
    }
};

} // namespace

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID reserved) {
    (void)hModule;
    (void)reason;
    (void)reserved;
    return TRUE;
}

extern "C" __declspec(dllexport) HRESULT __stdcall DllGetClassObject(
    REFCLSID rclsid, REFIID riid, void** ppv) {
    if (!ppv) {
        return E_POINTER;
    }
    *ppv = nullptr;
    if (rclsid != CLSID_XimeTextService) {
        return CLASS_E_CLASSNOTAVAILABLE;
    }
    auto* factory = new (std::nothrow) XimeClassFactory();
    if (!factory) {
        return E_OUTOFMEMORY;
    }
    const HRESULT hr = factory->QueryInterface(riid, ppv);
    factory->Release();
    return hr;
}

extern "C" __declspec(dllexport) HRESULT __stdcall DllCanUnloadNow() {
    return g_lockCount == 0 ? S_OK : S_FALSE;
}

extern "C" __declspec(dllexport) HRESULT __stdcall DllRegisterServer() {
    return S_OK; // registration handled by xime-register.exe
}

extern "C" __declspec(dllexport) HRESULT __stdcall DllUnregisterServer() {
    return S_OK;
}
