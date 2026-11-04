@echo off
setlocal
cd /d "%~dp0"
call build.bat
if errorlevel 1 exit /b %errorlevel%
if not exist target\test-classes mkdir target\test-classes
dir /s /b src\test\java\*.java > target\test-sources.txt
javac -source 17 -target 17 -encoding UTF-8 -cp target\classes -d target\test-classes @target\test-sources.txt
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.TechZoneStoreRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.SemanticSearchRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.CatalogBackendRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.BackendSqliteRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.DesignSchemaRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.ApplicationUseCaseRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.DomainOperationsRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.DemoCatalogMigrationRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.AdvisorRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.BuildChecksRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.PairCompatibilityRegression
if errorlevel 1 exit /b %errorlevel%
python src\test\resources\gearvn_offer_test.py
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.AdvisorChatRegression
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp "target\classes;target\test-classes;lib\*" vn.techzone.AdvisorApiLiveRegression
if errorlevel 1 exit /b %errorlevel%
python src\test\resources\web_smoke.py
exit /b %errorlevel%
