// 跨平台兼容垫片（仅 Windows 生效）。Android/Linux 构建不受影响。
// T9 定制插件最初在 Android 上开发，用到了 POSIX 的 localtime_r 与 mkdir(path,mode)。
#ifndef XIME_T9_PLATFORM_COMPAT_H_
#define XIME_T9_PLATFORM_COMPAT_H_

#if defined(_WIN32)

#ifndef _CRT_SECURE_NO_WARNINGS
#define _CRT_SECURE_NO_WARNINGS
#endif

#include <time.h>
#include <sys/stat.h>
#include <direct.h>

namespace rime {
namespace t9_compat {
inline int LocaltimeR(const time_t* t, struct tm* out) {
  return localtime_s(out, t) == 0 ? 0 : -1;
}
inline int Makedir(const char* path) {
  return _mkdir(path);
}
} // namespace t9_compat
} // namespace rime

// 宏放到全局作用域，匹配调用点的函数式写法。
#define localtime_r(t, tm) ::rime::t9_compat::LocaltimeR((t), (tm))
#define mkdir(path, mode) ::rime::t9_compat::Makedir((path))

#endif // _WIN32

#endif // XIME_T9_PLATFORM_COMPAT_H_
