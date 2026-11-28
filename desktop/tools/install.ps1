# Xime 桌面版一键安装：构建 -> 安装到 %LOCALAPPDATA%\Xime\ime -> 注册 -> 加入输入法列表。
# 用法：powershell -ExecutionPolicy Bypass -File desktop\tools\install.ps1 [-SkipBuild] [-Uninstall]
param(
    [switch]$SkipBuild,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path (Join-Path $repo 'desktop\CMakeLists.txt'))) {
    $repo = (Get-Location).Path
}
$build = Join-Path $repo 'desktop\build'
$prefix = Join-Path $env:LOCALAPPDATA 'Xime\ime'
$register = Join-Path $prefix 'xime-register.exe'
$clsid = '{8A7C1E20-3F5B-4A2D-9E11-6F3D5C8A2B47}'
$profGuid = '{B4E92F10-7A6C-4D85-A3F2-1C8D9E4B7A60}'
$tip = '0804:' + $clsid + $profGuid

function Write-Step([string]$text) { Write-Host "==> $text" -ForegroundColor Cyan }

if ($Uninstall) {
    Write-Step '注销输入法'
    if (Test-Path $register) {
        Start-Process -FilePath $register -ArgumentList 'uninstall' -Verb RunAs -Wait
    } else {
        Write-Host '未找到注册工具，请手动清理注册表项。' -ForegroundColor Yellow
    }
    Write-Step '从输入法列表移除'
    $list = Get-WinUserLanguageList
    foreach ($lang in $list) {
        $kept = @($lang.InputMethodTips | Where-Object { $_ -ne $tip })
        if ($kept.Count -ne $lang.InputMethodTips.Count) {
            $lang.InputMethodTips.Clear()
            foreach ($k in $kept) { $lang.InputMethodTips.Add($k) }
        }
    }
    Set-WinUserLanguageList $list -Force
    Write-Step '删除程序文件'
    if (Test-Path $prefix) { Remove-Item -Recurse -Force $prefix }
    Write-Host '卸载完成，请注销或重启系统。' -ForegroundColor Green
    return
}

# 1) 构建
if (-not $SkipBuild) {
    Write-Step '构建'
    $env:Path = 'C:\msys64\mingw64\bin;' + $env:Path
    cmake -S (Join-Path $repo 'desktop') -B $build -G Ninja -DCMAKE_BUILD_TYPE=Release
    if ($LASTEXITCODE -ne 0) { throw 'cmake 配置失败' }
    cmake --build $build --parallel 8
    if ($LASTEXITCODE -ne 0) { throw '构建失败' }
}

if (-not (Test-Path (Join-Path $build 'xime-tsf.dll'))) {
    throw "未找到 $build\xime-tsf.dll，请先构建。"
}

# 2) 安装到固定目录
Write-Step "安装到 $prefix"
cmake --install $build --prefix $prefix
if ($LASTEXITCODE -ne 0) { throw '安装失败' }

if (-not (Test-Path $register)) { throw "未找到 $register" }

# 3) 提权注册
Write-Step '注册为系统输入法（请在弹出的 UAC 窗口点「是」）'
Start-Process -FilePath $register -ArgumentList 'install' -Verb RunAs -Wait

$key = "HKLM:\SOFTWARE\Classes\CLSID\$clsid\InprocServer32"
if (-not (Test-Path $key)) {
    throw '注册未生效（UAC 可能被取消）。请以管理员身份运行 xime-register.exe install。'
}
Write-Host ("    DLL = " + (Get-ItemProperty $key).'(default)') -ForegroundColor Green

# 4) 加入当前用户输入法列表
Write-Step '加入输入法列表'
$list = Get-WinUserLanguageList
$zh = $list | Where-Object { $_.LanguageTag -eq 'zh-Hans-CN' } | Select-Object -First 1
if (-not $zh) {
    Write-Host '未安装中文(简体)语言，请先在系统设置中添加。' -ForegroundColor Yellow
} else {
    if ($zh.InputMethodTips -contains $tip) {
        Write-Host '   已在列表中'
    } else {
        $zh.InputMethodTips.Add($tip)
        Set-WinUserLanguageList $list -Force
        Write-Host '   已添加'
    }
}

Write-Host ''
Write-Host '安装完成！' -ForegroundColor Green
Write-Host '下一步：注销并重新登录（或重启），然后 Win+空格 切换到「Xime 输入法」。'
Write-Host '首次启用会自动部署词库，等 10~60 秒出候选。' -ForegroundColor Yellow
