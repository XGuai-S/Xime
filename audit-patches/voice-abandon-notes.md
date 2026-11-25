# 语音弃用多尾包回归补丁（仅测试，待主管应用）

## 产物与状态

- `voice-abandon-tests.patch` 仅新增 `app/src/test/java/com/kingzcheung/xime/service/VoiceAbandonAuditTest.kt`（138 行、4 项测试）。原测试不变，生产未修改。
- 已执行 `git apply --check audit-patches/voice-abandon-tests.patch` 成功；未 apply、未运行 Gradle/JUnit。不能声称已取得红测结果。
- 第一轮补丁 hunk 行数误写 140，静态检查发现后改为实际 138，重查通过。

## 基底 SHA-256

- VoiceRecognitionHandler.kt: `8462f3cd954322bde4115fd86626f3af5ed0a6184f6141f4052315dbbc50e17a`
- SpeechRecognitionManager.kt: `9f0f9a404599cd43032a13285862c8ca777b561bfdfd199de72f0ab3f0c2e4f8`
- VoiceRecognitionHandlerFinishTest.kt: `3f84bb367b344d3954e400916944e06ac42ebd5550fdedc716d3941dd20ae4d3`

## 应用与红测（仅由主管在当前组验收后执行）

先重新核对上述源码哈希与新增类不存在，再执行：

```sh
git apply --check audit-patches/voice-abandon-tests.patch
git apply audit-patches/voice-abandon-tests.patch
```

使用项目既有离线构建入口，定向运行 `com.kingzcheung.xime.service.VoiceAbandonAuditTest`；预期前三项在本基底失败，第四项正向通过。应保存实际测试报告后再授权生产修复，不修改或跳过原 FinishTest。该预期来自源码路径推演，尚非实际运行结果。

## fixture 与隔离

沿用 FinishTest 的 Mockito Manager/Handler/Prefs 与回调捕获方式。`getInputConnection` 通过可变 activeInputConnection 动态返回 A 或 B。先确认旧会话 partial 确实写入 A，然后 abandon，再切换 B，验证 B 的 composing、commit、finishComposing 与 deleteSurroundingText 均零调用。

Manager 是 mock，`startRecognition()` 仅验证 mock 方法调用。没有录音、服务绑定、模型预热、网络或真实输入连接。Context/Prefs 是 mock；filesDir 显式定向 JUnit TemporaryFolder 空目录：本地引擎显示名查询会检查模型目录，空目录确保没有已安装模型，因此 initialize 不启动 warmup 线程，也不会读取日用模型文件。测试不读取真实偏好或凭据。

## 四项场景与预期失效点

1. abandon A → 切 B → final1 → final2：第一个 final 将 sessionAbandoned 清 false，第二个 final 写 B。
2. abandon A → 切 B → final → partial：第一个 final 将闸门清除，后续 partial 写 B。
3. abandon A → 切 B → late LISTENING → partial/final：handleSpeechStateChange 在 LISTENING 时清 sessionAbandoned 和 suppressDuplicateFinal，因此迟到状态重开旧会话。当前枚举使用 LISTENING 表示录音中，没有 RECORDING 常量；补丁使用真实枚举。
4. abandon A → 切 B → 显式 startRecognition → LISTENING → 新 partial/final：确认新会话可写 B（正常增量路径只补中文逗号），避免用永久禁用语音来通过隐私回归。

## 后续生产修复约束（本补丁不实施）

- sessionAbandoned 当前不是 volatile；若保留跨线程写入/回调，至少需要可见性，且 volatile 不等于会话身份与多字段操作原子性。
- 弃用闸应保持到**显式 startRecognition**，不能在第一个迟到 final 或 LISTENING 状态回调中清除。startRecognition 当前仅清 suppressDuplicateFinal，没有显式清 sessionAbandoned；实际依赖 LISTENING 清闸，后续迁移应把合法恢复点移到 explicit start。
- SpeechRecognitionManager.suppressResults 虽为 volatile，但仅 handleResult 在入队前和投递时检查。handlePartialResult 与 state 路径没有同样抑制，不能指望 Manager.cancelRecognition 已屏蔽全部尾包。
- 本组只覆盖 abandon 后、下次 explicit start 之前的尾包。旧会话尾包在新 explicit start 后仍可能混入，必须进一步考虑 callback generation/session token；不能用本4项测试宣称完整代次隔离已完成。
- 本测试只验证不向新编辑器写文本，不约束 onVoiceComplete 次数或所有 UI 状态，避免与现有收尾完成契约混改。
