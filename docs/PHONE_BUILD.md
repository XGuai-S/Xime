# Xime 手机构建与测试环境验收

> 当前 Ubuntu ARM64/proot 的独立测试与打包入口已配置，见 [Ubuntu 构建说明](UBUNTU_BUILD.md)。本页以下保留此前 root/chroot 路径的验收记录。

## 结论

构建和测试执行环境已可用；**Debug 构建成功，App 单测全部通过，插件单测仍有 1 项真实契约失败，不能报告全绿**。

- JDK 21.0.10、Gradle 9.8.1、Rust 1.100.0、xipm 0.2.0 均实际运行成功。
- 官方 Android SDK 平台 37.2、Build Tools 36/37、Platform Tools 已安装并可由 SDK Manager 正确识别。
- 官方 x86 AAPT2/AIDL/zipalign 通过 QEMU 兼容运行，原始可执行文件保留为 `.x86_64`。
- Rust 插件构建器 `cargo build --locked --release` 成功，15 个插件及测试夹具由 Gradle 的真实依赖任务构建。
- `:app:testDebugUnitTest`：**1824 项，0 失败、0 错误、0 跳过**。其中此前新增 AI 隐私契约测试 3 项全部通过。
- `:plugin-core:testDebugUnitTest`：**312 项，1 失败、0 错误、0 跳过**。
- `:app:assembleDebug`：**BUILD SUCCESSFUL**，退出码 0；APK 签名验证通过，未安装。
- 测试脚本的 13 项替身回归也在新环境中全部通过。

## 实际修复的环境问题

1. 沙箱应用 SELinux 执行域阻止 JVM 内存执行；改为已有授权的 root/Shizuku 通道，在独立挂载命名空间中运行同一 JDK，不关闭 SELinux、不改系统策略。
2. 安装并注册匹配项目的 SDK，补齐 ARM 主机运行 x86 Android 编译工具的兼容层。
3. Rust 1.83 不支持项目要求；增加独立的 1.100 工具链，不改 Cargo.toml/Cargo.lock。
4. PRoot 的包管理硬链接在 chroot 中不可见；仅在私有命名空间提供对应路径别名。
5. Mockito 在 Android 内核下不能自动附加 Byte Buddy；仅在本机 Gradle 用户目录自动加载显式 Java Agent。首轮因此失败的 59 项 App 测试已在真实重跑中通过，未跳过或修改断言。

## 唯一剩余测试失败

`plugin-core/src/test/java/com/kingzcheung/xime/plugin/core/js/JsAiPluginTest.kt:370`

测试「qwen-translate 控件行与互换语言」期待 3 个控件，实际为 4 个。`plugins/qwen-translate/main.ts:164–171` 已有 `engine / sourceLang / swapLang / targetLang` 四项，真实 JS 产物与源码一致；旧测试仍按三控件及旧位置索引检查。

本轮只打通环境，**没有删除测试、放宽断言或擅自修改翻译 UI**。如要修复此项，应单独同步四控件契约，并继续精确验证语言互换行为；不是把数量断言改宽即可。

## 可重复使用的手机入口

已新增项目脚本：`scripts/gradlew-phone.sh`。需从已授权的 Android root/Shizuku 环境调用，脚本拒绝 clean、安装、卸载和发布任务。

项目根目录的 Android 终端命令示例：

```sh
su -c 'sh /storage/emulated/0/AiWork/Xime-backup-3.4.09/Xime-3.4.09-source/scripts/gradlew-phone.sh :app:testDebugUnitTest --max-workers=3'
su -c 'sh /storage/emulated/0/AiWork/Xime-backup-3.4.09/Xime-3.4.09-source/scripts/gradlew-phone.sh :plugin-core:testDebugUnitTest --max-workers=3'
su -c 'sh /storage/emulated/0/AiWork/Xime-backup-3.4.09/Xime-3.4.09-source/scripts/gradlew-phone.sh :app:assembleDebug --max-workers=3'
```

第二条当前会因上述真实测试失败返回非零。普通 `bash run_tests.sh unit` 必须在构建 chroot 内运行，不能把旧 Android Shell 的 PATH 当成已经全局改好。

## 路径与产物

- 环境启动器：`/data/local/tmp/xime-build-env/enter.sh`。
- Gradle/Rust 缓存与日志：`/data/local/tmp/xime-build-env/state/`。
- Minis Alpine 根目录：`/data/data/com.openminis.app/files/alpine-rootfs`；依赖其已安装包，不是独立系统安装。
- SDK：构建环境内 `/opt/android-sdk`；Rust：`/opt/xime-rust-1.100.0`。
- 本机 Mockito 设置：Gradle 用户目录 `init.d/xime-host-tests.gradle`，不改仓库共享测试配置。
- 项目 `local.properties` 新建（Git 忽略）：`sdk.dir=/opt/android-sdk`、`xime.abi=arm64-v8a`、`xime.skipNative=true`。此前不存在该文件。
- APK：`app/build/outputs/apk/debug/Xime-3.4.09-arm64-v8a.apk`，133004496 字节。
- APK SHA-256：`59c481efdd96a288358359d6e4c55fb9b2730d441ea16aead2ac19c4f1ff67dc`。
- JUnit XML/HTML：各模块 `build/test-results/testDebugUnitTest/` 与 `build/reports/tests/testDebugUnitTest/`。

## 边界和残余风险

- **ARM64 快构建复用现存 `.so`，未重编 C++**。不能据此宣称原生源码可复现或预编译库与当前源码完全一致。
- 构建报告提示 app 预编译库与 QuickJS 依赖同时提供 `libquickjs.so`；AGP 当前选择 app 版本。APK 已构建，不等于完成该二进制选择的实机兼容验证。
- 没有安装 APK、运行设备/模拟器测试、升级项目依赖、改版本/签名配置、提交 Git 或关闭 SELinux。无业务 Kotlin 代码追加修改。
- 已检查主机与构建的 mount namespace 不同；构建退出后主机未出现 `/work` 项目绑定，SELinux 仍为 Enforcing。
- 环境需要保留 Minis 数据和 `/data/local/tmp/xime-build-env`，并继续授予执行权限。清理 App 数据、卸载 Minis 或清理该临时目录后，需要恢复环境；不保证普通非 root Shell 可直接构建。
