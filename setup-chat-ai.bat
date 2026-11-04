@echo off
setlocal
cd /d "%~dp0"
if not exist .venv-ai\Scripts\python.exe (
  echo Run setup-ai.bat first to install the AI environment.
  exit /b 1
)
set TECHZONE_MODEL=qwen-chat
.venv-ai\Scripts\python.exe ai\download_models.py
exit /b %errorlevel%
