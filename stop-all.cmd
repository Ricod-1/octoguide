@echo off
rem OctoGuide one-click stopper: stops processes on 8080 / 5173 and leftover wrappers.
chcp 65001 >nul
setlocal
set "SCRIPT=%~dp0start-all.ps1"
if not exist "%SCRIPT%" (
  echo [launcher] start-all.ps1 not found next to this script.
  pause
  exit /b 1
)
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" -Stop %*
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" (
  echo.
  echo [launcher] stop-all exited with code %CODE%.
  pause
)
endlocal
exit /b %CODE%
