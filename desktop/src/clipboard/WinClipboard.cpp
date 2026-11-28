#include "WinClipboard.h"

#include <windows.h>

#include <cstring>
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

std::vector<uint8_t> BuildDib24(int width, int height,
                                const std::vector<uint8_t>& rgbTopDown) {
    std::vector<uint8_t> out;
    if (width <= 0 || height <= 0 ||
        rgbTopDown.size() < static_cast<size_t>(width) * height * 3) {
        return out;
    }
    BITMAPINFOHEADER bih = {};
    bih.biSize = sizeof(BITMAPINFOHEADER);
    bih.biWidth = width;
    bih.biHeight = height; // 正数 = bottom-up
    bih.biPlanes = 1;
    bih.biBitCount = 24;
    bih.biCompression = BI_RGB;
    const size_t stride = ((static_cast<size_t>(width) * 3 + 3) / 4) * 4;
    bih.biSizeImage = static_cast<DWORD>(stride * height);

    out.resize(sizeof(BITMAPINFOHEADER) + bih.biSizeImage, 0);
    std::memcpy(out.data(), &bih, sizeof(BITMAPINFOHEADER));
    uint8_t* pixels = out.data() + sizeof(BITMAPINFOHEADER);
    // DIB 存 BGR，且 bottom-up：源第 r 行写到 DIB 第 (height-1-r) 行。
    for (int r = 0; r < height; ++r) {
        const int dstRow = height - 1 - r;
        const uint8_t* src = rgbTopDown.data() +
                             static_cast<size_t>(r) * width * 3;
        uint8_t* dst = pixels + dstRow * stride;
        for (int c = 0; c < width; ++c) {
            dst[c * 3 + 0] = src[c * 3 + 2]; // B
            dst[c * 3 + 1] = src[c * 3 + 1]; // G
            dst[c * 3 + 2] = src[c * 3 + 0]; // R
        }
    }
    return out;
}

bool ParseDibHeader(const std::vector<uint8_t>& dib, int& width, int& height,
                    int& bitCount, size_t& dataOffset) {
    if (dib.size() < sizeof(BITMAPINFOHEADER)) {
        return false;
    }
    BITMAPINFOHEADER bih;
    std::memcpy(&bih, dib.data(), sizeof(BITMAPINFOHEADER));
    width = bih.biWidth;
    height = bih.biHeight;
    bitCount = bih.biBitCount;
    dataOffset = bih.biSize;
    if (dataOffset == 0 || dataOffset > dib.size()) {
        dataOffset = sizeof(BITMAPINFOHEADER);
    }
    return true;
}

bool WriteClipboardImage(const std::vector<uint8_t>& dib) {
    if (dib.empty()) {
        return false;
    }
    if (!OpenClipboard(nullptr)) {
        return false;
    }
    if (!EmptyClipboard()) {
        CloseClipboard();
        return false;
    }
    HGLOBAL mem = GlobalAlloc(GMEM_MOVEABLE, dib.size());
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
    std::memcpy(dst, dib.data(), dib.size());
    GlobalUnlock(mem);
    if (!SetClipboardData(CF_DIB, mem)) {
        GlobalFree(mem);
        CloseClipboard();
        return false;
    }
    CloseClipboard();
    return true;
}

std::vector<uint8_t> ReadClipboardImage() {
    std::vector<uint8_t> result;
    if (!OpenClipboard(nullptr)) {
        return result;
    }
    HANDLE handle = GetClipboardData(CF_DIB);
    if (handle) {
        const SIZE_T size = GlobalSize(handle);
        void* data = GlobalLock(handle);
        if (data && size > 0) {
            result.assign(static_cast<uint8_t*>(data),
                          static_cast<uint8_t*>(data) + size);
            GlobalUnlock(handle);
        }
    }
    CloseClipboard();
    return result;
}

} // namespace clipboard
} // namespace xime