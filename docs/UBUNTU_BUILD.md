# Ubuntu ARM64 测试与打包环境

本说明对应 2026-11-19 在 DSH Ubuntu 24.04 ARM64 proot 中配置的环境。实际验收结果见 `PROGRESS.md`，不要把旧手机 chroot 的测试结果当成本环境结果。

## 已配置工具

- OpenJDK / javac 21.0.13.1：`/usr/lib/jvm/java-21-openjdk-arm64`。
- 项目 Gradle Wrapper 9.8.1：使用现有 Wrapper，不修改项目依赖版本。
- SDK：`/opt/android-sdk`，命令行工具 22.0、平台 `android-37.2`、Build Tools 36.0.0 / 37.0.0。
- Rust / Cargo 1.100.0；按 `Cargo.lock` 构建的 xipm 0.2.0 位于 `/opt/xime-tools/bin/xipm`。
- GCC/G++、pkg-config、QEMU 用户态工具已安装。
- SDK 的 x86_64 工具使用 QEMU；原文件保留为同目录的 `.x86_64` 文件。独立 x86 运行库位于 `/opt/xime-x86`，没有修改 Android 系统运行库。

Room/KSP 的 SQLite JDBC 3.50.3.0 在本机误识别为 `Linux-Android`。已从同版本依赖 JAR 提取 `org/sqlite/native/Linux/aarch64/libsqlitejdbc.so` 到 `/opt/xime-tools/lib/sqlite-jdbc-3.50.3.0/`，仅在本机 Xime 环境的 `JAVA_TOOL_OPTIONS` 中指定 `org.sqlite.lib.path`。该库已通过真实内存 SQL 查询，Room/KSP 阶段也已通过；没有替换依赖版本或关闭数据库 SQL 校验。依赖版本变化时应重新核对并提取同版本库，不能混用旧库。

当前 Java、Gradle、Rust 和兼容工具能在普通 Ubuntu 进程运行，不依赖 Android Shell 授权。proot 中显示 UID 0 不代表获得 Android root；旧 root/Shizuku 入口 `scripts/gradlew-phone.sh` 仍保留。

## 环境配置与检查

本机配置位于 `$HOME/.config/xime-build/env.sh`，两个入口会自动加载；可用 `XIME_BUILD_ENV` 指定另一份可信 Bash 配置。保留已设置的环境变量。Gradle 缓存与 Rust 编译产物位于 `/root/.cache/xime-build/`。

```bash
bash scripts/check-build-env.sh
bash scripts/gradlew-ubuntu.sh --version
bash scripts/test-gradlew-ubuntu.sh
```

独立 Mockito 探针可以自附加，但 Gradle 测试进程实际仍出现自附加失败。已在本机 Gradle 用户目录的 `init.d/xime-host-tests.gradle` 为 Xime 的 Test 任务配置同一运行时 classpath 中的 Mockito Java Agent；不更换 mock 引擎、不跳过或修改测试。显式 Agent 的独立探针已通过，全量回归应以重跑报告为准。

环境自检会实际运行 Java、aapt2、aidl、zipalign、Rust 与 xipm，并检查 SDK 和预编译库。它不证明业务测试已通过。隔离入口回归使用临时假工具，不执行真实 Gradle 或操作手机数据。

## 测试

```bash
bash scripts/gradlew-ubuntu.sh :app:testDebugUnitTest --tests com.kingzcheung.xime.clipboard.OtpClipboardPolicyTest --max-workers=2 --console=plain
bash scripts/gradlew-ubuntu.sh :app:testDebugUnitTest --max-workers=2 --console=plain
bash scripts/gradlew-ubuntu.sh :plugin-core:testDebugUnitTest --max-workers=2 --console=plain
```

插件模块的 Gradle 任务会调用 xipm 编译插件及测试夹具。不要用旧 JS 替身或排除测试来报告通过。此前插件测试存在翻译控件数量契约失败，本轮应以新 JUnit XML 为准。

## 打包

```bash
bash scripts/gradlew-ubuntu.sh :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --console=plain
```

`assembleDebugAndroidTest` 仅编译测试 APK，不安装或执行设备测试。设备测试 fixture 会清空目标应用剪贴板历史，只能在明确同意的隔离测试数据环境运行。

## 预编译库边界

本机 `local.properties` 保留 `sdk.dir=/opt/android-sdk`、`xime.abi=arm64-v8a` 和 `xime.skipNative=true`。入口检查 8 个 ARM64 库存在且非空，并排除 `downloadOnnx` / `downloadKnf`，避免 preBuild 覆盖已有库；没有排除测试，也没有重编 C++。打包成功不等于验证 native 源码和现有二进制一致。

入口只允许明确列出的测试、Debug 编译与帮助任务，拒绝 clean、安装、卸载、发布及未知任务缩写。要增加其他任务，先审查其影响再修改白名单。SDK 的标准开发工具许可在本次配置过程中接受；日常脚本不会自动接受许可。

## 产物与验收

- App APK：`app/build/outputs/apk/debug/`。
- 测试 APK：`app/build/outputs/apk/androidTest/debug/`。
- JUnit XML：各模块 `build/test-results/testDebugUnitTest/`。
- HTML 测试报告：各模块 `build/reports/tests/testDebugUnitTest/`。
- 原生库构建前哈希：`/root/.cache/xime-build/native-before.json`。

出现失败时保留退出码和真实报告；不要改断言、跳过失败或把已有 APK 当作新补丁产物。
