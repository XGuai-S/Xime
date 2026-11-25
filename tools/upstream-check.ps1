# upstream-check.ps1 -- compare local repo against upstream XGuai-S/Xime
# Read-only: never modifies the working tree, never merges.
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\upstream-check.ps1
#         add -NoFetch to skip the network round trip
param([switch]$NoFetch)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

function Write-Section($title) {
    Write-Host ''
    Write-Host "=== $title ===" -ForegroundColor Cyan
}

$remotes = git remote 2>$null
if ($remotes -notcontains 'upstream') {
    Write-Host 'adding upstream remote...' -ForegroundColor Yellow
    git remote add upstream https://github.com/XGuai-S/Xime.git
}

if (-not $NoFetch) {
    Write-Section 'fetch upstream'
    git fetch upstream --prune 2>&1 | Where-Object { $_ -notmatch '^warning:' } | Select-Object -Last 3
}

$upstreamRef = 'upstream/main'
git rev-parse --verify --quiet $upstreamRef *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "cannot resolve $upstreamRef; run: git fetch upstream" -ForegroundColor Red
    exit 1
}

# ---- 1. commit divergence ----
Write-Section '1. commits: local HEAD vs upstream/main'
$ahead  = [int](git rev-list --count "$upstreamRef..HEAD" 2>$null)
$behind = [int](git rev-list --count "HEAD..$upstreamRef" 2>$null)
$localHead = git rev-parse --short HEAD
$upHead    = git rev-parse --short $upstreamRef
Write-Host "local HEAD     : $localHead"
Write-Host "upstream/main  : $upHead"
Write-Host "local ahead    : $ahead"
Write-Host "local behind   : $behind"

if ($behind -eq 0) {
    Write-Host '-> upstream has no new commits; nothing to follow.' -ForegroundColor Green
} else {
    Write-Host '-> upstream has new commits:' -ForegroundColor Yellow
    git log --oneline --no-decorate "HEAD..$upstreamRef" 2>$null
}

# ---- 2. uncommitted work ----
Write-Section '2. uncommitted changes'
$status    = @(git status --porcelain 2>$null)
$modified  = @($status | Where-Object { $_ -match '^ ?M' }).Count
$untracked = @($status | Where-Object { $_ -match '^\?\?' }).Count
Write-Host "tracked modified : $modified"
Write-Host "untracked added  : $untracked"
Write-Host "total            : $($status.Count)"

if ($status.Count -gt 0) {
    Write-Host ''
    Write-Host 'WARNING: uncommitted work present. Commit before following upstream,' -ForegroundColor Yellow
    Write-Host '         otherwise conflicts cannot be rolled back safely.' -ForegroundColor Yellow
}

# ---- 3. conflict surface ----
if ($behind -gt 0) {
    Write-Section '3. conflict surface (changed upstream AND locally)'
    $upChanged  = @(git diff --name-only "HEAD...$upstreamRef" 2>$null)
    $locChanged = @(git diff --name-only HEAD 2>$null) + @(
        $status | Where-Object { $_ -match '^\?\?' } |
            ForEach-Object { $_.Substring(3).Trim().Trim('"') }
    )
    $overlap = @($upChanged | Where-Object { $locChanged -contains $_ })
    if ($overlap.Count -eq 0) {
        Write-Host '-> no overlap; merge should be clean.' -ForegroundColor Green
    } else {
        Write-Host "-> $($overlap.Count) file(s) touched on both sides:" -ForegroundColor Yellow
        $overlap | ForEach-Object { Write-Host "    $_" }
    }
}

# ---- 4. version comparison ----
Write-Section '4. version'
$locVer = $null
$m = Select-String -Path 'app/build.gradle.kts' -Pattern 'versionName = "([^"]+)"' 2>$null |
    Select-Object -First 1
if ($m) { $locVer = $m.Matches[0].Groups[1].Value }

$upVer = $null
$raw = git show "${upstreamRef}:app/build.gradle.kts" 2>$null
$m2 = $raw | Select-String 'versionName = "([^"]+)"' | Select-Object -First 1
if ($m2) { $upVer = $m2.Matches[0].Groups[1].Value }

Write-Host "local    : $locVer"
Write-Host "upstream : $upVer"

Write-Host ''
Write-Host 'This script is read-only. To follow upstream: fetch -> report -> confirm -> merge.' -ForegroundColor DarkGray
