# Xime Release Installation Script
param(
    [string]$Action = "all",
    [string]$Source = "ide"
)

$PackageName = "com.kingzcheung.xime"
$Plugins = @(
    "com.kingzcheung.xime.plugin.emoji",
    "com.kingzcheung.xime.plugin.kaomoji"
)

Write-Host ""
Write-Host "================================"
Write-Host "  Xime Release Install Script"
Write-Host "================================"
Write-Host ""
Write-Host "Actions: all | app | plugins | uninstall"
Write-Host ""

# Check device
$devices = adb devices
if ($devices -notmatch "device") {
    Write-Host "No device connected" -ForegroundColor Red
    exit 1
}
Write-Host "Device connected" -ForegroundColor Green

# Pure uninstall action
if ($Action -eq "uninstall") {
    Write-Host "Uninstalling all..." -ForegroundColor Yellow
    adb uninstall $PackageName
    foreach ($plugin in $Plugins) {
        Write-Host "  Uninstalling $plugin"
        adb uninstall $plugin
    }
    Write-Host ""
    Write-Host "=== Uninstall Complete ===" -ForegroundColor Green
    exit 0
}

# Get paths based on source
if ($Source -eq "ide") {
    $AppPath = "app\release"
    $PluginPaths = @{
        "meme-bunny" = "plugins\meme-bunny\release"
        "kaomoji" = "plugins\kaomoji\release"
    }
} else {
    $AppPath = "app\build\outputs\apk\release"
    $PluginPaths = @{
        "meme-bunny" = "plugins\meme-bunny\build\outputs\apk\release"
        "kaomoji" = "plugins\kaomoji\build\outputs\apk\release"
    }
}

# Uninstall
if ($Action -eq "all" -or $Action -eq "app") {
    Write-Host "Uninstalling app..." -ForegroundColor Yellow
    adb uninstall $PackageName
}

if ($Action -eq "all" -or $Action -eq "plugins") {
    Write-Host "Uninstalling plugins..." -ForegroundColor Yellow
    foreach ($plugin in $Plugins) {
        adb uninstall $plugin
    }
}

Write-Host ""

# Install app
if ($Action -eq "all" -or $Action -eq "app") {
    Write-Host "Installing app..." -ForegroundColor Yellow
    $apks = Get-ChildItem $AppPath -Filter "*.apk" -ErrorAction SilentlyContinue
    if ($apks) {
        $apk = $apks | Where-Object { $_.Name -match "arm64-v8a" } | Select-Object -First 1
        if (-not $apk) { $apk = $apks[0] }
        Write-Host "  APK: $($apk.Name)"
        adb install -r $apk.FullName
    } else {
        Write-Host "  No APK found in $AppPath" -ForegroundColor Red
    }
}

# Install plugins
if ($Action -eq "all" -or $Action -eq "plugins") {
    Write-Host "Installing plugins..." -ForegroundColor Yellow
    foreach ($key in $PluginPaths.Keys) {
        $path = $PluginPaths[$key]
        $apks = Get-ChildItem $path -Filter "*.apk" -ErrorAction SilentlyContinue
        if ($apks) {
            $apk = $apks[0]
            $name = $apk.Name
            Write-Host "  ${key}: $name"
            adb install -r $apk.FullName
        } else {
            Write-Host "  ${key}: No APK in $path" -ForegroundColor Yellow
        }
    }
}

Write-Host ""
Write-Host "=== Done ===" -ForegroundColor Green