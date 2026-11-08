# App ProGuard rules
-dontobfuscate

-optimizations !class/merging/*

# Keep utility classes used by Application and services
-keep class com.kingzcheung.xime.util.** { *; }

# 说明（勿删）：com.kingzcheung.xime.settings.** 与 ui.settings.** **不需要**显式 keep。
# 它们全部经 Kotlin 直接调用/Compose 静态引用可达，R8 不会裁掉；而本文件开局
# 已 `-dontobfuscate`，符号名也不会被改写。日志开关（SettingsPreferences /
# LogSettingsContent）与配置文件目录直达路由（user_config_browser）均属此类。
# 仅当改用反射/字符串查找访问这些类时，才需要在此补 keep 规则。

-dontwarn com.sun.nio.file.**
-dontwarn kotlin.Cloneable$DefaultImpls

# 插件系统（宿主侧 com.kingzcheung.xime.plugin.* + plugin-core）整体保留：
# JS 桥/序列化/动态约定多，防止 R8 裁剪造成 release 行为缺失
# （库侧自完备规则见 plugin-core/consumer-rules.pro）
-keep class kotlin.Metadata { *; }

-keep class com.kingzcheung.xime.plugin.** { *; }
-keepclassmembers class com.kingzcheung.xime.plugin.** { *; }

-keep class com.kingzcheung.xime.rime.** { *; }
-keep class com.kingzcheung.xime.**Jni** { *; }

-keepattributes SourceFile,LineNumberTable

-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

-processkotlinnullchecks remove

# ── R8 优化强度（实测结论：保持 1 轮）──
# 上游为「打包提速」只用 1 轮（原作者实测 R8 占打包约 80%，8Gen3 上约 4 分钟）。
# 本机做过对照实验（-optimizationpasses 1 → 3，强制 --no-build-cache --no-configuration-cache 重跑）：
#   dex 9805756 字节 / APK 32044623 字节 —— 与 1 轮**完全一致（字节级）**，
#   而 R8+打包耗时从约 2 分钟涨到 4m14s。
# 原因：本项目已 -dontobfuscate + -optimizations !class/merging/* + R8 full mode，
#   优化在第一轮即到达不动点，增加轮数不再产生任何变化。
# 结论：不要调高轮数，纯亏时间。保持 1 轮。
-optimizationpasses 1
