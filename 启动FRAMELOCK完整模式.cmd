@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0video-search\scripts\launch.ps1" -Infra
if errorlevel 1 pause
