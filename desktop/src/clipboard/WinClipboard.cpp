#include "WinClipboard.h"

#include <windows.h>

#include <vector>

namespace xime {
namespace clipboard {

namespace {

std::wstring Utf8ToWide(const std::string& s) {
    if (s.empty()) {
        return L"";
    }
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(),
                                      static_cast<int>(s.size()), nullptr, 0);
    std::wstring out(static_cast<size_t>(n > 0 ? n : 0), L'\0');
    if (n > 0) {
        MultiByteToWideChar(CP_UTF8, 0, s.c_str(), static_cast<int>(s.size()),
                            &out[0], n);
    }
    return out;
}

std::string WideToUtf8(const std::wstring& w) {
    if (w.empty()) {
        return "";
    }
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(),
                                      static_cast<int>(w.size()), nullptr, 0,
                                      nullptr, nullptr);
    std::string out(static_cast<size_t>(n > 0 ? n : 0), '\0');
    if (n > 0) {
        WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()),
                            &out[0], n, nullptr, nullptr);
    }
    return out;
}

} // namespace

std::string ReadClipboardText() {
    std::string result;
    if (!OpenClipboard(nullptr)) {
        return result;
    }
    HANDLE handle = GetClipboardData(CF_UNICODETEXT);
    if (handle) {
        auto* data = static_cast<wchar_t*>(GlobalLock(handle));
        if (data) {
            result = WideToUtf8(std::wstring(data));
            GlobalUnlock(handle);
        }
    }
    CloseClipboard();
    return result;
}

bool WriteClipboardText(const std::string& utf8) {
    const std::wstring w = Utf8ToWide(utf8);
    if (!OpenClipboard(nullptr)) {
        return false;
    }
    if (!EmptyClipboard()) {
        CloseClipboard();
        return false;
    }
    const size_t bytes = (w.size() + 1) * sizeof(wchar_t);
    HGLOBAL mem = GlobalAlloc(GMEM_MOVEABLE, bytes);
    if (!mem) {
        CloseClipboard();
        return false;
    }
    void* dst = GlobalLock(mem);
    if (!dst) {
        GlobalFree(mem);
        CloseClipboard();
        return false;
    }
    memcpy(dst, w.c_str(), bytes);
    GlobalUnlock(mem);
    if (!SetClipboardData(CF_UNICODETEXT, mem)) {
        GlobalFree(mem);
        CloseClipboard();
        return false;
    }
    CloseClipboard();
    return true;
}

} // namespace clipboard
} // namespace xime
