@echo off
rem Kills whatever is listening on the three lab ports (server, object, relay).
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -eq 1099 -or $_.LocalPort -eq 1100 -or $_.LocalPort -eq 8080 } | ForEach-Object { Write-Host ('  killing pid ' + $_.OwningProcess + ' on port ' + $_.LocalPort); Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }"
echo done.
