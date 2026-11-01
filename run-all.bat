@echo off
setlocal
set "ROOT=%~dp0"
set "PID=%~1"
if not "%PID%"=="" goto attach
echo === JVMs available for attach ===
java --add-modules jdk.attach -cp "%ROOT%loader\build" McAttach --list
echo.
set /p PID=Enter Minecraft PID (blank = skip injection^): 
if "%PID%"=="" goto open
:attach
java --add-modules jdk.attach -cp "%ROOT%loader\build" McAttach --pid %PID% --jar "%ROOT%java\build\mcsi-agent.jar" --port 8791
:open
set "TW=%ROOT%tools\turbowarp\TurboWarp.Portable.1.17.1.x64.exe"
if exist "%TW%" goto tw
echo No bundled TurboWarp found, opening the web build instead.
start "" "https://turbowarp.org/"
echo In the editor: File - Load from your computer - ScratchMC.sb3
pause
exit /b 0
:tw
start "" "%TW%" "%ROOT%ScratchMC.sb3"