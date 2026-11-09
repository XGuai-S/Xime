# APK 体积构成

Xime 3.3.29 发布包（`Xime-3.3.29-arm64-v8a.apk`，32.09 MB / 30.60 MiB）各组成部分体积：

| 大类 | 未压缩 | 说明 |
|---|---|---|
| native .so（8 个） | 35.49 MB | libonnxruntime.so 27.3MB 为最大头；其余 librime_jni/quickjs/asr 等 |
| classes.dex | 9.40 MB | R8 混淆 + shrinkResources 后的单 dex |
| 词库 assets | 13.46 MB | pinyin_simp_ext 8MB + wubi98 2.5MB + pinyin_simp 2MB 等 |
| opencc 字典 | 0.99 MB | 简繁转换（s2t.json / s2hk.json + *.ocd2） |
| res 等 | 1.24 MB | 布局/资源 + resources.arsc |
| 其他 | 1.95 MB | 字体、www 设置页、其他 assets |
| **合计（未压缩）** | **62.52 MB** | |
| **APK 实际（压缩后）** | **30.60 MB** | 整体约 49% 压缩率 |

## 说明

- 仅单 ABI（arm64-v8a），无多 ABI 冗余。
- onnxruntime 为语音/手写/纠错功能组所需，未启用时运行时按需 dlopen，但白占 APK 体积（约 14MB 压缩后）。
- 词库 pinyin_simp_ext 被 pinyin_simp 方案 import，为有效数据，不可删。