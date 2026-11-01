@echo off
setlocal
set "PORT=%~1"
if "%PORT%"=="" set "PORT=8791"
echo ================= ScratchMC doctor (port %PORT%) =================
echo.
echo [1/4] /ping
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:%PORT%/ping' -TimeoutSec 3; Write-Host ('  OK  ' + $r.Content) } catch { Write-Host '  FAIL  no listener -> run inject.bat first' }"
echo.
echo [2/4] /extension.js  (TurboWarp must see JS here, not JSON)
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:%PORT%/extension.js' -TimeoutSec 5; Write-Host ('  type: ' + $r.Headers['Content-Type']); Write-Host ('  len : ' + $r.Content.Length); Write-Host ('  head: ' + ($r.Content -split [char]10)[0]) } catch { Write-Host '  FAIL  not reachable' }"
echo.
echo [3/4] /status
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:%PORT%/status' -TimeoutSec 5; $j = $r.Content | ConvertFrom-Json; Write-Host ('  player  : ' + $j.game.player); Write-Host ('  inGame  : ' + $j.game.inGame); Write-Host ('  canAim  : ' + $j.game.canAim); Write-Host ('  canAttack: ' + $j.game.canAttack); Write-Host ('  entities: ' + $j.game.entities) } catch { Write-Host '  FAIL  not reachable' }"
echo.
echo [4/4] mapping report (top 5 lines)
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:%PORT%/mappings' -TimeoutSec 5; $j = $r.Content | ConvertFrom-Json; ($j.report -split [char]10) | Select-Object -First 5 | ForEach-Object { Write-Host ('  ' + $_) } } catch { Write-Host '  FAIL  not reachable' }"
echo.
echo agent log: %TEMP%\mcsi-agent.log
pause