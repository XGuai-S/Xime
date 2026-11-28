# Xime 桌面版（Windows）全面审计报告

审计时间：2026-11-28　｜　范围：`desktop/` 全部源码　｜　方式：逐文件通读 + 分级定责

本文档记录审计发现的问题、风险等级、修复状态。**审计只读**，所有修复均另起提交。

---

## 一、总体结论

| 模块 | P0 | P1 | P2 | P3 | 已修 |
|---|---|---|---|---|---|
| core 引擎层 | 1 | 7 | 14 | 8 | P0 全部 + P1 主要项 |
| AI/语音/剪贴板/插件/备份/设置 | 2 | 12 | 32 | 15 | P0 全部 + P1 关键项 |
| TSF/UI 层 | 见下方专节 | | | | 历史崩溃类已修 |

**已修复并加回归测试的问题共 9 类**，新增测试 6 个：
`xime-fuzz-test`（恶意输入）、`xime-limit-test`（资源限额）、`xime-space-smoke`（空格选词）、
`xime-exsafe-test`（异常安全）、`xime-pathresolve-test`（路径解析）、
`xime-langbar-test`（语言栏）、`xime-placement-test`（候选窗定位）。

---

## 二、已修复的 P0（会导致崩溃 / 数据损坏）

### P0-1 JSON 解析器无递归深度限制 → 栈溢出
- **位置**：`desktop/src/ai/Json.cpp` `ParseObject`/`ParseArray`
- **问题**：两者互相递归调用，无 depth 计数。输入 `"[[[[…"` 数万层即耗尽 C++ 栈。
- **影响面**：AI 响应体（对端可控）、`clipboard.json`、`settings.json` 三处不可信源。
  崩溃发生在 TSF 宿主进程内 —— **崩的是用户正在用的 Word/浏览器**。
- **修复**：加 `depth` 计数 + `kMaxDepth = 64`，用 RAII `DepthGuard` 保证所有
  return 路径正确配对。
- **验证**：`xime-fuzz-test` 喂 10 万层 `[` 与 5 万层 `{`，均安全拒绝；
  正常浅层 JSON 仍可解析。

### P0-2 ZIP 偏移校验 uint32 回绕 → 越界读崩溃
- **位置**：`desktop/src/backup/BackupArchive.cpp` `ReadZip`
- **问题**：中央目录偏移 `p` 与 `localOffset` 取自文件内容且为 `uint32_t`。
  校验式 `p + 46 > data.size()` 在 `p = 0xFFFFFFF0` 时回绕成 `46`，校验通过，
  随后按约 4GB 偏移读内存 → 必崩。
- **触发条件**：导入一个几十字节的构造 zip（中央目录偏移写 `0xFFFFFFF0`）。
- **修复**：偏移全部改用 `size_t`，边界判断改用**减法形式** `data.size() - p < 46`
  （先减后比，不会回绕）；同时补 zip-slip 过滤与 CRC 校验。
- **验证**：`xime-fuzz-test` 构造恶意偏移包 → 安全拒绝；
  `../escape.txt` 条目被跳过但同包合法条目仍可读；CRC 篡改条目被跳过。

### P0-3 词频表非原子保存 → 断电/崩溃即丢失全部词频
- **位置**：`desktop/src/core/WordFreqStore.cpp` `Save()`
- **问题**：原实现 `std::remove(path)` 后 `std::rename(tmp, path)`。
  两者之间崩溃/断电/进程被结束 → **旧文件已删、新文件还叫 `.tmp`**，
  而 `Load` 只认 `word_freq.txt` → 用户全部词频丢失。且无 `.bak` 回退。
- **放大因素**：每次上屏都调用一次 `Save()`，即每个词命中一次该窗口。
- **修复**：改用 `MoveFileExW(..., MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)`
  单步原子替换，失败时原文件保持不变；退化路径先备份 `.bak`。
- **验证**：`xime-word-smoke` 端到端通过（词频记录 → 落盘 → 重排生效）。

---

## 三、已修复的 P1（功能错误 / 明显泄漏 / 卡顿）

### P1-1 空格键完全未接入引擎（最严重）
- **位置**：`desktop/src/core/KeyMap.cpp`
- **问题**：`VK_SPACE` 既不在 `IsRimeKey` 也不在 `ToRimeKey`。
  `OnKeyDown` 里 `if (!IsRimeKey(vk)) return S_OK;` 直接放行 → 引擎永收不到空格。
- **后果**：① 无法用空格选中高亮候选（**中文输入最主要的选词键**）；
  ② 组合态下按空格把字面空格插进文档，而组合态仍留在引擎里，候选窗与文档错位。
- **修复**：`IsRimeKey` 加 `VK_SPACE`；`ToRimeKey` 加 `VK_SPACE → 0x20 (XK_space)`。
- **验证**：`xime-space-smoke` 输入 `nihao` 后按空格 → 提交「你好」而非字面空格。

### P1-2 修饰键掩码写错（Ctrl/Alt 错位）
- **位置**：`desktop/src/core/KeyMap.cpp`
- **问题**：代码写 `Ctrl=2, Alt=4`，而 librime 实际是
  `Shift=1, Lock=2, Control=4, Alt=8` → **把 CapsLock 当 Ctrl、把 Ctrl 当 Alt**。
- **修复**：改为 1/4/8，与 `librime/src/rime/key_table.h` 对齐。

### P1-3 插件宿主无内存上限 + 加载期无超时
- **位置**：`desktop/src/plugins/PluginHost.cpp`
- **问题**：① QuickJS 默认 `malloc_limit = -1`，插件可撑爆 TSF 宿主内存；
  ② `LoadFromSource`/`onLoad`/`onUnload` 完全不设超时，`main.js` 顶层写
  `while(true){}` 会让 `Activate` 永不返回 —— **每次切换输入法都卡死**。
- **修复**：`JS_SetMemoryLimit(16MB)`；加载与生命周期回调套用 2 秒超时。
- **验证**：`xime-limit-test` 实测死循环插件 **2000ms 内被中断**；
  512MB 分配被拦截；正常插件不受影响。

### P1-4 剪贴板淘汰用失效迭代器（UB）
- **位置**：`desktop/src/clipboard/ClipboardStore.cpp` `AddText`
- **问题**：反向迭代器 `erase` 后继续用 `rit` 比较/解引用 → 未定义行为，
  Debug 版直接断言崩溃。历史超过 200 条后**每复制一次就走到这段**。
- **修复**：改为下标正序收集待删项，再从后往前删除。

### P1-5 重复复制会清除置顶
- **位置**：同上
- **问题**：去重时整条 `erase`（无论是否置顶），再以 `pinned=false` 新建。
  用户置顶的常用语，再复制一次就掉出置顶区。
- **修复**：去重时记录原 `pinned` 状态并继承。

### P1-6 const 入口不取锁（数据竞争）
- **位置**：`desktop/src/core/RimeEngine.cpp` `HasSession`/`IsMaintaining`/`IsDeployed`
- **问题**：裸读 `rime_`/`session_`/`initialized_`，而这些字段在锁内被
  `Initialize`/`DestroySession` 改动；`IsMaintaining` 还被按键线程在 900 次
  轮询里反复调用，与部署线程并发。
- **修复**：对外入口加锁；**另拆出 `HasSessionLocked()` 供类内已持锁代码调用**
  （`std::mutex` 不可重入，否则自死锁）。

### P1-7 UTF-8 窄路径导致中文用户名下全面失效
- **位置**：`RimeEngine.cpp` `IsDeployed`、`WordFreqStore.cpp` 文件读写
- **问题**：Windows 的 `std::fstream(std::string)` 与
  `std::filesystem::path(std::string)` 按 **ANSI 代码页**解释窄字符，
  而上层一律传 UTF-8。中文用户名（`C:\Users\张三\…`）下路径全部错码。
- **后果链**：`IsDeployed()` 恒 false → 每次激活都重新部署 →
  `EnsureSession` 在按键线程 `Sleep(100)×900` 等待（**最长 90 秒冻结**）。
- **修复**：新增 `desktop/src/core/PathUtil.h`（`Utf8ToWidePath`/`WideToUtf8Path`），
  `IsDeployed` 与词频保存改用宽路径。

### P1-8 重复建会话泄漏 librime Session
- **位置**：`RimeEngine.cpp` `CreateSession`
- **问题**：直接覆盖 `session_`，不先销毁旧的，旧 Session（连同词典与组件）永久泄漏。
- **修复**：建立前先 `destroy_session`。

### P1-9 上屏热路径在引擎锁内做全表磁盘写
- **位置**：`RimeEngine.cpp` `ProcessKeyAndGetResult`
- **问题**：每次上屏都 `WordFreqStore::Save()`（遍历全表 + 排序 + 写文件），
  全程持有 `g_engine_mutex` → 阻塞所有引擎入口（含语言栏查 ascii 状态）。
  词库越大越卡。
- **修复**：改为脏标记 + 2 秒节流落盘；`DestroySession` 时强制补写。
  注意：词频在 `RecordCommit` 时已进内存表，**读侧重排立即生效**，
  推迟的只是"写文件"。

---

## 三·五、TSF 层第二轮修复（日志驱动）

加入 `xime-tsf.log` 后立刻暴露出 4 个此前完全看不见的缺陷：

### P0 组合文本永久残留在用户文档
- **位置**：`XimeTextService.cpp` `EndCompositionInSession`
- **问题**：`ITfComposition::EndComposition` 只移除组合属性，**不删除文本**。
  选词后 `InsertTextAtSelection` 再插入候选 → 文档里变成 `nihao你好`。
  Esc 取消组合同样把 `nihao` 落进文档。
- **修复**：结束组合前在同一 edit session 内把组合 range `SetText` 为空。

### P0 按键回调里 Sleep 最长 90 秒冻结宿主 UI
- **位置**：`EnsureSession`
- **问题**：`for (i<900) Sleep(100)` 等待部署完成。该函数由 `OnKeyDown`、
  `OnPreservedKey`、语言栏点击调用，全部跑在宿主 UI 线程 →
  首启部署期间 explorer/Word 被冻成"未响应"，用户只能强杀。
- **修复**：按键路径改为**非阻塞** —— 部署中或仍无产物时直接返回，
  放弃本次按键（不吞键），由用户下次按键自然重试。

### P0 单例会话被多个 TIP 实例互相销毁
- **位置**：`RimeEngine::CreateSession` / `DestroySession`
- **问题**：TSF 为每个激活线程建一个 `XimeTextService`，但引擎是进程级单例，
  `session_` 只有一份。实例 B 建会话会覆盖 A 正在用的；A 的 `Deactivate`
  又会销毁 B 的会话 → 文本可能进入错误文档。
- **修复**：会话改**引用计数**，最后一个实例销毁时才真正 `destroy_session`。

### P1 Deactivate 不撤销保留键、不结束组合
- **位置**：`Deactivate`
- **问题**：只 `SafeRelease(composition_)`，不结束组合（残留编码串）；
  且全仓**没有任何 `UnpreserveKey`** → Shift/F4 永久绑定在旧 clientId 上，
  重激活时 `PreserveKey` 返回 `TF_E_ALREADY_EXISTS` 被忽略。
- **修复**：`Deactivate` 里先 `EndComposition`（清空 range），
  再对两个 GUID 调 `UnpreserveKey`。

### 语言栏：平台限制（已确认为不可用）
`ITfLangBarItemMgr::AddItem` 在 Win10 19045 上恒返回 `E_FAIL`。
已系统性排除：vtable 12 槽布局（验证正确）、CLSID 6 种组合（含 `CLSID_NULL`
与微软拼音真实 CLSID）、5 种 `dwStyle`、`GUID_LBI_INPUTMODE`、
注册表 `Enable`/`ShowStatus=3`、`TF_CreateLangBarItemMgr`。
根因是 Win10 1809+ 的任务栏输入指示器由 `TextInputHost`（Windows 输入体验）
接管，不再渲染传统 `ITfLangBarItem` 项。

**不影响输入功能**：`Shift` 切换中英（保留键已确认注册成功 `hr=0`）、
`F4` 切换方案、`Win+Space` 切换输入法均正常。语言栏缺失仅是任务栏图标。

## 四、已修复的历史崩溃（本轮之前）
| 问题 | 症状 | 修复 |
|---|---|---|
| 15 个 TSF COM 回调无 `try/catch` | 宿主进程 `TextInputFramework.dll` + `0xC000041D` 崩溃（曾打崩 DSH 与资源管理器） | 全部加异常边界，`xime-exsafe-test` 锁定 |
| `pfEaten` 空指针解引用 | `0xC0000005` 访问违例（try/catch 兜不住） | 4 个回调入口统一判空 |
| `GetModuleFileNameW(nullptr)` 取到宿主 EXE 路径 | data 目录算成 `C:\Windows\data` → 引擎零方案 → **只能打英文** | 改用本 DLL 的 HMODULE |
| `IsDeployed` 只看目录存在 | rime 提前建空目录 → 误判已部署 → 永不重试 → 永远无候选 | 要求存在 `*.bin` 产物 |

---

## 五、待处理问题（按优先级）

### 高优先（建议下一轮修）

| 问题 | 位置 | 影响 |
|---|---|---|
| `engineIndex > 9` 选词静默失效 | `RimeEngine.cpp` `SelectCandidateByEngineIndex` | page_size > 10 时数字键/点击返回空结果、按键被吞、TSF 组合被结束但引擎仍在组字 |
| `SchemaListConfig::Write` 整文件覆盖 | `SchemaListConfig.cpp` | 抹掉用户 `default.custom.yaml` 里的其它 patch（标点/按键/方案自定义） |
| 词频表无上限增长 | `WordFreqStore.cpp` | 内存与文件无限膨胀，与热路径全表写形成正反馈 |
| `http://` 明文发送 Bearer 密钥 | `AiHttp.cpp` | 同网段嗅探即可拿到 API Key；无 TLS |
| `ProviderVault::SetKey` 无调用者 | `AiProviderConfig.cpp` | **AI 润色/帮写/STT 三条链路当前实际不可达** |
| 备份可整体覆盖 `settings.json` | `BackupService.cpp` | 恶意备份可改 `llm_base_url` 指向攻击者地址 |

### 中优先

- 剪贴板文本读取未以 `GlobalSize` 为界（越界读）
- HTTP 响应不解 chunked、无大小上限、截断响应当成功
- 录音停止时忽略 `WaitForSingleObject` 返回值（UAF 风险）
- `host.quickSend.list()` 每次按键重读整个 `clipboard.json`（热路径磁盘 IO）
- `connect()` 无超时（黑洞路由下阻塞数十秒，期间打不了字）
- OTP 隔离是死代码：验证码照常进普通历史、照常进备份、照常暴露给插件
- `Transform` 返回空数组 = 清空全部候选（插件写错即触发）
- 正常 ZIP（deflate 压缩 / 带注释）无法导入

### 低优先（代码质量）

- `JsonParse` 不拒绝尾随垃圾；`JsonEscape` 缺 `<cstdio>`
- `FormatDouble` 对 NaN/Inf 输出非法 JSON
- `RimeEngine.cpp` 有死变量 `rime::Service& service`
- `AutoWordStore` 与 `SchemeCodeIndex` 当前无生产调用方（已实现未接线）
- 三套不一致的「原子写」实现，建议抽公共函数

---

## 六、修复验证方式

```powershell
# 构建
$env:Path = "C:\msys64\mingw64\bin;" + $env:Path
cmake -S desktop -B desktop/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build --parallel 8

# 全量单元测试（26 个）
# 全量引擎冒烟（10 个，需传 data 与临时 user 目录）
```

当前状态：**26 单测 + 10 冒烟全部通过，FAILS=0**。
