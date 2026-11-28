# 基线：Android Xime 3.4.70

本文件记录「适配电脑版本」之前的Android稳定基线。基线与桌面改造严格隔离，桌面工作只在 `desktop` 分支进行，不回写基线。

## 基线锚点（唯一标识）

| 项 | 值 |
|---|---|
| 版本 | 3.4.70（versionCode 20261414） |
| commit | `51eaf36751323ac32ed91ff1f2be214e323d4188` |
| 发行 tag | `v3.4.70`（release 3.4.70 - STT 复用 AI 服务商密钥 + 文本润色开关） |
| 基线 tag | `baseline-android-3.4.70`（本基线永久锚点） |
| 基线分支 | `baseline/android-3.4.70`（勿在此分支提交） |
| 桌面工作分支 | `desktop` |
| 本机源码快照 | `D:\AIWork\Xime-3.4.70-source`（源码 + .git + 20 个 submodule git 目录，排除构建缓存/local.properties） |

## 包含内容

- Android 输入法主体：Kotlin + Jetpack Compose，包名 `com.kingzcheung.xime`
- librime 1.18.0 移植 + T9 定制（含 glog 声明修复），7 个直接 submodule + 嵌套依赖
- ONNX Runtime 1.28.0 / ASR / 手写 native 库（8 个 arm64-v8a `.so`）
- AI 能力：多服务商密钥统一复用、STT 复用 AI 服务商密钥、文本润色开关、插件系统
- CI：recursive checkout + tag 触发自动构建/签名/发布，仅 arm64-v8a；签名密钥仅存 GitHub Actions Secrets，不入库
- 本地 Release APK：`app/build/outputs/apk/release/Xime-3.4.70-arm64-v8a.apk`
  - 40,651,445 字节
  - SHA-256 `5EAB2E253F8719EB6449EABFABA079DF09A1342E96B84738D614BD74827C7C6F`
  - 签名证书 SHA-256 `4bac28c4fea41b8740dc315af98f439c95bac333add1602a0aa6cf518f6ed329`

## 已知基线状态（非缺陷修复范围）

- 工作区存在两处非内容改动：`app/src/main/jni/librime`（Windows submodule 执行位）与 `gradlew`（Windows 文件属性 vs git mode 100755；远端 mode 正确）。
- 未做真机安装/FPS/PSS/耗电实测；native 库沿用既有产物，未在本轮重编。
- 加载项与既有边界详见 `PROGRESS.md` 中 3.4.68 / 3.4.69 / 3.4.70 各节。

## 基线纪律

1. 基线 commit（`51eaf367`）与 tag `baseline-android-3.4.70` 永不移动、永不改写。
2. 桌面改造单功能点推进，每个功能点端到端验证通过后才进入下一个。
3. 任何桌面改动不得影响 Android 构建；如需共享代码，先在 `desktop` 分支验证再评估回合并。
4. 每次打包继续升补丁版本；tag 与 `versionName` 一一对应。
5. 桌面版本发布前，先明确平台/形态/范围（见对话确认），不擅自扩大改造面。

## 下一步：电脑版本适配

平台、形态与范围待确认；确认后按「每次只做一个功能点」推进。
