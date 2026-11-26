@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0video-search\scripts\launch.ps1"
if errorlevel 1 (
  echo.
  echo FRAMELOCK could not start. Read the error above.
  pause
)
