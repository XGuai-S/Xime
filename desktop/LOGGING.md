# Xime 输入法调试日志查看

## 日志在哪

```
%LOCALAPPDATA%\Xime\logs\xime-tsf.log      ← 主日志（TSF 层：激活/会话/按键/选词）
%LOCALAPPDATA%\Xime\logs\xime-tsf.log.1     ← 上一份（超过 4MB 自动轮转）
%LOCALAPPDATA%\Xime\logs\xime.desktop.*.log  ← librime 引擎自己的日志
```

一条命令打开目录并跟读：

```powershell
# 实时跟读（PowerShell 7）
Get-Content "$env:LOCALAPPDATA\Xime\logs\xime-tsf.log" -Wait -Tail 40

# 只看错误
Select-String -Path "$env:LOCALAPPDATA\Xime\logs\xime-tsf.log" -Pattern 'ERROR|WARN' |
  Select-Object -Last 20
```

## 日志长什么样

```
[23:47:01.234][tid:12345][INFO ] Activate 开始 tid=12345
[23:47:01.234][tid:12345][INFO ] 路径 userDir=C:\Users\me\AppData\Local\Xime
[23:47:01.234][tid:12345][INFO ] 路径 dataDir=C:\Users\me\AppData\Local\Xime\ime\data
[23:47:01.290][tid:12345][INFO ] RimeEngine::Initialize = 1
[23:47:01.291][tid:12345][INFO ] IsDeployed = 1
[23:47:01.400][tid:12345][INFO ] EnsureSession: 会话已建立
[23:47:01.410][tid:12345][INFO ] Activate 完成（插件=无 语言栏=已注册）
[23:47:05.100][tid:12345][DEBUG] key 0x4E processed=1 preedit='n' commit='' cands=0 eat=1
[23:47:05.300][tid:12345][DEBUG] key 0x20 processed=1 preedit='' commit='你' cands=0 eat=1
```

## 日志级别

默认记录 **INFO 及以上**（激活、会话、部署、错误）。
要看**每次按键**的细节（debug 级），设置环境变量后重启宿主进程：

```powershell
setx XIME_LOG debug      # 之后重新登录或重启 explorer 生效
```

完全关闭日志：

```powershell
setx XIME_LOG 0
```

## 用日志排查常见问题

| 现象 | 在日志里找什么 | 结论 |
|---|---|---|
| 切换了但打不了字 | 有没有 `Activate 开始` | 没有 → TSF 没加载本 DLL，检查注册 |
| 同上 | `IsDeployed = 0` 且随后有 `触发部署` | 数据目录缺方案，或部署未完成 |
| 同上 | `EnsureSession: 仍无部署产物` | `data/` 与 DLL 不同级，或方案缺失 |
| 同上 | `EnsureSession: 部署进行中 → 放行本次按键` | 部署还没跑完，等一会儿再试 |
| 同上 | 有 `key 0x..` 但 `processed=0` | 引擎收到键但没处理，方案/按键映射有问题 |
| 同上 | 完全没有 `key 0x..` 行 | 按键没到 DLL（键被别的输入法/热键吃掉） |
| 只能打英文 | `IsDeployed = 0` 或 `dataDir=` 指向错误位置 | 共享数据目录没找到方案 |
| 打一个字就卡 | `EnsureSession` 反复出现 | 会话反复重建，检查引擎初始化 |
| 候选窗位置不对 | `DEBUG` 里看 preedit 是否为空 | 空 preedit 时取不到光标矩形 |
| 崩溃 | 最后几行 `ERROR` | 崩溃点前的最后操作 |

## 给开发者：代码里怎么加日志

```cpp
#include "../core/Log.h"

log::WriteInfo("会话已建立 schema=%s", schema.c_str());
log::WriteWarn("未部署 → 触发部署 start=%d", started);
log::WriteError("CreateSession 失败");
log::WriteDebug("key 0x%02X eat=%d", vk, eaten);  // 仅 XIME_LOG=debug 时输出
```

日志特点：
- **每次写立即 flush** —— 崩溃前的最后一条不会丢，这是排查崩溃的关键
- 线程安全（内部加锁），带线程 id，可分辨同一进程内的多个 TIP 实例
- 单文件 4MB 上限，超出自动轮转为 `.1`，长期运行不会撑爆磁盘
- 同时输出到 `OutputDebugString`，可用 DebugView / VS 输出窗口实时看
