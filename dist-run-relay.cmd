@echo off
rem The only port that faces the network. --allow restricts which targets a
rem client is allowed to ask the relay to dial.
cd /d "%~dp0"
java -cp "%~dp0app;%~dp0lib\*" lab.relay.RelayMain %*
