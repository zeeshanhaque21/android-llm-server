@echo off
REM android-llm-server bootstrap installer for Windows CMD.
REM
REM Usage:
REM   curl -fsSL https://raw.githubusercontent.com/zeeshanhaque21/android-llm-server/main/install.cmd -o install.cmd && install.cmd && del install.cmd
REM
REM Env overrides: ALSER_DIR, ALSER_REPO, ALSER_REF, ALSER_SKIP_SETUP
REM
REM Delegates to install.ps1 so there is only one source of truth.

setlocal
set "INSTALL_PS1_URL=https://raw.githubusercontent.com/zeeshanhaque21/android-llm-server/main/install.ps1"

where powershell >nul 2>nul
if errorlevel 1 (
  echo [error] PowerShell is required but was not found on PATH.
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; " ^
  "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; " ^
  "iex (iwr -useb '%INSTALL_PS1_URL%').Content"

endlocal
exit /b %ERRORLEVEL%
