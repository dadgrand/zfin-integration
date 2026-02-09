@echo off
setlocal
cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File "%~dp0install-ready.ps1"
set EXIT_CODE=%ERRORLEVEL%
endlocal & exit /b %EXIT_CODE%
