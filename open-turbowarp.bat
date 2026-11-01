@echo off
setlocal
set "ROOT=%~dp0"
set "TW=%ROOT%tools\turbowarp\TurboWarp.Portable.1.17.1.x64.exe"
if not exist "%TW%" goto web
start "" "%TW%" "%ROOT%ScratchMC.sb3"
exit /b 0
:web
echo No bundled TurboWarp found, opening the web build.
start "" "https://turbowarp.org/"
echo In the editor: File - Load from your computer - ScratchMC.sb3
pause