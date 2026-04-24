# android-llm-server bootstrap installer (Windows PowerShell).
#
# Usage:
#   irm https://raw.githubusercontent.com/zeeshanhaque21/android-llm-server/main/install.ps1 | iex
#
# Env overrides (set before invoking):
#   $env:ALSER_DIR          target directory        (default: $env:USERPROFILE\android-llm-server)
#   $env:ALSER_REPO         git url                 (default: GitHub main repo)
#   $env:ALSER_REF          branch or tag           (default: main)
#   $env:ALSER_SKIP_SETUP   "1" to clone only

$ErrorActionPreference = 'Stop'

$Repo = if ($env:ALSER_REPO) { $env:ALSER_REPO } else { 'https://github.com/zeeshanhaque21/android-llm-server.git' }
$Ref  = if ($env:ALSER_REF)  { $env:ALSER_REF  } else { 'main' }
$Dir  = if ($env:ALSER_DIR)  { $env:ALSER_DIR  } else { Join-Path $env:USERPROFILE 'android-llm-server' }

function Log  ($m) { Write-Host "[install] $m" -ForegroundColor Cyan   }
function Warn ($m) { Write-Host "[warn]  $m"   -ForegroundColor Yellow }
function Fail ($m) { Write-Host "[error] $m"   -ForegroundColor Red; exit 1 }

function Refresh-Path {
    $env:Path = [System.Environment]::GetEnvironmentVariable('Path','Machine') + ';' +
                [System.Environment]::GetEnvironmentVariable('Path','User')
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Log 'git not found — installing via winget'
        winget install --id Git.Git --silent --accept-source-agreements --accept-package-agreements | Out-Null
        Refresh-Path
    } else {
        Fail 'git is required and winget is not available. Install Git for Windows from https://git-scm.com/ and retry.'
    }
}

if (Test-Path (Join-Path $Dir '.git')) {
    Log "Updating existing clone at $Dir"
    git -C $Dir fetch --depth 1 origin $Ref
    git -C $Dir checkout $Ref
    git -C $Dir reset --hard "origin/$Ref" 2>$null
} elseif (Test-Path $Dir) {
    Fail "$Dir exists and is not a git clone. Move it aside or set `$env:ALSER_DIR."
} else {
    Log "Cloning $Repo ($Ref) into $Dir"
    git clone --depth 1 --branch $Ref $Repo $Dir
}

Log 'Syncing submodules'
git -C $Dir submodule update --init --recursive

if ($env:ALSER_SKIP_SETUP -eq '1') {
    Log 'ALSER_SKIP_SETUP=1 — skipping toolchain install'
    Log "Next: cd `"$Dir`" ; powershell -ExecutionPolicy Bypass -File scripts\setup.ps1 ; .\gradlew.bat assembleDebug"
    exit 0
}

Log 'Running scripts\setup.ps1 (installs JDK 17, Android SDK/NDK/CMake)'
$setup = Join-Path $Dir 'scripts\setup.ps1'
powershell -NoProfile -ExecutionPolicy Bypass -File $setup

Write-Host ''
Write-Host '------------------------------------------------------------' -ForegroundColor Green
Write-Host "Install complete."                                           -ForegroundColor Green
Write-Host "Project:   $Dir"                                             -ForegroundColor Green
Write-Host "Build:     cd `"$Dir`" ; .\gradlew.bat assembleDebug"         -ForegroundColor Green
Write-Host "Device:    cd `"$Dir`" ; adb install app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
Write-Host '------------------------------------------------------------' -ForegroundColor Green
