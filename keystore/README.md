# 自建发布签名

本目录存放 Xime 自建发布签名，用于 debug 与 release 构建统一签名，保证包名相同可以**覆盖安装**、保留应用数据与系统授权（通知使用权、自启动 appops 等）。

## 文件

| 文件 | 说明 |
|---|---|
| `xime-release.jks` | RSA 2048，有效期 10000 天（至 2054-03-25），alias `xime` |
| `README.md` | 本说明 |

证书 SHA-256 指纹：

```
A5:B4:56:EB:F2:EA:AE:57:3A:C0:CA:72:BA:7B:78:40:83:FA:AB:B0:23:C6:51:01:BE:1B:A9:85:D0:78:8C:4F
```

## 密码

**不写入本仓库。** `.jks` 已入库便于异地恢复，但 `storePassword` / `keyPassword` 只保存在构建机的 `app/keystore.properties`（已被 `.gitignore` 排除）与项目所有者本地备份中，需要时向所有者索取。

原因：本仓库为公开仓库，签名私钥的密码一旦公开，任何人都能签发与手机现有版本签名一致的 APK 冒用包名。`.jks` 本身受密码加密，单独泄露风险可控；密码与 `.jks` 同时泄露则等于签名权完全交出。

## 恢复步骤

1. 把 `xime-release.jks` 放回 `keystore/`（或任意路径）
2. 新建 `app/keystore.properties`：

```properties
storeFile=../keystore/xime-release.jks
storePassword=<向所有者索取>
keyAlias=xime
keyPassword=<向所有者索取>
```

3. 正常执行 `./gradlew :app:assembleDebug`，`app/build.gradle.kts` 会自动使用该签名（`signingConfigs.release`）

缺少 `app/keystore.properties` 时会回退到 debug 签名；这样打出的 APK 与手机已装版本签名不一致，必须卸载重装，**会清空应用私有数据**（插件配置、剪贴板历史 Room 库）。
