# Build all TS plugin xipk packages (Rust CLI: tools/xime-plugin).
#
# Usage:
#   .\scripts\build-plugins.ps1
#   .\scripts\build-plugins.ps1 --with-assets
#
# Plugin sources are TypeScript (plugins/<name>/main.ts + manifest.json + resources/);
# the xipm CLI compiles them to an IIFE single-file main.js and packs the xipk.

$ErrorActionPreference = "Stop"

$PROJECT_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

# 内置插件（随 APK 打包进 assets/plugins，安装后开箱可用；debug 与 release 一致）
# 必须与实际内置的插件一致：列出不存在的插件目录会让 --with-assets 必然失败
$BUNDLED_PLUGINS = @("webdav-backup")

$WITH_ASSETS = $false
$CLI_ARGS = @()
foreach ($arg in $args) {
    if ($arg -eq "--with-assets") { $WITH_ASSETS = $true } else { $CLI_ARGS += $arg }
}

Write-Host "=== Building TS plugin xipk packages (xipm CLI) ==="

Set-Location (Join-Path $PROJECT_DIR "tools\xime-plugin")
cargo run --quiet -- pack --all `
  --plugins-dir (Join-Path $PROJECT_DIR "plugins") `
  --out (Join-Path $PROJECT_DIR "build\plugin-js") `
  --release-dir (Join-Path $PROJECT_DIR "build\plugin-release") `
  @CLI_ARGS

if ($WITH_ASSETS) {
    $ASSETS_DIR = Join-Path $PROJECT_DIR "app\src\main\assets\plugins"
    Write-Host ""
    Write-Host "=== 同步内置插件到 assets ==="
    Get-ChildItem -Path $ASSETS_DIR -Filter "*.xipk" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force
    foreach ($name in $BUNDLED_PLUGINS) {
        $xipk = Get-ChildItem -Path (Join-Path $PROJECT_DIR "build\plugin-release") -Filter "$name-*.xipk" -File -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $xipk) { throw "未找到 $name 的 xipk 产物" }
        Copy-Item $xipk.FullName $ASSETS_DIR
        Write-Host "  ↳ $($xipk.Name)"
    }
}

Write-Host ""
Write-Host "=== Done ==="
Get-ChildItem -Path (Join-Path $PROJECT_DIR "build\plugin-release") -Filter "*.xipk" -File |
    Sort-Object Name |
    ForEach-Object { "{0,10:N1} K  {1}" -f ($_.Length / 1KB), $_.Name }
