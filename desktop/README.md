# Xime 桌面版（Windows）

自研 TSF 原生输入法前端 + 定制 librime 引擎，目标是完整对齐安卓版功能。

## 分支与基线纪律

- 基线：commit `51eaf367`，tag `baseline-android-3.4.70`，分支 `baseline/android-3.4.70`（永不改动）。
- 桌面版只在 `desktop` 分支开发；每个功能点端到端验证通过后才推进下一个。
- 单次只做一个功能点，不在实现 A 时重构 B。

## 目标平台形态

- Windows 优先：自研 TSF（Text Services Framework）text service，注册为系统输入法。
- librime 核心复用 `app/src/main/jni/librime`（含 T9 定制），词库/方案复用 `app/src/main/assets/rime`。
- 后续再评估 macOS（IMK）与 Linux（IBus/Fcitx5），共用同一套 core。

## 目录结构

```
desktop/
  CMakeLists.txt          顶层构建（MSVC / windows-latest）
  cmake/                  librime 与依赖的 Windows 构建配置
  src/
    core/                 平台无关引擎层（librime 封装、T9、键位映射）
    tsf/                  TSF COM 前端（文本服务、按键接收、注册）
    ui/                   Win32 候选窗口 / 状态窗口
    settings/             配置持久化（JSON / 注册表）
    ai/                   LLM / STT（对齐安卓版服务商与润色开关）
    plugins/              QuickJS 插件宿主（后续里程碑）
    backup/               备份恢复（后续里程碑）
  tools/                  注册/卸载工具
  tests/                  单元测试
resources/                图标、清单、注册表脚本
```

## 里程碑（单功能点推进）

| # | 功能点 | 验收标准 |
|---|--------|----------|
| M1 | 打字闭环 | 注册后可切换；拼音/五笔/T9 输入出候选、选词上屏、切方案 |
| M2 | 设置与词库 | 设置界面、词频/自动造词、备份恢复 |
| M3 | AI | 多服务商密钥、STT 复用、文本润色开关、帮写 |
| M4 | 生态 | 插件系统、语音、剪贴板 |
| M5 | 多平台 | macOS / Linux 前端 |

## 构建

本机无 MSVC/Windows SDK，先用 GitHub Actions `windows-latest` 编译验证；本机工具链就绪后可本地复验。

```powershell
cmake -S desktop -B desktop/build -A x64
cmake --build desktop/build --config Release
```

注册（管理员）：`desktop/build/Release/xime-register.exe install`

## 已知边界

- 本机工具链：仅 Python 3.9 + Git；MSYS2/LLVM 安装中。
- 未做真机输入验证；CI 编译通过不等于系统输入法链路可用。
