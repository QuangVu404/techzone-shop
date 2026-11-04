@echo off
setlocal
cd /d "%~dp0"
if not exist target\classes mkdir target\classes
dir /s /b src\main\java\*.java > target\sources.txt
javac -source 17 -target 17 -encoding UTF-8 -d target\classes @target\sources.txt
if errorlevel 1 exit /b %errorlevel%
if exist src\main\resources xcopy /e /i /y /q src\main\resources target\classes >nul
if errorlevel 1 exit /b %errorlevel%
echo Build complete: target\classes
