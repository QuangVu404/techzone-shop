@echo off
setlocal
cd /d "%~dp0"
call build.bat
if errorlevel 1 exit /b %errorlevel%
> target\manifest.mf echo Manifest-Version: 1.0
>> target\manifest.mf echo Main-Class: vn.techzone.backend.BackendMain
>> target\manifest.mf echo Class-Path: ../lib/sqlite-jdbc-3.49.1.0.jar ../lib/slf4j-api-2.0.16.jar ../lib/slf4j-nop-2.0.16.jar
>> target\manifest.mf echo.
jar --create --file target\techzone-shop.jar --manifest target\manifest.mf -C target\classes .
if errorlevel 1 exit /b %errorlevel%
echo Package complete: target\techzone-shop.jar
