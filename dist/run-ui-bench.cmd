@echo off
cd /d "%~dp0"
java --enable-native-access=javafx.graphics --module-path "%~dp0lib" --add-modules javafx.controls -cp "%~dp0app;%~dp0lib\*" lab.ui.LabApp --tab 1 %*
