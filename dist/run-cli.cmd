@echo off
rem Usage: run-cli --relay 127.0.0.1:8080 --size 512 --filter grayscale
cd /d "%~dp0"
java -cp "%~dp0app;%~dp0lib\*" lab.client.TunnelClientMain %*
