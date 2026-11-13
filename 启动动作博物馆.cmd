@echo off
rem Starts the museum. Options: -Fresh, -Resume, -Menu, -Profile (record client performance)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch_museum.ps1" %*
if errorlevel 1 pause
