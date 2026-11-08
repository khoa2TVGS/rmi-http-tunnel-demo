@echo off
rem Opens straight on the Benchmark tab, for demoing the matrix run.
cd /d "%~dp0"
mvn -q javafx:run "-Djavafx.args=--tab 1"
