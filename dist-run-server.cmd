@echo off
rem Starts the RMI registry (1099) and the exported object (1100), both bound
rem to 127.0.0.1 on purpose: only the relay beside it can reach them.
cd /d "%~dp0"
java -cp "%~dp0app;%~dp0lib\*" lab.server.ServerMain %*
