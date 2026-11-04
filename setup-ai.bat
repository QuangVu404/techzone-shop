@echo off
setlocal
cd /d "%~dp0"
where python >nul 2>&1
if errorlevel 1 (
  echo Python 3.10 or newer is required. Install Python and add it to PATH.
  exit /b 1
)
if not exist .venv-ai\Scripts\python.exe python -m venv --system-site-packages .venv-ai
if errorlevel 1 exit /b %errorlevel%
.venv-ai\Scripts\python.exe -m pip install -r ai\requirements.txt
if errorlevel 1 exit /b %errorlevel%
.venv-ai\Scripts\python.exe ai\download_models.py
if errorlevel 1 exit /b %errorlevel%
echo AI setup complete.
