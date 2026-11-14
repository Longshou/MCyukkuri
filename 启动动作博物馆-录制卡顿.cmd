@echo off
rem Starts the museum and records client performance (JFR) until Minecraft closes. Same as the museum launcher with -Profile
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch_museum.ps1" -Profile %*
pause
