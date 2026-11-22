@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start_review.ps1" -Page ".local/previews/inbox/index.html"
if errorlevel 1 pause
