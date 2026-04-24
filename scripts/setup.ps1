# Bootstraps the build toolchain for android-llm-server on Windows.
# Run from an elevated PowerShell:  powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
# Uses winget for system packages. Idempotent: re-runs install only missing pieces.

$ErrorActionPreference = 'Stop'

$CmdlineToolsVersion = '11076708'
$PlatformVersion     = '35'
$BuildToolsVersion   = '35.0.0'
$NdkVersion          = '26.1.10909125'
$CmakeVersion        = '3.22.1'

function Log  ($m) { Write-Host "[setup] $m" -ForegroundColor Cyan  }
function Warn ($m) { Write-Host "[warn]  $m" -ForegroundColor Yellow }
function Fail ($m) { Write-Host "[error] $m" -ForegroundColor Red; exit 1 }

function Have ($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }

function Winget-Install ($id) {
    Log "winget install $id"
    winget install --id $id --silent --accept-source-agreements --accept-package-agreements | Out-Null
}

if (-not (Have 'winget')) {
    Fail 'winget not found. Install "App Installer" from the Microsoft Store and retry.'
}

# System packages
if (-not (Have 'git'))  { Winget-Install 'Git.Git' }
if (-not (Have 'java')) { Winget-Install 'EclipseAdoptium.Temurin.17.JDK' }
if (-not (Have 'make')) { Winget-Install 'GnuWin32.Make' }  # optional — Makefile targets can also be run via gradlew directly

# Refresh PATH so newly-installed commands resolve in this session.
$env:Path = [System.Environment]::GetEnvironmentVariable('Path','Machine') + ';' +
            [System.Environment]::GetEnvironmentVariable('Path','User')

# Android SDK
$AndroidHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_HOME     = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome

$sdkmanager = Join-Path $AndroidHome 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    Log "Installing Android command-line tools to $AndroidHome"
    $ctDir = Join-Path $AndroidHome 'cmdline-tools'
    New-Item -ItemType Directory -Force -Path $ctDir | Out-Null
    $tmp = New-Item -ItemType Directory -Force -Path (Join-Path $env:TEMP "android-ct-$(Get-Random)")
    $zip = Join-Path $tmp 'ct.zip'
    $url = "https://dl.google.com/android/repository/commandlinetools-win-${CmdlineToolsVersion}_latest.zip"
    Log "Downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $latest = Join-Path $ctDir 'latest'
    if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }
    Move-Item (Join-Path $tmp 'cmdline-tools') $latest
    Remove-Item -Recurse -Force $tmp
} else {
    Log "Android cmdline-tools already present at $AndroidHome"
}

Log 'Accepting SDK licenses'
'y' * 20 -split '' | Out-Null  # noop, just for readability
cmd /c "echo y| `"$sdkmanager`" --licenses" | Out-Null

Log "Installing SDK packages"
& $sdkmanager --install `
    "platform-tools" `
    "platforms;android-$PlatformVersion" `
    "build-tools;$BuildToolsVersion" `
    "ndk;$NdkVersion" `
    "cmake;$CmakeVersion"

# Persist env vars for the user
[System.Environment]::SetEnvironmentVariable('ANDROID_HOME',     $AndroidHome, 'User')
[System.Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $AndroidHome, 'User')

$userPath = [System.Environment]::GetEnvironmentVariable('Path','User')
$additions = @(
    (Join-Path $AndroidHome 'cmdline-tools\latest\bin'),
    (Join-Path $AndroidHome 'platform-tools')
)
foreach ($p in $additions) {
    if ($userPath -notlike "*$p*") {
        $userPath = "$userPath;$p"
    }
}
[System.Environment]::SetEnvironmentVariable('Path', $userPath, 'User')

# Write local.properties at repo root
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$lp = Join-Path $repoRoot 'local.properties'
$sdkLine = "sdk.dir=" + ($AndroidHome -replace '\\','\\')
if (-not (Test-Path $lp) -or -not (Select-String -Path $lp -Pattern '^sdk\.dir=' -Quiet)) {
    Log "Writing $lp"
    Set-Content -Path $lp -Value $sdkLine -Encoding ASCII
}

# Sync git submodules (llama.cpp, stable-diffusion.cpp)
if (Test-Path (Join-Path $repoRoot '.gitmodules')) {
    Log 'Syncing git submodules (llama.cpp, stable-diffusion.cpp)'
    Push-Location $repoRoot
    try {
        git submodule update --init --recursive
    } finally {
        Pop-Location
    }
}

Write-Host ''
Write-Host '------------------------------------------------------------' -ForegroundColor Green
Write-Host 'Setup complete. Open a NEW PowerShell so PATH/env vars pick up.' -ForegroundColor Green
Write-Host 'Next:  .\gradlew.bat assembleDebug'                             -ForegroundColor Green
Write-Host '------------------------------------------------------------' -ForegroundColor Green
