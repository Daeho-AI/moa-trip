@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\run_local.ps1" -Both
if errorlevel 1 pause
