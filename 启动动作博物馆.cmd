@echo off
rem Starts the museum, the single in-game test map. Options: -Fresh (new world), -Resume (keep the old one), -Menu
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch_museum.ps1" %*
if errorlevel 1 pause
