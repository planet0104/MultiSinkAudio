@echo off
REM ====================================================================
REM TestAudioDevice - RK3399 一键配置设备脚本（双击即可运行）
REM 实际工作交给同目录下的 setup_device_rk3399.ps1
REM ====================================================================

setlocal
set "SCRIPT_DIR=%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%setup_device_rk3399.ps1" %*
set "EC=%ERRORLEVEL%"

echo.
echo ====================================================================
pause
exit /b %EC%
