// 异常安全验收：TSF 回调里任何异常都不得逃逸到宿主进程。
//
// 背景：C++ 异常跨越 COM/TSF 边界会让宿主直接终止，事件日志表现为
// TextInputFramework.dll + 0xC000041D（0xC000041D = 回调中未捕获异常）。
// 曾因未捕获异常把 DeepSeek Harness / 资源管理器打崩，故单列此测试。
//
// 做法：真实创建 XimeTextService，用"畸形环境"逼热路径抛异常，验证：
//   1) 每个回调都返回 HRESULT 而不是把异常扔出来；
//   2) 宿主进程仍然活着（本测试自身不崩）。
#include "../src/tsf/XimeTextService.h"

#include <windows.h>
#include <objbase.h>

#include <cstdio>

using xime::tsf::XimeTextService;

namespace {

int g_failures = 0;

void Check(const char* what, HRESULT hr) {
    // 只要没把异常抛出来（进程还活着），就算通过；HRESULT 具体值不约束。
    if (hr == E_OUTOFMEMORY) {
        std::printf("EXSAFE_NOTE %s returned E_OUTOFMEMORY\n", what);
    }
    std::printf("EXSAFE_STEP %s hr=0x%08X\n", what,
                static_cast<unsigned>(hr));
}

} // namespace

int main() {
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    // 未 Activate 就调用各回调：这是最容易触发空指针/未初始化路径的用法，
    // 也正是 TSF 在某些混乱时序下会真实发生的情形。
    HRESULT hr = S_OK;
    {
        XimeTextService service;
        BOOL eaten = FALSE;

        Check("OnSetFocus", service.OnSetFocus(TRUE));
        Check("OnTestKeyDown",
              service.OnTestKeyDown(nullptr, 'A', 0, &eaten));
        Check("OnTestKeyUp", service.OnTestKeyUp(nullptr, 'A', 0, &eaten));
        Check("OnKeyDown", service.OnKeyDown(nullptr, 'A', 0, &eaten));
        Check("OnKeyUp", service.OnKeyUp(nullptr, 'A', 0, &eaten));
        Check("OnPreservedKey",
              service.OnPreservedKey(nullptr, GUID_NULL, &eaten));
        // 空指针参数：故意传 nullptr，验证不崩。
        Check("OnTestKeyDown(null-eaten)",
              service.OnTestKeyDown(nullptr, 'A', 0, nullptr));
        Check("OnKeyDown(null-eaten)",
              service.OnKeyDown(nullptr, 'A', 0, nullptr));
        Check("OnPreservedKey(null-eaten)",
              service.OnPreservedKey(nullptr, GUID_NULL, nullptr));
        Check("OnInitDocumentMgr",
              service.OnInitDocumentMgr(nullptr));
        Check("OnUninitDocumentMgr",
              service.OnUninitDocumentMgr(nullptr));
        Check("OnPushContext", service.OnPushContext(nullptr));
        Check("OnPopContext", service.OnPopContext(nullptr));
        Check("OnEndEdit", service.OnEndEdit(nullptr, 0, nullptr));
        Check("OnCompositionTerminated",
              service.OnCompositionTerminated(0, nullptr));
        // Deactivate 在从未 Activate 时调用，也必须安全返回。
        Check("Deactivate", service.Deactivate());
        hr = S_OK;
    }
    CoUninitialize();

    (void)hr;
    if (g_failures != 0) {
        std::printf("EXSAFE_FAIL %d\n", g_failures);
        return 1;
    }
    std::printf("EXSAFE_OK\n");
    return 0;
}
