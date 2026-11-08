@echo off
rem Builds a self-contained folder you can zip and hand to someone else.
rem Every launcher inside uses paths relative to itself, so it runs from anywhere.
setlocal
cd /d "%~dp0"

call mvn -q compile
if errorlevel 1 goto :fail

if exist dist rmdir /s /q dist
mkdir dist\app
mkdir dist\lib

call mvn -q dependency:copy-dependencies -DoutputDirectory=dist\lib
if errorlevel 1 goto :fail
xcopy /e /i /q target\classes dist\app >nul

copy /y dist-run-ui.cmd dist\run-ui.cmd >nul
copy /y dist-run-ui-bench.cmd dist\run-ui-bench.cmd >nul
copy /y dist-run-server.cmd dist\run-server.cmd >nul
copy /y dist-run-relay.cmd dist\run-relay.cmd >nul
copy /y dist-run-cli.cmd dist\run-cli.cmd >nul
copy /y dist-stop-all.cmd dist\stop-all.cmd >nul
if exist README.md copy /y README.md dist\README.md >nul

echo dist ready:
dir /b dist
goto :eof

:fail
echo build failed
exit /b 1
