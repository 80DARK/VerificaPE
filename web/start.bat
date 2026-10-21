@echo off
rem NotiBridge: levanta el servidor web con tunel de Cloudflare
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
pause
