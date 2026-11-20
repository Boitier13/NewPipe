@echo off
setlocal
rem Explicit opt-in. No installed settings or data are copied or modified.
set "BOITIERTUBE_PORTABLE_ROOT=%~dp0"
"%~dp0BoitierTube.exe"
exit /b %errorlevel%
