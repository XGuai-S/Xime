# Xime 桌面版（Windows）安装说明

Xime 是一个 **TSF 输入法**（Text Services Framework），与微软拼音、搜狗同类，
不需要常驻后台程序，由系统在需要时载入 `xime-tsf.dll`。

## 一、安装（两种方式任选）

### 方式 A：命令行安装（推荐）

在仓库根目录执行：

```powershell
# 1) 构建（首次或改动后）
$env:Path = "C:\msys64\mingw64\bin;" + $env:Path
cmake -S desktop -B desktop/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build --parallel 8

# 2) 安装到 %LOCALAPPDATA%\Xime\ime
cmake --install desktop/build

# 3) 注册为系统输入法（会弹 UAC，点「是」）
& "$env:LOCALAPPDATA\Xime\ime\xime-register.exe" install
```

### 方式 B：把已有构建直接装好（一条命令）

```powershell
powershell -ExecutionPolicy Bypass -File desktop\tools\install.ps1
```

脚本会完成：构建 → 安装到 `%LOCALAPPDATA%\Xime\ime` → 提权注册 → 加入输入法列表。

## 二、启用

1. **注销并重新登录**（或重启），让系统重新扫描输入法。
2. `设置 → 时间和语言 → 语言和区域 → 中文(简体，中国) → 语言选项 → 键盘 → 添加键盘 → Xime 输入法`。
3. 任意输入框按 **`Win + 空格`** 切换到 Xime。

首次启用会**自动部署词库**（生成 `%LOCALAPPDATA%\Xime\build\*.bin`），
通常 10~60 秒；部署完成后才会出候选，请稍等再试。

## 三、使用

| 操作 | 键 |
|---|---|
| 输入中文 | 直接打拼音，如 `nihao` |
| 选词 | 数字键 `1`~`9`，或鼠标点击候选 |
| 翻页 | `PageUp` / `PageDown`，或 `-` / `=` |
| 中英文切换 | `Shift` |
| 切换方案 | `F4` |
| 上/下屏 | `Space` / `Enter` |

## 四、目录与数据

| 内容 | 路径 |
|---|---|
| 程序（DLL + 注册工具 + data） | `%LOCALAPPDATA%\Xime\ime` |
| 用户数据（词频、造词、设置、剪贴板、备份） | `%LOCALAPPDATA%\Xime` |
| 部署产物 | `%LOCALAPPDATA%\Xime\build` |
| 运行日志 | `%LOCALAPPDATA%\Xime\logs` |

共享数据目录（`data/`）必须与 `xime-tsf.dll` 同级，移动程序时请整体移动。

## 五、卸载

```powershell
# 注销（会弹 UAC）
& "$env:LOCALAPPDATA\Xime\ime\xime-register.exe" uninstall

# 删除程序文件（用户数据是否保留按需）
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\Xime\ime"
# 如需彻底清除用户数据：
# Remove-Item -Recurse -Force "$env:LOCALAPPDATA\Xime"
```

注销后注销/重启一次系统，输入法列表中即消失。

## 六、已知限制

- **仅 64 位**：32 位程序（部分老软件）无法使用，需另编 32 位 DLL。
- 需要在**有输入焦点的可编辑控件**中使用；部分自绘界面（如某些游戏、远程桌面）可能不响应。
- 首次部署依赖 CPU，较慢的机器可能要等更久。
- 若切换后打字无反应，可重启输入法服务：任务管理器结束 `ctfmon.exe`，系统会自动重启它。
