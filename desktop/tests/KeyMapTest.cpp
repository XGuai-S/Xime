// KeyMap 运行时验证：验证 VK→keysym 映射与修饰位。可独立编译（不依赖 librime）。
#include "../src/core/KeyMap.h"

#include <cstdio>

using namespace xime::core;

namespace {
int fails = 0;
void check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++fails;
    }
}
} // namespace

int main() {
    // 字母大小写
    check(ToRimeKey('A', false, false) == 'a', "A->a");
    check(ToRimeKey('A', true, false) == 'A', "shift A->A");
    check(ToRimeKey('A', false, true) == 'A', "caps A->A");
    check(ToRimeKey('A', true, true) == 'a', "shift+caps A->a");

    // 数字
    check(ToRimeKey('1', false, false) == '1', "1->1");
    check(IsRimeKey('5'), "digit is rime key");
    check(IsRimeKey('Z'), "letter is rime key");

    // 控制键
    check(ToRimeKey(0x08 /*VK_BACK*/, false, false) == 0xff08, "backspace->0xff08");
    check(ToRimeKey(0x0D /*VK_RETURN*/, false, false) == 0xff0d, "return->0xff0d");
    check(!IsRimeKey(0x10 /*VK_SHIFT*/), "shift not rime key");

    // 标点
    check(ToRimeKey(0xBA /*VK_OEM_1*/, false, false) == ';', "oem1->;");
    check(ToRimeKey(0xBA, true, false) == ':', "shift oem1->:");

    // 修饰位
    check(ToRimeMask(false, false, false) == 0, "mask none");
    check(ToRimeMask(true, false, false) == 1, "mask shift");
    check(ToRimeMask(true, true, true) == 7, "mask all");

    if (fails == 0) {
        std::printf("KEYMAP_TEST_OK\n");
        return 0;
    }
    std::printf("KEYMAP_TEST_FAILS=%d\n", fails);
    return 1;
}
