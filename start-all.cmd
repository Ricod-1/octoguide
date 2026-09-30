@echo off
rem OctoGuide one-click launcher (frontend + backend).
rem All real logic lives in start-all.ps1 next to this file.
chcp 65001 >nul
setlocal
set "SCRIPT=%~dp0start-all.ps1"
if not exist "%SCRIPT%" (
  echo [launcher] start-all.ps1 not found next to this script.
  pause
  exit /b 1
)
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" (
  echo.
  echo [launcher] start-all.ps1 exited with code %CODE%.
  pause
)
endlocal
exit /b %CODE%
