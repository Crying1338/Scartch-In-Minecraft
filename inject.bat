@echo off
setlocal
set "ROOT=%~dp0"
set "PID=%~1"
if not "%PID%"=="" goto attach
echo === JVMs available for attach ===
java --add-modules jdk.attach -cp "%ROOT%loader\build" McAttach --list
echo.
set /p PID=Enter Minecraft PID: 
if "%PID%"=="" exit /b 0
:attach
echo === Attaching to PID %PID% ===
java --add-modules jdk.attach -cp "%ROOT%loader\build" McAttach --pid %PID% --jar "%ROOT%java\build\mcsi-agent.jar" --port 8791
echo.
echo Verify with: http://127.0.0.1:8791/status
pause