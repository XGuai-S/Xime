# 版本记录

## 版本序列重置说明（2026-11-19）

**旧序列作废。** 从本文件起，版本号回到 **`3.4.0`** 重新开始递增，`3.3.29` ～ `3.11.47` 这一整段作为历史序列封存，不再延续。

选 `3.4.0` 而不是更大的数字，是为了把当年被跳过的那一段接回来：旧序列在 `3.3.32` 之后直接跳到 `3.11.12`，`3.4` ～ `3.10` 从来没存在过。现在从 `3.4.0` 起按正常节奏走，这些编号终于是"真的"了。

### 为什么要重置

旧序列有两处历史断裂，导致"应用里看到的版本号"与"git 里的版本"长期对不上：

1. `3.3.32` → `3.11.12` 之间跳过了 `3.4` ～ `3.10` 共 7 个次版本，发生在提交 `cd4ba79`（剪贴板搜索 + 翻译多引擎聚合 + AI 润色插件）。
2. versionCode 同时出现四处空洞，且**这些版本从未真实存在**（没有任何构建产物）：
   - `20261219` ～ `20261220`（对应 3.3.34 / 3.3.35）
   - `20261224` ～ `20261306`，共 83 个（对应 3.3.40 ～ 3.11.11）
   - `20261313`（对应 3.11.18）
   - `20261330`（对应 3.11.35）

这些空洞无法通过重新编号补回——历史提交里没有对应的构建。因此选择另起一条干净的序列，并给**每一个真实存在过的版本**补上 tag（共 41 个，`v3.3.29` ～ `v3.11.47`），使任何 APK 都能回溯到具体提交。

### 新序列的规则

- `versionName`：**从 `3.4.01` 起**，按 `3.4.02 / 3.4.03 … 3.4.99 → 3.5.00` 递增，不再跳号。
- **补丁位固定两位、且不用 `00`**（`3.4.01`，不是 `3.4.0` 也不是 `3.4.00`）：定宽后字符串排序即版本排序，避免 `3.4.10` 排在 `3.4.9` 前面；`00` 留空不用，`01` 才是序列起点。
- `versionCode`：从 `20261343` 起保持连续递增。**versionCode 不能回退**，否则无法覆盖安装。
- 每个发版提交都打对应 tag（`v3.4.01`、`v3.4.02` …），tag 与 `versionName` 严格一一对应。
- ⚠️ 新序列将来走到 `3.11.x` 时会与封存的旧序列**重名**（旧 `3.11.x` 已于 2026-11-19 封存）。届时的区分依据是 versionCode 与 tag 日期：新序列 versionCode ≥ `20261343`，旧序列版本 versionCode 均 ≤ `20261342`。
- 已安装旧序列（`3.11.47` 及更早）的设备可正常覆盖升级：versionCode 仍是递增的（`20261343`），只是 `versionName` 会从 `3.11.47` "降"到 `3.4.0`。这是刻意的——Android 只认 versionCode，安装不受影响；界面上显示的数字变小是版本序列重置的标志。

---

## 项目位置迁移（2026-11-23）

**源码从 `/sdcard` 迁到 `/root/work/Xime-3.4.09-source`。**

原因：`/sdcard` 是 FUSE 挂载，小文件 I/O 慢 54 倍（实测创建 500 个文件：
FUSE 2.72 秒 vs 原生盘 0.05 秒）。Gradle 要创建数万个小文件，这部分开销占了
release 打包的一大半。

实测收益：

| 场景 | 迁移前 | 迁移后 |
|---|---|---|
| 增量 debug 构建 | ~2 分钟 | **22 秒** |
| release 全量构建 | 14~22 分钟 | **5 分 33 秒** |

详见 `MIGRATION.md`。旧目录保留作冷备份，确认稳定后可删（释放 804MB）。

---

## 3.4.68（versionCode 20261412）

**耗电审计后的三项收尾：异常录音自清理、ONNX 共享线程池真正生效、AI 面板静置不跑动画。**

- 录音采集（读设备失败 / 写文件异常 / 外部取消）现在统一释放 AudioRecord、音频焦点、
  麦克风闸门与 WAV 文件句柄，并通过 `onCaptureFailure` 同步结束键盘语音与悬浮胶囊状态，
  避免面板悬挂、麦克风常亮与后台持续耗电；正常停止/取消链路不变。
- ONNX 联想、手写、离线语音的 session 统一调用 `DisablePerSessionThreads()` 并显式关闭
  空闲自旋，真正复用全局 2 线程池；此前只有 env 配置了线程数、session 仍各自建池。
- 军师雷达点、语音光晕只在录音/推演中脉冲，空闲态不再创建 `rememberInfiniteTransition` 时钟。
- 回归：新增 10 项专项/契约测试（修改前均失败、修改后通过）；全量单测 2563 项通过；
  release native（NDK 29 / CMake 4.0.2）编译通过，四个 ONNX 相关 .so 已裁剪更新。

---

## 3.4.67（versionCode 20261411）

**统一三个部署入口 + 修复增量部署后 session 未重建。**

- 设置页"输入方案→部署方案"改走 `ensureDeployment`（增量优先），与 oiz 命令、
  功能菜单"部署方案"一致——三入口都享受 librime 1.18.0 的并行/增量编译。
- 修复 oiz 命令 / 功能菜单共用的 `reloadConfig`：增量部署（full=false）后 native
  startMaintenance 不重建 session，旧 session 持有旧 prism/table 引用会导致
  "部署成功但候选旧/打不了字"；现部署成功后显式 endSession+ensureSession 再切方案
  （与设置页口径一致）。
- 契约测试清单同步（直接 .deploy() 从 5 处减为 4 处，设置页改走 ensureDeployment）。
- 验证：全量单测 2553 通过；修改前后 native gtest 192/195、端到端候选一致。

---

## 3.4.66（versionCode 20261410）

**修复 librime 1.18.0 user_dict 缓存缺陷（9777cb2）。**

- `user_dictionary.cc` 删除缓存分支外多余的 `DfsLookup`：此前缓存命中仍跑全表扫描，
  使 1.18.0 的用户词典内存缓存收益归零（且未命中时重复扫描）。现缓存真正生效。
- native 交叉编译重编 `librime_jni.so`（NDK 29 / CMake 4.0.2），JNI 64 方法齐全、librime 1.18.0 确认。
- 含 3.4.65 的第三轮审计 7 项 Kotlin 修复。

---

## 3.4.65（versionCode 20261409）

**第三轮审计 7 项修复（只明显 bug + 过度设计清理）。**

- M1：`pooledMedians` 按实际样本数分配数组，修复尾部 0 污染中位数（邻键纠错自校准失真）。
- M2：Android 13+ 请求 POST_NOTIFICATIONS 权限，悬浮胶囊前台服务通知可显示。
- M3：增量恢复 SHA 校验的文件流用 `.use{}` 关闭，修复常驻进程 fd 泄漏。
- M4：服务初始化协程透传 CancellationException，消除"Job was cancelled"假故障日志。
- L1：auto_word 缺失块时写回补齐全部字段，避免词典排除列表被静默重置。
- L2/L3：删除零逻辑转发空壳 AsrBackendFactory（8 处调用直连 AsrSupport）、
  删除从未调用的 ModelManager.initialize() 死代码。
- 验证：全量单测通过（2553 项）；修复前后 native gtest 192/195、端到端候选一致，无功能回归。
- 另附 librime 1.18.0 特性核查报告 `docs/librime-1.18.0-feature-audit.md`。

---

## 3.4.64（versionCode 20261408）

**长按展开后的条目内容卡片补齐立体感。**

- 修正 3.4.63 的理解偏差：未展开的列表条目保持原样（与其他按键一致，不加额外阴影）；
  真正缺立体感的是**长按展开后**显示条目内容的 `ContentCard`（此前 Surface 无 elevation）。
- 给 `ContentCard` 加 `shadowElevation = 8.dp`，与右侧菜单卡片一致。

---

## 3.4.63（versionCode 20261407）

**剪贴板条目立体感调整。**

- 长按弹出菜单改回只保留外层卡片立体，菜单条目行恢复平面（去掉条目自身的阴影/高光）。
- 左侧剪贴板/快捷发送条目卡片增强立体感（在双层高光基础上叠加 Material 阴影 6dp）。

---

## 3.4.62（versionCode 20261406）

**构建提速：R8 线程数实测调优。**

- 重新实测 R8 线程数（Ryzen 7 4800H，不看历史机器数据）：
  threads=1 → 205s，threads=2 → 167s，threads=4 → 172s；2 线程最快且内存安全（最低 1.6GB 空闲）。
  将 `systemProp.com.android.tools.r8.threads` 从 1 改为 2，R8 阶段省约 38s（18%）。

---

## 3.4.61（versionCode 20261405）

**菜单条目立体感 + 恢复词频重排 + 构建提速。**

- 长按弹出菜单的每个条目行补齐立体感（阴影 + 高光/压暗双层），不再只有卡片浮起而条目平整。
- 恢复词频重排：内置配置 `sort_by_freq` 与 `AutoWordConfig.sortByFreq` 默认值改回 true，
  候选顺序随上屏词频刷新（口径与注释统一）。
- 构建提速：打包命令不再强制 `--no-configuration-cache`，启用 gradle.properties 已有的
  configuration-cache（省配置阶段约 40 秒）；R8 单线程/4GB 堆的 OOM 防护配置保持不变。

---

## 3.4.60（versionCode 20261404）

**长按弹出的上下文菜单补齐立体感。**

- 剪贴板/快捷发送条目长按弹出的菜单卡片（置顶/编辑/删除、拆词/快捷/多选）此前为纯白平面；
  现加 Material3 阴影（shadowElevation 8dp）+ 色调层次（tonalElevation 2dp），与其他按键观感一致。

---

## 3.4.59（versionCode 20261403）

**剪贴板/快捷发送条目、方案选择、定制工具栏方框补齐立体感。**

- 剪贴板条目与快捷发送条目（含长按选中态方框）、菜单栏方案选择方框、定制工具栏方框
  统一补齐与键盘按键一致的阴影 + 高光/压暗双层立体感。

---

## 3.4.58（versionCode 20261402）

**修复部署方案后打不了字 + 剪贴板条目卡片立体感。**

- 修复部署方案后打不了字：部署会重建词库编译产物（prism/table），但旧 session 仍持有
  旧数据引用。现两个部署入口（输入方案页 / ImeSchemaController）成功后重建 session
  并恢复原方案，避免"部署成功但打不了字"。
- 剪贴板条目卡片补齐立体感（阴影 + 高光/压暗双层），与其它键盘按键观感一致。

---

## 3.4.57（versionCode 20261401）

**第二轮全维度审计修复（部署方案后打不了字 + 15 项新问题）。**

- 修复 H2（部署方案后打不了字的根因）：私有目录配置写入 `files/rime/xime.yaml`，
  但读取回退只读 `xime.custom.yaml`，导致无存储权限时部署的方案配置写完即被忽略、回退内置；
  现私有回退与可见目录同构（依次尝试 xime.yaml → xime.txt → xime.custom.yaml）。
- 修复 H1：长按删除候选按点击时快照的 text 在当前候选重定位，避免连打字时删错词/错拉黑。
- 修复 H3/H4/M8/M9/M10：多处 ASR 取消信号（CancellationException）不再被改写成成功/失败。
- 修复 M1：RimeEngine 选词/删词/切方案后作废快照缓存，避免下一键用旧编码拼码。
- 修复 M2：工具面板按键绑定编辑器代次，切框后旧任务不再向新框选词。
- 修复 M3/M4/M5/M6/M7：移除每键性能日志；截图 OCR 版本门槛 API30→34；
  URL 导入/市场下载加字节上限；按键日志按字节裁剪防超限。
- 验证：全量单测通过；修复前后 native gtest 192/195、端到端候选一致，无功能回归。
- 详见 `docs/XIME-AUDIT-REPORT-2.md`。

---

## 3.4.56（versionCode 20261400）

**全维度审计 11 项问题修复（4 high / 6 medium / 1 low）。**

- 修复 H1：`mergeSpacingOverrides` 从空 result 取值导致 custom 按键间距/圆角覆盖完全失效。
- 修复 H2/H3/H4：`serviceScope` 加 `CoroutineExceptionHandler` 兜底，插件/IO/ASR 协程异常不再
  冒泡杀死输入法进程（统一根因修复，覆盖工具面板轮询/词库落盘/流式转写）。
- 修复 M1：语音转写文本不再明文写 logcat，改为仅输出长度元数据。
- 修复 M2：无障碍后台启动麦克风前台服务失败改为记录日志，不再静默吞掉。
- 修复 M3：重新录音前关闭上一轮遗留 RandomAccessFile，避免 fd 泄漏。
- 修复 M4：`correction.schemas` 热重载戳改用实际生效配置文本，可见 xime.yaml 修改即时生效。
- 修复 M5/M6/L1：统一 `sort_by_freq` 配置为 false（与注释/代码默认一致）；阴影 elevation
  配置改为整数 1（与 Int 解析一致）；修正多处默认值注释。
- 验证：全量单测通过（274 项）；修复前后 native gtest 192/195 与端到端候选完全一致，无功能回归。
- 详见 `docs/XIME-AUDIT-REPORT.md`。

---

## 3.4.55（versionCode 20261399）

**手写键盘按键补齐立体感与按压效果。**

- 手写键盘右侧符号栏（，。？！删除）与底部功能键（符号/123/空格/ABC/换行）此前为
  纯色平面按钮；现自动适配主键盘同款立体感：阴影层（clip 前）+ 高光/压暗层
  （background 后）+ 按下缩放（keyPressScale），按压态随 pressedButton 实时变化。

---

## 3.4.54（versionCode 20261398）

**编辑键盘按压反馈精修 + 菜单卡片补齐立体感。**

- 编辑键盘方向大圆盘：整体阴影/高光不再随按压变化，只在按下的箭头象限块显示局部按压高亮；
  中心「选择」小圆按下时圆面变为按压色（accent 淡色）、投影内缩，松开恢复常态；
  取消态（选择中）未按下时叠加淡色高亮。
- 主菜单功能卡片（剪贴板/快捷发送/输入方案/表情/键盘调节/深色模式/悬浮模式/部署方案等）
  补齐立体感：与其它按键一致的阴影（clip 前）+ 高光/压暗（background 后）双层。

---

## 3.4.53（versionCode 20261397）

**编辑键盘方向圆盘补齐立体感。**

- 编辑面板（复制/粘贴/方向键/段首段尾）中间的圆形方向盘与「选择」小圆此前为纯白平面，
  与两侧有立体感的按钮不协调；现改用与其他按键一致的阴影 + 高光/压暗双层立体感，
  并给「选择」小圆补底部投影与顶部高光弧。

---

## 3.4.52（versionCode 20261396）

**全功能链路静态审查 + 省电修复 + 符号补齐口径修正。**

- 修复：子进程（:asr / :inference）不再执行 Rime 预初始化与自动备份轮询——此前它们在
  每个进程都加载 librime、部署词库、启动 12h 备份轮询，浪费内存/电量，并让多进程争写
  同一个 native_crash.log 导致崩溃日志丢失。
- 修正：T9 左栏符号补齐仅针对旧版 4 符号（，。？！）配置升级，尊重用户在 xime.custom.yaml
  的整表覆盖；恢复候选态音节均分布局，空闲态常用符号仍可滑动。
- 修复：备份恢复时若引擎未就绪留下的 pending 词库合并标记此前无人消费（死开关）——
  现于引擎就绪后补做 `mergePendingSnapshots` 并按结果清除标记，兑现"恢复后词库合并"承诺。
- 验证：拼音九键输入→候选→上屏六段链路、服务生命周期链路、设置开关链路均静态审查通过；
  全量单测通过。

---

## 3.4.51（versionCode 20261395）

**九键左栏与空格补立体感；左栏符号扩充并支持滑动。**

- 九键空格键、左侧符号栏改用与数字键一致的「阴影层 + 高光/压暗层」双层立体感实现
  （此前只有 1dp 淡阴影、无高光层，视觉上显得扁平）。
- 左侧快捷符号由 4 个（，。？！）扩充为 12 个常用标点（新增 、；：""（）…），
  超过 4 个后左栏可上下滑动取用，与数字键盘左侧一致。
- 从补丁版本递增（3.4.50 → 3.4.51），遵循「打包从补丁位升」的版本约定。

---

## 3.4.50（versionCode 20261394）

**librime 升级到 1.18.0，保留全部 T9 九键定制；启用 user_dict 内存缓存。**

- 将 `app/src/main/jni/librime` 从定制 fork（1.17.0 + T9）移植到官方 1.18.0，
  保留 6 项 T9 定制（音节图缓存、增量查询、增量造句、genuine 协议等）。
- 适配破坏性变更：glog → spdlog、CMake 3.25（升 4.0.2）、Boost 升级。
- 纳入官方 1.18.0 全部 10 项性能优化（词典编译提速/并行编译/O(log n) 迭代、
  poet 剪枝、level_db 共享缓存等）；user_dict 内存缓存经人工移植启用（带 T9 min_end_pos 守卫）。
- candidate 递归解包 Shadow（#1276）未纳入：与 fork genuine 协议语义冲突，且 Xime 不触发该 bug。
- 实测：词库部署快 2.5 倍、候选查询持平、候选结果一致；native gtest 192/195（3 项非 fork 回归）、
  WSL 端到端与缓存开关对比验证通过；Android 编译与 APK 构建通过。

**九键左栏/空格立体感与常用符号。**

- 左栏与空格此前只用单层淡阴影、缺高光/压暗层，视觉上扁平；改用与数字键盘一致的
  shadow+bevel 双层烘焙位图，补齐立体感。
- 左侧快捷符号栏从 4 个（，。？！）增至 12 个常用标点，超过 4 个自动可上下滑动取用。

---

## 3.4.49（versionCode 20261393）

**去掉网络请求路径上重复的 Keystore 解密。**

### 问题

`ExtensionManager.getConfiguredNetworkHosts` 用来解析「用户自定义服务器地址」的白名单，
它要**遍历插件的全部配置项、逐个做一次 AES-GCM 解密**才能把值取出来。

而这个函数位于网络请求的必经之路上：

- `HttpHostApiImpl` —— 每次 HTTP 请求
- `WsHostApiImpl` —— 每次 WebSocket 连接
- `SseUrlAuthorizer` —— 每次 SSE 连接

配置项越多、请求越频繁，这里就越像给每个请求额外加了一道 Keystore 运算。
密钥本身已有 `cachedKey` 缓存，重复的是**每个值的解密**。

### 修复

`PluginConfigStoreImpl` 增加解密结果缓存，**以密文原文为缓存键**。

选密文原文而不是 `key` 作键，是为了让缓存自动失效：外部（设置页、插件自身）改了
配置，密文必然变化，缓存自然不命中并重新解密。这样不会出现「改了服务器地址却被旧
白名单拦住」的陈旧数据问题，也不需要额外的失效通知机制。

- 容量上限 256 项（LRU），防止长期运行累积；
- 解密失败**不写缓存**，每次重试（GCM 认证失败可能是密钥暂不可用）；
- 只缓存解密后的配置值，不缓存密钥。

### 测试

新增 `PluginConfigStoreImplTest`（7 项），重点钉住最危险的失效路径：

- 同一密文重复读取只解密一次（证明缓存生效）；
- 写入后读回是新值且不额外解密；
- **外部改动密文后必须重新解密并返回新值，不能返回旧缓存**（防「配置不生效」）；
- 解密失败返回 null 且不写缓存、缺失键不触碰解密、`keys()` 反映底层存储；
- 缓存容量有上限。

变异验证：把缓存键从「密文」改成 `key` 后 3 项转红（含关键的陈旧值用例）；
去掉容量上限后 1 项转红。均已还原。

### 验证

- App JVM 全量 **2553 项 / 274 个测试套件 / 0 失败 / 0 错误 / 33 跳过**。
- `:app:assembleDebug` 成功；`git diff --check` 退出码 0。
- **未做真机验证**：设备离线，缓存带来的实际耗时收益未经 `atrace` 量化。
  这是性能优化，正确性由单测覆盖，但「快了多少」没有测量数据。

---

## 3.4.48（versionCode 20261392）

**修「自动顶屏顶的是次选」和「用了不更新排序」两个手感问题，并给它们补上防回退护栏。**

> 说明：本文件条目从 3.4.33 起有缺口（3.4.33 ~ 3.4.47 未逐版记录），
> 此处只记录本轮实际改动，不追溯补齐历史。

### Bug 1：满码顶屏没有顶候选栏首词，顶的是次选

用户反馈「自动顶屏没有顶候选栏的首码，顶的是次选」。

根因在引擎与 UI 的分工上：满码顶屏（`speller/auto_select`，小鹤等顶功方案）由 librime
**直接提交它自己顺序的首选**，而候选栏显示的是经手动顺序/词频**重排后**的顺序。
两者不一致时，你看到的首词被忽略，顶出去的是引擎顺序的第二条。

修法：新增 `resolveAutoCommitTop()` —— 当引擎提交的词**出现在按键前那一屏候选里**时，
改以那一屏的首词上屏。三道守卫缺一不可：

- `preKeyCodeLength >= 4`：编码没满时引擎提交的是**新算出来的更长编码**的词，
  拿上一屏首词替换会把还没打完的词顶出去（前缀命中会造成这种巧合）；
- `committed in displayedCandidates`：提交的词不在那一屏里就不动它（简繁转换等场景）；
- 英文模式不改写：英文候选不参与重排，引擎提交即所见。

主路径与工具面板/搜索框路径同口径修改。

### Bug 2：上屏后候选排序不更新

用户反馈「用了立刻刷新位置排序」但排序一直不动。

根因在编码记账：`commitCodeHint` 原来恒按「上一键编码 + 本键」拼接。但满码后的那一键是
**顶字上屏**，被顶出去的是上一键已成形的整段编码，本键字符属于**新一段**——
于是 4 码 `hckj` 被记成 5 码 `hckja`，4 码的词频永远涨不上去，排序自然不更新。

修法：新增 `resolveConsumedCode()` —— 编码已满（≥4）时返回上一键的编码本身，
未满时才是「上一键 + 本键」。返回值最终经 `markCandidateSelection` → `recordWordFrequency`
落到 `word_freq` 表，所以这是排序能否生效的唯一开关。

### 防回退护栏

这两个 bug 的共同点是**失效时不会有任何测试变红**：顶错词只是偶尔错一个字，
排序不更新只是慢慢不动，都不是崩溃。因此补了两层：

- 行为测试：`CandidateDisplayRulesTest` 增 14 项，含「编码未满不得用上一屏首词替换」
  「提交词不在那一屏时不得改写」「满码边界」等反例；
- 接线契约：新增 `AutoCommitWiringContractTest` 6 项，守「生产代码真的调了这两个函数」
  ——这种退化单测抓不到，只能靠源码契约。

护栏有效性经变异验证：分别注入「顶屏直接提交引擎值」「编码改回朴素拼接」「删掉三道守卫」
五种退化，确认对应用例转红后还原。

生产代码的易改点（两处热路径、两个函数文档、`codeHint` 参数说明、词频记账处）均写明
「不要改回什么、改回后的用户侧表现、由哪些测试守住」。

### 验证

- App JVM 全量 **2552 项 / 274 个测试套件 / 0 失败 / 0 错误 / 33 跳过**（`--rerun-tasks` 强制重跑）。
- `:app:assembleDebug`、`:app:assembleRelease`、`:plugin-core:compileDebugKotlin`、
  `:app:compileDebugAndroidTestKotlin` 全部成功。
- `git diff --check` 退出码 0。
- **未做真机验证**：构建期间设备离线，两处修复的实际手感需装到真机复测。

---

## 3.4.32（versionCode 20261376）

**修 3.4.31 归档没生效的真因：未启用方案的文件夹根本不存在。**

3.4.31 装到真机后，顶层仍有 43 个文件（预期 33），归档和清理都没跑成功。

### Bug 1：归档前没建文件夹 → 永远跳过

```kotlin
// 3.4.31 的写法
val dir = File(File(rimeDir, SCHEMES_DIR), folder)
if (!dir.isDirectory) { 跳过 }   // ← 未启用方案的文件夹从来不存在，全部跳过
```

而 `syncEnabledSchemeFolders` **只给已启用方案建文件夹**。
两边一配合：未启用方案的文件夹永远不存在 → 它们的配置和词库永远归不了档。

真机后果：`english` / `numbers` / `wubi98` / `wubi86_pinyin` / `wubi86_trad` /
`wubi86_trad_pinyin` 这 6 个未启用方案的 9 个文件一直躺在顶层。

已改为**文件夹不存在就创建**。

### Bug 2：垃圾文件判断漏了真实形态

```kotlin
// 3.4.31 的写法
junkSuffixes.any { n.contains(it) && !n.endsWith(".yaml") }  // xime.yaml.bak_width 漏掉
```

`xime.yaml.bak_width` 名字里含 `.bak` 但以 `width` 结尾，被 `!endsWith(".yaml")`
这个条件排除掉了（因为它确实不以 .yaml 结尾，但条件写反了逻辑）。

已重写为 `isJunkFileName` 纯函数，先看最后一段扩展名：
- `.log` / `.tmp` / `~` / `.bak` → 垃圾
- 含 `.bak_` → 垃圾（覆盖 `xime.yaml.bak_width`）
- **最后一段是 `yaml`/`yml`/`txt`/`lua` 一律不删**（保护 `data.bak.yaml` 这类）

### 真机链路完整核对

顶层 43 个文件的归属已逐个确认：

| 类别 | 数量 | 归属 |
|---|---|---|
| 方案定义 `.schema.yaml` | 16 | 5 个启用留顶层，6 个未启用归档 |
| 词库 `.dict.yaml` | 17 | 按引用关系跟方案走 |
| 全局配置 | 5 | `default.yaml`/`default.custom.yaml`/`trime.custom.yaml`/`squirrel.custom.yaml`/`user.yaml` 留顶层 |
| 全局/脚本 | 5 | `custom_phrase.txt`/`rime.lua`/`installation.yaml`/`symbols.yaml`/`Xime 数据表` |
| 垃圾 | 1 | `reverse_wildcard_debug.log` 清理 |

**注意**：`word_freq` / `candidate_order` / `auto_word` 是 Xime 运行时生成的用户数据表，
会跟随引用它们的方案进文件夹（小鹤音形XGuai 已确认包含）。

### 验证

- App 单测 **2355 项**、plugin-core **338 项**，均 0 失败
- `SchemaLayoutRealAssetsTest` **21 项**，新增：
  - **从未启用过的方案也能建出文件夹并归档**（这条直接对应本次 bug）
  - 垃圾文件名判断覆盖真机 4 个真实形态 + 5 个正常文件不误判
- `:app:assembleDebug` BUILD SUCCESSFUL

### 真机预期

顶层 43 → 约 28 个文件（9 个归档 + 1 个垃圾清理 + 之前已归档的）

---

## 3.4.31（versionCode 20261375）

**把 rime 顶层真正收拾干净：未启用方案归档、垃圾文件清理。**

3.4.30 的 schemes/ 已正确建出 5 个文件夹（真机验证过），但顶层还散着 **46 个文件**，
其中 9 个属于未启用的内置方案、4 个是垃圾文件。

### 1. 未启用方案的文件归档进 schemes/

真机上未启用的内置方案（english / numbers / wubi98 / wubi86_pinyin /
wubi86_trad / wubi86_trad_pinyin）的文件一直躺在顶层，只在设置页启用时才需要。

新增 `archiveDisabledSchemeFiles`：把这些文件移进各自的 `schemes/<方案名>/`。

**只移动、不删除**——方案本体完整保留，用户随时能重新启用。
新增 `restoreSchemeFromFolders`：重新启用时从 schemes/ 把文件链接回顶层
（不实现这个的话，归档过的方案点启用也不会生效）。

安全顺序：**先确保目标就位，再删源文件**。反过来一旦失败就丢方案。

### 2. 修掉与 assets 同步的死循环

`updateBuiltinAssets` 每次启动会把 assets 里的内置方案写回顶层。
如果这边归档、那边写回，就是无限循环（3.4.28 踩过）。

已让它**只同步已启用方案**的文件：未启用的内置方案跳过，不再复活。
非方案文件（default.yaml、symbols.yaml、*.lua）照常同步。

### 3. 清理垃圾文件

真机上实测存在：`xime.yaml.bak_width`（82KB）、`XGuai_flypy.schema.yaml.bak_dupkey`、
`probe_min.log`、`reverse_wildcard_debug.log`。它们不参与任何功能，
但让目录显得乱，还会被备份打包进去。

新增 `cleanJunkFiles`：只删 `*.bak*` / `*.log` / `*.tmp` / `*~`。
**不动任何 `.yaml` / `.txt` / `.lua` 和目录。**

### 真机预期效果

| 项目 | 现在 | 3.4.31 之后 |
|---|---|---|
| 顶层文件数 | 46 | **33** |
| 未启用方案文件 | 9 个散在顶层 | 归档进 schemes/ |
| 垃圾文件 | 4 个 | 0 |
| schemes 文件夹 | 5 个（已正确） | 5 个 |

### 验证

- App 单测 **2353 项**、plugin-core **338 项**，均 0 失败
- `SchemaLayoutRealAssetsTest` **19 项**，新增：
  - 未启用方案文件被归档、启用方案留在顶层、归档不是删除
  - **归档后重新启用能恢复回顶层**（这条最关键，漏了就是"点了启用不生效"）
  - 垃圾文件清理，且不误删正常 yaml / 配置
- `:app:assembleDebug` BUILD SUCCESSFUL

---

## 3.4.30（versionCode 20261374）

**按两条要求重做：文件夹只留已启用方案 + 每个文件夹包含全部引用。**

### 1. 只显示已启用的方案

新增 `syncEnabledSchemeFolders`：为**已启用**的 schema 建文件夹，
其余方案的文件夹一律删除。真机上（启用 5 个）就是 5 个文件夹，不再是 11 个。

### 2. 文件夹包含该方案引用的全部配置与词库

不再按「独占/共享」拆分，而是走**完整依赖闭包**：

```
schema 自身 → dictionary 引用 → 词典 import_tables 子词典
          → __include 的 symbols → opencc 配置 → lua 模块 → 方案 custom 补丁
```

用**硬链接**填充：`wubi86.dict.yaml` 被 5 个方案引用时，5 个文件夹里各有一个名字，
磁盘上只有一份 inode。既满足「文件夹完整」，又不会把几十 MB 词典复制 5 份。

### 3. 修掉一个隐蔽的编译陷阱

KDoc 里写了 `lua/*.lua`、`opencc/*.json`。Kotlin 的块注释**支持嵌套**，
其中的 `/*` 会开启一个嵌套注释，把后面整个文件都吞掉——
报错表现是「Unsafe readText / Missing '}' / Unclosed comment」一堆无关位置。
已改成不带 `/*` 的表述。

### 验证

- App 单测 **2350 项**、plugin-core **338 项**，均 0 失败
- `SchemaLayoutRealAssetsTest` **16 项**，新增：
  - 启用 2 个方案 → 只有 2 个文件夹（且名字是 简体拼音 / English）
  - 文件夹含全部引用：pinyin_simp 目录里有 schema + 主词典 + 扩展词典 + symbols
  - 切换启用后旧方案文件夹被删除
- `:app:assembleDebug` BUILD SUCCESSFUL

---

## 3.4.29（versionCode 20261373）

**继续用真机数据追查：词库为什么没归纳进去 + 11 个目录的来历。**

### 词库归纳不全的真因：`import_tables` 行尾注释

真机上的词库长这样（`od -c` 确认）：

```yaml
import_tables:
  - Flypy                     # 主词库(完整码表 23.3 万行)
  - XGuai_user                # 用户词库
  - LF                        # 两分单字
```

解析器把 `# 主词库...` 整段当成了资源名的一部分，于是
`Flypy  # 主词库(完整码表 23.3 万行)` 和文件 `Flypy.dict.yaml` 永远对不上，
**这些词典全部归不进方案目录**。

真机后果：`XGuai_flypy` 目录里只有 4 个文件，缺了 5 个词库
（Flypy / LF / XGuai_user / flypy.fast.symbols / XGuai_reverse）。

已修：`dictionary:` / `__include:` / `import_tables`（块写法与行内数组）一律先剥行尾注释。
`#` 出现在值中间（如颜色 `#FF0000`）不会被误剥——只有前面是空白才算注释。

### 11 个目录的来历

用 shell 查 APK 内置资产：

```
assets/rime/ 里的方案：english handwriting numbers pinyin_simp t9_pinyin
                      wubi86 wubi86_pinyin wubi86_trad wubi86_trad_pinyin wubi98
```

**10 个内置方案 + 用户装的小鹤音形XGuai = 11 个目录**，而只启用了 5 个。

### 顺带修掉一个自己引入的冲突

3.4.28 的「按启用方案镜像」会**删除未启用方案的文件**，但
`RimeConfigHelper.updateBuiltinAssets` 每次启动都会把内置方案**重新写回**根目录
（维护者决策：内置方案随 app 更新）。两边一个删一个加：

- 每启动一次就重写/删除一批文件，引擎反复判定 hash 失配
- `discoverSchemas` 扫顶层看不到它们 → 设置页里用户**无法重新启用**这些方案

已移除删除逻辑。现在的语义：

- **顶层保留全部已安装方案**（启用哪个由 `schema_list` 决定，这是 rime 的标准模型）
- `schemes/` 只做「一个方案一个文件夹」的归档与镜像，不干预启用状态

所以目录数 = **已安装方案数**（11），不是启用数（5）。想减少已安装方案，
在 设置 → 方案管理 里卸载不用的内置方案即可。

### 验证

- App 单测 **2347 项**、plugin-core **338 项**，均 0 失败
- `SchemaLayoutRealAssetsTest` 13 项，新增：
  - 带行尾注释的 `import_tables` 能解析出干净资源名
  - `dictionary:` 带注释时主词典仍归进方案目录
  - 已安装但未启用的方案必须留在顶层（删了设置页就看不到）
- `:app:assembleDebug` BUILD SUCCESSFUL

---

## 3.4.28（versionCode 20261372）

**用真机数据查出为什么「还是没生效」——两个真实 bug。**

3.4.26/3.4.27 的代码逻辑本身没错，但只在构造的测试数据上验证过。
拉到设备真实 rime 目录一比对，问题立刻暴露。

### Bug 1：`schemes/` 已存在就跳过迁移 → 新内容永远补不进去

```kotlin
// 旧写法
if (!File(rimeDir, "schemes").exists()) migrateLayoutToSchemes(rimeDir)
```
升级场景下 `schemes/` 是旧版本建的，于是迁移整段被跳过：
- 新增的方案配置（`X.custom.yaml`）永远补不进去
- 新增的词典依赖永远补不进去

真机证据：`pinyin_simp` 目录里只有 schema，而顶层明明有 `pinyin_simp.custom.yaml`。

已改为**每次都跑**（迁移本身是增量的，目标存在且大小相同就跳过）。

### Bug 2：幽灵方案目录一直没清

真机上实际只有 5 个方案，`schemes/` 里却堆了 **11 个目录**：

```
真实方案：XGuai_flypy  handwriting  pinyin_simp  t9_pinyin  wubi86
幽灵目录：english  numbers  wubi86_pinyin  wubi86_trad  wubi86_trad_pinyin  wubi98
```

这些是早期版本凭内置资产建出来的，之后方案被删了但目录留着。
害处：镜像时会把它们的文件当"有效方案文件"处理，根目录堆一堆没用文件。

新增 `removeGhostSchemeFolders`：目录里的 schema 既不在顶层、也不在启用列表 → 清掉。
**有保护**：已启用但顶层暂时缺失的方案不会被误删。

### 真机验证过的预期结果

| 项目 | 现状（3.4.26） | 3.4.28 之后 |
|---|---|---|
| schemes 目录数 | 11（含 6 个幽灵） | 5 |
| 目录命名 | `pinyin_simp` | `简体拼音` |
| 方案配置 | 0 个归纳 | 3 个（XGuai_flypy/pinyin_simp/t9_pinyin 的 .custom.yaml） |
| 词库 | 部分 | 独占词典跟随方案 |

真机显示名实测：`小鹤音形XGuai`、`手写输入`、`简体拼音`、`拼音九键`、`五笔86`。

### 验证

- App 单测 **2346 项**、plugin-core **338 项**，均 0 失败
- `SchemaLayoutRealAssetsTest` 扩到 **12 项**，新增：
  - 升级后补齐缺失文件（模拟旧版目录只有 schema+dict，验证 .custom.yaml 被补上）
  - 顶层无 schema 时**不删**旧目录（可能是唯一副本）
  - 幽灵目录清理（删 3 个顶层 schema 后验证清理数）
  - 已启用方案不被当幽灵误删
- `:app:assembleDebug` BUILD SUCCESSFUL

---

## 3.4.26（versionCode 20261370）

**方案文件夹补齐：纳入方案配置与词库，文件夹按方案名命名，按启用方案生效。**

3.4.25 只归纳了 schema 和部分词典，用户反馈「方案配置和词库都没有归纳进去」。

### 1. 文件夹用方案名命名

`schemes/五笔86/`、`schemes/简体拼音/`、`schemes/English/`……
取自 schema 的 `name:` 字段，清洗掉路径分隔符与非法字符后作目录名。
读不到显示名时退回 schema_id。

### 2. 纳入方案配置

`X.custom.yaml`（librime 的方案级 patch）现在归进对应方案文件夹。
**但 `default.custom.yaml` / `trime.custom.yaml` / `squirrel.custom.yaml` 是全局配置，
不归任何方案**——它们没有同名的 `.schema.yaml`，按同名规则自然排除。

### 3. 纳入词库

- 方案独占的词典（`english.dict.yaml`、`wubi98.dict.yaml`）进对应文件夹
- `import_tables` 引用链上的子词典（`wubi86_extra`、`pinyin_simp_ext`）跟着归属走
- 被 2+ 方案共用的（`wubi86.dict.yaml` 5 个方案用、`pinyin_simp.dict.yaml` 5 个、
  `symbols.yaml` 3 个）按约定**留根**——用户选了「只放专属文件（省空间）」

### 4. 「启用哪个方案就用方案里的东西」

新增 `mirrorEnabledSchemes`，按已启用方案决定根目录内容：
- 已启用方案的专属文件从 `schemes/<方案名>/` 链接到根
- **未启用方案**的专属文件从根移除（只在 schemes 里待命）
- 共享资源、全局配置、引擎产物不动

权威依据是 `schemes/` 目录内容，**不是**当前根目录——否则切换方案时
（A 切到 B）B 的文件已经被上一轮删掉，就再也链接不回来。

### 验证

- App 单测 **2342 项**、plugin-core **338 项**，均 0 失败
- `SchemaLayoutRealAssetsTest` 扩到 **8 项**，全部用真实内置资产：
  - 文件夹按方案名命名（五笔86 / 简体拼音 / English）
  - 方案文件夹含 schema + 方案配置补丁
  - 独占词典进目录、共享词典留根
  - 全局配置不被任何方案认领
  - 只启用 pinyin_simp 时，wubi98 的专属文件不占根目录
  - 从 pinyin_simp 切到 wubi98，根目录文件正确替换
  - 依赖解析含 dict 与 symbols
- `:app:assembleDebug` BUILD SUCCESSFUL

---

## 3.4.25（versionCode 20261369）

**修复 3.4.24 的「方案没有自动整理归纳」。**

### 根因：两个真实 bug

**1. 初始化顺序错误——首次安装永远建不出 schemes/**

`ensureSchemeLayout` 被放在 `copyAssetsToRimeDir` **之前**。首次安装时 `rime/`
还是空目录，资产没拷进来就没有任何文件可归类，`schemes/` 永远建不出来。
同步/异步两条初始化路径都受影响。

已挪到资产拷贝之后、引擎初始化之前。

**2. 归属判定漏了 `import_tables` 引用**

真实资产里词典之间会互相引用：
```yaml
# wubi86.dict.yaml
import_tables:
  - wubi86_extra
```
`wubi86_extra.dict.yaml` 不被任何 schema 直接引用，只被上面的 import_tables 引用。
原来的判定只认 `dictionary:` 和 `__include:`，这类词典一律「无人引用」→ 留根目录。
内置资产里 `wubi86_extra.dict.yaml`、`pinyin_simp_ext.dict.yaml` 都是这种，用户看到的就是"没整理"。

已支持：解析 `import_tables`（块列表与行内数组两种写法），并做**传递解析**
（顺着「schema → 词典 → import_tables 子词典」把归属传下去）。

### 现在的归纳效果（用真实内置资产验证）

- 10 个方案各建一个 `schemes/<id>/` 目录
- `english.dict.yaml`、`wubi98.dict.yaml` 等独占词典跟着进对应目录
- `wubi86.dict.yaml`（4 个方案引用）、`pinyin_simp.dict.yaml`（4 个）、
  `symbols.yaml`（4 个）等共享文件按约定留在根目录
- 顶层方案文件数量不变（引擎读顶层，少了就是方案丢失）

### 验证

- App 单测 **2340 项**（+6）、plugin-core **338 项**，均 0 失败
- 新增 `SchemaLayoutRealAssetsTest` 6 项，**用真实内置资产**跑，覆盖：
  - 真实资产能归纳出 ≥5 个方案目录
  - 每个方案目录里都有它自己的 schema
  - 独占词典跟随方案进目录（english、wubi98）
  - `import_tables` 传递链解析（wubi86_extra、pinyin_simp_ext）
  - 共享词典留在根，不进任何方案目录
  - 迁移后顶层方案数不变、镜像后顶层仍可列出
- `:app:assembleDebug` BUILD SUCCESSFUL

---

## 3.4.24（versionCode 20261368）

**rime 目录下的方案改成「一个方案一个文件夹」，不再散落平铺。**

### 为什么不能直接搬

librime 的 `schema_discovery` **只扫描 `rime/` 顶层**的 `*.schema.yaml`（C++ 里硬编码）。
把方案文件真搬进子目录，引擎就一个方案都加载不到——输入法直接没法用。

### 采用的布局

- `rime/schemes/<方案>/` 存该方案**独占**的文件（schema、专属词典）
- **共享文件留 rime 根**：被 2+ 方案引用的（如 `symbols.yaml` 被 4 个方案引用、
  `wubi86.dict.yaml` 被 3 个、`pinyin_simp.dict.yaml` 被 4 个）留在根目录，
  否则第二份会覆盖第一份的链接
- **镜像回根**：部署时把 schemes 里的文件硬链接到 rime 顶层（失败回退复制），
  引擎读的还是顶层，但内容与 schemes 里的源文件是同一份（硬链接天然同步）

### 具体改动

1. **新增 `SchemaLayout`**：路径/归属判定/镜像/反镜像/从顶层重建，纯函数优先便于单测
2. **首次启动自动迁移**：把已平铺的方案收进 `schemes/<id>/`。
   **只链接不删顶层**——引擎要读顶层，删了就没方案了
3. **导入走独立文件夹**：导入方案后自动整理布局
4. **备份排除 `schemes/`**：顶层那份已在包里，两份都收会让体积翻倍、
   恢复时出现「顶层 + schemes」互相覆盖的歧义
5. **同步排除 `schemes/`**：同理，避免同一内容在可见目录出现两次、互相覆盖
6. **备份恢复后自动重建**：恢复的包里没有 schemes/，由 `rebuildSchemesFromRoot`
   从顶层文件重建，否则用户看到的方案又变回散落状态

### 验证

- App 单测 **2334 项**（+18）、plugin-core **338 项**，均 0 失败
- 新增 `SchemaLayoutTest` 17 项，覆盖：
  - **核心不变量**：镜像后顶层必须能列出该方案（失效 = 一个方案都加载不了）
  - 镜像幂等、源文件变更后同步、子目录结构保留
  - 共享文件不归任何方案、引擎产物不参与
  - 路径穿越防护、从顶层重建幂等
  - 备份/同步都排除 schemes 的集成约束
- `:app:assembleDebug` BUILD SUCCESSFUL

---

## 3.4.23（versionCode 20261367）

**云备份改成文件级增量：只传变化的文件，不再每次重传完整包。**

### 为什么改

旧流程每次备份都生成完整 zip 整包上传：哪怕只改了三个自造词，也要重传几十 MB。
而且包内自造词来自 librime 导出的 `*.userdb.txt` 文本快照——备份前必须先触发引擎
`sync` 导快照，引擎没就绪就退化成"用旧快照"，恢复时还要再合并一次。

### 现在的行为

- **远端按单文件存储**：`rime/user.yaml`、`_xime_backup/settings.json`、
  `_xime_backup/clipboard/images/<sha>.png`……各存各的，另有一份
  `.xime-manifest.json` 记录 `路径 → SHA-256`。
- **只传变化**：每次备份比对哈希，内容没变的文件一个字节都不传。
  实测"什么都没改"时为 0 上传。
- **清单最后传**：中途失败时远端清单仍是旧的，下次会把缺的文件补上，
  不会出现「清单说有、文件没有」的残缺状态。
- **本地打包/恢复逻辑不变**：备份仍复用原有打包（枚举/排除规则一致），
  恢复则先按清单把文件下回来、重建成同样的 zip，再走原恢复路径——
  格式零风险，恢复语义与整包完全一致。
- **整包回退**：插件不支持文件级、或远端查询失败时，自动回退整包上传。
  老插件（3.0.x）不需要改代码，只是享受不到增量。

### 唯一的行为取舍

- 远端不再保留「历史版本快照」：文件级是**持续同步的镜像**，远端始终是最新状态。
  想要按时间点回滚的用户需要另存整包（设置页的「本机导出」仍生成完整 zip）。
- 插件升级到 3.1.0；旧 3.0.1 内置包已移除。

### 自动备份间隔

默认 `backup.auto_interval_hours` **12 → 24 小时**（按需求）。

### 验证

- App 单测 **2316 项**（+12）、plugin-core **338 项**，均 0 失败
- 新增 `IncrementalBackupTest`（11 项：diff 计划、清单往返/版本校验/脏行、路径穿越、SHA-256 向量）
- 新增 `BackupNoSnapshotTest`（4 项：守住快照机制不被加回来）
- 新增内置包契约：assets 里的 xipk 必须含文件级方法，防止"资产没重建"导致静默回退整包
- `:app:assembleDebug` BUILD SUCCESSFUL，APK 内含 `webdav-backup-3.1.0.xipk`

---

## 3.4.22（versionCode 20261366）

**堵住敏感内容剩余的三个绕过点。**

3.4.18 修了两个 P0 外泄面（快捷发送洗白、AI 面板直发），本轮把同一类漏洞的
**其余入口**全部补齐——敏感判定的原则应该是「所有外传路径都过闸」，不能只堵用户可见的那几条。

### 1. 快捷发送的手动编辑入口未拦截

`addQuickSend`（把历史条目提升为快捷）已拦，但另外两条写入口没拦：
- `addQuickSendItem`：用户手动新建快捷短语
- `updateQuickSendItem`：用户手动编辑快捷短语

快捷发送条目会随完整备份外传（`backupEntries()` 收 `isQuickSend` 行），
手动输入的密码/卡号同样会进明文 zip。两处已加同一把尺子。

### 2. 备份恢复不重判敏感类型

`ClipboardBackup.restore` 直接用备份包里记录的 `type`。
3.4.14 之前生成的旧备份包没有 `sensitive` 标记，恢复后密码/卡号变回普通文本，
再次进备份与云同步。已改为恢复时以**当前识别结果**为准。

### 3. 存量数据未回溯重判（升级用户的历史密码）

3.4.14 之前入库的敏感条目是 `type=text`，会照常进备份 zip 并推云同步。
新增启动时一次性重扫：取出所有 `type='text'` 条目重新识别，命中即改判 `sensitive`。

- 幂等：标记后不再是 `text`，下次扫不到
- 开销：本地正则无 IO，条目上限 200，实测可忽略
- 日志：补标数量会写进 xime 日志，便于用户确认

### 验证

- App 单测 **2304 项**（+1）、plugin-core **338 项**，均 0 失败
- `:app:assembleDebug` BUILD SUCCESSFUL

---

## 3.4.21（versionCode 20261365）

**独立终审后的收尾：3 项必修 + 2 项遗留。**

### 必修

1. **修正「读取永不被打断」的过度承诺**（注释 + CHANGELOG）
   淘汰保护窗口只有 90 秒，窗口过后字节回收仍会删文件。原表述不成立。
   已改为如实说明：覆盖「下载完立即读取」这类秒级读取，不覆盖「持有 File 超 90 秒再读」的极端形态。
   根治需引用计数（要求插件配合改协议），暂不做。

2. **`wordBlacklist` 补 `@Volatile`**
   `reconcileUserDataFiles` 在 IO 线程写它，而热路径 `ImeSessionController` 无锁读——
   是 3.4.20 把 reconcile 挪到 IO 线程时新引入的跨线程写。其他三张词表本来就有 `@Volatile`，漏了这一张。

3. **删除死标志 `candidateOrderDirty`**
   它只用于打日志，而退出兜底本就无条件执行，注释却称「让兜底一定执行」，自相矛盾。删除。

### 遗留一并处理

4. **淘汰文件占字节预算导致插件写入失败**（终审 P2）
   90 秒保护期内反复淘汰会把 `chargedBytes` 占满，所有插件写入抛 IOException。
   改为只保护「淘汰前不久还被 resolve 过」的文件——淘汰前就闲置很久的不可能有人在读，
   字节压力下直接回收。预算不会再被打满。

5. **`WordBlacklistStore.save` 返回值被丢弃**（终审 P3）
   它返回「实际写入的表」（内部走三方合并，可能带入手改内容），不采纳会让内存表与磁盘脱钩。
   已采纳并回写内存。

### 验证

- App 单测 **2303 项**、plugin-core **338 项**，均 0 失败（228 + 56 个测试类全量重跑）
- `:app:assembleDebug` / `:app:assembleRelease` 均 BUILD SUCCESSFUL
- 独立终审报告：`audit-r4/J-final-recheck.md`（10 项，3 必修已修、2 遗留已修、
  4 项判为安全无需改）

---

## 3.4.20（versionCode 20261364）

**修完 3.4.19 记录的两个未修项。**

### 1. 退出兜底不写候选排序 / 黑名单

`saveWordData()` 只覆盖 freq/temp/fixed 三张表（走 `wordTableSaver`），候选排序表与黑名单
平时靠各自的即时保存。用户最后一次拖动排序后立刻杀进程，那次改动就丢了。

新增 `saveRemainingWordTables()`，在 `onDestroy` 的兜底协程里补一次全量落盘
（幂等：内容没变时 Store 内部跳过写盘）。

### 2. BlobStore LRU 淘汰会打断正在进行的读取

插件 `resolve` 拿到 File 后是在**锁外**慢慢读的（512MB 慢网上传可达几分钟）。
淘汰时立刻删文件会让那个读中途失败——30 秒宽限期只保护「最近 resolve 过」的句柄，
对大文件根本不成立。

改为：**淘汰只摘句柄释放槽位，不删文件**，并登记 90 秒保护窗口——
窗口内 LRU 与字节回收都不碰该文件。窗口过后文件重新变为「未受保护」，
字节回收在压力下仍会删除它。

所以保证是**有限的**：覆盖「下载完立即读取」这类秒级读取，
不覆盖「拿到 File 后持有超过 90 秒再慢慢读」的极端形态。
要彻底消除需要引用计数（要求插件配合改协议），暂不做。

### 验证

- App 单测 **2302 项**、plugin-core **338 项**，均 0 失败
- `:app:assembleDebug` BUILD SUCCESSFUL

### 工作流调整

从本版起：**有问题只出 debug 包，全绿且确认干净后才打 release 包**。

---

## 3.4.19（versionCode 20261363）

**并发复核：修 4 处竞态，其中 2 处用户可直接感知。**

### 1. 拖动候选「拖了又弹回」（P2，用户可见）

`saveCandidateOrder` 的内存更新是同步的，但落盘协程里要做 `reconcile`（读盘比对手改，
几十毫秒）。若这段时间用户又拖了一次，主线程已写入新顺序，而协程随后用**过期 base**
算出的 merged 无条件写回 → 覆盖新顺序，表现为「拖了又弹回」。

拖动每次换位都回调，这个窗口命中率不低。修复：加内存表代次计数 + 引用比对双重校验，
检测到并发改动就放弃本次写回（新顺序已生效，下次拖动会带上它）。

### 2. 词库重载发布与排序写回不互斥（P2，数据覆盖）

`loadWordTablesOnce` 的「发布到内存」在 `withContext(Dispatchers.Main)` 里执行，
**不在 `wordTableIo` 统一 IO 域内**。而 `saveCandidateOrder` / `saveWordData` / `reconcile`
都在域内改内存表 —— 一次并发的排序写回就能把刚发布的表（恢复/手改结果）覆盖回旧值
并落盘，与「删除被复活」是同一类缺陷。

修复：用上面新增的代次计数覆盖这条路径（发布时递增，写回前比对）。

### 3. 插件误回收后永久失效（P2）

回收判定里 `lastUsedAtMs == 0L`（加载后从未取用）直接放行。但加载流程是
「先入 loadedPlugins，再 touchPlugin」，中间有窗口 —— 回收恰好跑到时会把**刚加载好**
的插件卸掉。

更严重的是：热路径（`getPluginInstance` / `getAllPluginInstances`）只读表、**不会 relaunch**，
所以一次误回收 = 该插件在本次进程内长期失效（表情面板空、ASR 静默降级、
候选变换静默失效），必须重启输入法才恢复。

修复：
- 新增 `loadedAtMs` 记录加载时刻，`last == 0L` 时也要求过完 `idleMs` 宽限期
- `getAllPluginInstances()` 全量取用时对所有返回插件 touch（表情/ASR/工具列表都走这里）

### 4. `candidateOrderTable` 缺 @Volatile（P3）

`wordFreqTable` / `autoWordTemp` 都有 `@Volatile`，唯独 `candidateOrderTable` 没有，
而 `ImeSessionController` / `ImeKeyRouter` 有多处无锁读。补上。

### 验证

- App 单测 **2302 项**、plugin-core **338 项**，均 0 失败
- `:app:assembleDebug` / `:app:assembleRelease` 均 BUILD SUCCESSFUL

### 未修（记录）

- **退出兜底不写 candidate_order / 黑名单**：`onDestroy` 的最终落盘只覆盖
  freq/temp/fixed 三张表，排序表与黑名单靠各自的即时保存。若最后一次拖动后立即杀进程，
  可能丢一次排序。概率低（拖动本身会触发保存），暂不扩范围。
- **BlobStore LRU 对大文件的删除竞态**：30 秒宽限期只保护「最近 resolve 过」的句柄，
  512MB 慢网上传可能超过 30 秒，期间被淘汰会读到已删文件。建议后续按 size/inUse 计数，
  本轮不改。

---

## 3.4.18（versionCode 20261362）

**隐私复核揪出 2 个 P0 外泄面 + 能效优化。**

### 🔴 P0-1：快捷发送绕过敏感判定，把密码洗成普通文本

`ClipboardDao.addQuickSend` 只挡 `TYPE_OTP`，插入时硬编码默认 `type=TEXT`。
于是 `TYPE_SENSITIVE` 条目被用户点「快捷」后变回普通文本 → `backupEntries()` 的
`IN ('text','image')` 立刻收录 → 进明文备份 zip、上传云端、还给插件读。

入口全是正常 UI（剪贴板面板的「快捷」按钮），用户恰恰最想把「常用密码/卡号」设成快捷——
这条路径等于把隐私防护整个绕过。修复：拒绝提升敏感条目，并显式携带来源 type。

### 🔴 P0-2：AI 工具面板的剪贴板兜底把敏感内容发给第三方

`collectToolPanelContext` 在无选区时裸读系统剪贴板 → 预填面板 + 作为 `inputText`
交给工具插件。ai-reply / ai-write / ai-polish / qwen-translate 全部声明 `allowCustomHosts=true`，
一次「生成」就发给第三方 LLM。

同类路径还有两处：`ClipboardHostApiImpl.getText`（插件拿到即可外传）、
`XimeAiHostBridge`（语音胶囊读剪贴板上下文进 AI 请求）。

修复：`getCurrentClipboardText(allowSensitive: Boolean = true)` 增加内容级闸门，
上述三处外发路径全部传 `false`；用户主动粘贴的路径保持默认 true。

### P1：银行卡正则贪婪吞数字导致漏判

旧正则 `(\d(?:[ -]?\d){12,18})` 是贪婪的：`4111111111111111 12/26` 被吞成
`4111111111111111 12`（18 位）→ Luhn 必然失败。「卡号+有效期+CVV」正是信用卡
最典型的粘贴形态，这条漏判面很大。

修复：改为「组边界」匹配（每组 4~6 位，组间单个空格/连字符，后面不能再跟数字组），
并加一次「去掉末组」的 Luhn 回退兜底。

### P1：JSON / YAML 形态的密码漏判

`PASSWORD_CONTEXT_REGEX` 在冒号后直接取非空白串，JSON 的 `"` 会被吃进值里。
修复：允许键和值两侧的引号，`{"password":"xxx"}` / `password: xxx` / `PASSWORD=xxx` 均能识别。

### 能效优化

- **热路径日志短路**：`avoidAutoSelect` 的日志在每键路径上做 6 次字符串拼接 + 装箱，
  而默认日志级别是 ERROR（`d()` 不落盘）——纯浪费。加 `isDebugEnabled()` 短路。
- **候选变换免分配**：`transformFor` 原来无条件 `result.candidates.toList()`，
  但绝大多数用户没装 candidate_transform 插件。加 `hasTransformPlugin()` 前置判断，
  无插件时直接返回，省掉每键一次数组→List 复制。

### 验证

- App 单测 **2302 项**（+2）、plugin-core **338 项**，均 0 失败
- `:app:assembleDebug` BUILD SUCCESSFUL
- 新增测试：银行卡后跟有效期/CVV、JSON password 字段

### 已知未修（记录边界）

- **存量数据**：3.4.14 之前入库的旧敏感条目没有回溯重判，升级用户的历史密码仍会进备份。
  需要一次 DB 迁移重扫，风险较高，留待单独评估。
- **云备份明文**：普通文本（非敏感）仍以明文上传，且 `DEFAULT_BACKUP_ENABLED=true`，
  设置页文案未说明「会上传第三方」。属产品决策项。
- **canSync 是死代码**：`LEGACY_REALTIME_SYNC_ENABLED=false` 写死，UI 无开启入口。

---

## 3.4.17（versionCode 20261361）

**对 3.4.14~3.4.16 全部改动的系统性复核：又修 6 个。**

这轮不再扩大审计范围，而是专门复核前三个版本的改动——因为 3.4.16 的教训是
「修复本身会引入新缺陷」，只读审计看不出来，必须逐行核对改动。

### 1. `UserConfigDir.writeText` 恒返回 true（最严重）

改成 `AtomicFileWriter.write` 后没 `return` 返回值 → 这个 Boolean 函数**永远返回 true**。
它的 6 个调用方都靠返回值判断成败，其中 `BackupManager.restoreUserDataFile` 据此决定
是否回退私有目录 → **回退分支永远不会触发**，可见目录写失败时恢复会静默丢数据。

### 2. `SyncManager` 的 installation id 写失败静默

`ensureInstallationFile` 丢弃 write 返回值仍 `return stableId`。写失败时 librime 会用
自生成 id 重建 `installation.yaml` → `sync/<id>/` 目录轮换、与 SP 里的 id 脱钩，
旧快照变孤儿；用户看到「同步成功」但换机后少词条。
修复：采纳返回值记 ERROR 日志，并在 `syncNow` 里校验文件内容，写失败直接中止同步。

### 3. `SchemaManifestManager` 写失败仍报成功

`saveRegistry` / `saveManifest` 调用方靠外层 `try/catch` 判定成败，原来 writeText 抛异常
才能触发 catch。改成返回 false 后 catch 永不触发 → 注册表/manifest 没写进去也返回 true。
修复：失败时抛 `IOException`，还原原有异常语义。

### 4. SSE 死 id 泄漏（P3，一直没修）

宿主 `newEventSource` 同步抛异常时，`onSessionAllocated` 已回调（id 已进 runtime 的
`activeSseSessions`），但会话表已回滚、不会再发 onDone/onError → 该 id 永久滞留到
runtime 关闭。修复：建连失败时从活动集合摘掉已分配的 id。

### 5. LRU 用墙钟导致淘汰失效

`PluginBlobStore` 的 `lastUsedMs` 用 `System.currentTimeMillis()`。用户改系统时间或 NTP
校正若跳到未来，该条目 `lastUsedMs > cutoff` 恒成立 → **永不被淘汰**（槽位泄漏）；
跳到过去则连带淘汰刚用过的条目。修复：改用 `SystemClock.elapsedRealtime()` 单调时钟。

### 6. Toast 用已销毁的 Service 作 context

按键队列满的提示投递到主线程队列，执行时 Service 可能已 onDestroy。
用已销毁 Service 作 Toast context 在部分 ROM 上会抛 `BadTokenException` 并泄漏它。
修复：改用 `applicationContext` + `runCatching`。

### 顺带修正

- 4 处日志把 `${'$'}{path}` 写成了字面量（不会展开路径）→ 改回 `${path}`
- `WordFreqStore` / `AutoWordStore` / `CandidateOrderStore` / `UserDataMerge` 的落盘失败
  补上日志：返回值语义是「内容是否变化」不代表写盘成功，不记日志无从排查

### 验证

- App 单测 **2300 项**、plugin-core **338 项**，均 0 失败（228 + 56 个测试类全量重跑）
- `:app:assembleDebug` BUILD SUCCESSFUL
- 独立子代理复核了 22 个原子写调用点（报告 `audit-r4/G-atomic-recheck.md`）：
  必须修 1 / 可接受 12 / 需加注释 1

---

## 3.4.16（versionCode 20261360）

**3.4.15 之后的自查修复：4 个真实缺陷。**

本轮对 3.4.14/3.4.15 的改动做针对性复核，发现并修掉 4 个问题——都是上一轮修复引入或遗漏的。

### 1. 写盘失败被静默吞掉（两处）

`AtomicFileWriter.write` 失败时返回 `false` 而**不抛异常**，这与原 `File.writeText`（抛异常）的语义不同。
3.4.14 修 BackupManager 时踩过一次，本轮又找到两处同类问题：

- **`RimeUserFileSync.writeBaseline`**：`runCatching {}.onFailure{}` 只在抛异常时触发，返回 false 时什么都不做 →
  基线清单写失败静默丢失 → 下次同步把「本地已删的词」当新增再推一遍。
- **`SchemaManager` 写 `default.yaml` 的 schema_list**：函数返回 Boolean 表示成功，但忽略 write 返回值，
  写失败仍返回 true → 调用方以为方案列表已落盘，重启后配置丢失却查不到错误。

### 2. 候选排序异步化后内存写没加锁

3.4.15 把 `saveCandidateOrder` 的落盘挪到 IO 线程时，`candidateOrderTable = merged` 这次内存写
**落在了锁外**（锁只包住了 reconcile）。而上屏热路径会并发读这张表——违反了本项目「内存表读写在
wordTablesLock 内」的约定。改为两次内存访问各自持锁，锁不包 IO。

### 3. 本地备份清理会删用户的同名文件

3.4.15 新增的「本地 Downloads 保留最近 5 份」只按 `DISPLAY_NAME LIKE 'Xime完整备份-%'` 过滤，
**没限定 OWNER_PACKAGE_NAME** → 用户从其它设备拷进 Downloads 的同名备份（不是本应用创建的）
也会被当成旧备份删掉。已加包名限定（API 29+）；API 28 降级路径无法按 owner 过滤，已注释说明。

### 4. BlobStore LRU 淘汰的读取竞态（记录边界，不改）

淘汰时若有插件已 `resolve` 拿到 File 但还没读完，文件会被删导致读失败。30 秒宽限期已把窗口压到很小
（正在读的句柄刚被 resolve 过、时间戳是新的），且失败表现为插件侧一次可重试的 IO 错误，不静默丢数据。
要彻底消除需引入「读取租约」，代价是调用方必须显式归还——不值得为这个概率加复杂度，已在代码里注释说明。

### 验证

- App 单测 **2300 项**、plugin-core **338 项**，均 0 失败（228 + 56 个测试类全量重跑，非增量缓存）
- `:app:assembleDebug` BUILD SUCCESSFUL

---

## 3.4.15（versionCode 20261359）

**继续修 P2：消除 ANR 面、资源泄漏与静默失效。**

承接 3.4.14 的 P1 修复，本轮处理审计报告里的 P2 级问题。

### 主线程阻塞（ANR 面）

- **onDestroy 同步落盘改为独立 IO**：`saveWordData()` 要拿锁做整份词表落盘
  （词频 + 造词 + 固定词 + 手改合并，冷盘可达数十~数百毫秒），原来在 `onDestroy`
  主线程直接调，表现为退出时卡顿甚至 ANR。改用独立 `NonCancellable + Dispatchers.IO`：
  不能复用 `serviceScope`（上面刚 cancel 过）。
- **`saveCandidateOrder` 落盘挪出主线程**：拖动候选排序会触发 reconcile（读盘比对手改）
  + 序列化 + 两次原子写，原来整段在主线程持 `wordTablesLock` 执行。改为内存更新保持同步、
  落盘进 `wordTableIo` 统一 IO 域（与 saveWordData/reconcile 互斥）。

### 资源泄漏

- **安装暂存目录清理**：原来只清 `plugin-uninstall-staging`，安装中断留下的
  `plugin-install-staging/install-*` 无人回收，每崩一次永久占一份磁盘（解压树 + previous 备份）。
  现每次安装前清理，且只清 10 分钟没动过的（避免误删进行中的流程）；
  暂存根若是符号链接则拒绝清理（安全）。

### 静默失效

- **插件空闲回收记账**（3.4.14 已修主体）：本轮补充 `dispatchEvent` / `findRuntime` 热路径
  的 touch 覆盖。
- **BlobStore 槽位加 LRU 淘汰**：32 个槽位是全插件共享池，某插件反复下载不 release
  就能占满，导致其它插件 download 全部失败。改为满了先按「最久未取用」淘汰 owned 句柄
  （30 秒宽限期保护刚注册的句柄，`callerOwned` 永不代删）。
- **按键队列满不再静默丢键**：`KeyJobQueue` 满（64）时丢新键只记日志，用户看到的是
  「按了没反应」。新增丢弃计数 + 阈值（8 次）+ 冷却（5 秒）后给一次 Toast 提示。
  不改按键语义：合并同键会改掉输入内容（`nn ≠ n`），同步执行会阻塞 UI 且打乱 FIFO 顺序。
- **插件请求超时加上限**：插件可传 `timeoutMillis` 且原无上限，传 600000 就能把该插件
  的 JS 线程（单线程 executor）阻塞 10 分钟，期间同插件的候选变换持续超时、SSE 回调排队丢失。
  现统一截断到 120 秒（与 SSE readTimeout 对齐）并记录日志。
- **MicCaptureGate 加进程归属断言**：闸门是进程内单例，当前键盘语音与悬浮胶囊同进程
  故有效；一旦有人把任一迁到独立进程，闸门会静默失效（两条 AudioRecord 都「转圈」但读全 0）。
  现首次占用时记录进程名，发现不一致写一条明确 ERROR，只告警一次。
- **手写引擎绑定失败给用户提示**：`:inference` 进程绑定/加载模型失败原来只有日志，
  用户看到「手写板写了没候选」。现 30 秒节流提示一次。

### 清理

- 删除 6 组半死键（`compact_mode` / `stt_debug_record` / `vibration_enabled` /
  `vibration_intensity` / `layout_pref_*` / `rime_assets_version`）：函数存在但全库无读取点，
  留着会误导后续维护者以为它们在生效。
- 删除死 UI 文件 `ClipboardSyncSettingsScreen.kt`：实时同步已被
  `ClipboardCloudPolicy.LEGACY_REALTIME_SYNC_ENABLED = false` 策略下线，改由统一完整备份承担，
  该页面已无导航入口。

### 未修（评估后跳过）

- **五张表非事务**：改成真事务需重写落盘架构，风险与收益不匹配。现在每张表都用原子写，
  单表不会损坏；跨表不同步的实际影响是恢复后短窗口内候选数据略旧，已由 3.4.14 的
  「恢复后触发重载」缓解。

### 验证

- App 单测 **2300 项**（+11）、plugin-core **338 项**（+1），均 0 失败
- `:app:assembleDebug` BUILD SUCCESSFUL
- 新增测试：`KeyJobQueueTest`（丢弃计数/阈值/冷却）、`MicCaptureGateTest`（进程变化告警/只告警一次）、
  `HandwritingBindFailureToastTest`（首次/节流/再提示）、`JsSseHostTest`（超时截断）、
  `PluginBlobQuotaTest`（LRU 淘汰/宽限期/callerOwned 保护）

---

## 3.4.14（versionCode 20261358）

**全盘链路审计后修复 5 个 P1（数据安全 + 隐私）。**

本轮对 6 条功能链路做了只读审计（输入主链路、词库备份、插件系统、剪贴板隐私、语音模型、设置接线），
确认 0 个 P0，修复 5 个 P1。

### 1. 用户词典写盘改为原子写（P1，最重要）

`File.writeText()` 是「truncate 后写」。输入法是常驻进程，随时可能被 LMK 杀掉；死在 truncate 之后
就留下半截或空文件，下次加载解析失败，**用户的词频、自动造词、候选排序全部清零**。

新增 `util/AtomicFileWriter`（同目录临时文件 → 写入 → fsync → rename），替换全库 20+ 处直写：

- `UserDataMerge`（词频/造词/黑名单的三方合并落盘）
- `WordFreqStore`、`AutoWordStore`、`CandidateOrderStore`（三张核心词表）
- `PersonalDictManager`（用户手写短语、custom.yaml patch，7 处）
- `BackupManager`、`RimeUserFileSync`、`SchemaManifestManager`、`SchemaManager`、`SyncManager`
- `UserConfigDir` 改为复用同一实现（原来只它自己有原子写，词典类全漏了）

顺带修掉一个真实缺陷：目标路径是目录时，`renameTo` 失败后回退的 `copyTo(overwrite=true)`
会先删目录再写文件——现在直接拒绝，避免误删。

### 2. 剪贴板内容级敏感识别（P1 隐私）

采集入口原先只看密码管理器打的 `IS_SENSITIVE` extra。用户从普通应用复制银行卡号、明文密码、
API key 时没有任何标记 → 照常入库 → 进备份 zip（明文）→ 推云同步。

新增 `clipboard/ClipboardSensitivity`，复用 AI 截图链路已有的 `LocalPiiRedactor`
（带 Luhn 银行卡校验、GB 11643 身份证校验位、Gitleaks API key 正则）识别高风险凭据。
命中标记为 `TYPE_SENSITIVE`：**本机仍可粘贴**，但排除备份与云同步（外泄面才是要堵的）。
手机号/邮箱/地址等可还原类别不拦，避免正常复制被误伤。

### 3. 备份不再包含敏感剪贴板（P1 隐私）

`ClipboardBackupCodec` 的过滤从「只排除 otp」扩展为 `OtpClipboardPolicy.canBackup`，
otp 与 sensitive 都不进备份 zip；恢复入口也拒绝伪造的 sensitive 条目。
云同步闸门 `canSync` 同口径。

### 4. 插件空闲回收记账修复（P1）

3.4.09 新增的「插件空闲回收」实际**对启动加载的插件完全无效**：
`loadEnabledPlugins` 不调 `touchPlugin` → `lastUsedAtMs` 为 0 → 被回收条件 `last > 0L` 排除。

反向问题：`CandidateTransformCoordinator.findRuntime` 与 `dispatchEvent` 直接遍历
`loadedPluginsFlow` 拿 runtime，绕过 `getPluginInstance` 的 touch → 每键都在用的变换插件
和纯事件驱动的插件（如打字统计）`lastUsedAtMs` 永不更新，可能在活跃使用时被误回收。

修复：`loadEnabledPlugins` / `launchPluginLocked` / `reloadPluginLocked` 成功即记账；
`dispatchEvent` 投递成功记账；`findRuntime` 命中记账；回收条件改为 `last == 0L || 超时`
（无记账视为从未取用，仍可回收）。

### 5. 备份恢复后触发词库重载（P1）

恢复是「绕过 save 直接写盘」：`AutoWordStore.dataVersion` 不会自增，运行中的输入法服务
据此判定「词库没变」→ 不重载 → 用户恢复完立刻打字看到的仍是旧候选。

新增 `AutoWordStore.invalidateForExternalChange()`，在 `BackupManager.afterRestore` 调用，
让下个会话重载内存表。

### 验证

- App 单测 **2289 项**（新增 16），plugin-core **337 项**，均 0 失败
- `:app:assembleDebug` BUILD SUCCESSFUL
- 新增测试：`AtomicFileWriterTest`（7 项，含崩溃残留临时文件、多字节内容、目录目标拒绝）、
  `ClipboardSensitivityTest`（7 项，含银行卡 Luhn、密码上下文、API key、超长文本、手机号不误伤）、
  `OtpClipboardPolicyTest` 扩充（sensitive 不可同步/备份）

---

## 3.4.13（versionCode 20261357）

**清理过度设计，回到最小实现。**

前几轮为了修 bug 引入了几个抽象，回头看属于过度设计，本版删掉：

- **删 `WordBlacklistSaver`（26 行）**：它只是 `wordTableIo.io { 读内存表 → 写 }` 的
  重复抽象，和 `WordTableIoScheduler` 职责重叠。黑名单落盘直接并入
  `XimeInputMethodService.saveWordBlacklist()`，行为不变（仍在 IO 域内重新读内存表）。
- **`WordTableIoScheduler` 62 → 40 行**：删掉从未被生产代码调用的
  `snapshot()` 与 `snapshotPersistPublish()`（后者只被测试用过）。只留真正需要的 `io()`。
- **重载快照从「五张表整份深拷贝」改为「只存数据版本号」**：`WordTablesLive` 的
  order/freq/temp/fixed/blacklist 五个字段**除 `version` 外从未被读取**，
  等于每次开会话都把数十万条词频/造词记录深拷贝一遍再丢掉。
  守卫真正需要的只是「读取期间生词库是否被改动」→ `AutoWordStore.dataVersion()` 一个 Int 即可。
  这是纯粹的浪费，删掉后每次会话少一次全量深拷贝。

验证：App JVM 单测全绿、plugin-core 全绿、`assembleDebug` 成功。测试从 2276 → 2273 项
（删除的是随死代码一起移除的用例，覆盖不变：黑名单串行化的 5 条不变量改由等价局部实现验证）。

---

## 3.4.12（versionCode 20261356）

**回到最简单的做法：直接用系统 Toast。**

3.4.10 加了键盘顶部提示条，3.4.11 又加了依赖「显示在其他应用上层」权限的系统浮层。
用户明确要求：**跟输入法界面里的 Toast 一样用系统 Toast 就行**。

本版改动：

- **移除** `OverlayHint`（系统级浮层实现）与 `InputUIState.transientHint`
  （键盘内提示条），以及配套的 `showDeployFeedback()`、`TRANSIENT_HINT_MS`、
  `KeyboardView` 顶部提示渲染。
- 部署/重载提示统一改回项目里通行的写法：
  `Toast.makeText(service, "...", Toast.LENGTH_SHORT).show()`
  ——与「工具面板不支持在悬浮/横屏模式下使用」「已填入验证码」等提示完全同款
  （Service 上下文，不用 `applicationContext`）。
- 保留唯一的实质性改进：**Toast 在 `requestHideSelf(0)` 之前提交**。键盘收起后
  Service 失去前台窗口，Android 11+ 对后台应用的 Toast 有限制，顺序错了就看不到。

验证：App JVM 单测全绿、plugin-core 全绿、`assembleDebug` 成功。

---

## 3.4.11（versionCode 20261355）

**提示要「收起键盘也能看到」——改用系统级浮层。**

3.4.10 把部署提示放到了键盘顶部，但用户指出：真正需要提示的时刻恰恰是**键盘已经收起、
人已经留在别的应用里**，键盘内提示条此时也一起消失。系统 Toast 又会被 Android 11+ 的
后台策略静默丢弃，两条路都走不通。

本版改动：

- 新增 `OverlayHint`：用 `WindowManager` + `TYPE_APPLICATION_OVERLAY` 直接挂一个
  **系统级窗口**，与应用是否前台无关——键盘收起、切到微信/浏览器后仍可见。
  - `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE`：纯展示，绝不抢输入焦点、不拦截操作。
  - 2 秒后自动隐藏；同实例复用，连续提示只换文字。
  - 无「显示在其他应用上层」权限时静默降级（只记日志），不抛异常、不影响部署本身。
- `showDeployFeedback` 改为三级降级：**系统浮层 → 键盘内提示条 → 系统 Toast**
  （调用方仍补 Toast，能弹的时候用户更好感知）。
- `onDestroy` 显式 `dismiss()`，避免 WindowManager 持有已销毁 Service 的窗口。

验证：App JVM 单测全绿、plugin-core 全绿、`assembleDebug` 成功。

---

## 3.4.10（versionCode 20261354）

**在别的应用里点「重新部署」没有任何提示——系统把 Toast 吞了。**

用户反馈：键盘在别的应用里弹出时，输入候选栏的「重新部署」候选项，以前有弹窗，现在没有了。

日志实测（`XimeInputMethodService: reloadConfig: show deploy toast, inputViewShown=true`）证明
**Toast 代码确实执行了**，而且当时键盘可见，但屏幕上什么都没有。原因是
**Android 11（API 30）起会静默丢弃后台应用的系统 Toast**——输入法 Service 不是前台
Activity，`Toast.show()` 不抛异常也不显示。同时确认该顺序问题在 HEAD 版本里已存在，
不是近期改动引入的。

本版改动：

- **双通道提示**：新增 `InputUIState.transientHint` + `XimeInputMethodService.showDeployFeedback()`，
  部署/重载结果除了系统 Toast 之外，额外显示在**键盘顶部**（2 秒自动消失）。
  只要键盘还在显示，这条提示一定可见，不看系统脸色。
- **Toast 顺序修正**：`appToast()` 在 `requestHideSelf(0)` **之前**提交——键盘收起后
  Service 立即失去前台性，之后再 show 必被拦。
- **提示上下文**：改用 `applicationContext` 构造 Toast，避免持有部署期间可能已被销毁的
  Service；失败不再静默吞掉，改为写日志。
- **诊断日志**：触发部署时记录 `inputViewShown` 状态，便于后续定位提示是否可见。

同批修复（全盘检查第三轮）：

- **词库无限重载**：H04 的「读盘前 flush」会让 `dataVersion` 自增，若收尾落盘再 bump 一次，
  `loadedAutoWordVersion` 永远追不上 → 每个输入会话都全量重读四张词库（打字卡顿、日志刷屏）。
  改为重载流程末尾对齐到当前版本号。
- **跨方案单字表兜底是死代码**：`AutoWordConfig.charDict` 默认值非空，导致「方案主词典兜底」
  永远走不到；切到五笔/拼音后造词仍按小鹤字码拼码。新增族别判断（flypy/wubi/pinyin）。
- **造词配置变更不失效缓存**：`char_dict` / `char_dict_by_schema` 住在 `xime.yaml`，
  但方案词典快照只跟踪 `*.dict.yaml`，改配置后缓存照样命中，必须重启输入法才生效。
  已把 `xime.yaml`（可见 + 私有两侧）纳入快照。
- **nofreq 切断组合造词链**：`XGuai_user` 里的单字（如「雨」）上屏时直接 `return`，
  把 `advanceComboHistory` 一起跳过 → 再打「衣」拼不出「雨衣」。改为只跳过词频写入。
- **卸载插件暂存目录永不清理**：进程在「改名暂存」与「真删暂存」之间被杀会留下
  `plugin-uninstall-staging/`，插件文件既不算已安装也不会被删。改为每次安装/卸载前顺手清理。
- **`VoiceController.dispose()` 不释放麦克风**：`recorder` 是懒加载单例，服务销毁只取消
  协程不释放 AudioRecord，麦克风与音频焦点一直被占。改为同步等待录音真正停下（3 秒超时兜底）。
- **Blob 错误体读取粒度**：`request(2KB)` 实测会拉满一个 okio segment（8KB），
  注释已如实说明；对外可见读取量仍严格 ≤ 2KB。

验证：App JVM 单测全绿、plugin-core 全绿、`assembleDebug` 成功。

---

## 3.4.06（versionCode 20261350）

**「打 `hck` 还能打出 `好看`」的两层原因，以及彻底的口径。**

用户问：`好看` 的码是 `hckj`，为什么打 `hck` 还能选到它？两层：

1. **引擎行为（改不了，也不该改）**：rime 的 `table_translator` 按**前缀**给候选——输入 `hck`
   会把所有以 `hck` 开头的词条都列出来，这正是小鹤简码能用的前提。
2. **我上一版的排序改动（已撤回）**：3.4.05 为了「短码也能吃到长码的权重」做了前缀聚合，
   结果 `好看` 在 `hckj` 名下累积的 40 分被算进了 `hck` 的查询里，把它顶到首位。
   这属于「编码不对应却被排上去」，用户明确否掉。

本版改动：

- **排序侧**：`collectFrequency` 改回**只认编码完全一致**的记录，短码不再吃长码权重。
- **记录侧**：新增 `canonicalCodeOf` / `resolveCommitCode`——上屏词若是词典词，一律记**该词
  的词典编码**（打 `hck` 选中 `好看` → 记 `hckj`），自造词才用按键码。
- **造词侧**：词典里已有的词**不再进造词库**；即便进来也强制用词典编码，不会再产出
  「好看 hck」这类条目。

结果：`好看` 仍会出现在 `hck` 的候选里（引擎前缀匹配），但不再被词频提前，也不会再往
词频/造词库写错编码。要让它**完全不出现**只能关掉前缀匹配（废掉简码体验），需另行确认。

## 3.4.05（versionCode 20261349）

**词频记录真实的「上屏编码」，并让短码查询也能吃到长码的权重。**

现象（用户实测）：`好看` 的码表编码是 `hckj`，按 `hckj` 打上屏之后，`word_freq.dict.yaml` 里记的是

```
好看	hck	29
```

——少了最后一位。同一个词的 `豪阔`（`hcko`）、`好康`（`hckh`）也都被记成了 `hck`。

根因：小鹤音形开了「满码唯一自动上屏」（`auto_select_pattern: ^\w{4}$`）。第 4 个键触发上屏的
**同一瞬间**，`candidateState` 还停留在上一个键刷新出来的 3 码状态，而 `recordWordFrequency`
正是从这个 UI 快照里取编码的 —— 于是 `hckj` 被记成了 `hck`。词频挂错编码后，以后按 `hckj`
打反而拿不到自己累积的权重。

修复两半：

1. **记录侧（按键路径）**：按键前先读引擎缓存编码，`真实编码 = 按键前编码 + 本键字符`，
   作为 `markCandidateSelection(word, codeHint)` 的提示传入；`recordWordFrequency` 取
   「标记编码」与「UI 编码」中**更长且互为前缀**的那个。
2. **查询侧（`CandidateOrderStore.collectFrequency`）**：权重按**前缀聚合**而不是精确匹配。
   rime 给候选本来就是前缀匹配（打 `hck` 会带出 `hckj` 的词），所以查询也要同口径；
   同一个词在多个码长下都有记录时**取最大值**（不是累加——同一词用两种码长各打一次，
   不等于打了两次短码）。

历史数据不用迁移：老记录是 `hck`、新记录是 `hckj`，前缀聚合会把两者都算进 `hck` 的查询结果。

## 3.4.04（versionCode 20261348）

**native_crash.log 不再有歧义，也不会被悄悄删掉。**

原状：该文件由 `rime_jni.cc` 的信号处理器以 `O_APPEND` 写入，为空时无法区分「handler 装好了但没崩过」和「handler 根本没装上」；而 `FileLogger.cleanOrphanedLogs()` 会删掉超过 5MB 的非 `kime_` 日志，一旦崩溃风暴把文件顶到 5MB 就会被删除——而 C++ 侧仍持有旧 fd，后续崩溃记录会写进一个已 unlink 的 inode，等于把最关键的证据静默丢掉。

- 安装 handler 成功时写一行 header：`=== native crash handler installed pid=N at YYYY-MM-DD HH:MM:SS ===`。
  之后文件仍为空 = 确实没崩；只有崩溃记录没有 header = 本次进程没装上 handler。
  ⚠️ 该改动在 `rime_jni.cc` 里。本机 `local.properties` 设了 `xime.skipNative=true`，
  本地打包直接复用 `src/main/jniLibs/arm64-v8a/librime_jni.so`（预编译），**所以本版 APK 不含 header 逻辑**，
  要等一次真正编译 native 的构建（CI / 未设 skipNative 的环境）才生效。Kotlin 侧的保护本版即生效。
- `cleanOrphanedLogs()` 显式排除 `native_crash.log`，永不主动删除。

## 3.4.03（versionCode 20261347）

针对「还有什么地方耗电」的三项深度优化。其中**第一项经复核是我的误判，已纠正**。

### ①「两条常驻 TLS 连接」——误判，实际不存在

上一轮我报「进程里挂着两条到 AWS 新加坡 / 中国移动的 TLS 长连接」，那是**读错了作用域**：
`/proc/<pid>/net/tcp` 展示的是**整个网络命名空间**的套接字，不是该进程的。

按 inode 匹配 `/proc/<pid>/fd` 重新核对（协议环境）：该进程 14 个 socket **全部是本地 unix/netlink**，
TCP/TCPv6 里**一条已建立连接都没有**。所以这一项没有可优化的东西，结论作废。

同时确认已有的防护是有效的：`GeminiLiveTranscribeClient` 的预热会话本来就有 45 秒空闲驱逐
（`DEFAULT_MAX_PREWARM_IDLE_MS`），插件 JS 里也没有 `setInterval` / `WebSocket` 长连。

### ② 通知监听：每条通知少一次设置读 + 一次正则

原来 `onNotificationPosted` 对**每一条**系统通知都会：
1. 读一次 `SettingsPreferences.isOtpAutofillEnabled()`（SharedPreferences）；
2. 把 6 个字段拼成一个字符串，跑关键词正则。

亮屏期间每分钟十几条通知，其中绝大多数是聊天、新闻、下载进度。改动：

- 开关状态加 **60 秒缓存**（`otpEnabledCache`），读失败仍保持关闭（隐私设计不变）；监听器重连时失效重读。
- 新增**零分配预筛** `OtpExtractor.hasOtpLikeDigitRun()`：正文里不存在 4~8 位独立数字就直接返回，
  不再进正则。9 位以上的长数字串（订单号/流水号）同样拦掉。
- 正文先截断到 512 字符再匹配（验证码必然在开头几百字内）。
- 新增 9 条单测覆盖预筛的两个方向（该放行 / 该拦）。

### ③ 词频写盘：1.5 秒防抖 → 20 秒 + 会话结束补写

原来「打字停顿超过 1.5 秒」就会把 3 个词库文件整份重写（`word_freq` 54KB + `auto_word_temp` 44KB +
`auto_word`），一次约 150KB。正常聊天节奏等于每分钟写几次盘。

- 防抖拉长到 **20 秒**；
- 新增 `flushPendingWordData()`，在 **收键盘（`hideKeyboard`）** 与 **会话结束（`onFinishInput`）**
  立刻补写——只在确实有未保存数据时才动手；
- 代价明确写在注释里：进程若在 20 秒窗口内被杀，最近这段计数会丢（词频可再学）。

## 3.4.02（versionCode 20261346）

**修复 `:asr` 进程开关关了还常驻。**

现象：设置里「使用本地模型」已经是关的，但 `com.kingzcheung.xime:asr` 进程一直在跑，实测 **PSS 232MB**（RSS 147MB + swap 195MB），占着内存不还。

根因有两层：

1. `AsrInferenceService` 是**绑定型**服务，进程寿命取决于「还有没有人绑着」。服务端原有的空闲回收只释放模型句柄，**绑定是客户端资源，服务自己解不开**。
2. 关闭开关走的是 `AsrSupport.releaseModel()`，它只释放 `warmBackend` 这个预热后端——若该字段为 null（本进程没预热过、已被清空），整段是空操作，真正残留的会话客户端绑定没人解。

修复：

- 新增 `AsrConnectionWatchdog`：用弱引用登记所有活着的 `AsrInferenceClient`，并记录最后一次真实 ASR 调用时间。空闲超过 **3 分钟**即逐个 `unbind()` + `stopService()`，绑定清空后系统回收进程，内存归零。
- 关闭「使用本地模型」时**立即**回收，不等超时（`AsrSupport.releaseModel()` 末尾调 `shutdownNow`）。
- 显式开了「保持引擎常驻」时不自动回收——那是用户拿内存换首次响应的选择。
- 音频帧热路径（每 ~20ms 一帧）只写一个 volatile 时间戳，不排 Handler，避免看门狗本身带来开销；长录音不会在途中被判空闲。
- 新增 `AsrConnectionWatchdogTest` 5 条（从未用过要收 / 刚用过不收 / 正好到点收 / 差 1ms 不收 / 默认超时与常量一致）。
- `AsrInferenceService.onDestroy()` 里主动结束 `:asr` 进程：只解绑不退出的话，进程会变成系统的
  「cached empty」继续挂着（实测 RSS 仍 356MB），要等系统内存吃紧才回收。该进程只服务这一个
  服务，退出是安全的（exit-info 记 `reason=1 EXIT_SELF`，不是崩溃），下次用 `bindService` 重新拉起。

## 3.4.01（versionCode 20261345）

版本序列重置后的首个正式版本。代码内容与 `3.11.47` 完全一致，仅版本号与序列规则变化。

- 补丁位**两位定宽且从 `01` 起**（用户 2026-11-19 要求）：`3.4.01` → `3.4.02` → … → `3.4.99` → `3.5.00`，`00` 不使用。
- 重置过程中短暂产出过 `3.4.0`（versionCode 20261343）与 `3.4.00`（20261344），都只装在一台测试机上，均不作正式版本；本版取代它们。

## 3.11.47（versionCode 20261342）

- release 构建（R8 压缩后 40.2MB，debug 为 133MB）。
- 首次为历史版本补 tag。

## 3.11.46（versionCode 20261341）

- **全局麦克风互斥**（新增 `util/MicCaptureGate`）：键盘语音与悬浮胶囊各自持有一路 `AudioRecord` 时，两路都能 `startRecording` 成功但只有一路读到数据，表现为"两边都在转圈、谁都识别不出字"。改为抢占式闸门 + 5 分钟租约，抢不到时明确提示占用者。
- **图片上屏免复制**：`commitImage` 原来无条件把图片整份复制到 `cacheDir/emoji_cache`，且在主线程。改为源文件已在 FileProvider 根内就直接使用。
- **九键右选入队**：`onRightCandidateSelected` 原来"排空队列后仍飘在队列外"执行 JNI，连点时会插队导致跳字/错词。改为整段入队 + 2 秒兜底超时。
- **候选栏重组边界**：`KeyboardView` 不再在自身作用域内读候选状态，候选更新只重启候选栏，不再重跑整棵键盘的 compose；`HandwritingEngine.hasModel` 增加不对称缓存（正结果永久、负结果 3 秒）。

## 3.11.45（versionCode 20261340）

- 插件生命周期加 per-plugin 互斥（并发启用/禁用会各建一个 JS 运行时，泄漏引擎）。
- 统一配置 `updateTopLevelBlock` 读-改-写加锁（并发丢更新）。
- AI 流式回调按 66ms 时间窗节流（原来每 token 全量字符串 + 正则，O(n²)）。
- 悬浮胶囊隐私判定补 `imeOptions`（原来硬编码 0，隐私输入框的原文与录音仍会归档）。

## 3.11.44（versionCode 20261339）

- 插件错误降噪：网络类错误不存堆栈、60 秒内同类合并、宿主重述合并；落盘改追加 + 30 次压缩；「全部清除」真删磁盘。
