@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch_experimental_map.ps1"
if errorlevel 1 pause
