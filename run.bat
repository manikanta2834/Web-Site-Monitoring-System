@echo off
title WSMS Launcher
echo Starting Web Sites Monitoring System setup...
C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1"
if %errorlevel% neq 0 (
    echo.
    echo Something went wrong during execution. Please check the logs above.
    pause
)
