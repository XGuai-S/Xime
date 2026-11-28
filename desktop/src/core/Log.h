// 轻量日志：写文件 + OutputDebugString，专为 TSF 输入法调试设计。
//
// 为什么必须要它：TSF DLL 被加载进 explorer/Word 等宿主进程，没有控制台，
// 出错时用户只看到"打不了字"，开发者无法得知是激活失败、部署未完成、
// 会话没建起来、还是按键被 KeyEatPolicy 放行。此前的日志只有 librime
// 自己的（且默认 0 字节），TSF 主链路一条记录都没有。
//
// 设计要点：
//   - 线程安全，追加写，每次调用立即 flush（崩溃前的最后一条不会丢）
//   - 单文件上限 4MB，超出自动轮转为 .1（避免长期运行无限增长）
//   - 路径固定 %LOCALAPPDATA%\Xime\logs\xime-tsf.log
//   - 默认开启；可用环境变量 XIME_LOG=0 关闭
#pragma once

#include <string>

namespace xime {
namespace log {

// 日志级别。Info 记录主流程（激活/会话/按键结果），Debug 记录每次按键细节。
enum class Level { Debug, Info, Warn, Error };

// 初始化：确定日志路径并创建目录。进程内只生效一次，可重复调用。
void Init();

// 完全关闭日志（读环境变量 XIME_LOG=0 时自动设置）。
void SetEnabled(bool enabled);

// 写一条日志。fmt 为 printf 风格；自动附加时间、线程 id、级别。
void Write(Level level, const char* fmt, ...);

// 便捷宏：默认只记录 Info 及以上，XIME_LOG=debug 时连 Debug 也记录。
void WriteDebug(const char* fmt, ...);
void WriteInfo(const char* fmt, ...);
void WriteWarn(const char* fmt, ...);
void WriteError(const char* fmt, ...);

} // namespace log
} // namespace xime
