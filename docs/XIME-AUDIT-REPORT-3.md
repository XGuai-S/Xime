# Xime 第三轮全维度审计报告

> 日期：2026-11-27　方式：9 维度并行静态审计 + 关键项人工复核
> 范围：只找**明显 bug** + **过度设计**，不做过度优化；已排除前两轮修复项。
> 结果：**7 项**（medium 4 / low 3）。M1 已人工复核确认。

---

## 附：截图问题——"native crash handler installed" 是不是 bug？

**不是 bug，是设计好的标记行。**
- 来源：`librime_jni/rime_jni.cc:3319`，写的是专门的崩溃日志 `{filesDir}/logs/native_crash.log`，**不是普通运行日志/logcat**。
- 目的（代码 L3305-3308 注释）：该文件为空有两种可能——"装好了 handler 但没崩过" vs "根本没装上"，无法区分。故每次安装成功写一行 header 标记。
- 截图里只有 `=== native crash handler installed pid=... ===` 几行、**没有后续堆栈** = 进程成功安装了 handler 且**没有发生 native 崩溃**。有几个 pid 就有几次进程启动/重装。
- 日志查看器显示它只是因为查看器聚合了 `logs/` 目录，不是代码缺陷。这行 header 是必要的，不建议删。

---

## 一、中风险（4 项）

### M1. pooledMedians 用累计次数分配数组，中位数被尾部 0 污染
- **文件**：`correction/CorrectionPriors.kt:250-259`（已复核确认）
- **问题**：`FloatArray(ringTotal)` 分配（ringTotal 单调自增），但循环只写 kx 个元素（各 ring 实际元素数之和，每 ring 上限 256）。按键超 256 次后 kx < ringTotal，数组尾部为 0f，`medianOf` 排序后 0 拉偏中位数，击键越多偏差越大（0 过半时直接变 0）。
- **后果**：端到端自校准的 globalX/globalY 系统性偏小，邻键误触纠错失效/误校准。
- **修复**：按实际样本数分配与判定：
  ```
  val n = ringX.sumOf { it.size }
  if (n < MIN_N_GLOBAL) return Float.NaN to Float.NaN
  val allX = FloatArray(n); val allY = FloatArray(n)
  ```

### M2. POST_NOTIFICATIONS 权限已声明但从未请求
- **文件**：`AndroidManifest.xml:11` + 全项目无请求代码
- **问题**：API 33+ / targetSdk 35 下，声明了 `POST_NOTIFICATIONS`（注释说"需用户授权"），但无任何 `requestPermissions`/`ActivityResultContracts` 请求，也无跳转通知设置的入口。
- **后果**：悬浮胶囊的前台服务常驻通知不出现在通知抽屉，用户无法从通知栏感知/干预胶囊状态。
- **修复**：首次启动胶囊前用 `ActivityResultContracts.RequestPermission()` 请求；或至少在设置页提供 `ACTION_APP_NOTIFICATION_SETTINGS` 跳转入口。

### M3. 增量恢复 SHA 校验流的 FileInputStream 未关闭
- **文件**：`增量恢复相关:296`（对照同文件 L304 的 `.use{}`）
- **问题**：`sha256Of(dl.file.inputStream())`——`File.inputStream()` 新建 FileInputStream，`sha256Of`（L343-354）读到 EOF 但从不关闭。每校验一个远端文件泄漏一个 fd；输入法常驻进程，恢复数百文件时 fd 快速堆积。
- **修复**：`val actual = dl.file.inputStream().use { sha256Of(it) }`，或在 `sha256Of` 内部 `input.use{}`。

### M4. 服务初始化协程吞掉 CancellationException，产生假故障日志
- **文件**：`XimeInputMethodService.kt:1126`
- **问题**：`serviceScope.launch(IO)` 初始化块只有 `catch (e: Exception)`，CancellationException 是 Exception 子类被一并吞掉且未 rethrow。服务重建/切方案/进程收尾取消该协程时，只记 "Initialization failed: Job was cancelled" 假故障，破坏结构化并发。同文件 L1254 的同类协程**已正确处理**（catch CancellationException 后 rethrow），此处遗漏。
- **修复**：在 catch Exception 前补 `catch (e: CancellationException) { FileLogger.d(...); throw e }`。

---

## 二、低风险（3 项）

### L1. 缺少 auto_word 块时写回只生成 6 个键，词典排除列表被静默重置
- **文件**：`AutoWordSettingsScreen.kt:118-130`（对照 `XimeConfigModels.kt` 13 个键）
- **问题**：fresh 分支模板只写 6 个键，缺 `no_auto_word_dicts`/`no_freq_dicts` 等。代码默认为空表，而内置配置为 `[XGuai_user]`。用户配置不含该块时切一次开关，XGuai_user 词典词条开始进入自动造词/词频统计，界面无提示。
- **修复**：fresh 模板按当前生效配置写出全部 13 个键，或直接取内置 auto_word 块文本仅替换 enabled 行。

### L2. ASR 后端工厂是零逻辑纯转发空壳（过度设计）
- **文件**：`AsrBackendFactory.kt:9-19`
- **问题**：4 个方法全部逐字转发到同包 `AsrSupport`，自身无选择/判断逻辑。注释宣称"本地优先/在线回退"，实际回退在调用方 `SpeechRecognitionManager.kt:410`。5 处调用点都在同模块，多一层无收益间接调用且注释误导。
- **修复**：删该文件，5 处调用改直接调 `AsrSupport`。

### L3. ModelManager.initialize() 是从未被调用的死代码（过度设计）
- **文件**：`ModelManager.kt:12,18-22`
- **问题**：`initialized` 字段仅在该方法内读写，全项目无调用点；方法体只翻转标志+打日志，无实际初始化。
- **修复**：删该字段与方法。

---

## 三、修复优先级
| 顺序 | 项 | 理由 |
|---|---|---|
| 1 | M1 | 真实 bug，纠错校准静默失效，影响输入准确性 |
| 2 | M4 | 假故障日志 + 破坏取消语义，修复简单 |
| 3 | M3 | fd 泄漏，常驻进程风险 |
| 4 | M2 | 通知权限，兼容性/可感知性 |
| 5 | L1 | 配置静默重置 |
| 6 | L2/L3 | 过度设计清理，低风险 |

## 四、边界说明
- 纯静态审计，未跑真机；M1 已直接核对源码确认。
- 已排除前两轮 26 项修复与已确认正常项。
- 用户要求"只做明显 bug、不做过度优化"，故未报告微优化项；过度设计单列 2 项。
