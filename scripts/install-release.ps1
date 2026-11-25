# Preserve application data by default; Python owns validation and ADB execution.
param(
    [ValidateSet("all", "app", "plugins", "uninstall")]
    [string]$Action = "all",
    [ValidateSet("ide", "build")]
    [string]$Source = "ide",
    [string]$Apk,
    [string]$Serial,
    [string]$Adb = "adb",
    [string]$Aapt2,
    [switch]$ConfirmUninstall
)

$ErrorActionPreference = "Stop"
$installArgs = @((Join-Path $PSScriptRoot "install_release.py"), "--action", $Action, "--source", $Source, "--adb", $Adb)
if ($Apk) { $installArgs += @("--apk", $Apk) }
if ($Serial) { $installArgs += @("--serial", $Serial) }
if ($Aapt2) { $installArgs += @("--aapt2", $Aapt2) }
if ($ConfirmUninstall) { $installArgs += "--confirm-uninstall" }
try {
    if (Get-Command python3 -ErrorAction SilentlyContinue) {
        & python3 @installArgs
    } elseif (Get-Command python -ErrorAction SilentlyContinue) {
        & python @installArgs
    } elseif (Get-Command py -ErrorAction SilentlyContinue) {
        & py -3 @installArgs
    } else {
        throw "Python 3 is required."
    }
    exit $LASTEXITCODE
} catch {
    Write-Error $_
    exit 1
}
