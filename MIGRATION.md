# 项目位置迁移说明（2026-11-23）

## 当前工作目录

```
/root/work/Xime-3.4.09-source
```

**以后所有操作都在这个目录进行。**

## 为什么迁移

原目录 `/sdcard/AiWork/Xime-backup-3.4.09/Xime-3.4.09-source` 位于 FUSE 挂载点，
小文件 I/O 极慢（实测创建 500 个文件：FUSE 2.72 秒 vs 原生盘 0.05 秒，差 54 倍）。
Gradle 构建要创建数万个小文件，这部分开销占了 release 打包的一大半。

迁移后实测：

| 场景 | 迁移前 | 迁移后 |
|---|---|---|
| 增量 debug 构建 | ~2 分钟 | **22 秒** |
| release 全量构建 | 14~22 分钟 | **5 分 33 秒** |
| release 增量构建 | 4~6 分钟 | ~3 分钟 |

## 旧目录状态

`/sdcard/AiWork/Xime-backup-3.4.09/Xime-3.4.09-source` 仍然保留，作为**冷备份**。
它停在迁移那一刻的状态，之后的改动不会同步过去。

确认新目录工作正常一段时间后，可以删除旧目录释放 804MB。

## 相关配置变更

1. **`app/keystore.properties`**：`storeFile` 已改为新路径
   `/root/work/Xime-3.4.09-source/app/XGuai.pk12`

2. **构建入口 `/usr/local/bin/xime-build`**：`PROJ` 已指向新路径

3. **`/root/.gradle/init.d/redirect-build-dir.gradle`**：该脚本只在工程位于
   `/sdcard` 时把构建目录重定向到 `/root/xime-build`。现在项目已在内部目录，
   脚本会自动跳过，**不需要删**（万一将来又有工程放在 /sdcard 还能用）。

## 构建产物位置

现在走标准 Gradle 布局：

```
app/build/outputs/apk/debug/    # debug APK
app/build/outputs/apk/release/  # release APK
```

不再有 `build-outputs/` 目录（那是 /sdcard 时期的回传机制）。

## 注意事项

- 备份 APK 到手机下载目录时，仍需复制到 `/sdcard/Download/DSH-Go/`
- 内部目录不在 Android 文件管理器中可见，需要 `adb` 或本环境访问
