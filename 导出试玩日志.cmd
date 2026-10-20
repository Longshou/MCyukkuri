@echo off
rem Export saved playground logs. Does not launch the game.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\export_museum_logs.ps1" %*
pause
