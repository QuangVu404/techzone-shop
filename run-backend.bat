@echo off
setlocal
cd /d "%~dp0"
call build.bat
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;lib\*" vn.techzone.backend.BackendMain
