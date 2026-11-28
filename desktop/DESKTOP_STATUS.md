# Xime 桌面版（Windows）状态报告

生成时间：2026-11-28　｜　分支：`desktop`　｜　基线：Android 3.4.70（`baseline-android-3.4.70` / `51eaf367`，保持不变）

## 一、构建与产物

```powershell
# 本机（MSYS2 mingw-w64）配置
$env:Path = "C:\msys64\mingw64\bin;" + $env:Path
cmake -S desktop -B desktop/build -G Ninja -DCMAKE_BUILD_TYPE=Release `
  "-DCMAKE_CXX_FLAGS_RELEASE=-O0 -DNDEBUG" "-DCMAKE_C_FLAGS_RELEASE=-O0 -DNDEBUG"
cmake --build desktop/build --parallel 8

# CI（windows-latest MSVC，Actions 恢复后自动）
cmake -S desktop -B desktop/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build --config Release
```

产物（`desktop/build/`）：

| 文件 | 说明 |
|---|---|
| `libxime-tsf.dll` | TSF 文本服务 DLL（静态链入 librime + T9 + lua/octagram/predict + QuickJS），约 40 MB |
| `xime-register.exe` | `install` / `uninstall`（HKCU 注册 CLSID + CTF TIP 语言配置） |
| `xime-smoke.exe` 等 25 个测试/冒烟程序 | 见下 |

## 二、验证结果（全量，实际执行）

- **全量构建**：32 个 CMake 目标，`BUILD_EXIT=0`
- **25 项测试/冒烟全绿**（`FAILS=0`）：

| 层 | 测试 | 关键输出 |
|---|---|---|
| 核心 | `xime-smoke` | `nihao`→5 候选→首候选`你好`上屏 |
| 核心 | `xime-t9-smoke` | T9 九键 `64426`→`你好`，面板状态填充 |
| 核心 | `xime-word-smoke` | `拟好`两上屏后置顶，显示位选词回引擎 |
| 核心 | `xime-page-smoke` | 每页 5→9；方案 pinyin↔wubi 切换 |
| 核心 | `xime-pageflip-smoke` | 每页1候选 `nihao`→「你好」→下一页「拟好」→回退 |
| 核心 | `xime-autocand-smoke` | 造词`小霞`→码`ihfn`→候选出现 |
| 核心 | `xime-schemadeploy-smoke` | 写 schema_list→部署→选中 t9_pinyin |
| 逻辑 | `xime-autoword-test` / `sc_test` | 造词算法与编码生成（对齐安卓用例） |
| 逻辑 | `keymap_test` / `xime-schemalist-test` / `xime-settings-test` | 键位、方案列表、设置往返 |
| 备份 | `xime-backup-test` | ZIP `PK`、往返字节一致、防路径穿越 |
| AI | `xime-ai-test` / `aiprotocol-test` / `aiservice-test` | STT 复用密钥/润色开关、JSON/协议、帮写+9 场景决策 |
| AI | `xime-aihttp-test` / `aihttp-winhttp-test` / `stt-test` / `voice-test` | Winsock/WinHTTP 回环真实收发、multipart/语音 |
| 剪贴板 | `xime-clipboard-test` / `xime-winclip-test` | 文本+图片 DIB、OTP 隔离、真实 OS 剪贴板 |
| 插件 | `xime-plugin-test` / `pluginmgr-test` / `plugincand-smoke` / `pluginfull-smoke` | 加载 main.js、目录链式、简单改★仍选中正确词、**完整契约 engineIndex+新增候选** |

## 三、已实现功能（对照安卓）

| 功能 | 状态 |
|---|---|
| librime 1.18.0 + T9 定制 + lua/octagram/predict 插件 | ✅ |
| 拼音/五笔/T9 打字、候选/翻页/选词上屏 | ✅ |
| 每页候选数（5..50）、方案切换/管理持久化、候选翻页 | ✅ |
| 上屏词频 + 候选重排（engineIndex 保选词正确） | ✅ |
| 自动造词（组合算法 + 编码生成 + 候选接入 + 上屏） | ✅ |
| 备份/恢复（本地 ZIP 白名单、防穿越） | ✅ |
| 统一设置存储（settings.json 原子写） | ✅ |
| AI：服务商配置、STT 复用密钥、润色开关、帮写 HelpWrite、OpenAI 协议、Winsock/WinHTTP 传输、服务门面、STT 上传、语音链路 | ✅ |
| 剪贴板：文本历史/置顶/OTP 隔离、快捷发送、图片 DIB、真实 OS 读写 | ✅ |
| 插件：内嵌 QuickJS、加载 main.js、transform 15ms 硬超时（死循环不卡输入）、**XimeTransformRequest/Response 完整契约（engineIndex 重映射 + 新增候选）**、目录管理、链式变换、TSF 启动加载 | ✅ |
| TSF COM 前端（文本服务/按键/组合/edit session/候选窗口/注册工具） | ✅ 编译链接通过 |
| AI 帮写面板 UI / 插件完整扩展点 / 实时同步 | ⬜ 待做 |

## 四、环境限制（如实记录）

1. **TSF 真机注入**：需交互式图形会话，headless 环境无法验证。DLL 编译链接通过，但未在真实应用中注册/输入。
2. **本地离线 ASR / 手写**：依赖 Windows x64 ONNX Runtime；仓库仅有 Android arm64 `.so`，无法构建。语音改走 AI 服务商 STT（已实测）。
3. **真实外网 TLS**：WinHTTP 路径已用本机 http 回环验证，未对真实服务商 https 端点实测（沙箱不连外网）。
4. **GitHub Actions**：该仓库为 fork，自 2026-11-28 起 Actions 不处理任何事件（workflow 已注册 active，`desktop-build.yml` 在 desktop 与 main）。恢复后 push/tag 即自动构建。

## 五、基线纪律

- 基线 commit `51eaf367`、tag `baseline-android-3.4.70` 永不移动。
- 桌面版所有改动只在 `desktop` 分支；Android 源码未被改动（除 T9 插件新增跨平台兼容头文件，仅 `_WIN32` 生效，Android 不受影响）。
- 打包按补丁版本递增，tag 与 versionName 一一对应。
