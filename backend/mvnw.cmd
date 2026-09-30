@echo off
rem Development helper: use the Maven installation configured in PATH.
where mvn.cmd >NUL 2>NUL
if %ERRORLEVEL% EQU 0 (
  call mvn.cmd %*
  exit /b %ERRORLEVEL%
)
echo Maven was not found in PATH. Please use apache-maven-3.6.3\bin\mvn.cmd or install Maven.
exit /b 1
