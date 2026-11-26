@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0video-search\scripts\stop.ps1"
if errorlevel 1 pause
