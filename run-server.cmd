@echo off
setlocal
cd /d "%~dp0"
call mvn -q -DskipTests compile
if errorlevel 1 exit /b 1
java -cp "target\classes" lab.server.ServerMain %*
