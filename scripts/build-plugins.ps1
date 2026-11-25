# Build all TS plugin xipk packages (Rust CLI: tools/xime-plugin).
#
# Usage:
#   .\scripts\build-plugins.ps1
#   .\scripts\build-plugins.ps1 --with-assets
#
# Plugin sources are TypeScript (plugins/<name>/main.ts + manifest.json + resources/);
# the xipm CLI compiles them to an IIFE single-file main.js and packs the xipk.
#
# --with-assets 同步内置插件的顺序（M12）：构建产物必须先独立校验完整，
# 旧 assets 先备份，新产物全部复制成功后才删备份；任何失败都整体回滚。

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

# S1：记录构建起始时间，用于把「本次构建产物」与目录里的历史产物区分开
$BUILD_STARTED_UTC = (Get-Date).ToUniversalTime()

Write-Host "=== Building TS plugin xipk packages (xipm CLI) ==="

Set-Location (Join-Path $PROJECT_DIR "tools\xime-plugin")
cargo run --quiet -- pack --all `
  --plugins-dir (Join-Path $PROJECT_DIR "plugins") `
  --out (Join-Path $PROJECT_DIR "build\plugin-js") `
  --release-dir (Join-Path $PROJECT_DIR "build\plugin-release") `
  @CLI_ARGS
if ($LASTEXITCODE -ne 0) { throw "xipm pack failed (exit code $LASTEXITCODE)" }

# 从产物目录解析「本次构建生成的」xipk：每个内置插件必须恰好命中 1 个新产物。
# 不用「取第一个文件」的启发式——目录里可能同时存在历史版本。
function Get-FreshBuildArtifacts {
    param(
        [Parameter(Mandatory = $true)][string]$ReleaseDir,
        [Parameter(Mandatory = $true)][string[]]$Names,
        [Parameter(Mandatory = $true)][datetime]$BuildStartedUtc
    )
    # 文件时间戳精度有限，给 2 秒容差；仍要求「恰好一个」以避免新旧混杂时猜错
    $since = $BuildStartedUtc.AddSeconds(-2)
    $artifacts = @()
    foreach ($name in $Names) {
        $candidates = @(Get-ChildItem -Path $ReleaseDir -Filter "$name-*.xipk" -File -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTimeUtc -ge $since })
        if ($candidates.Count -ne 1) {
            throw "本次构建产物不完整：$name 期望 1 个新 xipk，实际 $($candidates.Count) 个（历史产物不参与发布）"
        }
        $artifacts += $candidates[0]
    }
    return $artifacts
}

# 独立校验产物完整：文件存在、非空、zip 头可读（本次构建自己的产物集合，不依赖 assets 现状）
function Assert-ArtifactsComplete {
    param([Parameter(Mandatory = $true)]$Artifacts)
    $Artifacts = @($Artifacts)
    foreach ($artifact in $Artifacts) {
        if (-not (Test-Path -LiteralPath $artifact.FullName)) {
            throw "产物缺失：$($artifact.FullName)"
        }
        if ($artifact.Length -le 0) {
            throw "产物为空：$($artifact.FullName)"
        }
        $head = New-Object byte[] 2
        $stream = [System.IO.File]::OpenRead($artifact.FullName)
        try {
            $read = $stream.Read($head, 0, 2)
        } finally {
            $stream.Dispose()
        }
        # zip 本地文件头 'PK'
        if ($read -ne 2 -or $head[0] -ne 0x50 -or $head[1] -ne 0x4B) {
            throw "产物不是有效的 xipk（zip）：$($artifact.FullName)"
        }
    }
}

# 发布到 assets：先备份 → 全部复制 → 校验 → 才删备份；任一步失败整体回滚
function Sync-BundledAssets {
    param(
        [Parameter(Mandatory = $true)][string]$AssetsDir,
        [Parameter(Mandatory = $true)]$Artifacts
    )
    $backupDir = Join-Path ([System.IO.Path]::GetTempPath()) ("xime-assets-backup-" + [guid]::NewGuid().ToString("N"))
    $backupReady = $false
    $publishNames = @($Artifacts | ForEach-Object { $_.Name })
    try {
        if (-not (Test-Path -LiteralPath $AssetsDir)) {
            New-Item -ItemType Directory -Path $AssetsDir -Force | Out-Null
        }
        # S4：把旧 assets 整体备份到临时目录（还不删除任何东西）
        New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
        Get-ChildItem -Path $AssetsDir -Force -ErrorAction SilentlyContinue | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $backupDir -Recurse -Force
        }
        $backupReady = $true

        # S5：本次构建的全部产物逐个复制（不是「首个」）
        foreach ($artifact in $Artifacts) {
            Copy-Item -LiteralPath $artifact.FullName -Destination (Join-Path $AssetsDir $artifact.Name) -Force
            Write-Host "  ↳ $($artifact.Name)"
        }
        # S5b：复制全部成功后才清掉不再内置的旧 xipk
        Get-ChildItem -Path $AssetsDir -Filter "*.xipk" -File -ErrorAction SilentlyContinue |
            Where-Object { $publishNames -notcontains $_.Name } |
            Remove-Item -Force

        # S6：发布后逐个校验，长度与源产物一致
        foreach ($artifact in $Artifacts) {
            $target = Join-Path $AssetsDir $artifact.Name
            if (-not (Test-Path -LiteralPath $target)) {
                throw "发布后校验失败：缺少 $($artifact.Name)"
            }
            if ((Get-Item -LiteralPath $target).Length -ne $artifact.Length) {
                throw "发布后校验失败：$($artifact.Name) 长度与产物不一致"
            }
        }

        # S7：全部成功，才删除备份
        Remove-Item -LiteralPath $backupDir -Recurse -Force
        $backupReady = $false
    } catch {
        if ($backupReady) {
            Write-Warning "同步 assets 失败，正在回滚：$($_.Exception.Message)"
            Get-ChildItem -Path $AssetsDir -Force -ErrorAction SilentlyContinue |
                Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
            Get-ChildItem -Path $backupDir -Force -ErrorAction SilentlyContinue | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $AssetsDir -Recurse -Force
            }
            Remove-Item -LiteralPath $backupDir -Recurse -Force -ErrorAction SilentlyContinue
        }
        throw
    }
}

if ($WITH_ASSETS) {
    $ASSETS_DIR = Join-Path $PROJECT_DIR "app\src\main\assets\plugins"
    $RELEASE_DIR = Join-Path $PROJECT_DIR "build\plugin-release"
    Write-Host ""
    Write-Host "=== 同步内置插件到 assets ==="
    # S2/S3：先确定并校验本次构建的产物集合，再动 assets
    $artifacts = Get-FreshBuildArtifacts -ReleaseDir $RELEASE_DIR -Names $BUNDLED_PLUGINS -BuildStartedUtc $BUILD_STARTED_UTC
    Assert-ArtifactsComplete -Artifacts $artifacts
    Sync-BundledAssets -AssetsDir $ASSETS_DIR -Artifacts $artifacts
}

Write-Host ""
Write-Host "=== Done ==="
Get-ChildItem -Path (Join-Path $PROJECT_DIR "build\plugin-release") -Filter "*.xipk" -File |
    Sort-Object Name |
    ForEach-Object { "{0,10:N1} K  {1}" -f ($_.Length / 1KB), $_.Name }
